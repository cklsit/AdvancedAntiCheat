package com.anticheat.detection;

import com.anticheat.managers.DetectionManager;
import com.anticheat.detection.ViolationRecord.ViolationType;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ScaffoldDetection extends Detection {

    private final Map<UUID, ScaffoldData> scaffoldData;
    private static final double MAX_SAFE_DISTANCE = 0.1;
    private static final double MAX_EXPECTED_REACH = 4.5;
    private static final int MIN_SAME_BLOCK_COUNT = 5;
    private static final double DIRECTION_CHANGE_THRESHOLD = 0.3;

    public ScaffoldDetection(DetectionManager manager) {
        super(manager);
        this.scaffoldData = new ConcurrentHashMap<>();
    }

    @Override
    public void check(Player player) {
        if (shouldSkipDetection(player)) {
            return;
        }

        UUID uuid = player.getUniqueId();
        Location playerLoc = player.getLocation();
        Vector direction = playerLoc.getDirection();

        Block targetBlock = playerLoc.add(direction.multiply(MAX_EXPECTED_REACH)).getBlock();

        if (isPlaceableBlock(targetBlock)) {
            ScaffoldData data = scaffoldData.computeIfAbsent(uuid, k -> new ScaffoldData());

            Location blockBelowPlayer = playerLoc.clone().add(0, -1, 0).getBlock().getLocation();
            double distance = playerLoc.distance(blockBelowPlayer);

            data.addSample(targetBlock.getLocation(), direction.clone(), distance);

            if (data.getSameBlockCount() >= MIN_SAME_BLOCK_COUNT) {
                analyzeScaffold(player, data);
            }
        }
    }

    private boolean isPlaceableBlock(Block block) {
        Material type = block.getType();
        return type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR;
    }

    private void analyzeScaffold(Player player, ScaffoldData data) {
        if (data.getAverageDistance() > 2.5) {
            getManager().getViolationManager().recordViolation(
                    player,
                    ViolationType.SCAFFOLD,
                    String.format("异常放置距离: %.2f", data.getAverageDistance()),
                    data.getAverageDistance() / 2.5
            );
            data.reset();
            return;
        }

        if (data.hasConsistentDirection()) {
            getManager().getViolationManager().recordViolation(
                    player,
                    ViolationType.SCAFFOLD,
                    "方向过度一致，可能使用自动搭路",
                    2.0
            );
            data.reset();
            return;
        }

        if (data.getSameBlockCount() >= MIN_SAME_BLOCK_COUNT * 2) {
            getManager().getViolationManager().recordViolation(
                    player,
                    ViolationType.SCAFFOLD,
                    String.format("持续自动搭路: %d次", data.getSameBlockCount()),
                    1.5
            );
            data.reset();
        }
    }

    public void onBlockPlace(Player player) {
        if (hasBypassPermission(player)) {
            return;
        }

        UUID uuid = player.getUniqueId();
        ScaffoldData data = scaffoldData.get(uuid);
        if (data != null) {
            data.incrementPlaceCount();
        }
    }

    public void cleanup(UUID uuid) {
        scaffoldData.remove(uuid);
    }

    private static class ScaffoldData {
        private Location lastBlockLocation;
        private Vector lastDirection;
        private double totalDistance;
        private int sampleCount;
        private int sameBlockCount;
        private int placeCount;
        private double directionVarianceSum;

        ScaffoldData() {
            this.totalDistance = 0;
            this.sampleCount = 0;
            this.sameBlockCount = 0;
            this.placeCount = 0;
            this.directionVarianceSum = 0;
        }

        void addSample(Location blockLoc, Vector direction, double distance) {
            if (lastBlockLocation != null && lastBlockLocation.equals(blockLoc)) {
                sameBlockCount++;
            }

            if (lastDirection != null) {
                double dotProduct = lastDirection.dot(direction);
                directionVarianceSum += Math.abs(1 - dotProduct);
            }

            lastBlockLocation = blockLoc;
            lastDirection = direction;
            totalDistance += distance;
            sampleCount++;
        }

        double getAverageDistance() {
            return sampleCount > 0 ? totalDistance / sampleCount : 0;
        }

        int getSameBlockCount() {
            return sameBlockCount;
        }

        boolean hasConsistentDirection() {
            return sampleCount > 3 && directionVarianceSum / sampleCount < DIRECTION_CHANGE_THRESHOLD;
        }

        void incrementPlaceCount() {
            placeCount++;
        }

        void reset() {
            lastBlockLocation = null;
            lastDirection = null;
            totalDistance = 0;
            sampleCount = 0;
            sameBlockCount = 0;
            placeCount = 0;
            directionVarianceSum = 0;
        }
    }
}