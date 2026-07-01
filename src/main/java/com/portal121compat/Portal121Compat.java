package com.portal121compat;

import com.portal121compat.commands.CommandHandler;
import com.portal121compat.listeners.EnderPearlListener;
import com.portal121compat.listeners.MobCapListener;
import com.portal121compat.listeners.PortalListener;
import com.portal121compat.listeners.WitchRedstoneListener;
import com.portal121compat.managers.PortalManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.HandlerList;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * Portal121Compat 主类
 * <p>
 * 复刻 Minecraft 1.21 下界传送门机制，附加 3 项实验性功能。
 * 适配平台：Paper / Leaves 1.20.6
 *
 * @author Portal121Compat
 * @version 1.0.0
 */
public class Portal121Compat extends org.bukkit.plugin.java.JavaPlugin {

    private static Portal121Compat instance;
    private PortalManager portalManager;

    /** 已注册的实验性功能监听器列表，用于热重载时注销 */
    private final List<org.bukkit.event.Listener> activeListeners = new ArrayList<>();

    @Override
    public void onEnable() {
        instance = this;

        // 生成默认配置文件（首次安装自动创建，已有配置不覆盖）
        generateDefaultConfig();

        // 初始化传送门管理器并加载数据
        portalManager = new PortalManager(this);
        portalManager.loadData();

        // 注册传送门监听器（高优先级，兼容多数插件联动）
        getServer().getPluginManager().registerEvents(
                new PortalListener(portalManager), this);

        // 注册实验性功能监听器
        if (getConfig().getBoolean("witch-redstone-drop", false)) {
            getServer().getPluginManager().registerEvents(
                    new WitchRedstoneListener(this), this);
            getLogger().info("实验功能已启用: 女巫红石掉落优化");
        }

        if (getConfig().getBoolean("ender-pearl-dimension", false)) {
            getServer().getPluginManager().registerEvents(
                    new EnderPearlListener(this), this);
            getLogger().info("实验功能已启用: 末影珍珠跨维度传送");
        }

        if (getConfig().getBoolean("hostile-mob-cap-70", false)) {
            getServer().getPluginManager().registerEvents(
                    new MobCapListener(this), this);
            getLogger().info("实验功能已启用: 敌对生物生成上限锁定");
        }

        // 注册指令
        getCommand("portal121compat").setExecutor(new CommandHandler(this));

        getLogger().info("Portal121Compat v" + getDescription().getVersion() + " 已启用");
    }

    @Override
    public void onDisable() {
        if (portalManager != null) {
            portalManager.saveData();
        }
        getLogger().info("Portal121Compat 已保存并关闭");
    }

    /**
     * 获取插件实例
     *
     * @return 插件单例
     */
    public static Portal121Compat getInstance() {
        return instance;
    }

    /**
     * 获取传送门管理器
     *
     * @return 传送门管理器实例
     */
    public PortalManager getPortalManager() {
        return portalManager;
    }

    /**
     * 重载所有配置和功能模块（由 /p121 reload 调用）
     */
    public void reloadAll() {
        reloadConfig();

        // 更新 PortalManager 的配置参数
        portalManager.updateConfig();

        // 重新注册实验性功能监听器
        reloadListener(WitchRedstoneListener.class, "witch-redstone-drop");
        reloadListener(EnderPearlListener.class, "ender-pearl-dimension");
        reloadListener(MobCapListener.class, "hostile-mob-cap-70");

        getLogger().info("配置已重载完成");
    }

    /**
     * 根据配置开关重新注册或注销实验性功能监听器
     *
     * @param listenerClass 监听器类
     * @param configKey     配置键名
     * @param <T>           监听器类型
     */
    private <T> void reloadListener(Class<T> listenerClass, String configKey) {
        boolean enabled = getConfig().getBoolean(configKey, false);
        boolean alreadyRegistered = activeListeners.stream()
                .anyMatch(l -> listenerClass.isInstance(l));

        if (enabled && !alreadyRegistered) {
            try {
                Object listener = listenerClass
                        .getConstructor(Portal121Compat.class)
                        .newInstance(this);
                org.bukkit.event.Listener l = (org.bukkit.event.Listener) listener;
                getServer().getPluginManager().registerEvents(l, this);
                activeListeners.add(l);
                getLogger().info("实验功能已启用: " + configKey);
            } catch (Exception e) {
                getLogger().log(Level.WARNING,
                        "无法启用监听器 " + configKey + ": " + e.getMessage());
            }
        } else if (!enabled && alreadyRegistered) {
            activeListeners.removeIf(l -> {
                if (listenerClass.isInstance(l)) {
                    HandlerList.unregisterAll(l);
                    getLogger().info("实验功能已关闭: " + configKey);
                    return true;
                }
                return false;
            });
        }
    }

    /**
     * 生成默认配置文件
     * <p>
     * 首次安装时自动创建带完整中文注释的 config.yml。
     * 已有配置文件时仅补全缺失的键，不覆盖用户已修改的值。
     */
    private void generateDefaultConfig() {
        File configFile = new File(getDataFolder(), "config.yml");

        if (!configFile.exists()) {
            // 首次安装：创建带注释的完整配置模板
            getDataFolder().mkdirs();
            try (InputStream is = getResource("config_template.yml")) {
                if (is != null) {
                    Files.copy(is, configFile.toPath());
                } else {
                    // fallback：通过代码生成配置
                    createFallbackConfig(configFile);
                }
                getLogger().info("已创建默认配置文件");
            } catch (Exception e) {
                getLogger().log(Level.WARNING,
                        "创建默认配置文件失败: " + e.getMessage());
            }
        } else {
            // 已有配置：仅补全缺失的配置项，不覆盖用户已修改的值
            FileConfiguration config = getConfig();
            boolean changed = false;

            if (!config.contains("portal-search-radius-overworld")) {
                config.set("portal-search-radius-overworld", 128);
                changed = true;
            }
            if (!config.contains("portal-search-radius-nether")) {
                config.set("portal-search-radius-nether", 16);
                changed = true;
            }
            if (!config.contains("portal-width")) {
                config.set("portal-width", 4);
                changed = true;
            }
            if (!config.contains("portal-height")) {
                config.set("portal-height", 5);
                changed = true;
            }
            if (!config.contains("witch-redstone-drop")) {
                config.set("witch-redstone-drop", false);
                changed = true;
            }
            if (!config.contains("ender-pearl-dimension")) {
                config.set("ender-pearl-dimension", false);
                changed = true;
            }
            if (!config.contains("hostile-mob-cap-70")) {
                config.set("hostile-mob-cap-70", false);
                changed = true;
            }

            if (changed) {
                saveConfig();
                getLogger().info("已自动补全缺失的配置项（已有配置未覆盖）");
            }
        }

        reloadConfig();
    }

    /**
     * fallback：当模板资源不存在时，通过代码直接创建配置文件
     *
     * @param configFile 配置文件
     */
    private void createFallbackConfig(File configFile) {
        FileConfiguration config = getConfig();
        config.set("portal-search-radius-overworld", 128);
        config.set("portal-search-radius-nether", 16);
        config.set("portal-width", 4);
        config.set("portal-height", 5);
        config.set("witch-redstone-drop", false);
        config.set("ender-pearl-dimension", false);
        config.set("hostile-mob-cap-70", false);

        config.options()
                .copyDefaults(true)
                .header("""
                =============================================
                 Portal121Compat 配置文件
                 插件版本: """ + getDescription().getVersion() + """
                 适配平台: Paper / Leaves 1.20.6
                =============================================

                -------- 核心设置 --------

                portal-search-radius-overworld:
                  下界传送门搜索半径（主世界方向）
                  当实体从下界传送到主世界时，以目标坐标为中心在此半径内搜索已有传送门
                  默认值: 128

                portal-search-radius-nether:
                  下界传送门搜索半径（下界方向）
                  当实体从主世界传送到下界时，以目标坐标为中心在此半径内搜索已有传送门
                  默认值: 16

                portal-width / portal-height:
                  传送门总尺寸（含门框），标准下界门为 4x5
                  请勿修改非标准尺寸，可能导致传送异常

                -------- 实验性功能 --------
                以下功能默认关闭，请根据需求谨慎开启

                witch-redstone-drop:
                  女巫掉落优化（复刻 1.21 机制）
                  女巫被击杀后必定额外掉落 4~8 个红石粉
                  可通过指令 /p121 witchredstone <on|off> 切换

                ender-pearl-dimension:
                  末影珍珠跨维度传送
                  玩家投掷的末影珍珠进入下界传送门后可完成跨维度传送

                hostile-mob-cap-70:
                  敌对生物生成上限锁定为 70
                  无视已加载区块数量、视距、在线人数
                  可能影响刷怪塔效率
                """);
        saveConfig();
    }
}
