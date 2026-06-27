package com.portalrework;

import org.bukkit.Location;
import org.bukkit.PortalType;
import org.bukkit.entity.Entity;
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
        if (isBlacklisted(event.getEntityType())) {
            event.setCancelled(true);
            return;
        }

        if (event.getPortalType() == PortalType.NETHER) {
            event.setSearchRadius(manager.getSearchRadius(event.getFrom()));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalGenerate(PortalCreateEvent event) {
        if (event.getReason() != PortalCreateEvent.CreateReason.NETHER_PAIR) {
            return;
        }

        Entity entity = event.getEntity();
        if (entity != null && isBlacklisted(entity.getType())) {
            event.setCancelled(true);
            return;
        }

        if (event.getBlocks().isEmpty()) {
            return;
        }

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

    private boolean isBlacklisted(EntityType type) {
        for (EntityType blacklistedType : BLACKLIST) {
            if (type == blacklistedType) {
                return true;
            }
        }
        return false;
    }
}
