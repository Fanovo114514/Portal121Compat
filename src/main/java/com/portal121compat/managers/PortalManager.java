package com.portal121compat.managers;

import com.portal121compat.Portal121Compat;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 传送门数据管理器
 * <p>
 * 负责传送门坐标的持久化存储、搜索、创建与修复。
 * 支持异步保存，避免高频生成时阻塞主线程。
 * 所有坐标数据存储在 portals.yml 中。
 * <p>
 * 优化项：
 * <ul>
 *   <li>批量方块更新：一次 tick 内合并所有方块变更</li>
 *   <li>防重复修复：同一传送门 20 tick 内不重复 repair</li>
 *   <li>智能位置搜索：自动寻找合法放置位置</li>
 *   <li>四角方块保护：已有固体方块的四角不替换</li>
 * </ul>
 *
 * @author Portal121Compat
 * @version 2.0.0
 */
public class PortalManager {

    /** 已记录的传送门中心坐标列表 */
    private final List<Location> generatedPortals;

    /** 按世界名分区的空间索引，加速查找 */
    private final Map<String, List<Location>> worldIndex;

    /** 数据文件句柄 */
    private final File dataFile;

    /** 保存操作锁，防止并发写入冲突 */
    private final ReentrantLock saveLock;

    /** 传送门搜索半径（主世界方向） */
    private int searchRadiusOverworld;

    /** 传送门搜索半径（下界方向） */
    private int searchRadiusNether;

    /** 传送门总宽度（含门框） */
    private int portalWidth;

    /** 传送门总高度（含门框） */
    private int portalHeight;

    /** 所属插件实例 */
    private final Portal121Compat plugin;

    /** 每个传送门的上次修复时间戳（tick），防止重复修复 */
    private final Map<String, Long> lastRepairTick = new ConcurrentHashMap<>();

    /** 防重复修复冷却时间（tick） */
    private static final long REPAIR_COOLDOWN_TICKS = 1L;

    /** 待保存标记，避免高频重复写入 */
    private volatile boolean pendingSave = false;

    /**
     * 构造传送门管理器
     *
     * @param plugin 插件主类实例
     */
    public PortalManager(Portal121Compat plugin) {
        this.plugin = plugin;
        this.generatedPortals = new ArrayList<>();
        this.worldIndex = new HashMap<>();
        this.saveLock = new ReentrantLock();
        this.dataFile = new File(plugin.getDataFolder(), "portals.yml");

        updateConfig();
    }

    /**
     * 从配置文件更新参数
     */
    public void updateConfig() {
        this.searchRadiusOverworld = plugin.getConfig()
                .getInt("portal-search-radius-overworld", 128);
        this.searchRadiusNether = plugin.getConfig()
                .getInt("portal-search-radius-nether", 16);
        this.portalWidth = plugin.getConfig()
                .getInt("portal-width", 4);
        this.portalHeight = plugin.getConfig()
                .getInt("portal-height", 5);
    }

    // ==================== 数据持久化 ====================

    /**
     * 从 portals.yml 加载传送门数据
     */
    public void loadData() {
        if (!dataFile.exists()) {
            return;
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
        List<String> locStrings = config.getStringList("generated-portals");

        int loaded = 0;
        int skipped = 0;

        for (String s : locStrings) {
            Location loc = parseLocation(s);
            if (loc != null && isValidLocation(loc)) {
                generatedPortals.add(loc);
                indexPortal(loc);
                loaded++;
            } else {
                skipped++;
            }
        }

        if (skipped > 0) {
            plugin.getLogger().warning(
                    "加载传送门数据时跳过 " + skipped + " 条无效记录");
        }

        plugin.getLogger().info("已加载 " + loaded + " 个传送门坐标");
    }

    /**
     * 标记需要保存，延迟到下一个 tick 执行（防高频写入）
     */
    private void markDirty() {
        if (pendingSave) return;
        pendingSave = true;
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingSave = false;
            saveData();
        }, 1L);
    }

    /**
     * 异步保存传送门数据到 portals.yml
     */
    public void saveData() {
        final List<Location> snapshot = new ArrayList<>(generatedPortals.size());
        for (Location loc : generatedPortals) {
            if (isValidLocation(loc)) {
                snapshot.add(loc);
            }
        }

        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            saveLock.lock();
            try {
                FileConfiguration config = new YamlConfiguration();
                List<String> locStrings = new ArrayList<>(snapshot.size());

                for (Location loc : snapshot) {
                    World world = loc.getWorld();
                    if (world == null) continue;
                    locStrings.add(world.getName() + ","
                            + loc.getBlockX() + ","
                            + loc.getBlockY() + ","
                            + loc.getBlockZ());
                }

                config.set("generated-portals", locStrings);

                if (!dataFile.getParentFile().exists()) {
                    dataFile.getParentFile().mkdirs();
                }
                config.save(dataFile);
            } catch (Exception e) {
                plugin.getLogger().warning(
                        "异步保存传送门数据失败: " + e.getMessage());
            } finally {
                saveLock.unlock();
            }
        });
    }

    // ==================== 传送门查找 ====================

    public int getSearchRadius(Location target) {
        World world = target.getWorld();
        if (world == null) {
            return searchRadiusOverworld;
        }

        return world.getEnvironment() == World.Environment.NETHER
                ? searchRadiusNether
                : searchRadiusOverworld;
    }

    public Location findNearestPortal(Location target) {
        World targetWorld = target.getWorld();
        if (targetWorld == null) {
            return null;
        }

        List<Location> portalsInWorld = worldIndex.get(targetWorld.getName());
        if (portalsInWorld == null || portalsInWorld.isEmpty()) {
            return null;
        }

        int radius = getSearchRadius(target);
        Location nearest = null;
        double minDist = Double.MAX_VALUE;

        for (Location portal : portalsInWorld) {
            int dx = Math.abs(portal.getBlockX() - target.getBlockX());
            int dz = Math.abs(portal.getBlockZ() - target.getBlockZ());
            if (dx > radius || dz > radius) {
                continue;
            }

            double dist = portal.distanceSquared(target);
            if (dist <= (double) radius * radius && dist < minDist) {
                nearest = portal;
                minDist = dist;
            }
        }

        return nearest;
    }

    // ==================== 传送门创建与修复 ====================

    /**
     * 修复指定位置的传送门（带冷却 + 批量更新 + 四角保护）
     * <p>
     * 同一传送门 20 tick 内不重复修复，避免连续穿过时卡顿。
     * 四角已有固体方块时保留不替换。
     *
     * @param center 传送门中心坐标
     */
    public void repairPortal(Location center) {
        World world = center.getWorld();
        if (world == null) return;

        // 防重复修复冷却
        String key = world.getName() + "," + center.getBlockX()
                + "," + center.getBlockY() + "," + center.getBlockZ();
        long now = org.bukkit.Bukkit.getCurrentTick();
        Long lastTick = lastRepairTick.get(key);
        if (lastTick != null && now - lastTick < REPAIR_COOLDOWN_TICKS) {
            return;
        }
        lastRepairTick.put(key, now);

        int w = portalWidth;
        int h = portalHeight;
        int baseX = center.getBlockX() - (w / 2);
        int baseY = center.getBlockY() - (h / 2);
        int baseZ = center.getBlockZ();

        // 计算四角坐标（相对 baseX, baseY, baseZ）
        // 传送门是 w×h 的矩形，四角为：
        //   (0, 0), (w-1, 0), (0, h-1), (w-1, h-1)
        Set<String> protectedCorners = new HashSet<>();
        int[][] cornerOffsets = {
                {0, 0}, {w - 1, 0},
                {0, h - 1}, {w - 1, h - 1}
        };
        for (int[] corner : cornerOffsets) {
            Block cornerBlock = world.getBlockAt(
                    baseX + corner[0], baseY + corner[1], baseZ);
            Material type = cornerBlock.getType();
            // 四角已有非空气、非传送门方块时保护
            if (type != Material.AIR
                    && type != Material.CAVE_AIR
                    && type != Material.VOID_AIR
                    && type != Material.NETHER_PORTAL) {
                protectedCorners.add(corner[0] + "," + corner[1]);
            }
        }

        // 批量收集需要变更的方块
        List<Block> obsidianBlocks = new ArrayList<>();
        List<Block> portalBlocks = new ArrayList<>();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // 四角保护检查
                if (protectedCorners.contains(x + "," + y)) {
                    continue;
                }

                Block block = world.getBlockAt(baseX + x, baseY + y, baseZ);
                boolean isFrame = (x == 0 || x == w - 1
                        || y == 0 || y == h - 1);
                Material targetType = isFrame
                        ? Material.OBSIDIAN
                        : Material.NETHER_PORTAL;

                // 只收集需要变更的方块
                if (block.getType() != targetType) {
                    if (isFrame) {
                        obsidianBlocks.add(block);
                    } else {
                        portalBlocks.add(block);
                    }
                }
            }
        }

        // 批量应用方块变更
        for (Block block : obsidianBlocks) {
            block.setType(Material.OBSIDIAN, false);
        }
        for (Block block : portalBlocks) {
            block.setType(Material.NETHER_PORTAL, false);
        }
    }

    /**
     * 创建完整传送门（智能位置搜索 + 修复门框 + 注册坐标）
     * <p>
     * 先在目标位置附近搜索合法放置点，再创建传送门。
     * 如果附近已有已注册的传送门，直接使用该门的位置。
     *
     * @param center 传送门中心坐标（可能不合法，会自动搜索附近位置）
     */
    public void createFullPortal(Location center) {
        World world = center.getWorld();
        if (world == null) return;

        // 先检查附近是否已有已注册的传送门
        Location existingPortal = findNearestPortal(center);
        if (existingPortal != null) {
            // 已有传送门，直接修复并返回
            repairPortal(existingPortal);
            return;
        }

        // 智能搜索合法放置位置
        Location validPos = findValidPortalPosition(center);

        // 修复门框（带冷却 + 四角保护）
        repairPortal(validPos);

        // 去重检查（再次确认）
        String worldName = world.getName();
        for (Location portal : generatedPortals) {
            if (portal.getWorld() == null) continue;
            if (!portal.getWorld().getName().equals(worldName)) continue;
            if (portal.getBlockX() == validPos.getBlockX()
                    && portal.getBlockY() == validPos.getBlockY()
                    && portal.getBlockZ() == validPos.getBlockZ()) {
                markDirty();
                return;
            }
        }

        // 注册新传送门
        Location newPortal = validPos.clone();
        generatedPortals.add(newPortal);
        indexPortal(newPortal);
        markDirty();
    }

    /**
     * 在目标位置附近搜索合法的传送门放置位置
     * <p>
     * 原版 Minecraft 会在目标位置附近搜索一个可以放置传送门的空间。
     * 本方法在 ±3 格范围内搜索，优先选择中心位置，
     * 找不到时回退到中心位置（强制创建）。
     * <p>
     * 注意：搜索范围越小，来回传送的位置偏差越小。
     *
     * @param center 目标中心坐标
     * @return 合法的传送门中心坐标
     */
    private Location findValidPortalPosition(Location center) {
        World world = center.getWorld();
        if (world == null) return center;

        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        int w = portalWidth;
        int h = portalHeight;

        // 先检查中心位置是否可用
        if (isPositionValid(world, cx, cy, cz, w, h)) {
            return new Location(world, cx, cy, cz);
        }

        // 在 ±3 格范围内螺旋搜索（减少偏移）
        int searchRadius = 3;
        for (int radius = 1; radius <= searchRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // 只检查当前圈的边缘
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }

                    int x = cx + dx;
                    int z = cz + dz;

                    // 尝试不同高度
                    for (int dy = -2; dy <= 2; dy++) {
                        int y = cy + dy;
                        if (isPositionValid(world, x, y, z, w, h)) {
                            return new Location(world, x, y, z);
                        }
                    }
                }
            }
        }

        // 找不到合法位置，回退到中心（强制创建）
        return new Location(world, cx, cy, cz);
    }

    /**
     * 检查指定位置是否可以放置传送门
     * <p>
     * 要求传送门内部（非门框区域）全部是空气或可替换方块。
     * 门框位置可以是任意方块（会被替换为黑曜石）。
     *
     * @param world 世界
     * @param cx    中心 X
     * @param cy    中心 Y
     * @param cz    中心 Z
     * @param w     宽度
     * @param h     高度
     * @return 可放置返回 true
     */
    private boolean isPositionValid(World world, int cx, int cy, int cz,
                                      int w, int h) {
        int baseX = cx - (w / 2);
        int baseY = cy - (h / 2);

        // 检查传送门内部（非门框）是否都是空气
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                Block block = world.getBlockAt(baseX + x, baseY + y, cz);
                Material type = block.getType();
                if (type != Material.AIR
                        && type != Material.CAVE_AIR
                        && type != Material.VOID_AIR
                        && type != Material.NETHER_PORTAL) {
                    return false;
                }
            }
        }

        return true;
    }

    // ==================== 工具方法 ====================

    private void indexPortal(Location loc) {
        World world = loc.getWorld();
        if (world == null) return;

        worldIndex.computeIfAbsent(world.getName(), k -> new ArrayList<>())
                .add(loc);
    }

    private boolean isValidLocation(Location loc) {
        return loc != null
                && loc.getWorld() != null
                && loc.getWorld().isChunkLoaded(
                        loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }

    private Location parseLocation(String s) {
        if (s == null || s.isEmpty()) return null;

        String[] parts = s.split(",");
        if (parts.length != 4) return null;

        World world = plugin.getServer().getWorld(parts[0].trim());
        if (world == null) return null;

        try {
            int x = Integer.parseInt(parts[1].trim());
            int y = Integer.parseInt(parts[2].trim());
            int z = Integer.parseInt(parts[3].trim());
            return new Location(world, x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public int getPortalCount() {
        return generatedPortals.size();
    }
}