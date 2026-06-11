package com.anticheat.detection;

import com.anticheat.managers.DetectionManager;
import com.anticheat.detection.ViolationRecord.ViolationType;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FastBreakDetection extends Detection {

    private final Map<UUID, BlockBreakData> breakData;
    private static final double NORMAL_BREAK_TIME_TOLERANCE = 1.5;
    private static final double MIN_BREAK_TIME_TOLERANCE = 0.1;
    private static final int SAMPLE_SIZE = 10;
    private static final double VARIANCE_THRESHOLD = 0.001;

    public FastBreakDetection(DetectionManager manager) {
        super(manager);
        this.breakData = new ConcurrentHashMap<>();
    }

    @Override
    public void check(Player player) {
        if (shouldSkipDetection(player)) {
            return;
        }
    }

    public void onBlockBreak(Player player, Block block, long breakTimeMillis) {
        if (hasBypassPermission(player) || player.getGameMode() == org.bukkit.GameMode.CREATIVE) {
            return;
        }

        Material material = block.getType();
        double expectedBreakTime = getExpectedBreakTime(material, player);

        if (breakTimeMillis < expectedBreakTime * MIN_BREAK_TIME_TOLERANCE) {
            getManager().getViolationManager().recordViolation(
                    player,
                    ViolationType.FAST_BREAK,
                    String.format("破坏过快: 实际%.2fs, 预期%.2fs", breakTimeMillis / 1000.0, expectedBreakTime / 1000.0),
                    expectedBreakTime / breakTimeMillis
            );
        }

        UUID uuid = player.getUniqueId();
        BlockBreakData data = breakData.computeIfAbsent(uuid, k -> new BlockBreakData());
        data.addBreakTime(breakTimeMillis, (long) expectedBreakTime);

        if (data.getCount() >= SAMPLE_SIZE) {
            analyzeBreakTimes(player, data);
            data.clear();
        }
    }

    public void startBreak(Player player, Block block) {
        UUID uuid = player.getUniqueId();
        BlockBreakData data = breakData.computeIfAbsent(uuid, k -> new BlockBreakData());
        data.setCurrentBreakStart(System.currentTimeMillis());
        data.setCurrentBlockType(block.getType());
    }

    public void cancelBreak(Player player) {
        UUID uuid = player.getUniqueId();
        BlockBreakData data = breakData.get(uuid);
        if (data != null) {
            data.setCurrentBreakStart(0);
        }
    }

    private double getExpectedBreakTime(Material material, Player player) {
        double baseTime = getBaseBreakTime(material);

        int efficiencyLevel = getEfficiencyLevel(player);

        double speedMultiplier = 1.0 + (efficiencyLevel * efficiencyLevel * 0.5);

        boolean inWater = player.isInWater();
        boolean sneaking = player.isSneaking();

        if (inWater && !player.hasPermission("anticheat.bypass.slow")) {
            speedMultiplier *= 0.2;
        }

        if (sneaking) {
            speedMultiplier *= 0.3;
        }

        return baseTime / speedMultiplier;
    }

    private int getEfficiencyLevel(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        org.bukkit.enchantments.Enchantment efficiencyEnchant = org.bukkit.enchantments.Enchantment.getByName("DIG_SPEED");
        if (item != null && item.hasItemMeta() && efficiencyEnchant != null && item.getItemMeta().hasEnchant(efficiencyEnchant)) {
            return item.getItemMeta().getEnchantLevel(efficiencyEnchant);
        }
        return 0;
    }

    private double getBaseBreakTime(Material material) {
        String name = material.name();

        if (name.equals("GRASS") || name.equals("DIRT") || name.equals("SAND") || name.equals("GRAVEL")) {
            return 1000;
        }
        if (name.equals("STONE") || name.equals("COBBLESTONE") || name.equals("OAK_PLANKS") || name.equals("STONE_BRICKS")) {
            return 3000;
        }
        if (name.contains("ORE")) {
            return 15000;
        }
        if (name.equals("BEDROCK") || name.equals("OBSIDIAN")) {
            return 300000;
        }
        if (name.equals("WATER") || name.equals("LAVA")) {
            return 1000;
        }
        if (name.contains("PLANKS") || name.contains("WOOD")) {
            return 2500;
        }
        return 2000;
    }

    private void analyzeBreakTimes(Player player, BlockBreakData data) {
        double averageRatio = data.getAverageRatio();
        double variance = data.getVariance();

        if (averageRatio > NORMAL_BREAK_TIME_TOLERANCE && variance < VARIANCE_THRESHOLD) {
            getManager().getViolationManager().recordViolation(
                    player,
                    ViolationType.FAST_BREAK,
                    String.format("破坏速度异常稳定: 平均比率%.2f, 方差%.6f", averageRatio, variance),
                    averageRatio / NORMAL_BREAK_TIME_TOLERANCE
            );
        }

        if (averageRatio > NORMAL_BREAK_TIME_TOLERANCE * 2) {
            getManager().getViolationManager().recordViolation(
                    player,
                    ViolationType.AUTO_MINER,
                    String.format("疑似自动矿工: 破坏速度比率%.2f", averageRatio),
                    2.0
            );
        }
    }

    public void cleanup(UUID uuid) {
        breakData.remove(uuid);
    }

    private static class BlockBreakData {
        private final double[] ratios = new double[20];
        private int count = 0;
        private double ratioSum = 0;
        private double ratioSumSquares = 0;
        private long currentBreakStart;
        private Material currentBlockType;

        void addBreakTime(long actualTime, long expectedTime) {
            if (actualTime <= 0 || expectedTime <= 0) return;

            double ratio = (double) expectedTime / actualTime;
            if (count < ratios.length) {
                ratios[count++] = ratio;
                ratioSum += ratio;
                ratioSumSquares += ratio * ratio;
            }
        }

        double getAverageRatio() {
            return count > 0 ? ratioSum / count : 0;
        }

        double getVariance() {
            if (count < 2) return 0;
            double mean = getAverageRatio();
            return (ratioSumSquares / count) - (mean * mean);
        }

        int getCount() {
            return count;
        }

        void clear() {
            count = 0;
            ratioSum = 0;
            ratioSumSquares = 0;
        }

        void setCurrentBreakStart(long time) {
            this.currentBreakStart = time;
        }

        long getCurrentBreakStart() {
            return currentBreakStart;
        }

        void setCurrentBlockType(Material type) {
            this.currentBlockType = type;
        }

        Material getCurrentBlockType() {
            return currentBlockType;
        }
    }
}