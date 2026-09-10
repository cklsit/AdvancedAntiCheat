package com.anticheat.managers.replay;

/**
 * 违规标记：记录违规事件对应的轨迹点索引，便于回放定位。
 */
public class ViolationMarker {

    /** 违规发生时，在对应玩家 RingBuffer 中最接近的 TracePoint 的"逻辑索引" */
    public int pointIndex;

    /** 违规类型名称，来自 ViolationType.name()，如 "FLY"、"SPEED" */
    public String type;

    /** 违规严重程度，来自 Severity.name()，如 "CRITICAL"、"HIGH" */
    public String level;

    public ViolationMarker() {
    }

    public ViolationMarker(int pointIndex, String type, String level) {
        this.pointIndex = pointIndex;
        this.type = type;
        this.level = level;
    }

    public int getPointIndex() {
        return pointIndex;
    }

    public void setPointIndex(int pointIndex) {
        this.pointIndex = pointIndex;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }
}
