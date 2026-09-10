package com.anticheat.detection;

import java.io.Serializable;
import java.util.UUID;

public class ViolationRecord implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Severity {
        CRITICAL(1, "极重"),
        HIGH(2, "重"),
        MEDIUM(3, "中"),
        LOW(4, "轻"),
        MINOR(5, "极轻");

        private final int level;
        private final String displayName;

        Severity(int level, String displayName) {
            this.level = level;
            this.displayName = displayName;
        }

        public int getLevel() {
            return level;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public enum ViolationType {
        FLY("飞行", Severity.CRITICAL),
        SPEED("速度作弊", Severity.CRITICAL),
        REACH("攻击距离", Severity.HIGH),
        TIMER("Timer/变速齿轮", Severity.HIGH),
        WATER_WALK("水面行走", Severity.HIGH),
        HIGH_JUMP("高跳", Severity.HIGH),
        NO_FALL("无摔伤", Severity.HIGH),
        SPIDER("蜘蛛攀爬", Severity.MEDIUM),
        KILLAURA("KillAura/Aimbot", Severity.HIGH),
        AUTO_HIT("自动攻击", Severity.LOW),
        CPS_ANOMALY("CPS异常", Severity.LOW),
        NO_KNOCKBACK("无击退", Severity.MEDIUM),
        AUTO_TOTEM("自动图腾", Severity.MEDIUM),
        X_RAY("X光透视", Severity.HIGH),
        CHEST_ESP("箱子ESP", Severity.HIGH),
        PLAYER_RADAR("玩家雷达", Severity.HIGH),
        TRACER("追踪ESP", Severity.MEDIUM),
        SCAFFOLD("脚手架", Severity.MEDIUM),
        FAST_BREAK("快速破坏", Severity.MEDIUM),
        AUTO_MINER("自动矿工", Severity.HIGH),
        NO_SLOW_MINING("无减速挖掘", Severity.HIGH),
        AUTO_FISH("自动钓鱼", Severity.LOW),
        AUTO_STACK("自动整理", Severity.MINOR),
        INVENTORY_DUPE("物品复制", Severity.CRITICAL),
        BEHAVIOR_ANOMALY("行为异常", Severity.MEDIUM),
        // ==== 以下为全项检测重构新增类型（追加至末尾，保持既有 ordinal 稳定） ====
        PHASE("相位/穿墙", Severity.CRITICAL),
        JESUS("水面/岩浆行走", Severity.HIGH),
        AIM_ANGLE("攻击角度异常", Severity.HIGH),
        AIMBOT_SPECTRUM("自瞄频谱异常", Severity.HIGH),
        KNOCKBACK_ENTROPY("击退熵异常", Severity.MEDIUM),
        ANTI_KNOCKBACK("反击退", Severity.MEDIUM),
        AUTO_ARMOR("自动盔甲", Severity.MEDIUM),
        AUTO_POTION("自动喝药", Severity.MEDIUM),
        BREAK_CONSISTENCY("破坏曲线一致性", Severity.HIGH),
        MINING_COORD("挖掘移动协调异常", Severity.MEDIUM),
        ILLEGAL_PLACE("非法放置", Severity.MEDIUM),
        CONTAINER_SPAM("容器操作异常", Severity.MINOR),
        ITEM_MOVE_SPAM("物品移动异常", Severity.MINOR),
        OFFHAND_SWAP("副手切换异常", Severity.MEDIUM),
        CLOCK_DRIFT("时钟漂移", Severity.HIGH),
        PROTOCOL_SPOOF("协议版本欺骗", Severity.HIGH),
        BRAND_SPOOF("伪造客户端品牌", Severity.HIGH),
        MALFORMED_PACKET("非法数据包", Severity.HIGH),
        GUI_FINGERPRINT("GUI响应指纹异常", Severity.MEDIUM),
        RENDER_DISTANCE("渲染距离异常", Severity.HIGH),
        KEYSTROKE("操作生物特征异常", Severity.MEDIUM),
        ANTI_RECON("反侦察行为", Severity.MEDIUM),
        GLOBAL_ANOMALY("全局异常", Severity.MEDIUM),
        // ==== 蜜罐补充类型（追加至末尾，保持既有 ordinal 稳定） ====
        FAKE_DROP("虚假掉落物", Severity.HIGH),
        FAKE_ESCAPE("假逃脱情报", Severity.HIGH);

        private final String displayName;
        private final Severity defaultSeverity;

        ViolationType(String displayName, Severity defaultSeverity) {
            this.displayName = displayName;
            this.defaultSeverity = defaultSeverity;
        }

        public String getDisplayName() {
            return displayName;
        }

        public Severity getDefaultSeverity() {
            return defaultSeverity;
        }
    }

    private final UUID playerUUID;
    private final String playerName;
    private final ViolationType type;
    private final Severity severity;
    private final long timestamp;
    private final String details;
    private final double violationLevel;

    public ViolationRecord(UUID playerUUID, String playerName, ViolationType type, String details, double violationLevel) {
        this.playerUUID = playerUUID;
        this.playerName = playerName;
        this.type = type;
        this.severity = type.getDefaultSeverity();
        this.timestamp = System.currentTimeMillis();
        this.details = details;
        this.violationLevel = violationLevel;
    }

    public ViolationRecord(UUID playerUUID, String playerName, ViolationType type, Severity severity, String details, double violationLevel) {
        this.playerUUID = playerUUID;
        this.playerName = playerName;
        this.type = type;
        this.severity = severity;
        this.timestamp = System.currentTimeMillis();
        this.details = details;
        this.violationLevel = violationLevel;
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public String getPlayerName() {
        return playerName;
    }

    public ViolationType getType() {
        return type;
    }

    public Severity getSeverity() {
        return severity;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getDetails() {
        return details;
    }

    public double getViolationLevel() {
        return violationLevel;
    }
}
