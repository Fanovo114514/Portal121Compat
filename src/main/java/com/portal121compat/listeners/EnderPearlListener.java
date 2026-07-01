package com.portal121compat.listeners;

import com.portal121compat.Portal121Compat;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.EntityTeleportEvent;

/**
 * 末影珍珠跨维度传送监听器
 * <p>
 * 复刻 Minecraft 1.21 机制：玩家投掷的末影珍珠进入下界传送门后，
 * 可正常完成跨维度传送，玩家随珍珠落点在对维度生成/匹配传送门并传送。
 * <p>
 * 此功能默认关闭，仅通过配置文件控制。
 *
 * @author Portal121Compat
 * @version 1.0.0
 */
public class EnderPearlListener implements Listener {

    private final Portal121Compat plugin;

    /**
     * 构造末影珍珠跨维度监听器
     *
     * @param plugin 插件实例
     */
    public EnderPearlListener(Portal121Compat plugin) {
        this.plugin = plugin;
    }

    /**
     * 监听投射物命中事件，检测末影珍珠命中传送门方块
     * <p>
     * 当末影珍珠命中 NETHER_PORTAL 方块时，取消原版落地逻辑，
     * 手动处理跨维度传送。
     *
     * @param event 投射物命中事件
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPearlHitBlock(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) {
            return;
        }

        // 只有玩家投掷的末影珍珠才处理
        if (!(pearl.getShooter() instanceof Player player)) {
            return;
        }

        Block hitBlock = event.getHitBlock();
        if (hitBlock == null || hitBlock.getType() != Material.NETHER_PORTAL) {
            return;
        }

        // 取消原版落地伤害和传送逻辑
        event.setCancelled(true);

        // 确定对端维度
        World currentWorld = hitBlock.getWorld();
        World targetWorld = getTargetDimension(currentWorld);
        if (targetWorld == null) {
            // 不在下界/主世界，按原版处理
            return;
        }

        // 计算对端坐标（8:1 换算）
        Location targetPos = calculateTargetPosition(
                hitBlock.getLocation(), currentWorld);

        // 在对端查找或创建传送门
        Location portalLoc = plugin.getPortalManager()
                .findNearestPortal(targetPos);
        if (portalLoc != null) {
            plugin.getPortalManager().repairPortal(portalLoc);
        } else {
            portalLoc = targetPos;
            plugin.getPortalManager().createFullPortal(portalLoc);
        }

        // 延迟 2 tick 执行传送（等待传送门方块就绪）
        final Location teleportTarget = portalLoc.clone().add(0.5, 0, 0.5);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;

            // 确保传送目标点安全
            teleportTarget.add(0, 1, 0); // 传送到传送门内部偏上位置

            player.teleportAsync(teleportTarget).thenAccept(success -> {
                if (success) {
                    // 给予末影珍珠正常落地伤害（约 2.5 格摔落）
                    double fallDistance = player.getFallDistance();
                    if (fallDistance > 0) {
                        // 模拟约 2.5 格摔落伤害（末影珍珠基础伤害）
                        double damage = Math.max(0, fallDistance - 2.5) * 1.0;
                        if (damage > 0) {
                            player.damage(damage);
                        }
                    }
                    player.setFallDistance(0);
                }
            });
        }, 2L);
    }

    /**
     * 监听实体传送事件，处理末影珍珠触发传送门的跨维度传送
     * <p>
     * 作为备用逻辑，捕获原版末影珍珠与传送门交互产生的传送事件。
     *
     * @param event 实体传送事件
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityTeleport(EntityTeleportEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        // 检查是否因末影珍珠触发
        if (event.getCause() != EntityTeleportEvent.TeleportCause.ENDER_PEARL) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (from == null || to == null) {
            return;
        }

        // 检测是否为跨维度传送（从传送门穿越）
        World fromWorld = from.getWorld();
        World toWorld = to.getWorld();
        if (fromWorld == null || toWorld == null) {
            return;
        }

        if (!fromWorld.getEnvironment().equals(toWorld.getEnvironment())) {
            // 确保对端有传送门
            Location targetPos = new Location(
                    toWorld, to.getBlockX(), to.getBlockY(), to.getBlockZ());
            Location portal = plugin.getPortalManager()
                    .findNearestPortal(targetPos);
            if (portal != null) {
                plugin.getPortalManager().repairPortal(portal);
            } else {
                plugin.getPortalManager().createFullPortal(targetPos);
            }
        }
    }

    // ==================== 内部工具方法 ====================

    /**
     * 获取对端维度
     *
     * @param world 当前维度
     * @return 对端维度，末地返回 null
     */
    private World getTargetDimension(World world) {
        return switch (world.getEnvironment()) {
            case NETHER -> world.getServer().getWorlds().stream()
                    .filter(w -> w.getEnvironment()
                            == World.Environment.NORMAL)
                    .findFirst().orElse(null);
            case NORMAL -> world.getServer().getWorlds().stream()
                    .filter(w -> w.getEnvironment()
                            == World.Environment.NETHER)
                    .findFirst().orElse(null);
            default -> null;
        };
    }

    /**
     * 计算对端目标坐标（主世界↔下界 8:1 坐标换算）
     *
     * @param source 源坐标
     * @param sourceWorld 源维度
     * @return 对端目标坐标
     */
    private Location calculateTargetPosition(Location source, World sourceWorld) {
        World targetWorld = getTargetDimension(sourceWorld);
        if (targetWorld == null) return null;

        if (sourceWorld.getEnvironment() == World.Environment.NETHER) {
            return new Location(targetWorld,
                    source.getBlockX() * 8,
                    source.getBlockY(),
                    source.getBlockZ() * 8);
        } else {
            return new Location(targetWorld,
                    source.getBlockX() / 8,
                    source.getBlockY(),
                    source.getBlockZ() / 8);
        }
    }
}
