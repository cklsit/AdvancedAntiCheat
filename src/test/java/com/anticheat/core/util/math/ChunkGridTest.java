package com.anticheat.core.util.math;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChunkGrid} 的回归测试。
 *
 * <p>这个类只做坐标运算，但它是实体索引的基石：区块键一旦碰撞，
 * 两个区块的实体会互相覆盖（表现为"偶发查不到目标"）；负数坐标取整方向一旦写错，
 * 整个负象限的实体都查不到。两种错误都不会抛异常，只能靠这里守住。</p>
 */
class ChunkGridTest {

    @Test
    @DisplayName("区块键在正负坐标上都不碰撞（负 z 必须做位掩码）")
    void chunkKeyDoesNotCollide() {
        // 不做掩码时 (1,-1) 会与 (0,-1) 都算成 -1L，把两个区块合并成一个
        assertNotEquals(ChunkGrid.chunkKey(1, -1), ChunkGrid.chunkKey(0, -1),
                "负 z 未做位掩码会污染高位，把不同区块算成同一个键");
        assertNotEquals(ChunkGrid.chunkKey(-1, 0), ChunkGrid.chunkKey(0, 0));
        assertNotEquals(ChunkGrid.chunkKey(-1, -1), ChunkGrid.chunkKey(1, 1));

        Set<Long> keys = new HashSet<>();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                assertTrue(keys.add(ChunkGrid.chunkKey(x, z)),
                        "区块 (" + x + "," + z + ") 的键与之前的重复");
            }
        }
        assertEquals(49, keys.size());
    }

    @Test
    @DisplayName("区块键可拆回原坐标（含负数）")
    void chunkKeyRoundTrip() {
        for (int x = -40; x <= 40; x += 8) {
            for (int z = -40; z <= 40; z += 8) {
                long key = ChunkGrid.chunkKey(x, z);
                assertEquals(x, ChunkGrid.chunkXOf(key));
                assertEquals(z, ChunkGrid.chunkZOf(key));
            }
        }
    }

    @Test
    @DisplayName("方块坐标 → 区块坐标：负数是向下取整而不是朝零取整")
    void blockToChunkUsesFloorDivision() {
        assertEquals(0, ChunkGrid.chunkOfBlock(0));
        assertEquals(0, ChunkGrid.chunkOfBlock(15));
        assertEquals(1, ChunkGrid.chunkOfBlock(16));

        // -1 属于区块 -1；用 / 16 会得到 0，于是 -1 与 0 被算成同一区块
        assertEquals(-1, ChunkGrid.chunkOfBlock(-1));
        assertEquals(-1, ChunkGrid.chunkOfBlock(-16));
        assertEquals(-2, ChunkGrid.chunkOfBlock(-17));
    }

    @Test
    @DisplayName("世界坐标 → 区块坐标：先 floor 再取区块")
    void worldCoordToChunk() {
        assertEquals(0, ChunkGrid.chunkOfWorldCoord(0.0));
        assertEquals(0, ChunkGrid.chunkOfWorldCoord(15.99));
        assertEquals(1, ChunkGrid.chunkOfWorldCoord(16.0));
        assertEquals(-1, ChunkGrid.chunkOfWorldCoord(-0.01));
        assertEquals(-1, ChunkGrid.chunkOfWorldCoord(-16.0));
        assertEquals(-2, ChunkGrid.chunkOfWorldCoord(-16.01));
    }

    @Test
    @DisplayName("邻域：半径 0 只有中心，半径 1 是 3×3 且中心在最前")
    void neighborhoodShape() {
        long center = ChunkGrid.chunkKey(5, -7);

        long[] zero = ChunkGrid.neighborhood(5, -7, 0);
        assertEquals(1, zero.length);
        assertEquals(center, zero[0]);

        long[] one = ChunkGrid.neighborhood(5, -7, 1);
        assertEquals(9, one.length);
        assertEquals(center, one[0], "由近及远排列：第一个必须是中心区块");

        Set<Long> unique = new HashSet<>();
        for (long key : one) {
            assertTrue(unique.add(key), "邻域里出现重复键");
        }
    }

    @Test
    @DisplayName("邻域按距离由近及远：半径 2 的前 9 个正好是半径 1 的集合")
    void neighborhoodIsOrderedByDistance() {
        long[] two = ChunkGrid.neighborhood(0, 0, 2);
        long[] one = ChunkGrid.neighborhood(0, 0, 1);
        assertEquals(25, two.length);

        Set<Long> firstNine = new HashSet<>();
        for (int i = 0; i < 9; i++) {
            firstNine.add(two[i]);
        }
        assertEquals(new HashSet<>(toList(one)), firstNine,
                "排序必须按切比雪夫距离分环，否则回退扫描不能提前结束");
    }

    @Test
    @DisplayName("邻域数量上限生效，且被截断时仍保留中心（最可能命中的区块）")
    void neighborhoodRespectsCap() {
        long[] capped = ChunkGrid.neighborhood(-3, 4, 2, 5);
        assertEquals(5, capped.length);
        assertEquals(ChunkGrid.chunkKey(-3, 4), capped[0]);
    }

    private static java.util.List<Long> toList(long[] values) {
        java.util.List<Long> list = new java.util.ArrayList<>(values.length);
        for (long value : values) {
            list.add(value);
        }
        return list;
    }
}
