package com.portalrework;

import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.world.PortalCreateEvent;

public class PortalListener implements Listener {
    private final PortalManager manager;

    // 禁止生成传送门的Boss实体
    private static final EntityType[] BLACKLIST = {
            EntityType.WITHER,
            EntityType.ENDER_DRAGON,
            EntityType.WARDEN
    };

    public PortalListener(PortalManager manager) {
        this.manager = manager;
    }

    // 复刻1.21：允许所有非Boss实体触发生成传送门
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityEnterPortal(EntityPortalEvent event) {
        EntityType type = event.getEntityType();
        for (EntityType t : BLACKLIST) {
            if (type == t) {
                event.setCanCreatePortal(false);
                return;
            }
        }
        event.setCanCreatePortal(true);
    }

    // 核心修复：拦截自动生成逻辑，改为补全模式
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalGenerate(PortalCreateEvent event) {
        // 只处理传送时自动配对生成的地狱门
        if (event.getReason() != PortalCreateEvent.CreateReason.NETHER_PAIR) return;

        // 取消原版的完整门框生成
        event.setCancelled(true);

        // 获取原版计算的生成位置，修正为门中心
        Location rawLoc = event.getBlocks().get(0).getLocation();
        Location center = manager.getPortalCenter(rawLoc);

        // 查找附近已记录的传送门
        Location existing = manager.findNearestPortal(center);

        if (existing != null) {
            // 已有记录：仅补全缺失黑曜石，不复用新位置
            manager.repairPortal(existing);
        } else {
            // 无记录：生成完整门并永久记录
            manager.createFullPortal(center);
        }
    }
}

