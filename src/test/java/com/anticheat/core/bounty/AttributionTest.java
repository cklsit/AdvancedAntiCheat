package com.anticheat.core.bounty;

import com.anticheat.core.check.impl.aim.AimC;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归因纯逻辑单测。
 *
 * <p>用的是**真实绕过证据包**（赏金沙箱 combat-basic 与它的对照组），
 * 因为归因最容易犯的错不是崩溃，而是"算出一个看起来合理、却指向了错误检测"的结论。
 * 只有钉在真实数据上，这类错误才会在改动时立刻暴露。</p>
 */
class AttributionTest {

    private static List<BountySample> loadSamples(String resource) throws Exception {
        InputStream stream = AttributionTest.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(stream, "证据采样缺失：" + resource);

        List<BountySample> samples = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split(",");
                samples.add(new BountySample(
                        Long.parseLong(parts[0].trim()),
                        Double.parseDouble(parts[1].trim()),
                        Double.parseDouble(parts[2].trim()),
                        Double.parseDouble(parts[3].trim()),
                        Float.parseFloat(parts[4].trim()),
                        Float.parseFloat(parts[5].trim()),
                        "1".equals(parts[6].trim()),
                        "1".equals(parts[7].trim())));
            }
        }
        return samples;
    }

    private static Map<String, Double> defaults() {
        return Map.of(
                "AimC", AimC.DEFAULT_FLAG_BALANCE,
                "AutoClickerD", 20.0,
                "SpeedB", 1.5
        );
    }

    private static Attribution.Candidate pick(List<Attribution.Candidate> candidates, String name) {
        return candidates.stream()
                .filter(c -> c.getCheckName().equals(name))
                .findFirst()
                .orElse(null);
    }

    @Test
    @DisplayName("真实绕过证据：AimC 现已能命中，就不再是调整对象")
    void aimCIsAlreadyCaughtOnRealEvidence() throws Exception {
        List<BountySample> samples = loadSamples("bounty-evidence/combat-basic-samples.csv");

        Attribution.Candidate aimC = pick(Attribution.candidates(samples, defaults()), "AimC");
        assertNotNull(aimC, "AimC 应该出现在候选里（它的证据得分为正）");
        assertTrue(aimC.getAlreadyCaught(),
                "按当前默认阈值 " + AimC.DEFAULT_FLAG_BALANCE + "，AimC 应当已经能命中该证据（实测得分 "
                        + aimC.getScore() + "）——如果这里变红，说明判据被改弱了");

        assertNull(Attribution.propose(aimC, AimC.AUTO_TUNE_FLOOR, 0.10, 1.10),
                "已经能命中的判据不该产生调整提案");
    }

    @Test
    @DisplayName("重放口径正确：三个判据的得分与离线复算一致")
    void scoresMatchTheOfflineAnalysis() throws Exception {
        List<BountySample> samples = loadSamples("bounty-evidence/combat-basic-samples.csv");
        List<Attribution.Candidate> candidates = Attribution.candidates(samples, defaults());

        // 这三个数字是独立用 Python 复算出来的（见 artifacts/auto-tune-design.md）。
        // 它们同时是「重放复用了判据自己的实现」的证据：窗口口径一旦漂移，这里立刻变红。
        assertEquals(73.0, pick(candidates, "AimC").getScore(), 1.0, "AimC 累积峰值约 73");
        assertEquals(17.0, pick(candidates, "AutoClickerD").getScore(), 1.0, "AutoClickerD 窗口内最高 vl 约 17");
        assertEquals(0.256, pick(candidates, "SpeedB").getScore(), 0.02, "SpeedB 窗口平均峰值约 0.256");
    }

    @Test
    @DisplayName("排序跟着接近度走：抬高 AimC 阈值会让它从「已命中」掉下来")
    void orderingFollowsRatio() throws Exception {
        List<BountySample> samples = loadSamples("bounty-evidence/combat-basic-samples.csv");

        List<Attribution.Candidate> atDefault = Attribution.candidates(samples, defaults());
        assertEquals("AimC", atDefault.get(0).getCheckName(), "默认阈值下 AimC 接近度最高");
        assertTrue(atDefault.get(0).getAlreadyCaught());

        // 把 AimC 抬到 95：它的比率降到 76.8%，于是 AutoClickerD（85%）成为首选。
        // 这一步模拟的正是「当初 AimC 阈值定得太高」的情形。
        List<Attribution.Candidate> raised = Attribution.candidates(samples,
                Map.of("AimC", 95.0, "AutoClickerD", 20.0, "SpeedB", 1.5));
        assertEquals("AutoClickerD", raised.get(0).getCheckName(),
                "排序必须跟着接近度走，实际：" + raised);
        assertEquals(73.0 / 95.0, pick(raised, "AimC").getRatio(), 0.005);

        // 阈值只影响比率，不影响得分——得分只由证据决定
        Attribution.Proposal proposal =
                Attribution.propose(pick(raised, "AimC"), AimC.AUTO_TUNE_FLOOR, 0.10, 1.10);
        assertNotNull(proposal);
        assertEquals(95.0, proposal.getOldValue(), 1e-6);
        assertEquals(85.5, proposal.getNewValue(), 1e-6, "一次最多收紧 10%：95 -> 85.5");
        assertTrue(proposal.getNewValue() >= AimC.AUTO_TUNE_FLOOR, "不得越过硬下限");
    }

    @Test
    @DisplayName("对照组：没有任何判据应当被选中或命中")
    void controlCaseProducesNoAttribution() throws Exception {
        List<BountySample> samples = loadSamples("bounty-evidence/combat-advanced-samples.csv");

        List<Attribution.Candidate> candidates = Attribution.candidates(samples, defaults());
        for (Attribution.Candidate candidate : candidates) {
            assertFalse(candidate.getAlreadyCaught(),
                    "对照组不该让任何判据命中：" + candidate);
            // 注意这里**不能**断言"不产生提案"：propose 只看是否已命中与三条约束，
            // 接近度门槛是准入（admit）的职责。有意义的断言是"没有任何判据接近门槛"。
            assertTrue(candidate.getRatio() < 0.70,
                    "对照组不该有判据接近归因门槛（0.70），否则准入会放它过去：" + candidate);
        }
    }

    @Test
    @DisplayName("提案受单次幅度与硬下限双重约束，且只会向下调")
    void proposalRespectsStepAndFloor() {
        // 证据 40、阈值 100：needed=44，stepped=90 -> 取 90（幅度先卡住）
        Attribution.Proposal byStep = Attribution.propose(
                new Attribution.Candidate("AimC", Attribution.PARAM_AIMC, 40.0, 100.0),
                55.0, 0.10, 1.10);
        assertNotNull(byStep);
        assertEquals(90.0, byStep.getNewValue(), 1e-6);
        assertFalse(byStep.getClampedByFloor());

        // 证据 50、阈值 60：needed=55，stepped=54，floor=55 -> 取 55（下限卡住）
        Attribution.Proposal byFloor = Attribution.propose(
                new Attribution.Candidate("AimC", Attribution.PARAM_AIMC, 50.0, 60.0),
                55.0, 0.10, 1.10);
        assertNotNull(byFloor);
        assertEquals(55.0, byFloor.getNewValue(), 1e-6);
        assertTrue(byFloor.getClampedByFloor(), "这次是被硬下限截断的");
    }

    @Test
    @DisplayName("max-step 再大也不能一次降超过三成")
    void maxStepIsCapped() {
        Attribution.Proposal proposal = Attribution.propose(
                new Attribution.Candidate("AimC", Attribution.PARAM_AIMC, 10.0, 100.0),
                0.0, 1.0, 1.10);
        assertNotNull(proposal);
        assertEquals(70.0, proposal.getNewValue(), 1e-6,
                "配置写 max-step=1.0（想一次降到底）时，必须被 MAX_MAX_STEP=0.30 截住");
    }

    @Test
    @DisplayName("样本太少时不给候选（几个 tick 的统计没有意义）")
    void tooFewSamplesYieldsNoCandidates() {
        List<BountySample> few = new ArrayList<>();
        for (int i = 0; i < Attribution.MIN_SAMPLES - 1; i++) {
            few.add(new BountySample(i, 0.0, 64.0, 0.0, 0f, 0f, true, false));
        }
        assertTrue(Attribution.candidates(few, defaults()).isEmpty());
    }

    @Test
    @DisplayName("阈值缺失或非正的检测被跳过，不会算出 NaN 比率")
    void missingOrInvalidThresholdIsSkipped() throws Exception {
        List<BountySample> samples = loadSamples("bounty-evidence/combat-basic-samples.csv");

        assertTrue(pick(Attribution.candidates(samples, Map.of("SpeedB", 0.0)), "SpeedB") == null,
                "阈值为 0 的检测应当被跳过，而不是产出 Infinity 接近度");
    }

    @Test
    @DisplayName("三个可重放判据都声明了硬下限（否则自动调参无从约束）")
    void everyReplayableCheckHasAFloor() {
        for (String name : new String[]{"AimC", "AutoClickerD", "SpeedB"}) {
            assertNotNull(Attribution.floorOf(name), name + " 必须声明自动调参硬下限");
        }
        assertNull(Attribution.floorOf("ReachA"),
                "未参与自动调参的检测不该有下限（返回 null 即表示不参与）");
    }
}
