package com.portalrework;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class PortalManager {
    private final List<Location> generatedPortals = new ArrayList<>();
    private final File dataFile;

    // 原版搜索半径：主世界128格，下界对应16格
    private static final int SEARCH_RADIUS_OVERWORLD = 128;
    private static final int SEARCH_RADIUS_NETHER = 16;
    // 标准地狱门尺寸：宽4格、高5格
    private static final int PORTAL_WIDTH = 4;
    private static final int PORTAL_HEIGHT = 5;

    public PortalManager() {
        dataFile = new File(PortalRework.getInstance().getDataFolder(), "portals.yml");
    }

    // 加载历史生成的传送门数据
    public void loadData() {
        if (!dataFile.exists()) return;
        FileConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
        List<String> locStrings = config.getStringList("generated-portals");
        for (String s : locStrings) {
            String[] parts = s.split(",");
            if (parts.length != 4) continue;
            World world = PortalRework.getInstance().getServer().getWorld(parts[0]);
            if (world == null) continue;
            try {
                int x = Integer.parseInt(parts[1]);
                int y = Integer.parseInt(parts[2]);
                int z = Integer.parseInt(parts[3]);
                generatedPortals.add(new Location(world, x, y, z));
            } catch (NumberFormatException ignored) {}
        }
    }

    // 持久化保存传送门数据
    public void saveData() {
        FileConfiguration config = new YamlConfiguration();
        List<String> locStrings = new ArrayList<>();
        for (Location loc : generatedPortals) {
            locStrings.add(loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ());
        }
        config.set("generated-portals", locStrings);
        try {
            if (!dataFile.getParentFile().exists()) dataFile.getParentFile().mkdirs();
            config.save(dataFile);
        } catch (Exception e) {
            PortalRework.getInstance().getLogger().warning("保存传送门数据失败: " + e.getMessage());
        }
    }

    // 在目标位置附近查找已记录的自动生成门
    public Location findNearestPortal(Location target) {
        int radius = target.getWorld().getEnvironment() == World.Environment.NETHER
                ? SEARCH_RADIUS_NETHER : SEARCH_RADIUS_OVERWORLD;

        Location nearest = null;
        double minDist = Double.MAX_VALUE;

        for (Location portal : generatedPortals) {
            if (!portal.getWorld().equals(target.getWorld())) continue;
            double dist = portal.distance(target);
            if (dist <= radius && dist < minDist) {
                nearest = portal;
                minDist = dist;
            }
        }
        return nearest;
    }

    // 补全传送门门框：仅替换缺失的黑曜石，不生成完整新门
    public void repairPortal(Location center) {
        World world = center.getWorld();
        int baseX = center.getBlockX() - 1;
        int baseY = center.getBlockY() - 2;
        int baseZ = center.getBlockZ();

        // 遍历4x5门框的所有边框位置
        for (int y = 0; y < PORTAL_HEIGHT; y++) {
            for (int x = 0; x < PORTAL_WIDTH; x++) {
                if (x == 0 || x == PORTAL_WIDTH - 1 || y == 0 || y == PORTAL_HEIGHT - 1) {
                    Block block = world.getBlockAt(baseX + x, baseY + y, baseZ);
                    if (block.getType() != Material.OBSIDIAN) {
                        block.setType(Material.OBSIDIAN);
                    }
                }
            }
        }
    }

    // 生成完整的新传送门并记录（仅首次生成时调用）
    public void createFullPortal(Location center) {
        repairPortal(center);
        // 新门加入缓存并持久化
        generatedPortals.add(center.clone());
        saveData();
    }

    // 从方块位置计算门的中心坐标
    public Location getPortalCenter(Location blockLoc) {
        // 以方块位置为基准，修正为门中心（兼容原版生成朝向）
        return new Location(blockLoc.getWorld(),
                blockLoc.getBlockX() + 1,
                blockLoc.getBlockY() + 2,
                blockLoc.getBlockZ());
    }
}

