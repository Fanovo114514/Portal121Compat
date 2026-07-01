package com.portal121compat.listeners;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Witch;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 女巫掉落完全复刻 1.21 机制监听器
 * <p>
 * 1.21 女巫掉落机制（与 1.20 完全不同）：
 * <ol>
 *   <li>杂物掷骰 1~3 次，每次从 {木棍(2/7), 玻璃瓶(1/7), 荧光石粉(1/7), 糖(1/7),
 *       火药(1/7), 蜘蛛眼(1/7)} 中抽取 1 个（红石已移出杂物池）</li>
 *   <li>红石独立掷骰 1 次，均匀分布 4~8（抢夺扩展上限，每级+1）</li>
 * </ol>
 * <p>
 * 本监听器会**清空原版所有掉落**，完全按 1.21 规则重新生成，
 * 确保行为与 1.21 一致。
 *
 * @author Portal121Compat
 * @version 1.0.0
 */
public class WitchRedstoneListener implements Listener {

    /** 杂物物品池（1.21 版本：红石已移出） */
    private static final Material[] MISC_POOL = {
            Material.STICK,         // 权重 2/7（占 2 个槽位）
            Material.STICK,
            Material.GLASS_BOTTLE,   // 权重 1/7
            Material.GLOWSTONE_DUST, // 权重 1/7
            Material.SUGAR,         // 权重 1/7
            Material.GUNPOWDER,      // 权重 1/7
            Material.SPIDER_EYE      // 权重 1/7
    };

    /** 抢夺对红石掉落的影响（每级额外允许的最大值+1） */
    private static final int[] LOOTING_REDSTONE_MAX = {8, 9, 10, 11};

    private final com.portal121compat.Portal121Compat plugin;

    /**
     * 构造女巫红石掉落监听器
     *
     * @param plugin 插件实例
     */
    public WitchRedstoneListener(com.portal121compat.Portal121Compat plugin) {
        this.plugin = plugin;
    }

    /**
     * 监听实体死亡事件，完全按 1.21 规则重算女巫掉落
     * <p>
     * 使用 HIGH 优先级，在原版掉落之后执行。
     * 先清空原版所有掉落（原版是 1.20 的旧机制），
     * 再按 1.21 的掷骰规则从零生成。
     *
     * @param event 实体死亡事件
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Witch witch)) {
            return;
        }

        // 获取抢夺等级
        int lootingLevel = getLootingLevel(witch);

        // 清空原版所有掉落（1.20 旧机制）
        event.getDrops().clear();

        // 按 1.21 规则重新生成所有掉落
        generate121Drops(event, lootingLevel);
    }

    /**
     * 按 1.21 规则生成女巫掉落
     *
     * @param event        死亡事件
     * @param lootingLevel 抢夺等级 0-3
     */
    private void generate121Drops(EntityDeathEvent event, int lootingLevel) {
        Random random = ThreadLocalRandom.current();

        // ---- 第一步：杂物掷骰 1~3 次 ----
        // 每次从 {木棍(2/7), 玻璃瓶(1/7), 荧光石粉(1/7), 糖(1/7),
        //              火药(1/7), 蜘蛛眼(1/7)} 中等概率抽取 1 个
        int miscRolls = 1 + random.nextInt(3); // 1, 2, 3
        for (int i = 0; i < miscRolls; i++) {
            event.getDrops().add(new ItemStack(rollMiscItem(random), 1));
        }

        // ---- 第二步：红石独立掷骰 1 次 ----
        // 无抢夺：均匀分布 4~8（各 1/5 概率）
        // 抢夺 I：4~9（各 1/10）
        // 抢夺 II：4~10（4/20, 3/20, 4/20）
        // 抢夺 III：4~11
        int redstoneCount = rollRedstone(random, lootingLevel);

        // 1.20.5+ REDSTONE 被拆分为 REDSTONE_WIRE(方块) 和 REDSTONE(物品)
        // 但 Bukkit API 的 Material.REDSTONE 在某些版本中已不存在
        // 使用 REDSTONE_WIRE 作为掉落物时，服务端可能无法正确渲染
        // 因此直接尝试 REDSTONE，不存在则 fallback 到 REDSTONE_WIRE
        Material redstoneMat = Material.matchMaterial("REDSTONE");
        if (redstoneMat == null) {
            redstoneMat = Material.REDSTONE_WIRE;
            plugin.getLogger().warning(
                    "Material.REDSTONE 不存在，使用 REDSTONE_WIRE 作为替代");
        }

        ItemStack redstoneDrop = new ItemStack(redstoneMat, redstoneCount);
        event.getDrops().add(redstoneDrop);
    }

    /**
     * 从杂物池中等概率抽取一个物品
     * <p>
     * MISC_POOL 共 7 个槽位（木棍占 2 个，其余各 1 个），
     * nextInt(7) 均匀命中每个槽位，概率精确匹配 1.21 原版。
     *
     * @param random 随机数生成器
     * @return 抽中的物品
     */
    private Material rollMiscItem(Random random) {
        return MISC_POOL[random.nextInt(MISC_POOL.length)];
    }

    /**
     * 按 1.21 概率表掷骰红石数量
     * <p>
     * 精确复刻 1.21 快照 24w20a 的概率分布：
     * <table>
     *   <tr><th>等级</th><th>4</th><th>5</th><th>6</th><th>7</th><th>8</th><th>9</th><th>10</th><th>11</th><th>均值</th></tr>
     *   <tr><td>无附魔</td><td>1/5</td><td>1/5</td><td>1/5</td><td>1/5</td><td>1/5</td><td>-</td><td>-</td><td>-</td><td>6.0</td></tr>
     *   <tr><td>抢夺 I</td><td>1/10</td><td>1/5</td><td>1/5</td><td>1/5</td><td>1/5</td><td>1/10</td><td>-</td><td>-</td><td>6.5</td></tr>
     *   <tr><td>抢夺 II</td><td>1/20</td><td>3/20</td><td>1/5</td><td>1/5</td><td>1/5</td><td>3/20</td><td>1/20</td><td>-</td><td>7.0</td></tr>
     *   <tr><td>抢夺 III</td><td>1/30</td><td>1/10</td><td>1/6</td><td>1/5</td><td>1/5</td><td>1/6</td><td>1/10</td><td>1/30</td><td>7.5</td></tr>
     * </table>
     * <p>
     * 实现方式：使用枚举映射，将概率量化为整数值，避免浮点误差。
     *
     * @param random        随机数生成器
     * @param lootingLevel 抢夺等级 0-3
     * @return 红石掉落数量
     */
    private int rollRedstone(Random random, int lootingLevel) {
        int clamped = Math.min(lootingLevel, 3);
        int max = LOOTING_REDSTONE_MAX[clamped];
        int range = max - 4 + 1; // 范围大小

        if (range == 5) {
            // 无抢夺：均匀 4-8
            return 4 + random.nextInt(5);
        }

        // 抢夺 I/II/III：使用精确概率映射
        // 将概率表量化为整数权重，总和为 range 的公倍数
        // 使用 Map<数量, 权重> 表示
        Map<Integer, Integer> weights = buildRedstoneWeights(range);
        int totalWeight = weights.values().stream()
                .mapToInt(Integer::intValue).sum();

        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (Map.Entry<Integer, Integer> entry : weights.entrySet()) {
            cumulative += entry.getValue();
            if (roll < cumulative) {
                return entry.getKey();
            }
        }
        return 4 + random.nextInt(range); // fallback
    }

    /**
     * 构建红石掉落概率权重映射
     * <p>
     * 按照原版概率表构建，以分数形式避免浮点误差。
     *
     * @param range 范围大小（6=抢夺I, 7=抢夺II, 8=抢夺III）
     * @return 数量→权重映射
     */
    private Map<Integer, Integer> buildRedstoneWeights(int range) {
        Map<Integer, Integer> map = new LinkedHashMap<>();

        if (range == 6) {
            // 抢夺 I: 4(1/10) 5(1/5) 6(1/5) 7(1/5) 8(1/5) 9(1/10)
            // 量化到 10: 1, 2, 2, 2, 2, 1
            map.put(4, 1); map.put(5, 2); map.put(6, 2);
            map.put(7, 2); map.put(8, 2); map.put(9, 1);
        } else if (range == 7) {
            // 抢夺 II: 4(1/20) 5(3/20) 6(1/5) 7(1/5) 8(1/5) 9(3/20) 10(1/20)
            // 量化到 20: 1, 3, 4, 4, 4, 3, 1
            map.put(4, 1); map.put(5, 3); map.put(6, 4);
            map.put(7, 4); map.put(8, 4); map.put(9, 3); map.put(10, 1);
        } else if (range == 8) {
            // 抢夺 III: 4(1/30) 5(1/10) 6(1/6) 7(1/5) 8(1/5) 9(1/6) 10(1/10) 11(1/30)
            // 量化到 30: 1, 3, 5, 6, 6, 5, 3, 1
            map.put(4, 1); map.put(5, 3); map.put(6, 5);
            map.put(7, 6); map.put(8, 6); map.put(9, 5); map.put(10, 3); map.put(11, 1);
        }

        return map;
    }

    /**
     * 从击杀者的手持武器获取抢夺附魔等级
     *
     * @param witch 被击杀的女巫
     * @return 抢夺等级 0-3
     */
    private int getLootingLevel(Witch witch) {
        Player killer = witch.getKiller();
        if (killer == null) {
            return 0;
        }

        ItemStack weapon = killer.getInventory().getItemInMainHand();
        if (weapon == null || !weapon.hasItemMeta()) {
            return 0;
        }

        var meta = weapon.getItemMeta();
        if (meta == null || !meta.hasEnchants()) {
            return 0;
        }

        // 通过 Enchantment.LOOTING 直接获取抢夺等级
        return meta.getEnchantLevel(org.bukkit.enchantments.Enchantment.LOOTING);
    }
}
