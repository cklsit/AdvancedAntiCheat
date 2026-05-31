package com.anticheat.detection.association;

import java.io.Serializable;
import java.util.UUID;

public class AltAccount implements Serializable {
    private static final long serialVersionUID = 1L;

    private final UUID mainAccount;
    private final UUID altAccount;
    private final double confidence;
    private final AltReason reason;
    private final long detectionTime;

    public AltAccount(UUID mainAccount, UUID altAccount, double confidence, AltReason reason) {
        this.mainAccount = mainAccount;
        this.altAccount = altAccount;
        this.confidence = confidence;
        this.reason = reason;
        this.detectionTime = System.currentTimeMillis();
    }

    public UUID getMainAccount() {
        return mainAccount;
    }

    public UUID getAltAccount() {
        return altAccount;
    }

    public double getConfidence() {
        return confidence;
    }

    public AltReason getReason() {
        return reason;
    }

    public long getDetectionTime() {
        return detectionTime;
    }

    public enum AltReason {
        IP_MATCH("IP地址匹配"),
        BEHAVIOR_SIMILARITY("行为相似度高"),
        DEVICE_FINGERPRINT("设备指纹相似"),
        NICKNAME_PATTERN("昵称模式匹配"),
        LOGIN_TIME_PATTERN("登录时间模式"),
        SOCIAL_CONNECTION("社交关联"),
        COMBINED("综合判断");

        private final String description;

        AltReason(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}
