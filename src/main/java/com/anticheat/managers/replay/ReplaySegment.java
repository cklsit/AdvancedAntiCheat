package com.anticheat.managers.replay;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 一次违规对应的回放片段。
 * 包含：违规前 ~30s + 违规后 ~10s 的轨迹点集合。
 */
public class ReplaySegment {

    /** 数据库主键，持久化后填充 */
    private long id;

    private UUID playerUuid;
    private String playerName;

    /** 违规类型名称，来自 ViolationType.name() */
    private String violationType;

    /** 违规等级，来自 Severity.name() */
    private String violationLevel;

    /** 片段开始时间戳（毫秒，epoch） */
    private long startMs;

    /** 片段结束时间戳（毫秒，epoch） */
    private long endMs;

    /** 违规触发时刻相对于 startMs 的偏移（毫秒） */
    private long violationOffsetMs;

    /** 轨迹点，分页查询时可能为 null/空 */
    private List<TracePoint> points;

    public ReplaySegment() {
        this.points = new ArrayList<>();
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public void setPlayerUuid(UUID playerUuid) {
        this.playerUuid = playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public String getViolationType() {
        return violationType;
    }

    public void setViolationType(String violationType) {
        this.violationType = violationType;
    }

    public String getViolationLevel() {
        return violationLevel;
    }

    public void setViolationLevel(String violationLevel) {
        this.violationLevel = violationLevel;
    }

    public long getStartMs() {
        return startMs;
    }

    public void setStartMs(long startMs) {
        this.startMs = startMs;
    }

    public long getEndMs() {
        return endMs;
    }

    public void setEndMs(long endMs) {
        this.endMs = endMs;
    }

    public long getViolationOffsetMs() {
        return violationOffsetMs;
    }

    public void setViolationOffsetMs(long violationOffsetMs) {
        this.violationOffsetMs = violationOffsetMs;
    }

    public List<TracePoint> getPoints() {
        return points;
    }

    public void setPoints(List<TracePoint> points) {
        this.points = points;
    }
}
