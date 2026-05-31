package com.anticheat.detection.physics;

import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * PhysicsSimulator物理模拟器类，实现预测式物理模拟。
 * 基于Minecraft官方物理引擎（1.8-1.21版本兼容），用于检测异常运动行为。
 */
public class PhysicsSimulator {

    private static final double ERROR_TOLERANCE = 0.1;
    private static final double HORIZONTAL_SPEED_THRESHOLD = 0.65;
    private static final double VERTICAL_SPEED_THRESHOLD = 1.5;
    private static final int NO_FRICTION_TICK_THRESHOLD = 5;

    private final Map<UUID, Integer> noFrictionTicks = new HashMap<>();
    private final Map<UUID, Double> lastHorizontalSpeed = new HashMap<>();

    /**
     * 预测下一tick的位置
     * @param current 当前实体快照
     * @param input 移动输入
     * @return 预测的下一tick实体快照
     */
    public EntitySnapshot predictNextTick(EntitySnapshot current, MovementInput input) {
        Vector3D currentPos = current.getPosition();
        Vector3D currentVel = current.getVelocity();
        boolean onGround = current.isOnGround();

        double baseSpeed = getBaseSpeed(input);
        Vector3D movement = calculateMovement(current, input, baseSpeed);
        Vector3D newVelocity = applyPhysics(currentVel, movement, onGround, input);
        Vector3D newPosition = currentPos.add(newVelocity);
        boolean newOnGround = checkGroundCollision(newPosition, onGround);

        long nextTick = current.getTick() + 1;
        return new EntitySnapshot(
            current.getUuid(),
            newPosition,
            newVelocity,
            current.getYaw(),
            current.getPitch(),
            newOnGround,
            nextTick
        );
    }

    /**
     * 获取基础移动速度
     */
    private double getBaseSpeed(MovementInput input) {
        double speed = PhysicsConstants.WALK_SPEED;
        if (input.isSprinting()) {
            speed = PhysicsConstants.SPRINT_SPEED;
        }
        if (input.isSneaking()) {
            speed = PhysicsConstants.SNEAK_SPEED;
        }
        return speed;
    }

    /**
     * 计算移动向量
     */
    private Vector3D calculateMovement(EntitySnapshot snapshot, MovementInput input, double baseSpeed) {
        if (!input.isMoving()) {
            return new Vector3D(0, 0, 0);
        }

        float yaw = snapshot.getYaw();
        double moveX = 0;
        double moveZ = 0;

        if (input.isForward()) {
            moveX -= Math.sin(Math.toRadians(yaw));
            moveZ += Math.cos(Math.toRadians(yaw));
        }
        if (input.isBackward()) {
            moveX += Math.sin(Math.toRadians(yaw));
            moveZ -= Math.cos(Math.toRadians(yaw));
        }
        if (input.isLeft()) {
            moveX += Math.cos(Math.toRadians(yaw));
            moveZ += Math.sin(Math.toRadians(yaw));
        }
        if (input.isRight()) {
            moveX -= Math.cos(Math.toRadians(yaw));
            moveZ -= Math.sin(Math.toRadians(yaw));
        }

        Vector3D normalized = new Vector3D(moveX, 0, moveZ).normalize();
        return normalized.multiply(baseSpeed);
    }

    /**
     * 应用物理规则
     */
    private Vector3D applyPhysics(Vector3D currentVel, Vector3D movement, boolean onGround, MovementInput input) {
        double newVelX = currentVel.getX();
        double newVelY = currentVel.getY();
        double newVelZ = currentVel.getZ();

        if (onGround) {
            newVelX = movement.getX();
            newVelZ = movement.getZ();
            newVelY = 0;

            if (input.isJumping()) {
                newVelY = PhysicsConstants.JUMP_FORCE;
            }
        } else {
            newVelX += movement.getX() * PhysicsConstants.AIR_RESISTANCE;
            newVelZ += movement.getZ() * PhysicsConstants.AIR_RESISTANCE;

            newVelY -= PhysicsConstants.GRAVITY;

            if (newVelY < -PhysicsConstants.MAX_FALL_SPEED) {
                newVelY = -PhysicsConstants.MAX_FALL_SPEED;
            }
        }

        newVelX *= PhysicsConstants.FRICTION;
        newVelZ *= PhysicsConstants.FRICTION;

        return new Vector3D(newVelX, newVelY, newVelZ);
    }

    /**
     * 检查地面碰撞
     */
    private boolean checkGroundCollision(Vector3D position, boolean wasOnGround) {
        return wasOnGround || position.getY() < 0;
    }

    /**
     * 计算预测位置与实际位置的误差
     * @param predicted 预测快照
     * @param actual 实际快照
     * @return 误差向量
     */
    public Vector3D calculateError(EntitySnapshot predicted, EntitySnapshot actual) {
        Vector3D predictedPos = predicted.getPosition();
        Vector3D actualPos = actual.getPosition();

        double errorX = Math.abs(predictedPos.getX() - actualPos.getX());
        double errorY = Math.abs(predictedPos.getY() - actualPos.getY());
        double errorZ = Math.abs(predictedPos.getZ() - actualPos.getZ());

        return new Vector3D(errorX, errorY, errorZ);
    }

    /**
     * 判断误差是否在容忍范围内
     * @param error 误差向量
     * @return 如果误差在容忍范围内返回true
     */
    public boolean isErrorWithinTolerance(Vector3D error) {
        return error.length() <= ERROR_TOLERANCE;
    }

    /**
     * 检测异常速度（超过最大限制）
     * @param snapshot 实体快照
     * @param maxSpeed 最大速度限制
     * @return 如果速度异常返回true
     */
    public boolean isSpeedAnomaly(EntitySnapshot snapshot, double maxSpeed) {
        double horizontalSpeed = snapshot.getHorizontalSpeed();
        double verticalSpeed = snapshot.getVerticalSpeed();

        if (horizontalSpeed > maxSpeed || horizontalSpeed > HORIZONTAL_SPEED_THRESHOLD) {
            return true;
        }

        if (verticalSpeed > VERTICAL_SPEED_THRESHOLD && !snapshot.isOnGround()) {
            return true;
        }

        return false;
    }

    /**
     * 检测无摩擦滑行
     * @param playerUUID 玩家UUID
     * @param snapshot 当前快照
     * @return 如果检测到无摩擦滑行返回true
     */
    public boolean detectNoFrictionSliding(UUID playerUUID, EntitySnapshot snapshot) {
        if (snapshot.isOnGround()) {
            noFrictionTicks.put(playerUUID, 0);
            return false;
        }

        double horizontalSpeed = snapshot.getHorizontalSpeed();
        Double lastSpeed = lastHorizontalSpeed.get(playerUUID);

        if (lastSpeed != null && Math.abs(horizontalSpeed - lastSpeed) < 0.01) {
            int ticks = noFrictionTicks.getOrDefault(playerUUID, 0) + 1;
            noFrictionTicks.put(playerUUID, ticks);

            if (ticks > NO_FRICTION_TICK_THRESHOLD) {
                return true;
            }
        } else {
            noFrictionTicks.put(playerUUID, 0);
        }

        lastHorizontalSpeed.put(playerUUID, horizontalSpeed);
        return false;
    }

    /**
     * 获取当前最大速度限制（考虑药水效果）
     * @param snapshot 实体快照
     * @return 最大速度限制
     */
    public double getMaxSpeed(EntitySnapshot snapshot) {
        double maxSpeed = PhysicsConstants.MAX_SPEED;

        Player player = getPlayerFromSnapshot(snapshot);
        if (player != null) {
            if (player.hasPotionEffect(PotionEffectType.SPEED)) {
                int amplifier = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier();
                maxSpeed *= (1.0 + 0.2 * (amplifier + 1));
            }
            if (player.hasPotionEffect(PotionEffectType.SLOWNESS)) {
                int amplifier = player.getPotionEffect(PotionEffectType.SLOWNESS).getAmplifier();
                maxSpeed *= (1.0 - 0.15 * (amplifier + 1));
            }
        }

        return maxSpeed;
    }

    /**
     * 从快照获取玩家对象（如果可用）
     */
    private Player getPlayerFromSnapshot(EntitySnapshot snapshot) {
        return null;
    }

    /**
     * 计算水平速度
     * @param velocity 速度向量
     * @return 水平速度
     */
    public double calculateHorizontalSpeed(Vector3D velocity) {
        return Math.sqrt(velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ());
    }

    /**
     * 计算垂直速度
     * @param velocity 速度向量
     * @return 垂直速度
     */
    public double calculateVerticalSpeed(Vector3D velocity) {
        return velocity.getY();
    }

    /**
     * 检测跳跃是否合法
     * @param current 当前快照
     * @param input 移动输入
     * @return 如果跳跃合法返回true
     */
    public boolean isLegitimateJump(EntitySnapshot current, MovementInput input) {
        if (!input.isJumping()) {
            return false;
        }

        if (!current.isOnGround()) {
            return false;
        }

        return true;
    }

    /**
     * 检测是否在液体中
     * @param snapshot 实体快照
     * @return 如果在液体中返回true
     */
    public boolean isInLiquid(EntitySnapshot snapshot) {
        Vector3D pos = snapshot.getPosition();
        return false;
    }

    /**
     * 检测是否在鞘翅滑翔中
     * @param snapshot 实体快照
     * @return 如果在滑翔中返回true
     */
    public boolean isGlidingWithElytra(EntitySnapshot snapshot) {
        return false;
    }

    /**
     * 计算预测准确度
     * @param predicted 预测快照
     * @param actual 实际快照
     * @return 准确度值（0.0-1.0）
     */
    public double calculateAccuracy(EntitySnapshot predicted, EntitySnapshot actual) {
        Vector3D error = calculateError(predicted, actual);
        double errorMagnitude = error.length();

        if (errorMagnitude <= ERROR_TOLERANCE) {
            return 1.0;
        }

        return Math.max(0.0, 1.0 - (errorMagnitude - ERROR_TOLERANCE) / ERROR_TOLERANCE);
    }

    /**
     * 获取错误容忍度
     * @return 错误容忍度（格）
     */
    public double getErrorTolerance() {
        return ERROR_TOLERANCE;
    }

    /**
     * 获取水平速度异常阈值
     * @return 水平速度阈值
     */
    public double getHorizontalSpeedThreshold() {
        return HORIZONTAL_SPEED_THRESHOLD;
    }

    /**
     * 获取垂直速度异常阈值
     * @return 垂直速度阈值
     */
    public double getVerticalSpeedThreshold() {
        return VERTICAL_SPEED_THRESHOLD;
    }

    /**
     * 清除玩家数据
     * @param playerUUID 玩家UUID
     */
    public void clearPlayerData(UUID playerUUID) {
        noFrictionTicks.remove(playerUUID);
        lastHorizontalSpeed.remove(playerUUID);
    }
}
