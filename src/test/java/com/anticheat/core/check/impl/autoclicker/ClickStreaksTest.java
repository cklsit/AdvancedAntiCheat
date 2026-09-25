package com.anticheat.core.check.impl.autoclicker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连击段分析单测。
 *
 * <p>重点是三条边界语义——它们错了不会抛异常，只会让阈值标定失去意义：</p>
 * <ol>
 *   <li>段的门槛是"长度 **超过** MIN_STREAK"（即连续 6 拍起）；</li>
 *   <li>双击（同 tick 多次）在同一段内要**减分**，且作用域仅限该段；</li>
 *   <li>窗口**末尾**未闭合的段不结算——它被窗口边界截断，长度天然偏短。</li>
 * </ol>
 */
class ClickStreaksTest {

    /** 生成"每 tick 都动作"的序列，段与段之间用静止拍隔开。 */
    private static boolean[] streaks(int... lengths) {
        int total = 0;
        for (int len : lengths) {
            total += len + 1; // 每段后面跟一个静止拍，让它闭合
        }
        boolean[] acted = new boolean[total];
        int cursor = 0;
        for (int len : lengths) {
            for (int i = 0; i < len; i++) {
                acted[cursor++] = true;
            }
            cursor++; // 静止拍
        }
        return acted;
    }

    private static boolean[] noMulti(int length) {
        return new boolean[length];
    }

    @Test
    @DisplayName("段长正好等于门槛（6 拍）不计入，7 拍才计入")
    void streakThresholdIsExclusive() {
        // 连续 6 拍 = MIN_STREAK + 1 → 计入
        // 连续 5 拍 = MIN_STREAK     → 不计入
        boolean[] five = streaks(5);
        assertEquals(0.0, ClickStreaks.violationLevel(five, noMulti(five.length)),
                "连续 5 拍（== MIN_STREAK）不该计入：门槛是'超过 5'");

        boolean[] six = streaks(6);
        assertEquals(6 + ClickStreaks.ADJUST_WITHOUT_MULTI,
                ClickStreaks.violationLevel(six, noMulti(six.length)),
                "连续 6 拍应计入：段长 + 无双击修正");
    }

    @Test
    @DisplayName("证据包里的三段 [6,7,8] 必须越过判定线")
    void reproducesEvidenceStreaks() {
        // 这三段来自真实案例（赏金沙箱 combat-basic 的逐 tick 采样），
        // 它们是"完成目标却零命中"那次绕过的可观测特征之一
        boolean[] acted = streaks(6, 7, 8);
        double vl = ClickStreaks.violationLevel(acted, noMulti(acted.length));

        assertEquals(27.0, vl, "8+9+10=27（每段 = 段长 + 2）");
        assertTrue(vl >= ClickStreaks.FLAG_VL,
                "27 必须越过判定线 " + ClickStreaks.FLAG_VL + "，否则这条判据对真实案例无效");
    }

    @Test
    @DisplayName("段内出现同 tick 多次动作要减分（手动连点的特征）")
    void multiPerTickReducesScore() {
        boolean[] acted = streaks(8);
        boolean[] multi = new boolean[acted.length];
        for (int i = 0; i < 8; i++) {
            multi[i] = true; // 整段都有双击
        }

        assertEquals(8 + ClickStreaks.ADJUST_WITH_MULTI,
                ClickStreaks.violationLevel(acted, multi),
                "有双击的段按修正减分，而不是加分");
    }

    @Test
    @DisplayName("双击标记只属于它所在的那一段，不污染后面的段")
    void multiFlagDoesNotLeakToNextStreak() {
        boolean[] acted = streaks(6, 6);
        boolean[] multi = new boolean[acted.length];
        multi[0] = true; // 只有第一段的第一拍有双击

        // 第一段：6 + (-1) = 5；第二段：6 + 2 = 8
        assertEquals(13.0, ClickStreaks.violationLevel(acted, multi),
                "第二段不该继承前一段的双击标记");
    }

    @Test
    @DisplayName("末尾未闭合的段不结算（窗口截断值不可信）")
    void trailingUnclosedStreakIsSkipped() {
        boolean[] acted = new boolean[10];
        for (int i = 0; i < 10; i++) {
            acted[i] = true; // 一整屏都是动作，直到窗口结束也没闭合
        }
        assertEquals(0.0, ClickStreaks.violationLevel(acted, noMulti(10)),
                "段被窗口边界截断，长度不可信；下一个窗口会完整覆盖它");
    }

    @Test
    @DisplayName("最长段统计：跨段取最大，且不受末尾未闭合段影响")
    void longestStreakReportsRawMaximum() {
        boolean[] acted = streaks(6, 9, 7);
        assertEquals(9, ClickStreaks.longestStreak(acted));

        boolean[] open = new boolean[12];
        for (int i = 0; i < 12; i++) {
            open[i] = true;
        }
        assertEquals(12, ClickStreaks.longestStreak(open),
                "最长段是给标定看的原始观测值，与是否结算无关");
    }
}
