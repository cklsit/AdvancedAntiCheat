package com.anticheat.core.bounty;

import com.anticheat.core.check.impl.aim.AimC;
import com.anticheat.core.check.impl.aim.RotationSnap;
import com.anticheat.core.check.impl.autoclicker.AutoClickerD;
import com.anticheat.core.check.impl.autoclicker.ClickStreaks;
import com.anticheat.contract.Repo;
import com.anticheat.core.check.impl.movement.SpeedB;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动调参的准入与护栏单测。
 *
 * <p>这个功能的危险不在于算错数，而在于**在不该动的时候动了**。
 * 所以测试的重点是"哪些情况必须被拦住"，以及"护栏是否真的挡得住"。</p>
 */
class AutoTunerTest {

    /** 默认配置（enabled=false / shadow=true / min-attribution=0.70 / max-step=0.10 …）。 */
    private static final AutoTuner.Config CFG = new AutoTuner.Config();

    private static AutoTuner.Admission admit(BountyVerdict verdict, BountyConfidence confidence,
                                             boolean baselineReady, double ratio,
                                             boolean hasCandidate, boolean cooldown, boolean quota) {
        return AutoTuner.admit(verdict, confidence, baselineReady, ratio, hasCandidate, cooldown, quota, CFG);
    }

    /** 除被测条件外全部满足的一次准入。 */
    private static AutoTuner.Admission baselineOk() {
        return admit(BountyVerdict.BYPASSED, BountyConfidence.MEDIUM, true, 0.9, true, false, false);
    }

    @Test
    @DisplayName("先确认基准场景是放行的，否则后面的拒绝断言可能是假的")
    void baselineScenarioIsAllowed() {
        assertTrue(baselineOk().getAllowed(), "基准场景必须放行：" + baselineOk().getReason());
    }

    @Test
    @DisplayName("LOW 置信度一律拒绝：此时「完成目标」本身不携带作弊信息")
    void rejectsLowConfidence() {
        AutoTuner.Admission admission =
                admit(BountyVerdict.BYPASSED, BountyConfidence.LOW, true, 0.9, true, false, false);
        assertFalse(admission.getAllowed());
        assertTrue(admission.getReason().contains("LOW"), admission.getReason());
    }

    @Test
    @DisplayName("基线未就绪一律拒绝：那时整个判定都处于降级状态")
    void rejectsWhenBaselineNotReady() {
        AutoTuner.Admission admission =
                admit(BountyVerdict.ZERO_DAY, BountyConfidence.HIGH, false, 0.9, true, false, false);
        assertFalse(admission.getAllowed());
        assertTrue(admission.getReason().contains("基线"), admission.getReason());
    }

    @Test
    @DisplayName("非发现类结论不处理（DETECTED / INCONCLUSIVE）")
    void rejectsNonFindingVerdicts() {
        for (BountyVerdict verdict : new BountyVerdict[]{
                BountyVerdict.DETECTED, BountyVerdict.INCONCLUSIVE}) {
            assertFalse(admit(verdict, BountyConfidence.HIGH, true, 0.9, true, false, false).getAllowed(),
                    verdict.name() + " 不该触发自动调参");
        }
    }

    @Test
    @DisplayName("没有候选被判据接近命中时不处理")
    void rejectsWhenNoCandidate() {
        AutoTuner.Admission admission =
                admit(BountyVerdict.BYPASSED, BountyConfidence.MEDIUM, true, 0.0, false, false, false);
        assertFalse(admission.getAllowed());
        assertTrue(admission.getReason().contains("候选"), admission.getReason());
    }

    @Test
    @DisplayName("接近度低于门槛时不处理（离该判据负责的维度太远）")
    void rejectsLowAttributionRatio() {
        AutoTuner.Admission admission =
                admit(BountyVerdict.BYPASSED, BountyConfidence.MEDIUM, true, 0.5, true, false, false);
        assertFalse(admission.getAllowed());
        assertTrue(admission.getReason().contains("接近度"), admission.getReason());
    }

    @Test
    @DisplayName("冷却期内不重复调整同一个检测")
    void rejectsDuringCooldown() {
        AutoTuner.Admission admission =
                admit(BountyVerdict.BYPASSED, BountyConfidence.MEDIUM, true, 0.9, true, true, false);
        assertFalse(admission.getAllowed());
        assertTrue(admission.getReason().contains("冷却"), admission.getReason());
    }

    @Test
    @DisplayName("当日配额耗尽后不再调整")
    void rejectsWhenQuotaExhausted() {
        AutoTuner.Admission admission =
                admit(BountyVerdict.BYPASSED, BountyConfidence.MEDIUM, true, 0.9, true, false, true);
        assertFalse(admission.getAllowed());
        assertTrue(admission.getReason().contains("配额"), admission.getReason());
    }

    @Test
    @DisplayName("硬下限必须严格高于判据的不变量边界")
    void floorsAreAboveInvariantBoundaries() {
        // AimC：不变量是「单次最强瞬转证据不能单独告警」，
        // 而最强档权重是 RotationSnap.weight(>178°)
        double strongestSnap = RotationSnap.weight(179.0);
        assertTrue(AimC.AUTO_TUNE_FLOOR > strongestSnap,
                "AimC 的自动调参下限 " + AimC.AUTO_TUNE_FLOOR + " 必须 > 单次最强证据 " + strongestSnap
                        + "，否则自动调参会把判据推到违反不变量测试的地方");

        // AutoClickerD：一个刚好越过门槛的段（6 拍）贡献 6 + 2 = 8 分
        double smallestQualifyingStreak = (ClickStreaks.MIN_STREAK + 1) + ClickStreaks.ADJUST_WITHOUT_MULTI;
        assertTrue(AutoClickerD.AUTO_TUNE_FLOOR_FLAG_VL > smallestQualifyingStreak,
                "AutoClickerD 的判定线下限 " + AutoClickerD.AUTO_TUNE_FLOOR_FLAG_VL
                        + " 必须 > 单个刚过线方段的得分 " + smallestQualifyingStreak);

        // SpeedB：实测合法窗口平均 ≤ 0.75，下限不得压进合法区间
        assertTrue(SpeedB.AUTO_TUNE_FLOOR >= 1.2,
                "SpeedB 的自动调参下限 " + SpeedB.AUTO_TUNE_FLOOR
                        + " 太低：会压到冰道交通与攒包噪声所在的合法区间");
    }

    @Test
    @DisplayName("上限也成立：自动调参不得把阈值推到比默认值还松")
    void floorsAreBelowDefaults() {
        assertTrue(AimC.AUTO_TUNE_FLOOR <= AimC.DEFAULT_FLAG_BALANCE,
                "下限高于默认值会让默认配置本身就违规");
        assertTrue(AutoClickerD.AUTO_TUNE_FLOOR_FLAG_VL <= AutoClickerD.DEFAULT_FLAG_VL,
                "下限高于默认值会让默认配置本身就违规");
        assertTrue(SpeedB.AUTO_TUNE_FLOOR <= SpeedB.DEFAULT_MAX_AVG_SPEED,
                "下限高于默认值会让默认配置本身就违规");
    }

    @Test
    @DisplayName("配置必须挂在 /ac reload 上（否则改了配置也不会生效）")
    void configIsWiredIntoReload() throws IOException {
        // 这条断言是**源码级**的，它守的是「AutoTuner.loadConfig 必须被
        // BountyManager.reload() 调用」。
        //
        // 漏掉它的后果是完全静默的：管理员改完 config.yml、执行 /ac reload，
        // 以为功能开了，实际跑的还是插件启动时读到的旧值，而且不报任何错、
        // 不写任何审计。2026-09-26 就是这么踩的——把 enabled 改成 true、
        // shadow 改成 false 之后又跑了三次绕过，audit_log 里一条都没有。
        Path source = Repo.mainJava().resolve("com/anticheat/bounty/BountyManager.java");
        assertTrue(java.nio.file.Files.exists(source), "找不到 " + source);
        String code = java.nio.file.Files.readString(source, java.nio.charset.StandardCharsets.UTF_8);

        int reloadAt = code.indexOf("public void reload()");
        assertTrue(reloadAt > 0, "BountyManager 里找不到 reload()");
        String reloadBody = code.substring(reloadAt, Math.min(code.length(), reloadAt + 900));
        assertTrue(reloadBody.contains("AutoTuner.loadConfig"),
                "BountyManager.reload() 必须调用 AutoTuner.loadConfig —— "
                        + "少了它，改完配置执行 /ac reload 不会让自动调参生效，且不会有任何报错");
    }

    @Test
    @DisplayName("默认配置是「关闭 + 影子模式」——上线姿势不冒险")
    void defaultConfigIsOffAndShadowed() {
        assertFalse(CFG.getEnabled(), "自动调参必须默认关闭");
        assertTrue(CFG.getShadow(), "即便打开，默认也应当是影子模式（只记录不写入）");
        assertTrue(CFG.getMinAttribution() >= 0.7, "归因门槛不应低于 0.7");
        assertTrue(CFG.getMaxStep() <= 0.1, "单次幅度不应超过 10%");
        assertTrue(CFG.getMaxPerDay() <= 3, "每日配额不应超过 3 次");
    }
}
