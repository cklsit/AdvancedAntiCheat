package com.anticheat.ai.cluster;

import com.anticheat.ai.AiMath;
import com.anticheat.ai.FeatureDimensions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 新作弊集群发现器。
 * <p>
 * 在每次孤立森林重建后的评分批次上运行：
 * <ol>
 *   <li>筛出全局异常分 &gt; 高分线（默认 0.75）的玩家；</li>
 *   <li>单遍贪心聚类：与已有集群质心余弦相似度 ≥ 阈值（默认 0.85）即并入，否则新建集群；</li>
 *   <li>集群成员 ≥ 2 才保留（单人高异常分走常规告警链路）；</li>
 *   <li>超时（默认 2h）自动过期。</li>
 * </ol>
 * 全部在异步调度线程运行。
 */
public class ClusterDetector {

    private final double scoreThreshold;
    private final double cosineThreshold;
    private final long clusterTtlMillis;
    private final Map<String, AnomalyCluster> clusters = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(System.currentTimeMillis() / 1000);
    /** 全局均值向量（指纹计算基准），由 AILabManager 注入。 */
    private volatile double[] globalMeanRef;

    public ClusterDetector(double scoreThreshold, double cosineThreshold, long ttlMillis) {
        this.scoreThreshold = scoreThreshold;
        this.cosineThreshold = cosineThreshold;
        this.clusterTtlMillis = ttlMillis;
    }

    public void setGlobalMean(double[] mean) {
        this.globalMeanRef = mean;
    }

    /**
     * 执行一轮集群发现。
     *
     * @param uuids       评分玩家 UUID
     * @param names       对应玩家名
     * @param normalized  对应标准化特征向量
     * @param globalScores 对应全局异常分
     * @return 本轮新发现（或更新的）集群列表
     */
    public List<AnomalyCluster> detect(List<UUID> uuids, List<String> names,
                                       List<double[]> normalized, double[] globalScores) {
        long now = System.currentTimeMillis();
        expireOld(now);

        List<AnomalyCluster> touched = new ArrayList<>();
        for (int i = 0; i < uuids.size(); i++) {
            if (globalScores[i] < scoreThreshold) continue;
            double[] vec = normalized.get(i);
            AnomalyCluster match = null;
            for (AnomalyCluster c : clusters.values()) {
                if (!"open".equals(c.getStatus())) continue;
                if (AiMath.cosine(vec, c.getCentroid()) >= cosineThreshold) {
                    match = c;
                    break;
                }
            }
            if (match == null) {
                String id = "cl-" + idGen.incrementAndGet();
                List<String> uuids1 = new ArrayList<>();
                List<String> names1 = new ArrayList<>();
                uuids1.add(uuids.get(i).toString());
                names1.add(names.get(i));
                match = new AnomalyCluster(id, now, uuids1, names1,
                        vec.clone(), globalMeanRef, globalScores[i]);
                clusters.put(id, match);
                touched.add(match);
            } else {
                if (!match.getMemberUuids().contains(uuids.get(i).toString())) {
                    match.getMemberUuids().add(uuids.get(i).toString());
                    match.getMemberNames().add(names.get(i));
                    // 质心增量更新
                    double[] c = match.getCentroid();
                    int n = match.getMemberUuids().size();
                    for (int d = 0; d < c.length; d++) {
                        c[d] = c[d] * (n - 1) / n + vec[d] / n;
                    }
                    match.setNote("成员 " + names.get(i) + " 新近加入");
                    touched.add(match);
                }
            }
        }
        // 仅保留成员 ≥ 2 的集群
        clusters.values().removeIf(c -> c.getMemberUuids().size() < 2);
        return touched;
    }

    private void expireOld(long now) {
        clusters.values().removeIf(c -> c.isExpired(clusterTtlMillis, now));
    }

    /** 手动处置：dismiss（误报丢弃）或 ruled（转为临时规则）。 */
    public boolean resolve(String clusterId, String status, String note) {
        AnomalyCluster c = clusters.get(clusterId);
        if (c == null) return false;
        c.setStatus(status);
        c.setNote(note);
        return true;
    }

    /** 集群快照（Web 用）。 */
    public List<Map<String, Object>> snapshot() {
        expireOld(System.currentTimeMillis());
        List<Map<String, Object>> out = new ArrayList<>();
        for (AnomalyCluster c : clusters.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("discoveredAt", c.getDiscoveredAt());
            m.put("members", c.getMemberNames());
            m.put("memberCount", c.getMemberUuids().size());
            m.put("avgGlobalScore", round(c.getAvgGlobalScore()));
            m.put("status", c.getStatus());
            m.put("note", c.getNote());
            // 特征指纹：Top5 偏差维度 → 可读描述
            int[][] fp = c.fingerprint(5);
            List<Map<String, Object>> fpList = new ArrayList<>();
            for (int[] p : fp) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("dim", p[0]);
                f.put("name", FeatureDimensions.NAMES[p[0]]);
                f.put("desc", FeatureDimensions.DESCRIPTIONS[p[0]]);
                f.put("delta", round(p[1] / 100.0));
                fpList.add(f);
            }
            m.put("fingerprint", fpList);
            out.add(m);
        }
        out.sort((a, b) -> Long.compare((long) b.get("discoveredAt"), (long) a.get("discoveredAt")));
        return out;
    }

    public int getOpenClusterCount() {
        expireOld(System.currentTimeMillis());
        int n = 0;
        for (AnomalyCluster c : clusters.values()) {
            if ("open".equals(c.getStatus())) n++;
        }
        return n;
    }

    public AnomalyCluster getCluster(String id) {
        return clusters.get(id);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
