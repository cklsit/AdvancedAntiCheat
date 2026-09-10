package com.anticheat.web.dto;

import java.util.List;

/**
 * 玩家视图。与前端 Player 接口字段完全对齐。
 */
public class PlayerDTO {

    public String uuid;
    public String name;
    public String avatar;
    /** online / offline / banned / watching */
    public String status;
    public int riskScore;
    /** 0=LOW 1=MEDIUM 2=HIGH 3=EXTREME */
    public int riskLevel;
    public String lastTrigger;
    /** 在线时长（秒） */
    public long onlineDuration;
    public String ip;
    public String firstJoin;
    public String lastJoin;
    public int violationsCount;
    public int ping;
    public String gameMode;
    public String world;
    /** 玩家在世界中的 X 坐标（用于实时地图） */
    public double locationX;
    /** 玩家在世界中的 Z 坐标（用于实时地图） */
    public double locationZ;
    public String version;
    public String country;
    public String hardwareId;
    public List<ViolationRecordDTO> violationHistory;
    public List<LinkedAccountDTO> linkedAccounts;

    public PlayerDTO() {
    }
}
