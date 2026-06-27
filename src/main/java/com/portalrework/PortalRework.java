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

    private static final EntityType[] BLACKLIST = {
            EntityType.WITHER,
            EntityType.ENDER_DRAGON,
            EntityType.WARDEN
    };

    public PortalListener(PortalManager manager) {
        this.manager = manager;
    }

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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalGenerate(PortalCreateEvent event) {
        if (event.getReason() != PortalCreateEvent.CreateReason.NETHER_PAIR) return;

        event.setCancelled(true);

        Location rawLoc = event.getBlocks().get(0).getLocation();
        Location center = manager.getPortalCenter(rawLoc);

        Location existing = manager.findNearestPortal(center);

        if (existing != null) {
            manager.repairPortal(existing);
        } else {
            manager.createFullPortal(center);
        }
    }
}
