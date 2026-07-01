package com.portal121compat.listeners;

import com.portal121compat.managers.PortalManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;

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
 *
 * @author Portal121Compat
 * @version 1.2.0
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
     * 使用 LOWEST 优先级，在原版处理之前介入。
     * 原版 1.20.6 已经内置了搜索+创门逻辑（仅限玩家），
     * 但 1.21 的搜索行为有微调。这里保持原版逻辑不变，
     * 仅在原版未找到门时确保正确生成。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (event.getPortalType() != PlayerPortalEvent.PortalType.NETHER) {
            return;
        }

        Location to = event.getTo();
        if (to == null) return;
        World targetWorld = to.getWorld();
        if (targetWorld == null) return;

        // 先用 PortalManager 索引查找已有传送门
        Location nearest = portalManager.findNearestPortal(to);

        if (nearest != null) {
            // 找到已有传送门 → 修复门框 + 指向该门
            portalManager.repairPortal(nearest);
            event.setTo(nearest.clone().add(0.5, 0, 0.5));
            event.setCanCreatePortal(false);
        } else {
            // 未找到 → 在搜索起始坐标处生成新传送门
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
     * 监听非玩家实体传送门传送事件（1.21 核心改动）
     * <p>
     * 1.20.6 原版中非玩家实体可以穿过传送门，但对端
     * 不会创建新门（搜索行为有限）。
     * 1.21 改为所有实体均可触发完整的搜索+创门逻辑。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityPortal(EntityPortalEvent event) {
        if (event.getPortalType() != EntityPortalEvent.PortalType.NETHER) {
            return;
        }

        // 跳过玩家（由 PlayerPortalEvent 处理）
        if (event.getEntity() instanceof Player) {
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
        } else {
            Location portalPos = new Location(
                    targetWorld,
                    to.getBlockX(),
                    to.getBlockY(),
                    to.getBlockZ());

            portalManager.createFullPortal(portalPos);
            event.setTo(portalPos.clone().add(0.5, 0, 0.5));
        }
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

        // 延迟 5 tick 检查是否成功创建了传送门
        org.bukkit.Bukkit.getScheduler().runTaskLater(
                com.portal121compat.Portal121Compat.getInstance(), () -> {
                    World world = clicked.getWorld();
                    if (world == null) return;

                    Location portalCenter = findNearbyPortalBlock(
                            clicked.getLocation(), 5);
                    if (portalCenter == null) return;

                    World targetWorld = getTargetDimension(world);
                    if (targetWorld == null) return;

                    Location targetPos = calculateTargetPosition(
                            portalCenter, world);
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
                                               World sourceWorld) {
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
