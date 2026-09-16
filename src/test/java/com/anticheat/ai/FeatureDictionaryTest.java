package com.anticheat.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 特征字典契约测试。
 *
 * <p>48 维特征向量是个人基线 / 孤立森林 / 逻辑回归 / Web 模拟器四方共享的
 * <b>数据契约</b>。任何一个数组长度与 DIMS 不一致，都会导致模型推理时
 * 静默错位（分数照出、但语义全错），因此这里做硬断言。
 */
class FeatureDictionaryTest {

    @Test
    @DisplayName("DIMS / NAMES / DESCRIPTIONS / GROUP_OF 长度必须严格一致")
    void allArraysHaveSameLength() {
        assertEquals(48, FeatureDimensions.DIMS, "特征维度契约是 48");
        assertEquals(FeatureDimensions.DIMS, FeatureDimensions.NAMES.length,
                "NAMES 长度必须等于 DIMS");
        assertEquals(FeatureDimensions.DIMS, FeatureDimensions.DESCRIPTIONS.length,
                "DESCRIPTIONS 长度必须等于 DIMS");
        assertEquals(FeatureDimensions.DIMS, FeatureDimensions.GROUP_OF.length,
                "GROUP_OF 长度必须等于 DIMS");
    }

    @Test
    @DisplayName("维度名唯一且非空")
    void namesAreUniqueAndNotBlank() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < FeatureDimensions.NAMES.length; i++) {
            String n = FeatureDimensions.NAMES[i];
            assertNotNull(n, "维度名不能为 null，index=" + i);
            assertTrue(!n.trim().isEmpty(), "维度名不能为空，index=" + i);
            assertTrue(seen.add(n), "维度名重复：" + n + "（Web 模拟器按名字查维度，重名会串）");
        }
    }

    @Test
    @DisplayName("indexOf 与 NAMES 顺序自洽（改名/插维度会立刻暴露）")
    void indexOfRoundTrip() {
        for (int i = 0; i < FeatureDimensions.NAMES.length; i++) {
            assertEquals(i, FeatureDimensions.indexOf(FeatureDimensions.NAMES[i]),
                    "indexOf 必须返回声明顺序下标：" + FeatureDimensions.NAMES[i]);
        }
        assertTrue(FeatureDimensions.indexOf("__not_a_feature__") < 0,
                "未知维度名必须返回负数而不是 0");
    }

    @Test
    @DisplayName("分组元数据可用：非空且能覆盖全部维度")
    void groupMetadataUsable() {
        for (int i = 0; i < FeatureDimensions.GROUP_OF.length; i++) {
            String g = FeatureDimensions.GROUP_OF[i];
            assertNotNull(g, "分组不能为 null，index=" + i);
            assertTrue(!g.trim().isEmpty(), "分组不能为空，index=" + i);
        }
        // 前端 AILabView「特征字典」Tab 依赖该结构
        List<String[]> desc = FeatureDimensions.describeAll();
        assertEquals(FeatureDimensions.DIMS, desc.size(),
                "describeAll() 必须为每个维度各返回一条");
        for (String[] row : desc) {
            assertTrue(row.length >= 3, "每行至少 (name, group, description)");
        }
    }

    @Test
    @DisplayName("FeatureVector 基本契约")
    void featureVectorContract() {
        double[] values = new double[FeatureDimensions.DIMS];
        values[0] = 1.5;
        FeatureVector v = new FeatureVector(1234L, values);
        assertEquals(1234L, v.getTimestamp());
        assertEquals(FeatureDimensions.DIMS, v.getDim());
        assertEquals(1.5, v.get(0), 1e-12);

        // 必须防御性拷贝：外部改动不能污染向量（1Hz 采样 + 异步计算会跨线程持有）
        values[0] = 99.0;
        assertEquals(1.5, v.get(0), 1e-12, "构造时必须拷贝入参，否则异步线程读到被复用的数组");

        double[] copy = v.copyValues();
        copy[0] = -1.0;
        assertEquals(1.5, v.get(0), 1e-12, "copyValues 必须是拷贝");

        FeatureVector zero = FeatureVector.zero(7L);
        assertEquals(7L, zero.getTimestamp());
        assertEquals(FeatureDimensions.DIMS, zero.getDim());
        for (int i = 0; i < FeatureDimensions.DIMS; i++) {
            assertEquals(0.0, zero.get(i), 1e-12);
        }
    }
}
