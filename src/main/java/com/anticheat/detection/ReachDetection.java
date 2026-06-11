package com.anticheat.detection;

import com.anticheat.managers.DetectionManager;
import com.anticheat.detection.ViolationRecord.ViolationType;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ReachDetection extends Detection {

    private final Map<UUID, ReachData> reachData;
    private static final double NORMAL_REACH = 3.0;
    private static final double MAX_REACH = 3.5;
    private static final double ABSOLUTE_MAX = 4.0;
    private static final int SAMPLE_SIZE = 10;
    private static final double VARIANCE_THRESHOLD = 0.01;

    public ReachDetection(DetectionManager manager) {
        super(manager);
        this.reachData = new ConcurrentHashMap<>();
    }

    @Override
    public void check(Player player) {
        if (shouldSkipDetection(player)) {
            return;
        }
    }

    public void onAttack(Player attacker, LivingEntity victim) {
        if (hasBypassPermission(attacker) || attacker.getGameMode() == org.bukkit.GameMode.CREATIVE) {
            return;
        }

        UUID uuid = attacker.getUniqueId();

        Location attackerLoc = attacker.getLocation().add(0, attacker.getEyeHeight(), 0);
        Location victimLoc = victim.getLocation().add(0, victim.getEyeHeight() * 0.5, 0);

        double distance = attackerLoc.distance(victimLoc);

        ReachData data = reachData.computeIfAbsent(uuid, k -> new ReachData());
        data.addReach(distance);

        if (distance > ABSOLUTE_MAX) {
            getManager().getViolationManager().recordViolation(
                    attacker,
                    ViolationType.REACH,
                    String.format("极端攻击距离: %.2f 方块", distance),
                    distance / MAX_REACH
            );
            data.clear();
            return;
        }

        if (data.getCount() >= SAMPLE_SIZE) {
            analyzeReach(attacker, data);
            data.clear();
        }
    }

    private void analyzeReach(Player player, ReachData data) {
        double average = data.getAverage();
        double variance = data.getVariance();

        if (average > NORMAL_REACH && variance < VARIANCE_THRESHOLD) {
            getManager().getViolationManager().recordViolation(
                    player,
                    ViolationType.REACH,
                    String.format("异常稳定攻击距离: 平均%.2f, 方差%.4f", average, variance),
                    average / NORMAL_REACH
            );
        }

        if (average > MAX_REACH) {
            getManager().getViolationManager().recordViolation(
                    player,
                    ViolationType.REACH,
                    String.format("超出正常攻击距离: 平均%.2f", average),
                    average / MAX_REACH
            );
        }
    }

    public void cleanup(UUID uuid) {
        reachData.remove(uuid);
    }

    private static class ReachData {
        private final double[] samples = new double[20];
        private int count = 0;
        private double sum = 0;
        private double sumSquares = 0;

        void addReach(double reach) {
            if (count < samples.length) {
                samples[count++] = reach;
                sum += reach;
                sumSquares += reach * reach;
            }
        }

        double getAverage() {
            return count > 0 ? sum / count : 0;
        }

        double getVariance() {
            if (count < 2) return 0;
            double mean = getAverage();
            return (sumSquares / count) - (mean * mean);
        }

        int getCount() {
            return count;
        }

        void clear() {
            count = 0;
            sum = 0;
            sumSquares = 0;
        }
    }
}