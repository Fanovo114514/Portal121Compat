package com.portal121compat.commands;

import com.portal121compat.Portal121Compat;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.List;

/**
 * Portal121Compat 指令处理器
 * <p>
 * 主指令：/portal121compat（别名 /p121）
 * <ul>
 *   <li>/p121 witchredstone on|off - 切换女巫红石掉落功能，同步写入配置文件</li>
 *   <li>/p121 reload - 重载配置文件，实时生效所有配置项</li>
 * </ul>
 * <p>
 * 权限节点：portal121compat.admin（默认 OP 可执行）
 *
 * @author Portal121Compat
 * @version 1.0.0
 */
public class CommandHandler implements CommandExecutor, TabCompleter {

    private static final String PREFIX =
            ChatColor.GRAY + "[" + ChatColor.GOLD + "Portal121Compat"
                    + ChatColor.GRAY + "] ";

    private final Portal121Compat plugin;

    /**
     * 构造指令处理器
     *
     * @param plugin 插件实例
     */
    public CommandHandler(Portal121Compat plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                              String label, String[] args) {
        // 权限检查
        if (!sender.hasPermission("portal121compat.admin")) {
            sender.sendMessage(PREFIX + ChatColor.RED
                    + "你没有权限执行此指令。");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "witchredstone" -> handleWitchRedstone(sender, args);
            case "reload" -> handleReload(sender);
            case "help" -> {
                sendHelp(sender);
                yield true;
            }
            default -> {
                sender.sendMessage(PREFIX + ChatColor.RED
                        + "未知子指令: " + args[0]);
                sender.sendMessage(PREFIX + ChatColor.GRAY
                        + "使用 /p121 help 查看帮助");
                yield true;
            }
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                        String alias, String[] args) {
        if (!sender.hasPermission("portal121compat.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return List.of("witchredstone", "reload", "help").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }

        if (args.length == 2
                && args[0].equalsIgnoreCase("witchredstone")) {
            return List.of("on", "off").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .toList();
        }

        return Collections.emptyList();
    }

    // ==================== 子指令处理 ====================

    /**
     * 处理女巫红石掉落开关指令
     *
     * @param sender 指令发送者
     * @param args   指令参数
     * @return 处理结果
     */
    private boolean handleWitchRedstone(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(PREFIX + ChatColor.RED
                    + "用法: /p121 witchredstone <on|off>");
            return true;
        }

        boolean enable;
        switch (args[1].toLowerCase()) {
            case "on" -> enable = true;
            case "off" -> enable = false;
            default -> {
                sender.sendMessage(PREFIX + ChatColor.RED
                        + "无效参数: " + args[1] + "，请使用 on 或 off");
                return true;
            }
        }

        // 修改配置文件并保存
        plugin.getConfig().set("witch-redstone-drop", enable);
        plugin.saveConfig();

        // 重载所有功能模块
        plugin.reloadAll();

        String status = enable
                ? ChatColor.GREEN + "已启用"
                : ChatColor.RED + "已关闭";
        sender.sendMessage(PREFIX + "女巫红石掉落优化 " + status);
        sender.sendMessage(PREFIX + ChatColor.GRAY
                + "配置已同步保存，重启后仍生效");

        return true;
    }

    /**
     * 处理重载配置指令
     *
     * @param sender 指令发送者
     * @return 处理结果
     */
    private boolean handleReload(CommandSender sender) {
        plugin.reloadAll();
        sender.sendMessage(PREFIX + ChatColor.GREEN + "配置已重载完成");

        // 显示当前配置状态
        sender.sendMessage(PREFIX + ChatColor.GRAY + "当前状态:");
        sendConfigStatus(sender, "witch-redstone-drop", "女巫红石掉落优化");
        sendConfigStatus(sender, "ender-pearl-dimension",
                "末影珍珠跨维度传送");
        sendConfigStatus(sender, "hostile-mob-cap-70",
                "敌对生物生成上限锁定");

        return true;
    }

    /**
     * 发送帮助信息
     *
     * @param sender 指令发送者
     */
    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== Portal121Compat v"
                + plugin.getDescription().getVersion() + " ===");
        sender.sendMessage(PREFIX + "/p121 witchredstone <on|off>"
                + ChatColor.GRAY + " - 切换女巫红石掉落");
        sender.sendMessage(PREFIX + "/p121 reload"
                + ChatColor.GRAY + " - 重载配置文件");
        sender.sendMessage(PREFIX + "/p121 help"
                + ChatColor.GRAY + " - 显示帮助信息");
    }

    /**
     * 发送单项配置状态
     *
     * @param sender    指令发送者
     * @param configKey 配置键名
     * @param label     功能名称
     */
    private void sendConfigStatus(CommandSender sender,
                                   String configKey, String label) {
        boolean enabled = plugin.getConfig().getBoolean(configKey, false);
        ChatColor color = enabled ? ChatColor.GREEN : ChatColor.RED;
        String status = enabled ? "已启用" : "已关闭";
        sender.sendMessage(PREFIX + ChatColor.GRAY
                + "  " + label + ": " + color + status);
    }
}
