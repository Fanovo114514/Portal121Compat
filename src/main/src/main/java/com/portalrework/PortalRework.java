package com.portalrework;

import org.bukkit.plugin.java.JavaPlugin;

public class PortalRework extends JavaPlugin {
    private static PortalRework instance;
    private PortalManager portalManager;

    @Override
    public void onEnable() {
        instance = this;

        portalManager = new PortalManager();
        portalManager.loadData();

        getServer().getPluginManager().registerEvents(new PortalListener(portalManager), this);
        getLogger().info("PortalParity 已启用");
    }

    @Override
    public void onDisable() {
        if (portalManager != null) {
            portalManager.saveData();
        }

        getLogger().info("PortalParity 已保存，插件已关闭");
    }

    public static PortalRework getInstance() {
        return instance;
    }
}
