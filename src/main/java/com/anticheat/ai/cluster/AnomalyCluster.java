package com.anticheat.ai.cluster;

import java.util.ArrayList;
import java.util.List;

/**
 * 疑似新作弊集群。
 * <p>
 * 由 {@link ClusterDetector} 自动发现：同一时间窗内多个玩家全局异常分同时偏高、
 * 且特征向量在标准化空间中彼此靠近（余弦相似度 ≥ 阈值）→ 归为一个集群。
 * <p>
 * 特征指纹 = 集群均值向量相对全局均值的 Top-N 偏差维度，
 * 用于生成可读的"共同特征签名"（如：极低转向熵 + 恒定 CPS）。
 */
public class AnomalyCluster {

    /** 集群 ID（时间戳递增）。 */
    private final String id;
    /** 发现时间。 */
    private final long discoveredAt;
    /** 成员 UUID。 */
    private final List<String> memberUuids = new ArrayList<>();
    /** 成员名（快照）。 */
    private final List<String> memberNames = new ArrayList<>();
    /** 集群均值向量（标准化空间）。 */
    private final double[] centroid;
    /** 全局均值向量（用于指纹计算）。 */
    private final transient double[] globalMean;
    /** 成员平均全局异常分。 */
    private final double avgGlobalScore;
    /** 管理员处置状态：open / dismissed / ruled。 */
    private volatile String status = "open";
    /** 处置备注。 */
    private volatile String note = "";

    public AnomalyCluster(String id, long discoveredAt, List<String> uuids, List<String> names,
                          double[] centroid, double[] globalMean, double avgGlobalScore) {
        this.id = id;
        this.discoveredAt = discoveredAt;
        this.memberUuids.addAll(uuids);
        this.memberNames.addAll(names);
        this.centroid = centroid;
        this.globalMean = globalMean;
        this.avgGlobalScore = avgGlobalScore;
    }

    /**
     * 生成特征指纹：偏差最大的前 N 个维度。
     *
     * @return [{dimIndex, zDelta}] 按绝对偏差降序
     */
    public int[][] fingerprint(int topN) {
        if (centroid == null || globalMean == null) return new int[0][0];
        double[] delta = new double[centroid.length];
        for (int i = 0; i < centroid.length; i++) {
            delta[i] = centroid[i] - globalMean[i];
        }
        Integer[] order = new Integer[delta.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(Math.abs(delta[b]), Math.abs(delta[a])));
        int n = Math.min(topN, delta.length);
        int[][] out = new int[n][2];
        for (int i = 0; i < n; i++) {
            out[i][0] = order[i];
            out[i][1] = (int) Math.round(delta[order[i]] * 100) / 1;
        }
        return out;
    }

    public String getId() {
        return id;
    }

    public long getDiscoveredAt() {
        return discoveredAt;
    }

    public List<String> getMemberUuids() {
        return memberUuids;
    }

    public List<String> getMemberNames() {
        return memberNames;
    }

    public double[] getCentroid() {
        return centroid;
    }

    public double getAvgGlobalScore() {
        return avgGlobalScore;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note == null ? "" : note;
    }

    /** 是否已过期（超过 TTL）。 */
    public boolean isExpired(long ttlMillis, long now) {
        return now - discoveredAt > ttlMillis;
    }
}
