package com.anticheat.web.dto;

/**
 * 在线玩家 session 列表 DTO。
 */
public class ReplayPlayerDTO {
    public String uuid;
    public String playerName;
    public long onlineSeconds;   // 当前已在线秒数（近似）
    public int replaySeconds;     // 当前录制秒数（点数/20）
    public int violationCount;    // 当前 session 违规数
    public String lastViolationType;
    public String lastViolationLevel;
}
