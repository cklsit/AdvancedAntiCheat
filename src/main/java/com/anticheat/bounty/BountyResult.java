package com.anticheat.bounty;

public enum BountyResult {
    DETECTED("检测成功", "你的操作已被检测"),
    BYPASSED("绕过成功", "实现了潜在绕过"),
    ZERO_DAY("高危发现", "发现了高危绕过");

    private final String displayName;
    private final String description;

    BountyResult(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
