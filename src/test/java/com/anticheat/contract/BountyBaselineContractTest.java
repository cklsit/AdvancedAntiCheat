package com.anticheat.contract;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 赏金人类基线的**量纲与标定不变量**。
 *
 * <p>这个测试守的是一类"改一个数字就静默失效"的配置——它们不会编译失败、
 * 不会抛异常、不会有日志，只会让判定结果悄悄变成错的。</p>
 */
@DisplayName("契约：赏金人类基线的量纲与标定")
class BountyBaselineContractTest {

    private static Map<String, Object> baseline;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadConfig() throws IOException {
        Path configFile = Repo.mainResources().resolve("config.yml");
        assertTrue(Files.isRegularFile(configFile), "必须随 jar 发布默认 config.yml");
        Map<String, Object> root = new Yaml().load(Repo.read(configFile));
        assertNotNull(root, "config.yml 必须是合法 YAML 映射");

        Object bounty = root.get("bounty");
        assertNotNull(bounty, "config.yml 缺少 bounty 段");
        Object section = ((Map<String, Object>) bounty).get("baseline");
        assertNotNull(section, "config.yml 缺少 bounty.baseline 段");
        baseline = (Map<String, Object>) section;
    }

    private static long longOf(String key) {
        Object value = baseline.get(key);
        assertNotNull(value, "bounty.baseline 缺少 " + key);
        return ((Number) value).longValue();
    }

    @Test
    @DisplayName("基线采样节拍必须是 1 tick —— 与判定侧同时间基（这条错了会让任何正常玩家都被判异常）")
    void samplingIntervalMustMatchTheJudgeTimeBase() {
        // BountyMetrics 的指标**没有时间基参数**：它把相邻两个样本当作"相邻两 tick"。
        // 判定侧（BountySession）确实每 tick 采一次；基线侧若按 1 Hz 采样，
        // 窗口里相邻样本实际相隔 20 tick，同一个 move-jitter 会变成完全不同的数量级。
        //
        // 实测（真实证据包，同一段采样）：
        //   逐 tick 口径   move-jitter = 0.086
        //   每 20 tick 口径 move-jitter = 2.79（与库里基线 mean 3.24 吻合）
        // 即 38 倍。拿 1 Hz 的均值去判定逐 tick 的观测值，z ≈ -6，
        // 任何正常玩家都会被算成"极度像机器"，异常分直接顶到满分 100。
        assertEquals(1L, longOf("sample-interval-ticks"),
                "bounty.baseline.sample-interval-ticks 必须为 1：基线必须与判定侧同为逐 tick 采样。"
                        + "改成 20（1 Hz）会让基线与判定处在不同量纲上，异常分对所有正常玩家饱和");
    }

    @Test
    @DisplayName("窗口长度换算成秒，必须落在设计文档说的「10 秒不重叠窗口」附近")
    void windowDurationMatchesDesign() {
        long interval = longOf("sample-interval-ticks");
        long windowSamples = longOf("window-ticks");
        assertTrue(windowSamples >= 40,
                "窗口 " + windowSamples + " 条太短：指标要求至少 3~4 个有效步，"
                        + "窗口过短会让大部分指标返回 NaN（等于该指标永不参与判定）");

        // 采样间隔为 1 时，条数与 tick 数相等
        double seconds = windowSamples * interval / 20.0;
        assertTrue(seconds >= 5.0 && seconds <= 20.0,
                "窗口折算 " + seconds + " 秒，偏离设计文档的「10 秒不重叠窗口」太远："
                        + "过短则样本独立性之外的统计量会抖，过长则基线攒满所需时间成倍增长");
    }

    @Test
    @DisplayName("min-samples 必须高到 z 分数可用（样本太少时 sd 不可信）")
    void minSamplesMustBeStatisticallyUsable() {
        long minSamples = longOf("min-samples");
        assertTrue(minSamples >= 30,
                "min-samples = " + minSamples + " 太低：sd 是估计值，样本少于约 30 时"
                        + "「z 分数」没有统计意义，异常分等于在噪声上做除法");
        assertTrue(minSamples <= 5000,
                "min-samples = " + minSamples + " 太高：基线永远攒不满，"
                        + "整条「完成目标但没被抓到」的判定分支会永久处于降级状态（历史上就这样）");
    }

    @Test
    @DisplayName("就绪所需指标数不能超过实际会被填充的指标数")
    void readyMetricCountIsReachable() {
        long minReadyMetrics = longOf("min-ready-metrics");
        // 基线采样时攻击位恒为 false（非沙箱玩家不采集攻击事件），
        // 所以 attack-interval-cv 在基线里永远是 NaN、不会入库——
        // 实际可用指标是另外 4 个。
        long fillableMetrics = 4L;
        assertTrue(minReadyMetrics >= 1 && minReadyMetrics <= fillableMetrics,
                "min-ready-metrics = " + minReadyMetrics + " 超出了基线实际会填充的指标数（"
                        + fillableMetrics + "）：基线将永远无法就绪");
    }
}
