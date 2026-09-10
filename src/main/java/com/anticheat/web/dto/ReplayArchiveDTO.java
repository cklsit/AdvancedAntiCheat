package com.anticheat.web.dto;

/**
 * 历史存档列表项 DTO。
 */
public class ReplayArchiveDTO {
    public String filename;      // 如 "Jonson_20260829_143022.zip"
    public String playerName;
    public long startTime;       // epoch ms
    public long durationMs;      // 本次 session 总时长
    public long sizeKB;         // 文件大小 KB
    public int violationCount;
}
