package com.anticheat.core.check;

import com.anticheat.core.check.impl.aim.RotationSnap;
import com.anticheat.core.check.impl.autoclicker.ClickStreaks;
import com.anticheat.core.check.impl.movement.SpeedB;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用**真实绕过证据**回放校验新判据。
 *
 * <h3>数据来源</h3>
 * `bounty-evidence/` 下的两个 CSV 是生产服务器上赏金沙箱的逐 tick 采样，
 * 由 `BountyEvidence` 落盘（列：`seq,x,y,z,yaw,pitch,onGround,attacked`）：
 *
 * <ul>
 *   <li>`combat-basic-samples.csv`：任务「战斗检测·初级」的**绕过案例**
 *       —— 玩家 4.45 秒清掉 5 个持续移动的傀儡，全程 0 次检测命中，
 *       赏金系统判定 BYPASSED。这是本测试要"抓住"的那一次。</li>
 *   <li>`combat-advanced-samples.csv`：**对照组**（同一玩家、同一沙箱、
 *       任务未达成）。它同样有大量位移与视角变化，但**没有**瞬转三拍指纹、
 *       也没有连击段——新判据必须对它保持沉默，否则就是误报。</li>
 * </ul>
 *
 * <h3>为什么把生产数据放进单元测试</h3>
 * 这三个判据的阈值若要"凭感觉"定，谁也说不出为什么是 40 度或 1.5 格/tick。
 * 把它们钉在真实案例上之后，阈值一改就会有人知道：
 * 要么抓不到这次绕过，要么开始误伤对照组。这比任何注释都可靠。
 *
 * <p>注意 `attacked` 列的语义是"本 tick 有挥臂**或**造成了伤害"
 * （见 `BountyListener`），因此本测试用它作为"是否处于战斗中"的近似——
 * 与 [com.anticheat.core.check.impl.aim.AimC] 里 `lastAttackMillis` /
 * `lastSwingMillis` 那道门是同一档次的信息。</p>
 */
class BountyBypassReplayTest {

    /** 一次采样。 */
    private record Sample(long seq, double x, double y, double z,
                          double yaw, double pitch, boolean onGround, boolean attacked) {
    }

    private static List<Sample> load(String resource) throws Exception {
        // 走 ClassLoader 而不是 Class.getResourceAsStream：后者的相对路径是
        // "相对于该类的包"，会被解析成 com/anticheat/core/check/bounty-evidence/...
        InputStream stream = BountyBypassReplayTest.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(stream, "证据采样缺失：" + resource);

        List<Sample> samples = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split(",");
                samples.add(new Sample(
                        Long.parseLong(parts[0].trim()),
                        Double.parseDouble(parts[1].trim()),
                        Double.parseDouble(parts[2].trim()),
                        Double.parseDouble(parts[3].trim()),
                        Double.parseDouble(parts[4].trim()),
                        Double.parseDouble(parts[5].trim()),
                        "1".equals(parts[6].trim()),
                        "1".equals(parts[7].trim())));
            }
        }
        assertTrue(samples.size() > 100, "样本太少（" + samples.size() + "），先检查证据文件");
        return samples;
    }

    /** 逐 tick 的 |Δyaw|（只统计 seq 连续的两拍）。 */
    private static List<Double> yawMotions(List<Sample> samples) {
        List<Double> motions = new ArrayList<>();
        for (int i = 1; i < samples.size(); i++) {
            Sample previous = samples.get(i - 1);
            Sample current = samples.get(i);
            if (current.seq() - previous.seq() != 1) continue;
            motions.add(Math.abs(normalizeDegrees(current.yaw() - previous.yaw())));
        }
        return motions;
    }

    private static double normalizeDegrees(double value) {
        double v = value % 360.0;
        if (v >= 180.0) v -= 360.0;
        if (v < -180.0) v += 360.0;
        return v;
    }

    /**
     * 按 [RotationSnap] 的三拍指纹统计命中数，并要求相邻（±3 tick）内有攻击。
     *
     * <p>与 `AimC` 的差别只有"用序号近似时间窗"这一点——`AimC` 用墙钟毫秒，
     * 而证据里没有时间戳。</p>
     */
    private static int countSnapsWithCombatGate(List<Sample> samples) {
        List<Double> motions = yawMotions(samples);
        int hits = 0;
        for (int i = 1; i < motions.size() - 1; i++) {
            if (!RotationSnap.isSnap(motions.get(i - 1), motions.get(i), motions.get(i + 1))) continue;
            if (hasNearbyAttack(samples, i)) hits++;
        }
        return hits;
    }

    /** 第 index 个 yaw motion 对应 samples 里的第 index+1 个采样，附近 ±3 拍内是否有攻击。 */
    private static boolean hasNearbyAttack(List<Sample> samples, int motionIndex) {
        int center = motionIndex + 1;
        for (int i = Math.max(0, center - 3); i <= Math.min(samples.size() - 1, center + 3); i++) {
            if (samples.get(i).attacked()) return true;
        }
        return false;
    }

    /** 逐 tick 攻击标记（只取 seq 连续、且属于后一个采样的那一拍）。 */
    private static boolean[] attackTicks(List<Sample> samples) {
        List<Boolean> flags = new ArrayList<>();
        for (int i = 1; i < samples.size(); i++) {
            if (samples.get(i).seq() - samples.get(i - 1).seq() != 1) continue;
            flags.add(samples.get(i).attacked());
        }
        boolean[] out = new boolean[flags.size()];
        for (int i = 0; i < flags.size(); i++) {
            out[i] = flags.get(i);
        }
        return out;
    }

    /** 20 tick 滑动窗口平均水平速度的最大值（复现 [SpeedB] 的窗口语义）。 */
    private static double maxWindowAverageSpeed(List<Sample> samples) {
        List<Double> speeds = new ArrayList<>();
        for (int i = 1; i < samples.size(); i++) {
            Sample previous = samples.get(i - 1);
            Sample current = samples.get(i);
            if (current.seq() - previous.seq() != 1) continue;
            double dx = current.x() - previous.x();
            double dz = current.z() - previous.z();
            speeds.add(Math.hypot(dx, dz));
        }

        double best = 0.0;
        for (int start = 0; start + SpeedB.WINDOW_TICKS <= speeds.size(); start++) {
            double sum = 0.0;
            for (int i = start; i < start + SpeedB.WINDOW_TICKS; i++) {
                sum += speeds.get(i);
            }
            best = Math.max(best, sum / SpeedB.WINDOW_TICKS);
        }
        return best;
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("绕过案例：瞬转判据必须命中（且全部落在战斗窗口内）")
    void snapDetectsBypassCase() throws Exception {
        List<Sample> samples = load("bounty-evidence/combat-basic-samples.csv");

        int withGate = countSnapsWithCombatGate(samples);
        assertTrue(withGate >= 5,
                "绕过案例里的瞬转三拍应被稳定检出（实测 7 次），实际 " + withGate
                        + " —— 阈值或判据被改弱了");
    }

    @Test
    @DisplayName("对照组：没有瞬转指纹就必须保持沉默")
    void snapStaysSilentOnControlCase() throws Exception {
        List<Sample> samples = load("bounty-evidence/combat-advanced-samples.csv");

        assertEquals(0, countSnapsWithCombatGate(samples),
                "对照组（任务未达成、无瞬转行为）不该产生瞬转告警——"
                        + "这里的命中就是误报，说明战斗窗口或指纹条件被放宽了");
    }

    @Test
    @DisplayName("绕过案例：连击段必须越过判定线")
    void clickStreaksFlagBypassCase() throws Exception {
        List<Sample> samples = load("bounty-evidence/combat-basic-samples.csv");
        boolean[] acted = attackTicks(samples);

        double vl = ClickStreaks.violationLevel(acted, new boolean[acted.length]);
        assertTrue(vl >= ClickStreaks.FLAG_VL,
                "绕过案例的连击段应越过判定线 " + ClickStreaks.FLAG_VL + "（实测 27），实际 " + vl);
    }

    @Test
    @DisplayName("两段数据都不该触发持续超速（实测窗口平均远低于阈值）")
    void speedWindowDoesNotFireOnEvidence() throws Exception {
        for (String resource : new String[]{
                "bounty-evidence/combat-basic-samples.csv",
                "bounty-evidence/combat-advanced-samples.csv"}) {
            List<Sample> samples = load(resource);
            double maxAverage = maxWindowAverageSpeed(samples);

            assertTrue(maxAverage < SpeedB.DEFAULT_MAX_AVG_SPEED,
                    resource + " 的窗口平均速度 " + maxAverage + " 超过了 SpeedB 阈值 "
                            + SpeedB.DEFAULT_MAX_AVG_SPEED + " —— 这会把实战采样判成超速");
            assertTrue(maxAverage < 1.0,
                    resource + " 的窗口平均 " + maxAverage + " 应落在原版合法区间（<1.0）内，"
                            + "用来确认阈值确实留有余量");
        }
    }

    @Test
    @DisplayName("单 tick 极值不足以判定：同段数据的窗口平均远小于单拍峰值")
    void singleTickPeakIsNotUsableAsEvidence() throws Exception {
        List<Sample> samples = load("bounty-evidence/combat-advanced-samples.csv");

        double peak = 0.0;
        for (int i = 1; i < samples.size(); i++) {
            Sample previous = samples.get(i - 1);
            Sample current = samples.get(i);
            if (current.seq() - previous.seq() != 1) continue;
            peak = Math.max(peak, Math.hypot(current.x() - previous.x(), current.z() - previous.z()));
        }

        assertTrue(peak > 2.0,
                "这段数据里单 tick 峰值应超过 2 格（客户端攒包把多拍位移合并成一包）");
        assertTrue(maxWindowAverageSpeed(samples) < 1.0,
                "而窗口平均仍在合法范围内 —— 这正是 SpeedB 不用单拍极值、"
                        + "而 SpeedA 只能保持实验性的原因");
    }
}
