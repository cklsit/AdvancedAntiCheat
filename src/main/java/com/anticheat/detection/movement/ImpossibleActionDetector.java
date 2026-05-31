package com.anticheat.detection.movement;

import com.anticheat.detection.physics.EntitySnapshot;
import com.anticheat.detection.physics.PhysicsConstants;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.projectiles.ProjectileSource;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ImpossibleActionDetector不可能动作检测器
 * 检测玩家执行的不可能物理动作，包括空中二次跳跃、无摔伤、水面行走和穿墙等
 */
public class ImpossibleActionDetector {

    private final Map<UUID, JumpData> playerJumpData = new ConcurrentHashMap<>();
    private final Map<UUID, FallData> playerFallData = new ConcurrentHashMap<>();
    private final Map<UUID, WaterWalkData> playerWaterWalkData = new ConcurrentHashMap<>();
    private final Map<UUID, PhaseData> playerPhaseData = new ConcurrentHashMap<>();

    private static final double AIR_JUMP_THRESHOLD = 0.42;
    private static final double FALL_DAMAGE_THRESHOLD = 3.0;
    private static final double WATER_WALK_Y_THRESHOLD = 0.01;
    private static final int AIR_JUMP_GRACE_TICKS = 5;
    private static final int PHASE_CHECK_DISTANCE = 3;
    private static final double MIN_PHASE_SPEED = 0.8;

    /**
     * 检测空中二次跳跃
     * @param player 玩家
     * @param from 起始快照
     * @param to 目标快照
     * @return 违规对象，如果没有违规返回null
     */
    public MovementViolation checkAirJump(Player player, EntitySnapshot from, EntitySnapshot to) {
        if (isCreativeOrSpectator(player)) {
            return null;
        }

        if (hasExemptionForAirJump(player)) {
            clearJumpData(player.getUniqueId());
            return null;
        }

        UUID uuid = player.getUniqueId();
        JumpData jumpData = playerJumpData.computeIfAbsent(uuid, k -> new JumpData());
        
        boolean wasOnGround = from.isOnGround();
        boolean isNowOnGround = to.isOnGround();
        double verticalVelocity = to.getVelocity().getY();
        
        if (wasOnGround && !isNowOnGround && verticalVelocity > AIR_JUMP_THRESHOLD) {
            jumpData.lastJumpTick = to.getTick();
            jumpData.airborne = true;
            jumpData.airborneTicks = 0;
        }
        
        if (!isNowOnGround && jumpData.airborne) {
            jumpData.airborneTicks++;
            
            if (verticalVelocity > AIR_JUMP_THRESHOLD && 
                (to.getTick() - jumpData.lastJumpTick > AIR_JUMP_GRACE_TICKS)) {
                
                double probability = calculateAirJumpProbability(jumpData.airborneTicks);
                
                jumpData.consecutiveAirJumps++;
                jumpData.lastAirJumpTick = to.getTick();
                
                return new MovementViolation(
                    player.getUniqueId(),
                    player.getName(),
                    MovementViolationType.IMPOSSIBLE_JUMP,
                    from,
                    to,
                    probability,
                    String.format("在空中执行了第%d次跳跃（速度Y=%.3f，空中时间=%dtick）",
                        jumpData.consecutiveAirJumps, verticalVelocity, jumpData.airborneTicks),
                    jumpData.consecutiveAirJumps
                );
            }
        }
        
        if (isNowOnGround) {
            jumpData.airborne = false;
            jumpData.consecutiveAirJumps = 0;
            jumpData.airborneTicks = 0;
        }
        
        return null;
    }

    /**
     * 检测无摔伤
     * @param player 玩家
     * @param from 起始快照
     * @param to 目标快照
     * @return 违规对象，如果没有违规返回null
     */
    public MovementViolation checkNoFallDamage(Player player, EntitySnapshot from, EntitySnapshot to) {
        if (isCreativeOrSpectator(player)) {
            return null;
        }

        if (hasExemptionForNoFall(player)) {
            clearFallData(player.getUniqueId());
            return null;
        }

        UUID uuid = player.getUniqueId();
        FallData fallData = playerFallData.computeIfAbsent(uuid, k -> new FallData());
        
        double fromY = from.getPosition().getY();
        double toY = to.getPosition().getY();
        double fallDistance = fallData.startFallY - toY;
        
        if (!from.isOnGround() && !to.isOnGround() && fromY > toY) {
            if (fallData.startFallY == 0 || fallData.startFallY < fromY) {
                fallData.startFallY = fromY;
                fallData.maxFallDistance = 0;
            }
            
            if (fallDistance > fallData.maxFallDistance) {
                fallData.maxFallDistance = fallDistance;
            }
        }
        
        if (to.isOnGround() && fallData.maxFallDistance > FALL_DAMAGE_THRESHOLD) {
            double expectedDamage = calculateExpectedFallDamage(fallData.maxFallDistance);
            
            if (expectedDamage > 0 && player.getHealth() >= expectedDamage) {
                
                double probability = calculateNoFallProbability(fallData.maxFallDistance, expectedDamage);
                
                MovementViolation violation = new MovementViolation(
                    player.getUniqueId(),
                    player.getName(),
                    MovementViolationType.NO_FALL,
                    from,
                    to,
                    probability,
                    String.format("从%.1f格高处落下应受到%.1f心伤害但未受伤",
                        fallData.maxFallDistance, expectedDamage),
                    1
                );
                
                fallData.startFallY = 0;
                fallData.maxFallDistance = 0;
                
                return violation;
            }
            
            fallData.startFallY = 0;
            fallData.maxFallDistance = 0;
        }
        
        return null;
    }

    /**
     * 检测水面行走
     * @param player 玩家
     * @param from 起始快照
     * @param to 目标快照
     * @return 违规对象，如果没有违规返回null
     */
    public MovementViolation checkWaterWalk(Player player, EntitySnapshot from, EntitySnapshot to) {
        if (isCreativeOrSpectator(player)) {
            return null;
        }

        if (hasExemptionForWaterWalk(player)) {
            clearWaterWalkData(player.getUniqueId());
            return null;
        }

        UUID uuid = player.getUniqueId();
        WaterWalkData walkData = playerWaterWalkData.computeIfAbsent(uuid, k -> new WaterWalkData());
        
        Location playerLoc = player.getLocation();
        Location belowLoc = playerLoc.clone().subtract(0, 1, 0);
        Block belowBlock = belowLoc.getBlock();
        
        if (!isWater(belowBlock.getType())) {
            walkData.waterWalkTicks = 0;
            return null;
        }
        
        if (player.isSprinting() || player.isSwimming() || player.isInWater()) {
            walkData.waterWalkTicks = 0;
            return null;
        }
        
        double yChange = Math.abs(to.getPosition().getY() - from.getPosition().getY());
        
        if (yChange < WATER_WALK_Y_THRESHOLD && to.isOnGround()) {
            walkData.waterWalkTicks++;
            
            if (walkData.waterWalkTicks > 20) {
                double horizontalSpeed = Math.sqrt(
                    Math.pow(to.getPosition().getX() - from.getPosition().getX(), 2) +
                    Math.pow(to.getPosition().getZ() - from.getPosition().getZ(), 2)
                );
                
                if (horizontalSpeed > 0.05) {
                    double probability = calculateWaterWalkProbability(walkData.waterWalkTicks);
                    
                    return new MovementViolation(
                        player.getUniqueId(),
                        player.getName(),
                        MovementViolationType.WATER_WALK,
                        from,
                        to,
                        probability,
                        String.format("在水面直立行走（水面时间=%dtick，水平速度=%.3f）",
                            walkData.waterWalkTicks, horizontalSpeed),
                        walkData.waterWalkTicks / 10
                    );
                }
            }
        } else {
            walkData.waterWalkTicks = Math.max(0, walkData.waterWalkTicks - 2);
        }
        
        return null;
    }

    /**
     * 检测穿墙
     * @param player 玩家
     * @param from 起始快照
     * @param to 目标快照
     * @return 违规对象，如果没有违规返回null
     */
    public MovementViolation checkPhase(Player player, EntitySnapshot from, EntitySnapshot to) {
        if (isCreativeOrSpectator(player)) {
            return null;
        }

        if (hasExemptionForPhase(player)) {
            clearPhaseData(player.getUniqueId());
            return null;
        }

        UUID uuid = player.getUniqueId();
        PhaseData phaseData = playerPhaseData.computeIfAbsent(uuid, k -> new PhaseData());
        
        double dx = to.getPosition().getX() - from.getPosition().getX();
        double dy = to.getPosition().getY() - from.getPosition().getY();
        double dz = to.getPosition().getZ() - from.getPosition().getZ();
        
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        
        if (distance < 0.01 || distance > MIN_PHASE_SPEED) {
            phaseData.phaseTicks = 0;
            return null;
        }
        
        Location fromLoc = from.getPosition().toLocation(player.getWorld());
        Location toLoc = to.getPosition().toLocation(player.getWorld());
        
        int solidBlocksPassed = countSolidBlocksBetween(fromLoc, toLoc);
        
        if (solidBlocksPassed > 0) {
            phaseData.phaseTicks++;
            
            if (phaseData.phaseTicks > 3) {
                double probability = calculatePhaseProbability(solidBlocksPassed, phaseData.phaseTicks);
                
                return new MovementViolation(
                    player.getUniqueId(),
                    player.getName(),
                    MovementViolationType.PHASE,
                    from,
                    to,
                    probability,
                    String.format("穿过了%d个固体方块（连续穿墙=%dtick）",
                        solidBlocksPassed, phaseData.phaseTicks),
                    phaseData.phaseTicks
                );
            }
        } else {
            phaseData.phaseTicks = Math.max(0, phaseData.phaseTicks - 1);
        }
        
        return null;
    }

    /**
     * 计算空气跳跃违规概率
     */
    private double calculateAirJumpProbability(int airborneTicks) {
        double base = 0.5;
        if (airborneTicks > 10) {
            base += 0.3;
        }
        if (airborneTicks > 20) {
            base += 0.2;
        }
        return Math.min(base, 1.0);
    }

    /**
     * 计算无摔伤违规概率
     */
    private double calculateNoFallProbability(double fallDistance, double expectedDamage) {
        double base = 0.7;
        if (fallDistance > 10) {
            base += 0.2;
        }
        if (expectedDamage > 10) {
            base += 0.1;
        }
        return Math.min(base, 1.0);
    }

    /**
     * 计算水面行走违规概率
     */
    private double calculateWaterWalkProbability(int waterWalkTicks) {
        double base = 0.6;
        if (waterWalkTicks > 40) {
            base += 0.2;
        }
        if (waterWalkTicks > 100) {
            base += 0.2;
        }
        return Math.min(base, 1.0);
    }

    /**
     * 计算穿墙违规概率
     */
    private double calculatePhaseProbability(int solidBlocks, int phaseTicks) {
        double base = 0.8;
        if (solidBlocks > 1) {
            base += 0.1;
        }
        if (phaseTicks > 5) {
            base += 0.1;
        }
        return Math.min(base, 1.0);
    }

    /**
     * 计算预期摔落伤害
     */
    private double calculateExpectedFallDamage(double fallDistance) {
        if (fallDistance <= 3.0) {
            return 0;
        }
        return (fallDistance - 3.0) * 0.5;
    }

    /**
     * 检查创意模式或旁观者模式
     */
    private boolean isCreativeOrSpectator(Player player) {
        return player.getGameMode() == org.bukkit.GameMode.CREATIVE ||
               player.getGameMode() == org.bukkit.GameMode.SPECTATOR;
    }

    /**
     * 检查空中跳跃豁免
     */
    private boolean hasExemptionForAirJump(Player player) {
        if (player.isInsideVehicle()) {
            return true;
        }
        
        if (hasPotionEffect(player, "SLOW_FALLING")) {
            return true;
        }
        
        if (isRidingEntity(player)) {
            return true;
        }
        
        return false;
    }

    /**
     * 检查无摔伤豁免
     */
    private boolean hasExemptionForNoFall(Player player) {
        if (player.isInsideVehicle()) {
            return true;
        }
        
        Location feetLoc = player.getLocation();
        Block belowBlock = feetLoc.subtract(0, 1, 0).getBlock();
        
        if (isFallDamageReductionBlock(belowBlock.getType())) {
            return true;
        }
        
        if (hasPotionEffect(player, "SLOW_FALLING")) {
            return true;
        }
        
        if (player.isGliding()) {
            return true;
        }
        
        return false;
    }

    /**
     * 检查水面行走豁免
     */
    private boolean hasExemptionForWaterWalk(Player player) {
        if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.WATER_BREATHING)) {
            return true;
        }
        
        if (player.getInventory().getBoots() != null &&
            player.getInventory().getBoots().containsEnchantment(org.bukkit.enchantments.Enchantment.DEPTH_STRIDER)) {
            return true;
        }
        
        if (player.isInsideVehicle()) {
            Entity vehicle = player.getVehicle();
            if (vehicle != null && vehicle.getType() == EntityType.SPIDER) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * 检查穿墙豁免
     */
    private boolean hasExemptionForPhase(Player player) {
        if (player.isInsideVehicle()) {
            return true;
        }
        
        if (hasPotionEffect(player, "LEVITATION")) {
            return true;
        }
        
        return false;
    }

    /**
     * 检查药水效果
     */
    private boolean hasPotionEffect(Player player, String effectName) {
        try {
            org.bukkit.potion.PotionEffectType effectType = 
                org.bukkit.potion.PotionEffectType.getByName(effectName);
            if (effectType != null) {
                return player.hasPotionEffect(effectType);
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /**
     * 检查是否骑乘实体
     */
    private boolean isRidingEntity(Player player) {
        return player.isInsideVehicle() && player.getVehicle() != null;
    }

    /**
     * 检查是否是减少摔落伤害的方块
     */
    private boolean isFallDamageReductionBlock(Material material) {
        return material == Material.WATER ||
               material == Material.SUGAR_CANE ||
               material == Material.COBWEB ||
               material == Material.HAY_BLOCK;
    }

    /**
     * 检查是否是水方块
     */
    private boolean isWater(Material material) {
        return material == Material.WATER;
    }

    /**
     * 计算两点之间的固体方块数量
     */
    private int countSolidBlocksBetween(Location from, Location to) {
        int count = 0;
        
        double steps = Math.max(
            Math.abs(to.getX() - from.getX()),
            Math.max(Math.abs(to.getY() - from.getY()), Math.abs(to.getZ() - from.getZ()))
        ) * 2;
        
        for (int i = 0; i <= steps; i++) {
            double ratio = steps == 0 ? 0 : i / steps;
            
            double x = from.getX() + (to.getX() - from.getX()) * ratio;
            double y = from.getY() + (to.getY() - from.getY()) * ratio;
            double z = from.getZ() + (to.getZ() - from.getZ()) * ratio;
            
            Location checkLoc = new Location(from.getWorld(), x, y, z);
            Block block = checkLoc.getBlock();
            
            if (block.getType().isSolid() && !block.isPassable()) {
                count++;
            }
        }
        
        return count;
    }

    /**
     * 清除跳跃数据
     */
    public void clearJumpData(UUID uuid) {
        playerJumpData.remove(uuid);
    }

    /**
     * 清除摔落数据
     */
    public void clearFallData(UUID uuid) {
        playerFallData.remove(uuid);
    }

    /**
     * 清除水面行走数据
     */
    public void clearWaterWalkData(UUID uuid) {
        playerWaterWalkData.remove(uuid);
    }

    /**
     * 清除穿墙数据
     */
    public void clearPhaseData(UUID uuid) {
        playerPhaseData.remove(uuid);
    }

    /**
     * 清除所有玩家数据
     */
    public void clearAllData(UUID uuid) {
        clearJumpData(uuid);
        clearFallData(uuid);
        clearWaterWalkData(uuid);
        clearPhaseData(uuid);
    }

    /**
     * 跳跃数据内部类
     */
    private static class JumpData {
        long lastJumpTick = 0;
        long lastAirJumpTick = 0;
        boolean airborne = false;
        int airborneTicks = 0;
        int consecutiveAirJumps = 0;
    }

    /**
     * 摔落数据内部类
     */
    private static class FallData {
        double startFallY = 0;
        double maxFallDistance = 0;
    }

    /**
     * 水面行走数据内部类
     */
    private static class WaterWalkData {
        int waterWalkTicks = 0;
    }

    /**
     * 穿墙数据内部类
     */
    private static class PhaseData {
        int phaseTicks = 0;
    }
}
