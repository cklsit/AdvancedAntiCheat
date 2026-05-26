package com.anticheat.bounty;

public enum BountyTaskType {
    MOVE_BASIC("移动检测·初级", "在不触发移动异常警告的前提下，使用飞行/加速，从A点到达B点", 5),
    MOVE_ADVANCED("移动检测·高级", "完成一次空中直角变向", 5),
    COMBAT_BASIC("战斗检测·初级", "在10秒内击杀5个移动中的傀儡", 3),
    COMBAT_ADVANCED("战斗检测·高级", "持续锁定幽灵实体而不被识别", 5),
    INVENTORY_CHALLENGE("背包检测·挑战", "快速完成图腾到副手的替换", 3),
    FREE_TEST("自由测试", "无特定目标，自由尝试任何作弊功能", 10);

    private final String displayName;
    private final String description;
    private final long durationMinutes;

    BountyTaskType(String displayName, String description, long durationMinutes) {
        this.displayName = displayName;
        this.description = description;
        this.durationMinutes = durationMinutes;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public long getDurationMinutes() {
        return durationMinutes;
    }
}
