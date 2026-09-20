package com.anticheat.core.util.math;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三维点环单测。
 *
 * <p>这里最值得钉死的是**距离的坐标约定**：`Location.y` 是**脚底**，
 * 而盒子的垂直范围是 `[y, y + height]`。把点当成"中心"会让所有距离整体
 * 偏移约半格——对伸手检测来说，半格偏移就是"该报的不报、不该报的全报"。</p>
 */
class PointHistoryTest {

    @Test
    @DisplayName("绕回后仍按写入顺序可访问（下标 0 = 最旧）")
    void keepsInsertionOrderAfterWrap() {
        PointHistory history = new PointHistory(3);
        for (int i = 1; i <= 5; i++) {
            history.add(i, 0, 0);
        }

        assertEquals(3, history.getSize());
        assertTrue(history.isFull());
        assertEquals(3.0, history.x(0), 1e-9, "最旧的应该是第 3 个样本");
        assertEquals(4.0, history.x(1), 1e-9);
        assertEquals(5.0, history.x(2), 1e-9, "最新的是第 5 个样本");
    }

    @Test
    @DisplayName("未填满时 size 与 isFull 正确")
    void reportsPartialFill() {
        PointHistory history = new PointHistory(4);
        assertEquals(0, history.getSize());
        assertEquals(false, history.isFull());

        history.add(1, 2, 3);
        assertEquals(1, history.getSize());
        assertFalse(history.isFull());
        assertEquals(1.0, history.x(0), 1e-9);
        assertEquals(2.0, history.y(0), 1e-9);
        assertEquals(3.0, history.z(0), 1e-9);
    }

    @Test
    @DisplayName("点到盒子的距离：水平出界、垂直出界、以及盒内为 0")
    void distanceToBoxHorizontally() {
        // 盒子：脚底中心 (0,0,0)、水平半宽 0.3、高 2.0。
        // 取 y=1 的点落在垂直范围内，于是水平分量是唯一贡献。
        assertEquals(2.7, PointHistory.distanceToBox(3, 1, 0, 0, 0, 0, 0.3, 2.0), 1e-9,
                "水平距离 3 要减掉半宽 0.3，剩下 2.7");
        assertEquals(0.0, PointHistory.distanceToBox(0.2, 1, 0, 0, 0, 0, 0.3, 2.0), 1e-9,
                "水平落在 [-0.3,0.3] 内应记 0，不能出现负距离");
        assertEquals(0.0, PointHistory.distanceToBox(0, 1, 0, 0, 0, 0, 0.3, 2.0), 1e-9,
                "盒子内部距离为 0");
    }

    @Test
    @DisplayName("垂直方向按 [y, y+height] 计算（y 是脚底，不是中心）")
    void distanceToBoxVertically() {
        // 脚底下方 2 格：出界量 2（若把 y 当中心会算出 1，全盘偏移）
        assertEquals(2.0, PointHistory.distanceToBox(0, -2, 0, 0, 0, 0, 0.3, 2.0), 1e-9,
                "y 是脚底：脚底下 2 格就是出界 2 格");
        // 头顶上方 2 格（盒顶在 y=2）
        assertEquals(2.0, PointHistory.distanceToBox(0, 4, 0, 0, 0, 0, 0.3, 2.0), 1e-9,
                "盒顶在 y+height 处，上方 2 格应出界 2 格");
        // 恰好贴住盒顶
        assertEquals(0.0, PointHistory.distanceToBox(0, 2, 0, 0, 0, 0, 0.3, 2.0), 1e-9);
    }

    @Test
    @DisplayName("三轴同时出界时取欧氏距离")
    void distanceToBoxCombinesAxes() {
        // dx = 3 - 0.3 = 2.7；dy = 4 - 2 = 2.0；dz = 4 - 0.3 = 3.7
        double expected = Math.sqrt(2.7 * 2.7 + 2.0 * 2.0 + 3.7 * 3.7);
        assertEquals(expected, PointHistory.distanceToBox(3, 4, 4, 0, 0, 0, 0.3, 2.0), 1e-9);
    }

    @Test
    @DisplayName("两组历史之间取最小盒距离（延迟补偿的数学形式）")
    void minDistanceTakesBestCaseOverHistories() {
        PointHistory eyes = new PointHistory(3);
        eyes.add(3, 1, 0);

        PointHistory targets = new PointHistory(3);
        targets.add(0, 0, 0);   // 距离 2.7
        targets.add(0.6, 0, 0); // 距离 2.1 —— 目标"走远前"的位置

        assertEquals(2.1, eyes.minDistanceToBoxes(targets, 0.3, 2.0), 1e-9,
                "必须取更小的那个：等价于替玩家做最有利的延迟补偿");
    }

    @Test
    @DisplayName("任一侧为空时返回 MAX_VALUE（调用方据此跳过判定，而不是当成违规）")
    void emptyHistoryIsUndecidable() {
        PointHistory eyes = new PointHistory(3);
        PointHistory targets = new PointHistory(3);

        assertEquals(Double.MAX_VALUE, eyes.minDistanceToBoxes(targets, 0.3, 1.8), 1e-9);

        eyes.add(1, 2, 3);
        assertEquals(Double.MAX_VALUE, eyes.minDistanceToBoxes(targets, 0.3, 1.8), 1e-9,
                "目标历史为空时不可判定");

        PointHistory singles = new PointHistory(1);
        singles.add(3, 1, 0);
        PointHistory one = new PointHistory(1);
        one.add(0, 0, 0);
        assertEquals(2.7, singles.minDistanceToBoxes(one, 0.3, 2.0), 1e-9);
    }

    @Test
    @DisplayName("clear 后历史归零且不再参与距离计算")
    void clearResetsHistory() {
        PointHistory history = new PointHistory(2);
        history.add(5, 5, 5);
        history.clear();

        assertEquals(0, history.getSize());
        assertFalse(history.isFull());
        assertEquals(Double.MAX_VALUE,
                history.minDistanceToBox(0, 0, 0, 0.3, 1.8), 1e-9);
    }

    @Test
    @DisplayName("容量必须为正")
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new PointHistory(0));
    }
}
