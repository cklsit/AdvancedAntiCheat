package com.anticheat.detection;

import com.anticheat.managers.DetectionManager;
import com.anticheat.detection.ViolationRecord.ViolationType;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NoSlowDetection extends Detection {

    private final Map<UUID, NoSlowData> noSlowData;
    private static final double NORMAL_SLOW_FACTOR = 0.2;
    private static final int SAMPLE_SIZE = 15;

    public NoSlowDetection(DetectionManager manager) {
        super(manager);
        this.noSlowData = new ConcurrentHashMap<>();
    }

    @Override
    public void check(Player player) {
        if (shouldSkipDetection(player)) {
            return;
        }

        UUID uuid = player.getUniqueId();

        boolean inWater = player.isInWater() || isInLava(player);
        boolean hasSlowEffect = hasSlowEffect(player);
        boolean isFlying = player.isFlying();

        NoSlowData data = noSlowData.computeIfAbsent(uuid, k -> new NoSlowData());

        if (inWater && !hasSlowEffect && !isFlying) {
            data.incrementWaterNoSlowCount();
        } else {
            data.resetWaterNoSlowCount();
        }

        if (isFlying && !hasSlowEffect) {
            data.incrementFlyingNoSlowCount();
        } else {
            data.resetFlyingNoSlowCount();
        }

        if (data.getWaterNoSlowCount() >= SAMPLE_SIZE) {
            getManager().getViolationManager().recordViolation(
                    player,
                    ViolationType.NO_SLOW_MINING,
                    String.format("水中无减速: %d次检测", data.getWaterNoSlowCount()),
                    data.getWaterNoSlowCount() / 10.0
            );
            data.resetWaterNoSlowCount();
        }

        if (data.getFlyingNoSlowCount() >= SAMPLE_SIZE) {
            getManager().getViolationManager().recordViolation(
                    player,
                    ViolationType.NO_SLOW_MINING,
                    String.format("飞行无减速: %d次检测", data.getFlyingNoSlowCount()),
                    data.getFlyingNoSlowCount() / 10.0
            );
            data.resetFlyingNoSlowCount();
        }
    }

    private boolean hasSlowEffect(Player player) {
        for (org.bukkit.potion.PotionEffect effect : player.getActivePotionEffects()) {
            PotionEffectType type = effect.getType();
            if (type != null) {
                String name = type.getName();
                if (name != null && (name.equals("SLOW") || name.equals("SLOWNESS"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isInLava(Player player) {
        Material mat = player.getLocation().getBlock().getType();
        return mat == Material.LAVA || mat.name().contains("LAVA");
    }

    public void onGroundCheck(Player player, double moveSpeed) {
        if (hasBypassPermission(player)) {
            return;
        }

        UUID uuid = player.getUniqueId();
        boolean inWater = player.isInWater();
        boolean hasSlowEffect = hasSlowEffect(player);
        boolean isFlying = player.isFlying();

        if (inWater && !hasSlowEffect && !isFlying && player.getGameMode() != org.bukkit.GameMode.CREATIVE) {
            double expectedSpeed = NORMAL_SLOW_FACTOR;
            if (moveSpeed > expectedSpeed * 2) {
                NoSlowData data = noSlowData.computeIfAbsent(uuid, k -> new NoSlowData());
                data.incrementWaterNoSlowCount();

                if (data.getWaterNoSlowCount() >= 5) {
                    getManager().getViolationManager().recordViolation(
                            player,
                            ViolationType.NO_SLOW_MINING,
                            String.format("水中移动速度异常: %.3f (预期: %.3f)", moveSpeed, expectedSpeed),
                            moveSpeed / expectedSpeed
                    );
                    data.resetWaterNoSlowCount();
                }
            }
        }
    }

    public void cleanup(UUID uuid) {
        noSlowData.remove(uuid);
    }

    private static class NoSlowData {
        private int waterNoSlowCount;
        private int flyingNoSlowCount;
        private long lastCheckTime;

        void incrementWaterNoSlowCount() {
            waterNoSlowCount++;
            lastCheckTime = System.currentTimeMillis();
        }

        void incrementFlyingNoSlowCount() {
            flyingNoSlowCount++;
            lastCheckTime = System.currentTimeMillis();
        }

        void resetWaterNoSlowCount() {
            waterNoSlowCount = 0;
        }

        void resetFlyingNoSlowCount() {
            flyingNoSlowCount = 0;
        }

        int getWaterNoSlowCount() {
            return waterNoSlowCount;
        }

        int getFlyingNoSlowCount() {
            return flyingNoSlowCount;
        }

        long getLastCheckTime() {
            return lastCheckTime;
        }
    }
}