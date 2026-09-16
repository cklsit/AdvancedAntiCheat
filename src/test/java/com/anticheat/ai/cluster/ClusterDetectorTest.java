package com.anticheat.ai.cluster;

import com.anticheat.ai.FeatureDimensions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新作弊集群发现（余弦聚类 + 特征指纹）回归测试。
 *
 * <p>业务语义：单个作弊玩家只能被逐个处罚，但"外挂批量更新后一群人特征高度相似"
 * 说明出现了新作弊家族——集群发现就是要把这批人并成一个可处置的实体。
 * 关键不变量：
 * <ul>
 *   <li>全局分低于入选线的玩家不参与聚类（否则正常玩家会被抱团）</li>
 *   <li>只有成员 ≥ 2 的集群才保留（单点不成"集群"）</li>
 *   <li>处置后的集群不再吸收新成员</li>
 *   <li>过期集群会被清理</li>
 * </ul>
 */
class ClusterDetectorTest {

    private static final int DIMS = FeatureDimensions.DIMS;
    private static final double SCORE_LINE = 0.75;
    private static final double COSINE_LINE = 0.85;

    /** 构造指向某个轴的稀疏向量，便于精确控制余弦相似度。 */
    private static double[] axis(int dim, double value) {
        double[] v = new double[DIMS];
        v[dim] = value;
        return v;
    }

    private static ClusterDetector detector(long ttlMillis) {
        ClusterDetector d = new ClusterDetector(SCORE_LINE, COSINE_LINE, ttlMillis);
        double[] mean = new double[DIMS];
        Arrays.fill(mean, 1.0);
        d.setGlobalMean(mean);
        return d;
    }

    private static List<double[]> wrap(double[]... rows) {
        return new ArrayList<>(Arrays.asList(rows));
    }

    private static List<UUID> uuids(int n) {
        List<UUID> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(UUID.nameUUIDFromBytes(("player-" + i).getBytes()));
        }
        return list;
    }

    private static List<String> names(int n) {
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add("Player" + i);
        }
        return list;
    }

    @Test
    @DisplayName("全局分未达入选线：不产生集群（正常玩家不会被抱团）")
    void belowScoreThresholdNoCluster() {
        ClusterDetector d = detector(120_000L);
        List<AnomalyCluster> touched = d.detect(uuids(3), names(3),
                wrap(axis(0, 1.0), axis(0, 1.0), axis(0, 1.0)),
                new double[]{0.10, 0.20, 0.74});
        assertTrue(touched.isEmpty(), "全部低于 0.75 入选线时不应产生集群");
        assertEquals(0, d.getOpenClusterCount());
    }

    @Test
    @DisplayName("两个高分且特征相似玩家 → 归为一个集群（成员≥2 才保留）")
    void similarHighScorePlayersFormCluster() {
        ClusterDetector d = detector(120_000L);
        d.detect(uuids(2), names(2),
                wrap(axis(0, 1.0), axis(0, 1.0)),
                new double[]{0.90, 0.92});
        assertEquals(1, d.getOpenClusterCount(), "两个相似高分玩家应归为一个集群");

        List<java.util.Map<String, Object>> snap = d.snapshot();
        assertEquals(1, snap.size());
        assertEquals(2, snap.get(0).get("memberCount"));
        assertEquals("open", snap.get(0).get("status"));
        assertNotNull(snap.get(0).get("fingerprint"), "必须给出特征指纹供管理员判断");
    }

    @Test
    @DisplayName("单个高分玩家不构成集群（成员 < 2 的集群被丢弃）")
    void singleHighScorePlayerIsNotCluster() {
        ClusterDetector d = detector(120_000L);
        d.detect(uuids(1), names(1), wrap(axis(0, 1.0)), new double[]{0.99});
        assertEquals(0, d.getOpenClusterCount(), "一个人不叫集群，避免刷屏与误判");
    }

    @Test
    @DisplayName("特征方向差异大的高分玩家分成两个独立集群")
    void orthogonalPlayersSplitIntoTwoClusters() {
        ClusterDetector d = detector(120_000L);
        d.detect(uuids(4), names(4),
                wrap(axis(0, 1.0), axis(0, 1.0), axis(1, 1.0), axis(1, 1.0)),
                new double[]{0.90, 0.90, 0.90, 0.90});
        assertEquals(2, d.getOpenClusterCount(), "正交特征不应归并，否则会把两种外挂混为一谈");
    }

    @Test
    @DisplayName("处置（dismiss）后集群不再吸收新成员")
    void resolvedClusterStopsAcceptingMembers() {
        ClusterDetector d = detector(120_000L);
        d.detect(uuids(2), names(2), wrap(axis(0, 1.0), axis(0, 1.0)), new double[]{0.90, 0.90});
        assertEquals(1, d.getOpenClusterCount());

        String id = d.snapshot().get(0).get("id").toString();
        assertTrue(d.resolve(id, "dismiss", "误报：同一网吧同一玩法"));
        assertEquals("dismiss", d.getCluster(id).getStatus());
        assertEquals(0, d.getOpenClusterCount(), "已处置集群不计入待处理数");

        // 新来一个同特征高分玩家：不应被并入已 dismiss 的集群，而应新建
        d.detect(uuids(2), names(2), wrap(axis(0, 1.0), axis(0, 1.0)), new double[]{0.91, 0.91});
        assertEquals(1, d.getOpenClusterCount(), "处置过的集群不能被复用");
        assertFalse(d.resolve("cl-not-exist", "dismiss", "x"), "不存在的集群 id 必须返回 false");
    }

    @Test
    @DisplayName("TTL 过期：陈旧集群被自动清理（避免历史噪声长期占用面板）")
    void expiredClustersArePurged() {
        ClusterDetector d = detector(1L);
        d.detect(uuids(2), names(2), wrap(axis(0, 1.0), axis(0, 1.0)), new double[]{0.90, 0.90});
        // 同一个 ID 不会被复用；TTL=1ms 时下一次访问即过期
        for (int i = 0; i < 5; i++) {
            d.snapshot();
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertEquals(0, d.getOpenClusterCount(), "超过 TTL 的集群必须被清理");
    }

    @Test
    @DisplayName("空输入不抛异常")
    void emptyInputIsSafe() {
        ClusterDetector d = detector(120_000L);
        assertTrue(d.detect(new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new double[0]).isEmpty());
    }

    @Test
    @DisplayName("AnomalyCluster 指纹：返回 Top-N 偏差维度且按偏差降序")
    void fingerprintTopN() {
        double[] mean = new double[DIMS];
        Arrays.fill(mean, 1.0);
        double[] vec = new double[DIMS];
        Arrays.fill(vec, 1.0);
        vec[3] = 9.0;   // 最大偏差
        vec[7] = 5.0;   // 次之
        vec[11] = 2.0;  // 再次

        AnomalyCluster c = new AnomalyCluster("cl-1", System.currentTimeMillis(),
                List.of("u1", "u2"), List.of("P1", "P2"), vec, mean, 0.9);
        int[][] fp = c.fingerprint(3);
        assertEquals(3, fp.length);
        assertEquals(3, fp[0][0], "偏差最大的维度必须排在首位");
        assertEquals(7, fp[1][0]);
        assertEquals(11, fp[2][0]);
        assertTrue(fp[0][1] > fp[1][1], "指纹必须按偏差降序");
        assertTrue(fp[1][1] > fp[2][1]);
    }

    @Test
    @DisplayName("AnomalyCluster.isExpired 边界")
    void clusterExpiry() {
        long now = System.currentTimeMillis();
        AnomalyCluster c = new AnomalyCluster("cl-2", now,
                List.of("u1"), List.of("P1"), new double[DIMS], new double[DIMS], 0.8);
        assertFalse(c.isExpired(1000L, now), "刚创建的集群在 TTL 内不应过期");
        assertTrue(c.isExpired(1000L, now + 1001L), "超过 TTL 必须过期");
    }
}
