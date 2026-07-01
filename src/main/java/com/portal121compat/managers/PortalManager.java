package com.portal121compat.managers;

import com.portal121compat.Portal121Compat;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 传送门数据管理器
 * <p>
 * 负责传送门坐标的持久化存储、搜索、创建与修复。
 * 支持异步保存，避免高频生成时阻塞主线程。
 * 所有坐标数据存储在 portals.yml 中。
 *
 * @author Portal121Compat
 * @version 1.0.0
 */
public class PortalManager {

    /** 已记录的传送门中心坐标列表 */
    private final List<Location> generatedPortals;

    /** 按世界名分区的空间索引，加速查找 */
    private final Map<String, List<Location>> worldIndex;

    /** 数据文件句柄 */
    private final File dataFile;

    /** 保存操作锁，防止并发写入冲突 */
    private final ReentrantLock saveLock;

    /** 传送门搜索半径（主世界方向） */
    private int searchRadiusOverworld;

    /** 传送门搜索半径（下界方向） */
    private int searchRadiusNether;

    /** 传送门总宽度（含门框） */
    private int portalWidth;

    /** 传送门总高度（含门框） */
    private int portalHeight;

    /** 所属插件实例 */
    private final Portal121Compat plugin;

    /**
     * 构造传送门管理器
     *
     * @param plugin 插件主类实例
     */
    public PortalManager(Portal121Compat plugin) {
        this.plugin = plugin;
        this.generatedPortals = new ArrayList<>();
        this.worldIndex = new HashMap<>();
        this.saveLock = new ReentrantLock();
        this.dataFile = new File(plugin.getDataFolder(), "portals.yml");

        updateConfig();
    }

    /**
     * 从配置文件更新参数
     */
    public void updateConfig() {
        this.searchRadiusOverworld = plugin.getConfig()
                .getInt("portal-search-radius-overworld", 128);
        this.searchRadiusNether = plugin.getConfig()
                .getInt("portal-search-radius-nether", 16);
        this.portalWidth = plugin.getConfig()
                .getInt("portal-width", 4);
        this.portalHeight = plugin.getConfig()
                .getInt("portal-height", 5);
    }

    // ==================== 数据持久化 ====================

    /**
     * 从 portals.yml 加载传送门数据
     * <p>
     * 自动过滤无效坐标（空世界、格式错误），防止脏数据导致异常。
     */
    public void loadData() {
        if (!dataFile.exists()) {
            return;
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
        List<String> locStrings = config.getStringList("generated-portals");

        int loaded = 0;
        int skipped = 0;

        for (String s : locStrings) {
            Location loc = parseLocation(s);
            if (loc != null && isValidLocation(loc)) {
                generatedPortals.add(loc);
                indexPortal(loc);
                loaded++;
            } else {
                skipped++;
            }
        }

        if (skipped > 0) {
            plugin.getLogger().warning(
                    "加载传送门数据时跳过 " + skipped + " 条无效记录");
        }

        plugin.getLogger().info("已加载 " + loaded + " 个传送门坐标");
    }

    /**
     * 异步保存传送门数据到 portals.yml
     * <p>
     * 使用独立线程执行文件 IO，不阻塞主线程。
     */
    public void saveData() {
        // 构建数据快照，避免并发修改
        final List<Location> snapshot = new ArrayList<>(generatedPortals.size());
        for (Location loc : generatedPortals) {
            if (isValidLocation(loc)) {
                snapshot.add(loc);
            }
        }

        // 异步写入文件
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            saveLock.lock();
            try {
                FileConfiguration config = new YamlConfiguration();
                List<String> locStrings = new ArrayList<>(snapshot.size());

                for (Location loc : snapshot) {
                    World world = loc.getWorld();
                    if (world == null) continue;
                    locStrings.add(world.getName() + ","
                            + loc.getBlockX() + ","
                            + loc.getBlockY() + ","
                            + loc.getBlockZ());
                }

                config.set("generated-portals", locStrings);

                if (!dataFile.getParentFile().exists()) {
                    dataFile.getParentFile().mkdirs();
                }
                config.save(dataFile);
            } catch (Exception e) {
                plugin.getLogger().warning(
                        "异步保存传送门数据失败: " + e.getMessage());
            } finally {
                saveLock.unlock();
            }
        });
    }

    // ==================== 传送门查找 ====================

    /**
     * 获取指定位置的传送门搜索半径
     *
     * @param target 目标位置
     * @return 搜索半径（方块数）
     */
    public int getSearchRadius(Location target) {
        World world = target.getWorld();
        if (world == null) {
            return searchRadiusOverworld;
        }

        return world.getEnvironment() == World.Environment.NETHER
                ? searchRadiusNether
                : searchRadiusOverworld;
    }

    /**
     * 查找距目标位置最近的传送门
     * <p>
     * 使用世界分区索引加速查找，避免全量遍历。
     *
     * @param target 目标位置
     * @return 最近的传送门中心坐标，未找到返回 null
     */
    public Location findNearestPortal(Location target) {
        World targetWorld = target.getWorld();
        if (targetWorld == null) {
            return null;
        }

        // 利用世界索引缩小搜索范围
        List<Location> portalsInWorld = worldIndex.get(targetWorld.getName());
        if (portalsInWorld == null || portalsInWorld.isEmpty()) {
            return null;
        }

        int radius = getSearchRadius(target);
        Location nearest = null;
        double minDist = Double.MAX_VALUE;

        for (Location portal : portalsInWorld) {
            // 包围盒快速剔除：X/Z 轴任一方向超出范围则跳过
            int dx = Math.abs(portal.getBlockX() - target.getBlockX());
            int dz = Math.abs(portal.getBlockZ() - target.getBlockZ());
            if (dx > radius || dz > radius) {
                continue;
            }

            double dist = portal.distanceSquared(target);
            if (dist <= (double) radius * radius && dist < minDist) {
                nearest = portal;
                minDist = dist;
            }
        }

        return nearest;
    }

    // ==================== 传送门创建与修复 ====================

    /**
     * 修复指定位置的传送门（替换为黑曜石门框 + 传送门方块）
     * <p>
     * 以中心坐标为基准，按配置的宽高重建标准下界传送门。
     *
     * @param center 传送门中心坐标（搜索结果的返回值）
     */
    public void repairPortal(Location center) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        int w = portalWidth;
        int h = portalHeight;
        int baseX = center.getBlockX() - (w / 2);
        int baseY = center.getBlockY() - (h / 2);
        int baseZ = center.getBlockZ();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                Block block = world.getBlockAt(baseX + x, baseY + y, baseZ);

                // 边缘为黑曜石门框，内部为传送门方块
                boolean isFrame = (x == 0 || x == w - 1
                        || y == 0 || y == h - 1);
                block.setType(isFrame
                        ? Material.OBSIDIAN
                        : Material.NETHER_PORTAL);
            }
        }
    }

    /**
     * 创建完整传送门（修复门框 + 注册坐标）
     * <p>
     * 如果该坐标已存在于记录中，仅修复门框不重复注册。
     *
     * @param center 传送门中心坐标
     */
    public void createFullPortal(Location center) {
        // 修复门框
        repairPortal(center);

        // 去重检查
        String worldName = center.getWorld() != null
                ? center.getWorld().getName() : null;
        if (worldName == null) {
            return;
        }

        for (Location portal : generatedPortals) {
            if (portal.getWorld() == null) continue;
            if (!portal.getWorld().getName().equals(worldName)) continue;
            if (portal.getBlockX() == center.getBlockX()
                    && portal.getBlockY() == center.getBlockY()
                    && portal.getBlockZ() == center.getBlockZ()) {
                // 已存在，修复完毕即可
                saveData();
                return;
            }
        }

        // 注册新传送门
        Location newPortal = center.clone();
        generatedPortals.add(newPortal);
        indexPortal(newPortal);
        saveData();
    }

    // ==================== 工具方法 ====================

    /**
     * 将传送门坐标加入世界索引
     *
     * @param loc 传送门坐标
     */
    private void indexPortal(Location loc) {
        World world = loc.getWorld();
        if (world == null) return;

        worldIndex.computeIfAbsent(world.getName(), k -> new ArrayList<>())
                .add(loc);
    }

    /**
     * 校验坐标是否有效（世界非空、世界已加载）
     *
     * @param loc 待校验坐标
     * @return 有效返回 true
     */
    private boolean isValidLocation(Location loc) {
        return loc != null
                && loc.getWorld() != null
                && loc.getWorld().isChunkLoaded(
                        loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }

    /**
     * 从字符串解析坐标（格式：worldName,x,y,z）
     *
     * @param s 坐标字符串
     * @return 解析成功的 Location，失败返回 null
     */
    private Location parseLocation(String s) {
        if (s == null || s.isEmpty()) return null;

        String[] parts = s.split(",");
        if (parts.length != 4) return null;

        World world = plugin.getServer().getWorld(parts[0].trim());
        if (world == null) return null;

        try {
            int x = Integer.parseInt(parts[1].trim());
            int y = Integer.parseInt(parts[2].trim());
            int z = Integer.parseInt(parts[3].trim());
            return new Location(world, x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 获取已注册的传送门数量
     *
     * @return 传送门总数
     */
    public int getPortalCount() {
        return generatedPortals.size();
    }
}
