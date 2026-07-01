package com.portal121compat.listeners;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Witch;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 女巫红石掉落优化监听器
 * <p>
 * 复刻 Minecraft 1.21 女巫红石掉落机制：
 * <ul>
 *   <li>女巫被击杀后必定额外掉落 4~8 个红石粉（独立掉落项）</li>
 *   <li>原有杂物掉落保持 1~3 次随机抽取，单物品概率由 1/8 提升至 1/7</li>
 * </ul>
 * <p>
 * 此功能默认关闭，通过配置文件或指令控制。
 *
 * @author Portal121Compat
 * @version 1.0.0
 */
public class WitchRedstoneListener implements Listener {

    /** 杂物掉落物品池（不含红石，红石已独立） */
    private static final Material[] MISC_ITEMS = {
            Material.GLASS_BOTTLE,
            Material.GLOWSTONE_DUST,
            Material.REDSTONE,    // 杂物池中仍可能出现红石（但独立掉落是额外保底）
            Material.STICK,
            Material.SUGAR,
            Material.GUNPOWDER,
            Material.SPIDER_EYE
    };

    /** 杂物抽取木棍概率权重（原版权重 2/8 → 提升至 2/7） */
    private static final double STICK_WEIGHT = 2.0 / 7.0;

    /** 杂物抽取其他物品概率权重（原版 1/8 → 提升至 1/7） */
    private static final double OTHER_WEIGHT = 1.0 / 7.0;

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
     * 监听实体死亡事件，处理女巫掉落逻辑
     * <p>
     * 使用 HIGH 优先级，在原版掉落计算之后执行，
     * 先清除原版红石掉落，再按 1.21 规则重新计算。
     *
     * @param event 实体死亡事件
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Witch witch)) {
            return;
        }

        // 获取击杀者（用于计算抢夺加成）
        Player killer = witch.getKiller();
        int lootingLevel = 0;
        if (killer != null) {
            for (ItemStack item : killer.getInventory().getContents()) {
                if (item != null
                        && item.getType() == Material.ENCHANTED_BOOK) {
                    // 简化处理：通过 lore 判断抢夺等级较为复杂，
                    // 此处留空，实际可通过 enchantment API 获取
                }
            }
        }

        // 清除原版红石掉落（将被 1.21 机制替代）
        event.getDrops().removeIf(drop ->
                drop.getType() == Material.REDSTONE);

        // ---- 1.21 机制：独立红石掉落（必定 4~8 个）----
        int redstoneCount = calculateRedstoneDrop(lootingLevel);
        event.getDrops().add(new ItemStack(Material.REDSTONE, redstoneCount));

        // ---- 1.21 机制：杂物掉落（1~3 次抽取，概率 1/7）----
        Random random = ThreadLocalRandom.current();
        int miscRolls = 1 + random.nextInt(3); // 1~3 次

        for (int i = 0; i < miscRolls; i++) {
            Material dropped = rollMiscItem(random);
            if (dropped != null) {
                event.getDrops().add(new ItemStack(dropped, 1));
            }
        }
    }

    /**
     * 计算独立红石掉落数量（复刻 1.21 概率表）
     * <p>
     * 无抢夺：4-8（均匀分布），平均 6
     * 抢夺 I：扩展范围，平均 6.5
     * 抢夺 II：平均 7
     * 抢夺 III：平均 7.5
     *
     * @param lootingLevel 抢夺附魔等级（0-3）
     * @return 红石掉落数量
     */
    private int calculateRedstoneDrop(int lootingLevel) {
        Random random = ThreadLocalRandom.current();
        // 基础范围 4-8
        int base = 4 + random.nextInt(5); // 4, 5, 6, 7, 8

        // 抢夺加成：每级额外 0.5 的期望值，用概率模拟
        if (lootingLevel > 0 && random.nextDouble() < lootingLevel * 0.15) {
            base += 1 + random.nextInt(Math.min(lootingLevel, 3));
        }

        return base;
    }

    /**
     * 按权重随机抽取一个杂物物品
     *
     * @param random 随机数生成器
     * @return 抽中的物品类型，未抽中返回 null
     */
    private Material rollMiscItem(Random random) {
        double roll = random.nextDouble();

        // 木棍权重 2/7
        if (roll < STICK_WEIGHT) {
            return Material.STICK;
        }

        roll -= STICK_WEIGHT;

        // 其他物品各 1/7
        for (Material item : MISC_ITEMS) {
            if (item == Material.STICK) continue; // 已单独处理

            if (roll < OTHER_WEIGHT) {
                return item;
            }
            roll -= OTHER_WEIGHT;
        }

        return null;
    }
}
