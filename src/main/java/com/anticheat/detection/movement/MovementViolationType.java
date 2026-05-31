package com.anticheat.detection.movement;

/**
 * MovementViolationType移动违规类型枚举
 * 定义所有与移动相关的违规类型及其严重程度
 */
public enum MovementViolationType {
    FLY("飞行", ViolationSeverity.CRITICAL),
    SPEED("速度作弊", ViolationSeverity.CRITICAL),
    AIR_WALK("空气行走", ViolationSeverity.HIGH),
    WATER_WALK("水面行走", ViolationSeverity.HIGH),
    NO_FALL("无摔伤", ViolationSeverity.HIGH),
    IMPOSSIBLE_JUMP("不可能跳跃", ViolationSeverity.HIGH),
    PHASE("穿墙", ViolationSeverity.CRITICAL),
    TELEPORT("传送", ViolationSeverity.HIGH),
    AIR_STRAFE("空气摆动", ViolationSeverity.MEDIUM),
    HIGH_JUMP("高跳", ViolationSeverity.MEDIUM);

    private final String displayName;
    private final ViolationSeverity defaultSeverity;

    MovementViolationType(String displayName, ViolationSeverity defaultSeverity) {
        this.displayName = displayName;
        this.defaultSeverity = defaultSeverity;
    }

    public String getDisplayName() {
        return displayName;
    }

    public ViolationSeverity getDefaultSeverity() {
        return defaultSeverity;
    }

    /**
     * ViolationSeverity违规严重程度枚举
     */
    public enum ViolationSeverity {
        CRITICAL(1, "极重", 1.0),
        HIGH(2, "重", 0.75),
        MEDIUM(3, "中", 0.5),
        LOW(4, "轻", 0.25);

        private final int level;
        private final String displayName;
        private final double probabilityWeight;

        ViolationSeverity(int level, String displayName, double probabilityWeight) {
            this.level = level;
            this.displayName = displayName;
            this.probabilityWeight = probabilityWeight;
        }

        public int getLevel() {
            return level;
        }

        public String getDisplayName() {
            return displayName;
        }

        public double getProbabilityWeight() {
            return probabilityWeight;
        }
    }
}
