package com.anticheat.detection.physics;

/**
 * PhysicsConstants物理常量类，定义所有Minecraft物理常量。
 * 包含1.8-1.21版本兼容的物理参数，用于物理模拟和运动检测。
 */
public final class PhysicsConstants {

    private PhysicsConstants() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    public static final double GRAVITY = 0.08;
    public static final double FRICTION = 0.91;
    public static final double WALK_SPEED = 0.7;
    public static final double MAX_SPEED = 0.65;
    public static final double SPRINT_MULTIPLIER = 1.3;
    public static final double JUMP_FORCE = 0.42;
    public static final double PLAYER_HEIGHT = 1.8;
    public static final double EYE_HEIGHT = 1.62;

    public static final double AIR_RESISTANCE = 0.98;
    public static final double LIQUID_FRICTION = 0.8;
    public static final double LADDER_SPEED = 0.1;
    public static final double SWIM_SPEED = 0.02;

    public static final double MAX_FALL_SPEED = 3.92;
    public static final double HORIZONTAL_FRICTION = 0.91;
    public static final double PLAYER_WIDTH = 0.3;

    public static final double SPRINT_SPEED = WALK_SPEED * SPRINT_MULTIPLIER;
    public static final double SNEAK_SPEED = 0.3;
    public static final double PLAYER_RADIUS = 0.5;

    public static final double DEFAULT_HORIZONTAL_SPEED = 0.1;
    public static final double DEFAULT_VERTICAL_SPEED = 0.02;

    public static final int TICKS_PER_SECOND = 20;
    public static final double SECONDS_PER_TICK = 1.0 / TICKS_PER_SECOND;
}
