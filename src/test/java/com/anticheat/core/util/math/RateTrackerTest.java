package com.anticheat.core.util.math;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 事件频率窗口单测。
 *
 * <p>被 CPS 检测与放置频率检测共用，验证的重点是**窗口推进的边界语义**：
 * 事件记在当前格、推进一格滑出最旧一格。这类"环形 + 增量总和"的实现
 * 错一格不会崩也不会报错，只会让频率长期偏移 1~2，使阈值标定失去意义。</p>
 */
class RateTrackerTest {

    @Test
    @DisplayName("同一格内连续记录：总数与单格峰值都如实反映")
    void countsWithinSameBucket() {
        RateTracker tracker = new RateTracker();
        for (int i = 0; i < 20; i++) {
            tracker.record();
        }
        assertEquals(20, tracker.count());
        assertEquals(20, tracker.peakPerBucket(),
                "单 tick 20 次必须被如实记为峰值——这是'协议层不可能'的强判据");
        assertEquals(20, tracker.getEventsInCurrentBucket());
    }

    @Test
    @DisplayName("推进一个完整窗口后，旧事件全部过期")
    void slidingWindowEvictsOldEvents() {
        RateTracker tracker = new RateTracker();
        for (int i = 0; i < 20; i++) {
            tracker.record();
        }
        assertEquals(20, tracker.count());

        for (int i = 0; i < RateTracker.DEFAULT_WINDOW_TICKS; i++) {
            tracker.tick();
        }
        assertEquals(0, tracker.count(),
                "一个窗口之后旧事件必须全部滑出，否则频率会永远虚高");
    }

    @Test
    @DisplayName("推进一格不会丢掉本 tick 的事件")
    void tickingKeepsCurrentBucket() {
        RateTracker tracker = new RateTracker();
        tracker.record();
        tracker.record();

        tracker.tick();
        assertEquals(0, tracker.getEventsInCurrentBucket(), "新的一格应从未记录状态开始");
        assertEquals(2, tracker.count(), "刚记录的事件不应被同一次推进挤掉");
    }

    @Test
    @DisplayName("每 tick 恰好一次事件：窗口内计数稳定在窗口长度附近")
    void oneEventPerTickSaturatesNearWindowLength() {
        RateTracker tracker = new RateTracker();
        for (int i = 0; i < RateTracker.DEFAULT_WINDOW_TICKS; i++) {
            tracker.record();
            tracker.tick();
        }
        // 游标已绕回 0，最早那一格刚被清空
        assertEquals(RateTracker.DEFAULT_WINDOW_TICKS - 1, tracker.count());

        tracker.record();
        assertEquals(RateTracker.DEFAULT_WINDOW_TICKS, tracker.count(),
                "每 tick 一次事件应恰好读作 20");
        assertEquals(1, tracker.peakPerBucket());
    }

    @Test
    @DisplayName("长时间运行不会累积（频率必须稳定）")
    void longRunStaysBounded() {
        RateTracker tracker = new RateTracker();
        for (int i = 0; i < 5000; i++) {
            tracker.record();
            tracker.tick();
        }
        // 循环以 tick 收尾：新 tick 还没有事件，所以读到窗口上界减一。
        // 关键断言是"稳定"，不是具体数值。
        assertEquals(RateTracker.DEFAULT_WINDOW_TICKS - 1, tracker.count(),
                "长时间运行后频率必须稳定在窗口上界附近，不能随运行时长增长");
    }

    @Test
    @DisplayName("reset 清空窗口与总量")
    void resetClearsEverything() {
        RateTracker tracker = new RateTracker();
        for (int i = 0; i < 15; i++) {
            tracker.record();
        }
        tracker.reset();

        assertEquals(0, tracker.count());
        assertEquals(0, tracker.peakPerBucket());
        assertEquals(0, tracker.getEventsInCurrentBucket());
    }

    @Test
    @DisplayName("窗口过小直接拒绝：1 tick 的窗口算不出频率")
    void rejectsTooSmallWindow() {
        assertThrows(IllegalArgumentException.class, () -> new RateTracker(1));
    }

    @Test
    @DisplayName("自定义窗口长度生效")
    void customWindowLength() {
        RateTracker tracker = new RateTracker(5);
        for (int i = 0; i < 5; i++) {
            tracker.record();
            tracker.tick();
        }
        assertEquals(4, tracker.count());
        tracker.record();
        assertEquals(5, tracker.count());
    }
}
