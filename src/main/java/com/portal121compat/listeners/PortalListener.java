package com.portal121compat.listeners;

import com.portal121compat.Portal121Compat;
import com.portal121compat.managers.PortalManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * 传送门核心监听器（复刻 1.21 全实体传送门机制）
 * <p>
 * 1.21 核心改动：
 * <ul>
 *   <li>所有实体（玩家、生物、掉落物、投掷物、矿车、骑乘组合）均可使用传送门</li>
 *   <li>所有实体均可触发对端传送门的搜索与创建</li>
 *   <li>传送门被破坏后，实体穿过时会在搜索范围内寻找已有门，
 *       找不到则在原版搜索起始坐标处原地生成新门</li>
 * </ul>
 * <p>
 * 注意：Paper 1.20.6 没有 EntityPortalEvent（1.21+ 新增），
 * 非玩家实体通过 EntityPortalEnterEvent + 手动传送实现。
 *
 * @author Portal121Compat
 * @version 1.3.0
 */
public class PortalListener implements Listener {

    private final PortalManager portalManager;

    public PortalListener(PortalManager portalManager) {
        this.portalManager = portalManager;
    }

    // ==================== 玩家传送门事件 ====================

    /**
     * 监听玩家传送门传送
     * <p>
     * Paper 1.20.6 中 PlayerPortalEvent 继承 PlayerTeleportEvent，
     * 使用 getCause() 判断传送原因（而非 1.21 的 getPortalType()）。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            return;
        }

        Location to = event.getTo();
        if (to == null) return;
        World targetWorld = to.getWorld();
        if (targetWorld == null) return;

        Location nearest = portalManager.findNearestPortal(to);

        if (nearest != null) {
            portalManager.repairPortal(nearest);
            event.setTo(nearest.clone().add(0.5, 0, 0.5));
            event.setCanCreatePortal(false);
        } else {
            Location portalPos = new Location(
                    targetWorld,
                    to.getBlockX(),
                    to.getBlockY(),
                    to.getBlockZ());

            portalManager.createFullPortal(portalPos);
            event.setTo(portalPos.clone().add(0.5, 0, 0.5));
            event.setCanCreatePortal(false);
        }
    }

    // ==================== 非玩家实体传送门事件 ====================

    /**
     * 监听非玩家实体进入传送门（1.21 核心改动）
     * <p>
     * Paper 1.20.6 中没有 EntityPortalEvent（1.21+ 新增），
     * 使用 EntityPortalEnterEvent 检测实体进入传送门，
     * 然后手动计算对端坐标、查找/创建传送门、传送实体。
     * <p>
     * 使用 LOWEST 优先级确保在原版传送逻辑之前拦截。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityEnterPortal(EntityPortalEnterEvent event) {
        Entity entity = event.getEntity();

        // 跳过玩家（由 PlayerPortalEvent 处理）
        if (entity instanceof Player) {
            return;
        }

        Location portalBlock = event.getLocation();
        World sourceWorld = portalBlock.getWorld();
        if (sourceWorld == null) return;

        // 确定对端维度
        World targetWorld = getTargetDimension(sourceWorld);
        if (targetWorld == null) return;

        // 计算对端目标坐标（8:1 换算）
        Location targetPos = calculateTargetPosition(
                portalBlock, sourceWorld, targetWorld);

        // 在对端查找或创建传送门
        Location nearest = portalManager.findNearestPortal(targetPos);
        Location teleportDest;
        if (nearest != null) {
            portalManager.repairPortal(nearest);
            teleportDest = nearest.clone().add(0.5, 0, 0.5);
        } else {
            portalManager.createFullPortal(targetPos);
            teleportDest = targetPos.clone().add(0.5, 0, 0.5);
        }

        // 延迟 1 tick 传送实体（等待传送门方块就绪）
        final Location dest = teleportDest;
        org.bukkit.Bukkit.getScheduler().runTaskLater(
                Portal121Compat.getInstance(), () -> {
                    if (!entity.isValid()) return;
                    entity.teleportAsync(dest);
                }, 1L);
    }

    // ==================== 手动激活传送门 ====================

    /**
     * 监听玩家右键使用打火石/火焰弹激活传送门
     * 确保手动激活传送门时也对端生成配对门。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerActivatePortal(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        Material item = event.getMaterial();
        if (item != Material.FLINT_AND_STEEL && item != Material.FIRE_CHARGE) {
            return;
        }
        if (clicked.getType() != Material.OBSIDIAN) return;

        org.bukkit.Bukkit.getScheduler().runTaskLater(
                Portal121Compat.getInstance(), () -> {
                    World world = clicked.getWorld();
                    if (world == null) return;

                    Location portalCenter = findNearbyPortalBlock(
                            clicked.getLocation(), 5);
                    if (portalCenter == null) return;

                    World targetWorld = getTargetDimension(world);
                    if (targetWorld == null) return;

                    Location targetPos = calculateTargetPosition(
                            portalCenter, world, targetWorld);
                    if (targetPos == null) return;

                    Location nearest = portalManager.findNearestPortal(targetPos);

                    if (nearest != null) {
                        portalManager.repairPortal(nearest);
                    } else {
                        portalManager.createFullPortal(targetPos);
                    }
                }, 5L);
    }

    // ==================== 内部工具方法 ====================

    private World getTargetDimension(World world) {
        org.bukkit.Server server = org.bukkit.Bukkit.getServer();
        return switch (world.getEnvironment()) {
            case NETHER -> server.getWorlds().stream()
                    .filter(w -> w.getEnvironment()
                            == World.Environment.NORMAL)
                    .findFirst().orElse(null);
            case NORMAL -> server.getWorlds().stream()
                    .filter(w -> w.getEnvironment()
                            == World.Environment.NETHER)
                    .findFirst().orElse(null);
            default -> null;
        };
    }

    private Location calculateTargetPosition(Location source,
                                               World sourceWorld,
                                               World targetWorld) {
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

    private Location findNearbyPortalBlock(Location center, int radius) {
        if (center == null || center.getWorld() == null) return null;

        World world = center.getWorld();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (world.getBlockAt(cx + dx, cy + dy, cz + dz).getType()
                            == Material.NETHER_PORTAL) {
                        return new Location(world, cx + dx, cy + dy, cz + dz);
                    }
                }
            }
        }
        return null;
    }
}