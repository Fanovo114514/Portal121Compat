package com.portal121compat.listeners;

import com.portal121compat.Portal121Compat;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

/**
 * 敌对生物生成上限锁定监听器
 * <p>
 * 全局敌对生物自然生成上限强制锁定为 70，
 * 无视已加载区块数量、视距、在线人数。
 * 所有维度统一执行该上限。
 * <p>
 * 实现原理：当敌对生物总数达到 70 时，
 * 取消后续的自然生成事件（非刷怪笼、非事件生成）。
 * <p>
 * ⚠️ 此功能为实验性，默认关闭。开启后可能影响刷怪塔效率。
 *
 * @author Portal121Compat
 * @version 1.0.0
 */
public class MobCapListener implements Listener {

    /** 强制锁定的敌对生物上限 */
    private static final int FORCED_HOSTILE_CAP = 70;

    /** 敌对生物实体类型集合（用于快速判断） */
    private static final java.util.Set<EntityType> HOSTILE_TYPES = Set.of(
            EntityType.ZOMBIE,
            EntityType.ZOMBIE_VILLAGER,
            EntityType.ZOMBIFIED_PIGLIN,
            EntityType.SKELETON,
            EntityType.CREEPER,
            EntityType.SPIDER,
            EntityType.ENDERMAN,
            EntityType.WITCH,
            EntityType.SLIME,
            EntityType.PHANTOM,
            EntityType.DROWNED,
            EntityType.HUSK,
            EntityType.STRAY,
            EntityType.CAVE_SPIDER,
            EntityType.SILVERFISH,
            EntityType.PIGLIN_BRUTE,
            EntityType.WARDEN,
            EntityType.BOGGED,
            EntityType.BREEZE,
            EntityType.EVOKER,
            EntityType.ILLUSIONER,
            EntityType.VINDICATOR,
            EntityType.PILLAGER,
            EntityType.RAVAGER
    );

    private final Portal121Compat plugin;

    /**
     * 构造敌对生物上限监听器
     *
     * @param plugin 插件实例
     */
    public MobCapListener(Portal121Compat plugin) {
        this.plugin = plugin;
    }

    /**
     * 监听生物生成事件，强制执行 70 上限
     * <p>
     * 仅拦截自然生成（NATURAL）、不拦截刷怪笼（SPAWNER）、
     * 袭击（RAID）、事件生成等特殊来源。
     *
     * @param event 生物生成事件
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        // 仅处理自然生成
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (reason != CreatureSpawnEvent.SpawnReason.NATURAL
                && reason != CreatureSpawnEvent.SpawnReason.JOCKEY
                && reason != CreatureSpawnEvent.SpawnReason.MOUNTAIN) {
            return;
        }

        // 仅处理敌对生物
        EntityType type = event.getEntityType();
        if (!isHostileMob(type)) {
            return;
        }

        // 统计所有已加载世界中的敌对生物总数
        int totalHostile = countHostileMobs();

        // 达到上限则取消生成
        if (totalHostile >= FORCED_HOSTILE_CAP) {
            event.setCancelled(true);
        }
    }

    /**
     * 判断实体类型是否为敌对生物
     *
     * @param type 实体类型
     * @return 是敌对生物返回 true
     */
    private boolean isHostileMob(EntityType type) {
        return HOSTILE_TYPES.contains(type);
    }

    /**
     * 统计所有已加载世界中的敌对生物总数
     *
     * @return 敌对生物数量
     */
    private int countHostileMobs() {
        int count = 0;
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            for (org.bukkit.entity.Entity entity : world.getEntities()) {
                if (entity instanceof org.bukkit.entity.Mob mob) {
                    if (isHostileMob(mob.getType())) {
                        count++;
                        // 提前终止：已达上限无需继续计数
                        if (count >= FORCED_HOSTILE_CAP) {
                            return count;
                        }
                    }
                }
            }
        }
        return count;
    }
}
