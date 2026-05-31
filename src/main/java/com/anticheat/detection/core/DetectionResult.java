package com.anticheat.detection.core;

import java.util.UUID;

/**
 * DetectionResult类封装了检测结果的详细信息。
 * 包含玩家UUID、检测模块名称、概率值、证据和时间戳。
 * 用于记录和传递检测模块的执行结果。
 */
public class DetectionResult {

    private final UUID playerId;
    private final String moduleName;
    private final double probability;
    private final Evidence evidence;
    private final long timestamp;

    /**
     * 构造函数
     * @param playerId 被检测玩家的UUID
     * @param moduleName 检测模块名称
     * @param probability 违规概率值（0.0-1.0）
     * @param evidence 证据对象
     */
    public DetectionResult(UUID playerId, String moduleName, double probability, Evidence evidence) {
        this.playerId = playerId;
        this.moduleName = moduleName;
        this.probability = probability;
        this.evidence = evidence;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 获取被检测玩家的UUID
     * @return 玩家UUID
     */
    public UUID getPlayerId() {
        return playerId;
    }

    /**
     * 获取检测模块名称
     * @return 模块名称
     */
    public String getModuleName() {
        return moduleName;
    }

    /**
     * 获取违规概率值
     * @return 概率值（0.0-1.0）
     */
    public double getProbability() {
        return probability;
    }

    /**
     * 获取证据对象
     * @return 证据对象
     */
    public Evidence getEvidence() {
        return evidence;
    }

    /**
     * 获取检测时间戳
     * @return 时间戳（毫秒）
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * 判断是否为高概率检测
     * @param threshold 阈值（默认0.7）
     * @return 如果概率大于等于阈值返回true
     */
    public boolean isHighProbability(double threshold) {
        return probability >= threshold;
    }

    @Override
    public String toString() {
        return "DetectionResult{" +
                "playerId=" + playerId +
                ", moduleName='" + moduleName + '\'' +
                ", probability=" + probability +
                ", timestamp=" + timestamp +
                '}';
    }
}
