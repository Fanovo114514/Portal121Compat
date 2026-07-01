package com.portal121compat.listeners;

import com.portal121compat.managers.PortalManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.PortalCreateEvent;

import java.util.List;

/**
 * 传送门核心监听器
 * <p>
 * 监听 PortalCreateEvent，在传送门创建时触发对端配对生成逻辑。
 * 全实体触发生成机制：玩家、生物、掉落物、投掷物、骑乘组合均触发。
 * <p>
 * 已彻底删除 Boss 实体拦截逻辑，所有实体均可正常通过传送门。
 * 事件优先级设为 HIGH，兼容多数插件联动。
 *
 * @author Portal121Compat
 * @version 1.0.0
 */
public class PortalListener implements Listener {

    private final PortalManager portalManager;

    /**
     * 构造传送门监听器
     *
     * @param portalManager 传送门管理器
     */
    public PortalListener(PortalManager portalManager) {
        this.portalManager = portalManager;
    }

    /**
     * 监听传送门创建事件
     * <p>
     * 当传送门因实体穿越而创建时（NETHER_PAIR），在对端维度查找
     * 或创建匹配的传送门，实现 1.21 全实体触发生成机制。
     *
     * @param event 传送门创建事件
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPortalCreate(PortalCreateEvent event) {
        // 仅处理下界配对触发
        if (event.getReason() != PortalCreateEvent.CreateReason.NETHER_PAIR) {
            return;
        }

        // 无效事件检查
        if (event.getBlocks().isEmpty()) {
            return;
        }

        // 确定对端维度
        World currentWorld = event.getWorld();
        if (currentWorld == null) {
            return;
        }

        World targetWorld = getTargetDimension(currentWorld);
        if (targetWorld == null) {
            return;
        }

        // 计算目标坐标（主世界↔下界坐标换算）
        Location sourceCenter = calculatePortalCenter(event.getBlocks());
        if (sourceCenter == null) {
            return;
        }

        Location targetPos = calculateTargetPosition(sourceCenter, currentWorld);

        // 在对端维度查找最近传送门
        Location nearestPortal = portalManager.findNearestPortal(targetPos);

        if (nearestPortal != null) {
            // 找到已有传送门 → 修复门框确保完整
            portalManager.repairPortal(nearestPortal);
        } else {
            // 未找到 → 新建标准传送门
            portalManager.createFullPortal(targetPos);
        }
    }

    /**
     * 监听玩家右键使用打火石/火焰弹激活传送门
     * <p>
     * 确保手动激活传送门时也对端生成配对门。
     *
     * @param event 玩家交互事件
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerActivatePortal(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }

        Material item = event.getMaterial();
        if (item != Material.FLINT_AND_STEEL && item != Material.FIRE_CHARGE) {
            return;
        }

        // 检查点击的方块是否是黑曜石（传送门框架的一部分）
        if (clicked.getType() != Material.OBSIDIAN) {
            return;
        }

        // 延迟 5 tick 检查是否成功创建了传送门（给事件传播留时间）
        org.bukkit.Bukkit.getScheduler().runTaskLater(
                com.portal121compat.Portal121Compat.getInstance(), () -> {
                    // 检查附近是否有新创建的传送门方块
                    World world = clicked.getWorld();
                    if (world == null) return;

                    Location portalCenter = findNearbyPortalBlock(
                            clicked.getLocation(), 5);
                    if (portalCenter == null) {
                        return;
                    }

                    // 对端维度查找/创建配对门
                    World targetWorld = getTargetDimension(world);
                    if (targetWorld == null) return;

                    Location targetPos = calculateTargetPosition(
                            portalCenter, world);
                    Location nearest = portalManager.findNearestPortal(targetPos);

                    if (nearest != null) {
                        portalManager.repairPortal(nearest);
                    } else {
                        portalManager.createFullPortal(targetPos);
                    }
                }, 5L);
    }

    // ==================== 内部工具方法 ====================

    /**
     * 获取对端维度
     *
     * @param world 当前维度
     * @return 对端维度世界，末地返回 null
     */
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
            default -> null; // 末地不处理
        };
    }

    /**
     * 从传送门方块列表计算传送门中心坐标
     *
     * @param blocks 传送门方块列表
     * @return 中心坐标，列表为空返回 null
     */
    private Location calculatePortalCenter(List<BlockState> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }

        double sumX = 0, sumY = 0, sumZ = 0;
        int count = 0;
        World world = null;

        for (BlockState state : blocks) {
            Block block = state.getBlock();
            if (world == null) {
                world = block.getWorld();
            }
            sumX += block.getX();
            sumY += block.getY();
            sumZ += block.getZ();
            count++;
        }

        if (world == null || count == 0) {
            return null;
        }

        return new Location(
                world, sumX / count, sumY / count, sumZ / count);
    }

    /**
     * 计算对端目标坐标（主世界↔下界 8:1 坐标换算）
     *
     * @param source 源维度坐标
     * @param sourceWorld 源维度
     * @return 对端目标坐标
     */
    private Location calculateTargetPosition(Location source, World sourceWorld) {
        World targetWorld = getTargetDimension(sourceWorld);
        if (targetWorld == null) {
            return null;
        }

        // 下界→主世界乘8，主世界→下界除8
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

    /**
     * 在指定位置附近搜索传送门方块
     *
     * @param center 搜索中心
     * @param radius 搜索半径
     * @return 找到的传送门方块坐标，未找到返回 null
     */
    private Location findNearbyPortalBlock(Location center, int radius) {
        if (center == null || center.getWorld() == null) {
            return null;
        }

        World world = center.getWorld();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Block block = world.getBlockAt(
                            cx + dx, cy + dy, cz + dz);
                    if (block.getType() == Material.NETHER_PORTAL) {
                        return block.getLocation();
                    }
                }
            }
        }
        return null;
    }
}
