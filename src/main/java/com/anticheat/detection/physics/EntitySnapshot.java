package com.anticheat.detection.physics;

import java.util.UUID;

/**
 * EntitySnapshot实体快照类，记录玩家在某个tick的状态。
 * 用于物理模拟对比，保存位置、速度、朝向等信息。
 */
public class EntitySnapshot {

    private final UUID uuid;
    private final Vector3D position;
    private final Vector3D velocity;
    private final float yaw;
    private final float pitch;
    private final boolean onGround;
    private final long tick;

    public EntitySnapshot(UUID uuid, Vector3D position, Vector3D velocity,
                         float yaw, float pitch, boolean onGround, long tick) {
        this.uuid = uuid;
        this.position = position;
        this.velocity = velocity;
        this.yaw = yaw;
        this.pitch = pitch;
        this.onGround = onGround;
        this.tick = tick;
    }

    public UUID getUuid() {
        return uuid;
    }

    public Vector3D getPosition() {
        return position;
    }

    public Vector3D getVelocity() {
        return velocity;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public boolean isOnGround() {
        return onGround;
    }

    public long getTick() {
        return tick;
    }

    public double getHorizontalSpeed() {
        return Math.sqrt(velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ());
    }

    public double getVerticalSpeed() {
        return velocity.getY();
    }

    public double getTotalSpeed() {
        return velocity.length();
    }

    public EntitySnapshot withPosition(Vector3D newPosition) {
        return new EntitySnapshot(uuid, newPosition, velocity, yaw, pitch, onGround, tick);
    }

    public EntitySnapshot withVelocity(Vector3D newVelocity) {
        return new EntitySnapshot(uuid, position, newVelocity, yaw, pitch, onGround, tick);
    }

    @Override
    public String toString() {
        return "EntitySnapshot{" +
                "uuid=" + uuid +
                ", position=" + position +
                ", velocity=" + velocity +
                ", yaw=" + yaw +
                ", pitch=" + pitch +
                ", onGround=" + onGround +
                ", tick=" + tick +
                '}';
    }
}
