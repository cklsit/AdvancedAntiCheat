package com.anticheat.core.check.impl.reach;

import com.anticheat.core.platform.api.entity.ServerEntitySnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命中盒表单测。
 *
 * <p>这里守的是一条**方向性**不变量：非玩家实体的盒子绝不能比默认盒小。
 * 盒子是**从距离里减掉**的，偏小会让距离虚大，而伸手检测是"距离超过阈值就记证据"
 * ——盒子偏小等价于把检测阈值整体调低，是纯粹的误报来源。
 * 有了这条不变量，就不必逐个核对每个实体的真实尺寸是否被写对。</p>
 */
class EntityBoxesTest {

    private static ServerEntitySnapshot entity(String typeName, boolean isPlayer) {
        return new ServerEntitySnapshot(1, typeName, 0, 0, 0, isPlayer, true, -1);
    }

    @Test
    @DisplayName("玩家用精确盒（0.6 × 1.8）")
    void playerUsesExactBox() {
        EntityBoxes.Box box = EntityBoxes.of(entity("PLAYER", true));
        assertEquals(0.3, box.getHalfWidth(), 1e-9);
        assertEquals(1.8, box.getHeight(), 1e-9);
    }

    @Test
    @DisplayName("isPlayer 优先于类型名（某些服务端会改写玩家类型名）")
    void isPlayerFlagWins() {
        EntityBoxes.Box box = EntityBoxes.of(entity("UNKNOWN", true));
        assertEquals(0.3, box.getHalfWidth(), 1e-9);
        assertEquals(1.8, box.getHeight(), 1e-9);
    }

    @Test
    @DisplayName("未列出的实体一律用默认盒，且默认盒明显偏大")
    void unknownTypesFallBackToGenerousDefault() {
        EntityBoxes.Box box = EntityBoxes.of(entity("SOME_FUTURE_MOB", false));
        assertEquals(0.8, box.getHalfWidth(), 1e-9);
        assertEquals(2.2, box.getHeight(), 1e-9);

        // 与玩家盒对照：默认盒在两个轴上都必须更大（方向安全的证据）
        assertTrue(box.getHalfWidth() > EntityBoxes.playerBox().getHalfWidth());
        assertTrue(box.getHeight() > EntityBoxes.playerBox().getHeight());
    }

    @Test
    @DisplayName("不变量：非玩家实体的生效盒子绝不小于默认盒（防止条目把盒子改小）")
    void nonPlayerBoxesAreNeverSmallerThanDefault() {
        EntityBoxes.Box fallback = EntityBoxes.defaultBox();

        for (String type : EntityBoxes.explicitlyListedTypes()) {
            EntityBoxes.Box box = EntityBoxes.ofType(type, false);
            assertTrue(box.getHalfWidth() >= fallback.getHalfWidth() - 1e-9,
                    type + " 的水平半宽 " + box.getHalfWidth() + " 小于默认盒 "
                            + fallback.getHalfWidth() + " —— 会让距离虚大而误报");
            assertTrue(box.getHeight() >= fallback.getHeight() - 1e-9,
                    type + " 的高度 " + box.getHeight() + " 小于默认盒 "
                            + fallback.getHeight() + " —— 会让距离虚大而误报");
        }
    }

    @Test
    @DisplayName("显式条目必须真的在某轴上超过默认盒（否则写进来是负收益）")
    void everyExplicitEntryJustifiesItself() {
        EntityBoxes.Box fallback = EntityBoxes.defaultBox();

        for (String type : EntityBoxes.explicitlyListedTypes()) {
            EntityBoxes.Box raw = EntityBoxes.rawBoxOf(type);
            assertNotNull(raw, type + " 的原始尺寸缺失");

            boolean wider = raw.getHalfWidth() > fallback.getHalfWidth();
            boolean taller = raw.getHeight() > fallback.getHeight();
            assertTrue(wider || taller,
                    type + " 的原始盒 " + raw + " 在两个轴上都不超过默认盒 " + fallback
                            + " —— 它不该出现在表里，默认盒已经更宽容");
        }
    }

    @Test
    @DisplayName("回归守卫：明显超出默认盒的常见实体必须被显式收录")
    void oversizedEntitiesStayListed() {
        // 这些实体的真实盒在某轴上大于默认盒（0.8 / 2.2）。
        // 条目一旦被误删，它们就会退回默认盒而被低估 → 误报。
        for (String type : new String[]{
                "IRON_GOLEM", "ENDERMAN", "WARDEN", "WITHER_SKELETON",
                "WITHER", "ENDER_DRAGON", "RAVAGER", "END_CRYSTAL", "GHAST"
        }) {
            assertTrue(EntityBoxes.explicitlyListedTypes().contains(type),
                    type + " 必须显式收录：它的真实命中盒大于默认盒，"
                            + "退回默认盒会让距离被低估而导致误报");
        }
    }

    @Test
    @DisplayName("高实体：生效盒子的高度必须覆盖其真实高度")
    void tallEntitiesKeepTheirHeight() {
        assertEquals(2.9, EntityBoxes.ofType("ENDERMAN", false).getHeight(), 1e-9);
        assertEquals(4.0, EntityBoxes.ofType("GHAST", false).getHeight(), 1e-9);
        // 宽实体：宽度必须覆盖，且不会被压回默认宽度以下
        assertEquals(1.0, EntityBoxes.ofType("RAVAGER", false).getHalfWidth(), 1e-9);
        // 高而窄（末影人真实半宽 0.3）：生效盒子的宽度被抬到默认盒，方向安全
        assertEquals(0.8, EntityBoxes.ofType("ENDERMAN", false).getHalfWidth(), 1e-9);
    }
}
