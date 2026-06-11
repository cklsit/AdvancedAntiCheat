package com.anticheat.detection;

import com.anticheat.detection.ViolationRecord.ViolationType;
import com.anticheat.managers.DetectionManager;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class KillAuraDetection extends Detection {

    private final Map<UUID, List<Double>> hitAngles;
    private final Map<UUID, List<Double>> cpsHistory;
    private final Map<UUID, Integer> targetSwitches;
    private final Map<UUID, Long> lastHitTime;
    private final Map<UUID, UUID> currentTarget;

    private static final double MAX_CPS = 20.0;
    private static final double MIN_CPS_VARIANCE = 0.1;
    private static final int SAMPLE_SIZE = 20;
    private static final double SUSPICIOUS_REACH = 3.5;

    public KillAuraDetection(DetectionManager manager) {
        super(manager);
        this.hitAngles = new ConcurrentHashMap<>();
        this.cpsHistory = new ConcurrentHashMap<>();
        this.targetSwitches = new ConcurrentHashMap<>();
        this.lastHitTime = new ConcurrentHashMap<>();
        this.currentTarget = new ConcurrentHashMap<>();
    }

    @Override
    public void check(Player player) {
        if (shouldSkipDetection(player)) {
            return;
        }
    }

    public void onEntityHit(Player attacker, LivingEntity victim, long timestamp) {
        if (hasBypassPermission(attacker)) {
            return;
        }

        if (attacker.getGameMode() == org.bukkit.GameMode.CREATIVE) {
            return;
        }

        UUID attackerUUID = attacker.getUniqueId();

        double reach = attacker.getLocation().distance(victim.getLocation());
        if (reach > SUSPICIOUS_REACH) {
            getManager().getViolationManager().recordViolation(
                    attacker,
                    ViolationType.REACH,
                    String.format("攻击距离: %.2f 方块", reach),
                    reach / SUSPICIOUS_REACH
            );
        }

        Location attackerEye = attacker.getLocation().add(0, attacker.getEyeHeight(), 0);
        Location victimEye = victim.getLocation().add(0, victim.getEyeHeight(), 0);

        Vector direction = attackerEye.getDirection();
        Vector toVictim = victimEye.toVector().subtract(attackerEye.toVector()).normalize();

        double angle = Math.toDegrees(Math.acos(direction.dot(toVictim)));

        hitAngles.computeIfAbsent(attackerUUID, k -> new ArrayList<>()).add(angle);

        Long lastHit = lastHitTime.get(attackerUUID);
        if (lastHit != null) {
            double timeDiff = (timestamp - lastHit) / 1000.0;
            if (timeDiff > 0.05 && timeDiff < 1.0) {
                double cps = 1.0 / timeDiff;
                cpsHistory.computeIfAbsent(attackerUUID, k -> new ArrayList<>()).add(cps);
            }
        }
        lastHitTime.put(attackerUUID, timestamp);

        UUID previousTarget = currentTarget.get(attackerUUID);
        if (previousTarget != null && !previousTarget.equals(victim.getUniqueId())) {
            targetSwitches.computeIfAbsent(attackerUUID, k -> 0);
            targetSwitches.put(attackerUUID, targetSwitches.get(attackerUUID) + 1);
        }
        currentTarget.put(attackerUUID, victim.getUniqueId());

        List<Double> angles = hitAngles.get(attackerUUID);
        if (angles != null && angles.size() >= SAMPLE_SIZE) {
            analyzeAngles(attacker, angles);
            angles.clear();
        }

        List<Double> cps = cpsHistory.get(attackerUUID);
        if (cps != null && cps.size() >= SAMPLE_SIZE) {
            analyzeCPS(attacker, cps);
            cps.clear();
        }
    }

    private void analyzeAngles(Player player, List<Double> angles) {
        if (angles.size() < 5) return;

        double sum = 0;
        for (double angle : angles) {
            sum += angle;
        }
        double mean = sum / angles.size();

        double varianceSum = 0;
        for (double angle : angles) {
            varianceSum += (angle - mean) * (angle - mean);
        }
        double variance = varianceSum / angles.size();
        double stdDev = Math.sqrt(variance);

        if (mean < 5.0 && stdDev < 2.0) {
            getManager().getViolationManager().recordViolation(
                    player,
                    ViolationType.KILLAURA,
                    String.format("精准度过高: 平均角度%.2f, 标准差%.2f", mean, stdDev),
                    2.0
            );
        }

        if (angles.stream().allMatch(a -> a < 1.0)) {
            getManager().getViolationManager().recordViolation(
                    player,
                    ViolationType.KILLAURA,
                    String.format("完美瞄准: 所有命中角度<1度"),
                    3.0
            );
        }
    }

    private void analyzeCPS(Player player, List<Double> cpsList) {
        if (cpsList.size() < 5) return;

        double sum = 0;
        for (double cps : cpsList) {
            sum += cps;
        }
        double mean = sum / cpsList.size();

        double varianceSum = 0;
        for (double cps : cpsList) {
            varianceSum += (cps - mean) * (cps - mean);
        }
        double variance = varianceSum / cpsList.size();
        double stdDev = Math.sqrt(variance);

        if (mean > MAX_CPS) {
            getManager().getViolationManager().recordViolation(
                    player,
                    ViolationType.AUTO_HIT,
                    String.format("CPS过高: %.1f", mean),
                    mean / MAX_CPS
            );
        }

        if (stdDev < MIN_CPS_VARIANCE && mean > 5.0) {
            getManager().getViolationManager().recordViolation(
                    player,
                    ViolationType.CPS_ANOMALY,
                    String.format("CPS异常恒定: %.1f, 方差: %.4f", mean, variance),
                    2.0
            );
        }
    }

    public void onPlayerAttack(Player attacker, Entity victim) {
        if (victim instanceof LivingEntity) {
            onEntityHit(attacker, (LivingEntity) victim, System.currentTimeMillis());
        }
    }

    public void cleanup(UUID uuid) {
        hitAngles.remove(uuid);
        cpsHistory.remove(uuid);
        targetSwitches.remove(uuid);
        lastHitTime.remove(uuid);
        currentTarget.remove(uuid);
    }
}