package com.anticheat.web.dto;

/**
 * 实时告警条目。与前端 AlertItem 对齐。
 * 同时作为 WebSocket 推送的 payload。
 */
public class AlertDTO {

    public String id;
    /** critical / high / medium / low */
    public String level;
    public String title;
    public String message;
    public String playerName;
    public String time;
    public String module;
    public int score;
    /** 关联的违规回放片段 id（该玩家最近一次已保存的片段；可能为 null） */
    public Long replayId;

    public AlertDTO() {
    }

    public AlertDTO(String id, String level, String title, String message, String playerName,
                    String time, String module, int score) {
        this.id = id;
        this.level = level;
        this.title = title;
        this.message = message;
        this.playerName = playerName;
        this.time = time;
        this.module = module;
        this.score = score;
    }
}
