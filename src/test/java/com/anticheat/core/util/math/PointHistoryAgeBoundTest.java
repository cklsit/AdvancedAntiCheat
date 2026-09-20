package com.anticheat.core.util.math;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link PointHistory#minDistanceToBoxes} 的**样本年龄上限**回归测试。
 *
 * <p>这是本类里唯一一个"放松就会静默漏判"的开关：不限制年龄等价于假设客户端
 * 能看到 8 tick 以前的目标位置，于是目标在这段时间里靠近过多少，实测距离就被
 * 削掉多少（冲刺目标可达 2.8 格）。削掉之后数据看起来完全正常，
 * 只是作弊抓不到了——所以这里必须把口径钉死。</p>
 */
class PointHistoryAgeBoundTest {

    /** 构造一个"眼睛固定在原点、目标逐 tick 远离"的对照场景。 */
    private static PointHistory eyes() {
        PointHistory eyes = new PointHistory(8);
        for (int i = 0; i < 8; i++) {
            // 与盒子同高度：本测试只考察"样本年龄"这一维度，
            // 竖直分量会引入 sqrt 项把预期值污染成 7.057 这类数
            eyes.add(0.0, 0.0, 0.0);
        }
        return eyes;
    }

    private static PointHistory targetMovingAway() {
        PointHistory target = new PointHistory(8);
        for (int i = 0; i < 8; i++) {
            target.add((double) i, 0.0, 0.0);
        }
        return target;
    }

    @Test
    @DisplayName("年龄 0 = 只用最新样本（距离就是当前距离，不被历史靠近量削掉）")
    void ageZeroUsesLatestOnly() {
        double distance = eyes().minDistanceToBoxes(targetMovingAway(), 0.0, 0.0, 0, 0);
        assertEquals(7.0, distance, 1e-9,
                "只用最新目标位置时距离应为 7；若更小说明把历史样本也算进来了");
    }

    @Test
    @DisplayName("年龄越大越宽松：1 tick -> 6，不设上限 -> 0（历史里最靠近的那一帧）")
    void largerAgeIsMorePermissive() {
        assertEquals(6.0, eyes().minDistanceToBoxes(targetMovingAway(), 0.0, 0.0, 1, 1), 1e-9);
        assertEquals(0.0, eyes().minDistanceToBoxes(targetMovingAway(), 0.0, 0.0), 1e-9,
                "不设上限时目标历史里的 x=0 会被算进来，距离归零——这就是必须限制年龄的原因");
    }

    @Test
    @DisplayName("两侧年龄各自独立生效")
    void eyeAndTargetAgeAreIndependent() {
        PointHistory eyes = eyes();
        PointHistory target = targetMovingAway();
        // 眼睛侧放宽不影响"目标侧只用最新样本"这一约束
        assertEquals(7.0, eyes.minDistanceToBoxes(target, 0.0, 0.0, 7, 0), 1e-9);
    }

    @Test
    @DisplayName("年龄超过历史长度时退化为不限制（不能越界）")
    void ageBeyondCapacityIsSafe() {
        assertEquals(0.0, eyes().minDistanceToBoxes(targetMovingAway(), 0.0, 0.0, 100, 100), 1e-9);
    }

    @Test
    @DisplayName("任一侧为空时返回哨兵值（调用方据此放弃判定）")
    void emptySideReturnsSentinel() {
        PointHistory empty = new PointHistory(8);
        assertEquals(Double.MAX_VALUE, empty.minDistanceToBoxes(targetMovingAway(), 0.3, 1.8, 1, 1), 1e-9);
        assertEquals(Double.MAX_VALUE, eyes().minDistanceToBoxes(empty, 0.3, 1.8, 1, 1), 1e-9);
    }
}
