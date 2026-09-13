package com.anticheat.ai.feedback;

import java.util.List;
import java.util.Map;

/**
 * 一条人工/自动标签样本（监督学习最小单元）。
 * <ul>
 *   <li><b>强标签</b>：管理员面板标记（ADMIN）、自动封禁后申诉失败（AUTO_BAN）；</li>
 *   <li><b>弱标签</b>：Captcha 审判失败（CAPTCHA_FAIL）/通过（CAPTCHA_PASS）、
 *       赏金确认绕过（BOUNTY）、集群误报丢弃（CLUSTER_DISMISS）；</li>
 *   <li><b>label</b>：1 = 作弊正样本，0 = 正常负样本。</li>
 * </ul>
 */
public class LabeledSample {

    /** 标签来源常量。 */
    public static final String SRC_ADMIN = "ADMIN";
    public static final String SRC_CAPTCHA_FAIL = "CAPTCHA_FAIL";
    public static final String SRC_CAPTCHA_PASS = "CAPTCHA_PASS";
    public static final String SRC_AUTO_BAN = "AUTO_BAN";
    public static final String SRC_BOUNTY = "BOUNTY";
    public static final String SRC_CLUSTER_DISMISS = "CLUSTER_DISMISS";
    public static final String SRC_WHITELIST = "WHITELIST";

    public long id;
    /** 玩家 UUID（可为空串：模拟样本）。 */
    public String uuid = "";
    public String playerName = "";
    /** 1 = cheat, 0 = benign。 */
    public int label;
    /** 来源常量。 */
    public String source;
    /** 特征向量（原始空间，48 维；可能为 null = 仅标记不训练）。 */
    public double[] features;
    /** 采样时间。 */
    public long timestamp;
    /** 备注。 */
    public String note = "";

    public LabeledSample() {
    }

    public LabeledSample(long id, String uuid, String playerName, int label, String source,
                         double[] features, long timestamp, String note) {
        this.id = id;
        this.uuid = uuid == null ? "" : uuid;
        this.playerName = playerName == null ? "" : playerName;
        this.label = label;
        this.source = source;
        this.features = features;
        this.timestamp = timestamp;
        this.note = note == null ? "" : note;
    }

    /** Web 序列化（不回传完整特征，只回传统计）。 */
    public Map<String, Object> toMap() {
        return Map.of(
                "id", id,
                "uuid", uuid,
                "playerName", playerName,
                "label", label,
                "source", source,
                "hasFeatures", features != null && features.length > 0,
                "timestamp", timestamp,
                "note", note
        );
    }
}
