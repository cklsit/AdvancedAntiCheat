package com.anticheat.core.util.math;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 环形缓冲单测。
 *
 * <p>这个类承载了所有"最近 N 个样本"的统计，其中 [DoubleRing#toArray] 的
 * 顺序正确性是唯一容易写错、又最难在线上发现的地方——绕回之后顺序错位，
 * 均值与标准差照样正确，只有按顺序消费样本的逻辑会静默失准。</p>
 */
class DoubleRingTest {

    @Test
    @DisplayName("未填满时按写入顺序返回有效样本")
    void partialFillKeepsInsertionOrder() {
        DoubleRing ring = new DoubleRing(4);
        ring.add(10.0);
        ring.add(20.0);
        ring.add(30.0);

        assertEquals(3, ring.getSize());
        assertEquals(false, ring.isFull());
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new double[]{10.0, 20.0, 30.0}, ring.toArray());
    }

    @Test
    @DisplayName("绕回之后 toArray 仍是写入顺序（不是底层数组顺序）")
    void fullRingKeepsInsertionOrderAfterWrap() {
        DoubleRing ring = new DoubleRing(4);
        ring.add(1.0);
        ring.add(2.0);
        ring.add(3.0);
        ring.add(4.0);
        // 第 5 个元素把最旧的 1.0 挤出去，游标绕回 0
        ring.add(5.0);

        assertEquals(4, ring.getSize());
        assertEquals(true, ring.isFull());
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new double[]{2.0, 3.0, 4.0, 5.0}, ring.toArray(),
                "绕回后必须是写入顺序；返回底层数组顺序会得到 [5,2,3,4]");
        assertEquals(3.5, ring.mean(), 1e-9);
    }

    @Test
    @DisplayName("总体标准差（除以 N，不是 N-1）")
    void populationStandardDeviation() {
        DoubleRing ring = new DoubleRing(4);
        ring.add(1.0);
        ring.add(2.0);
        ring.add(3.0);
        ring.add(4.0);

        // 均值 2.5；偏差平方和 = 2.25+0.25+0.25+2.25 = 5.0；除以 N=4 得 1.25
        assertEquals(2.5, ring.mean(), 1e-9);
        assertEquals(Math.sqrt(1.25), ring.populationStandardDeviation(), 1e-9,
                "必须除以 N；用样本标准差(N-1)会得到 " + Math.sqrt(5.0 / 3.0));
    }

    @Test
    @DisplayName("样本不足时标准差返回 0（不足以下结论，不能当异常）")
    void sdNeedsAtLeastTwoSamples() {
        DoubleRing ring = new DoubleRing(4);
        assertEquals(0.0, ring.populationStandardDeviation(), 1e-9);

        ring.add(7.0);
        assertEquals(0.0, ring.populationStandardDeviation(), 1e-9, "单个样本没有离散度概念");
    }

    @Test
    @DisplayName("全部取值相同时标准差为 0")
    void identicalSamplesHaveZeroDeviation() {
        DoubleRing ring = new DoubleRing(5);
        for (int i = 0; i < 5; i++) {
            ring.add(100.0);
        }
        assertEquals(0.0, ring.populationStandardDeviation(), 1e-9);
        assertEquals(100.0, ring.mean(), 1e-9);
    }

    @Test
    @DisplayName("维护增量总和，绕回后仍与内容一致")
    void totalStaysConsistentAfterWrap() {
        DoubleRing ring = new DoubleRing(3);
        for (int i = 1; i <= 10; i++) {
            ring.add(i);
        }
        // 最近三个是 8,9,10
        assertEquals(27.0, ring.getTotal(), 1e-9);
        assertEquals(9.0, ring.mean(), 1e-9);
    }

    @Test
    @DisplayName("clear 之后回到初始状态，旧数据不得残留")
    void clearResetsEverything() {
        DoubleRing ring = new DoubleRing(3);
        ring.add(1.0);
        ring.add(2.0);
        ring.clear();

        assertEquals(0, ring.getSize());
        assertEquals(0.0, ring.getTotal(), 1e-9);
        assertEquals(0.0, ring.mean(), 1e-9);
        // 清空后重新写入必须从下标 0 开始
        ring.add(42.0);
        org.junit.jupiter.api.Assertions.assertArrayEquals(new double[]{42.0}, ring.toArray());
    }

    @Test
    @DisplayName("容量必须为正")
    void rejectsNonPositiveCapacity() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new DoubleRing(0));
    }
}
