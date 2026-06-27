package com.portalrework;

import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;

import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Paper 1.20.6 传送门监听器 — 最终优化版（Java 21）
 * <p>
 * 功能：
 * <ul>
 *   <li>完全阻止凋灵、末影龙、监守者使用任何传送门</li>
 *   <li>接管下界传送门的自动生成，交由 PortalManager 统一管理</li>
 * </ul>
 */
public final class PortalListener implements Listener {

    /** 禁止使用传送门的实体类型 */
    private static final Set<EntityType> BLOCKED_BOSSES = Set.of(
            EntityType.WITHER,
            EntityType.ENDER_DRAGON,
            EntityType.WARDEN
    );

    private final PortalManager manager;
    private final Logger logger;

    /**
     * 构造函数 — 核心依赖绝不可空。
     *
     * @param manager 传送门管理器，不可为 null
     * @param logger  日志记录器，不可为 null
     * @throws NullPointerException 如果任一参数为 null
     */
    public PortalListener(PortalManager manager, Logger logger) {
        this.manager = Objects.requireNonNull(manager, "PortalManager cannot be null");
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null");
    }

    // --------------------------------------------------------------------
    //  1. 完全阻止 Boss 生物通过传送门
    // --------------------------------------------------------------------
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityTeleport(EntityTeleportEvent event) {
        // 只拦截传送门导致的传送
        if (!isPortalCause(event.getTeleportCause())) {
            return;
        }

        if (BLOCKED_BOSSES.contains(event.getEntityType())) {
            event.setCancelled(true);
            logger.info(() -> "已阻止 %s 使用传送门".formatted(event.getEntityType()));
        }
    }

    /**
     * 判断传送原因是否来自传送门（下界门、末地门、末地折跃门）。
     */
    private boolean isPortalCause(EntityTeleportEvent.TeleportCause cause) {
        if (cause == null) return false;

        return switch (cause) {
            case NETHER_PORTAL, END_PORTAL, END_GATEWAY -> true;
            default -> false;
        };
    }

    // --------------------------------------------------------------------
    //  2. 接管下界传送门生成
    // --------------------------------------------------------------------
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalGenerate(PortalCreateEvent event) {
        // 仅处理下界自动配对
        if (event.getReason() != PortalCreateEvent.CreateReason.NETHER_PAIR) {
            return;
        }

        // 防御性检查
        if (event.getBlocks() == null || event.getBlocks().isEmpty()) {
            logger.warning("PortalCreateEvent 含有空的方块列表，已跳过");
            return;
        }

        // 取消原版生成
        event.setCancelled(true);

        // 获取有效坐标
        Location rawLoc = event.getBlocks().get(0).getLocation();
        if (rawLoc.getWorld() == null) {
            logger.warning("无法获取有效世界，传送门生成事件已跳过");
            return;
        }

        // 计算传送门中心点
        Location center = manager.getPortalCenter(rawLoc);
        if (center == null) {
            logger.warning(() -> "PortalManager.getPortalCenter 返回 null，原始坐标: " + rawLoc);
            return;
        }

        // 查找并修复 / 创建新传送门
        Location existing = manager.findNearestPortal(center);
        if (existing != null) {
            manager.repairPortal(existing);
            logger.info(() -> "修复现有传送门: " + existing);
        } else {
            manager.createFullPortal(center);
            logger.info(() -> "创建新传送门: " + center);
        }
    }
}