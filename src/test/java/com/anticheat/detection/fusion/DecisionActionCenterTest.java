package com.anticheat.detection.fusion;

import com.anticheat.detection.fusion.DecisionActionCenter.ActionLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 融合决策中心的档位判定与处罚动作门控契约。
 *
 * <p>为什么需要这个测试：{@code AdvancedDetectionManager.makeDecision} 以 10Hz（每 100ms）
 * 调用一次 {@code executeAction}，而四个处罚动作（增强监控 / 验证码 / 临时封禁 / 永久封禁）
 * 全是"重"操作——验证码会传送玩家并清空背包、封禁会踢人写库、取证会向回放缓冲追加条目。
 *
 * <p>这里锁住两类不变量：
 * <ol>
 *   <li>同一档位在冷却窗口内只能真正执行一次（否则接入实现后会变成每秒 10 次封禁）；</li>
 *   <li>"连续验证码"次数只能由真实发起点累加，不能被 10Hz 检查循环放大。</li>
 * </ol>
 *
 * <p>本测试不触碰任何 Bukkit API，可脱离服务端运行。</p>
 */
class DecisionActionCenterTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    // ==================== 档位判定 ====================

    @Test
    @DisplayName("RCP 到动作档位的映射边界")
    void actionLevelMapping() {
        assertEquals(ActionLevel.NORMAL, ActionLevel.fromRCP(0.0));
        assertEquals(ActionLevel.NORMAL, ActionLevel.fromRCP(0.4999));
        assertEquals(ActionLevel.MONITOR, ActionLevel.fromRCP(0.5));
        assertEquals(ActionLevel.MONITOR, ActionLevel.fromRCP(0.7499));
        assertEquals(ActionLevel.CAPTCHA, ActionLevel.fromRCP(0.75));
        assertEquals(ActionLevel.CAPTCHA, ActionLevel.fromRCP(0.9499));
        assertEquals(ActionLevel.TEMP_BAN, ActionLevel.fromRCP(0.95));
        assertEquals(ActionLevel.TEMP_BAN, ActionLevel.fromRCP(0.9949));
        assertEquals(ActionLevel.PERM_BAN, ActionLevel.fromRCP(0.995));
        assertEquals(ActionLevel.PERM_BAN, ActionLevel.fromRCP(1.0));
    }

    @Test
    @DisplayName("decide 拒绝非法 RCP")
    void decideRejectsInvalidRcp() {
        DecisionActionCenter center = new DecisionActionCenter();
        assertThrows(IllegalArgumentException.class, () -> center.decide(PLAYER, -0.01));
        assertThrows(IllegalArgumentException.class, () -> center.decide(PLAYER, 1.01));
    }

    @Test
    @DisplayName("shouldTakeAction 只在非 NORMAL 档为真")
    void shouldTakeActionOnlyAboveNormal() {
        DecisionActionCenter center = new DecisionActionCenter();
        assertFalse(center.shouldTakeAction(PLAYER, 0.3));
        assertTrue(center.shouldTakeAction(PLAYER, 0.6));
        assertTrue(center.shouldTakeAction(PLAYER, 0.9));
    }

    // ==================== 处罚动作门控 ====================

    @Test
    @DisplayName("同一档位在冷却窗口内只放行一次")
    void sameLevelIsThrottledWithinCooldown() {
        DecisionActionCenter center = new DecisionActionCenter();
        long now = 1_000_000L;
        long cooldown = DecisionActionCenter.actionCooldownMs(ActionLevel.MONITOR);
        assertTrue(cooldown > 0, "监控档必须有正冷却");

        assertTrue(center.acquireActionSlot(PLAYER, ActionLevel.MONITOR, now), "首次应放行");
        assertFalse(center.acquireActionSlot(PLAYER, ActionLevel.MONITOR, now + 1), "1ms 后应拦下");
        assertFalse(center.acquireActionSlot(PLAYER, ActionLevel.MONITOR, now + cooldown - 1),
                "冷却未到点应拦下");
        assertTrue(center.acquireActionSlot(PLAYER, ActionLevel.MONITOR, now + cooldown),
                "冷却到点应放行");
    }

    @Test
    @DisplayName("不同档位各自独立，档位升级立即生效")
    void levelsAreIndependent() {
        DecisionActionCenter center = new DecisionActionCenter();
        long now = 2_000_000L;

        assertTrue(center.acquireActionSlot(PLAYER, ActionLevel.MONITOR, now));
        // 同一次检查中升级到验证码：不能被监控档的冷却挡住
        assertTrue(center.acquireActionSlot(PLAYER, ActionLevel.CAPTCHA, now),
                "档位升级必须立即执行处罚动作");
        assertTrue(center.acquireActionSlot(PLAYER, ActionLevel.TEMP_BAN, now));
        assertTrue(center.acquireActionSlot(PLAYER, ActionLevel.PERM_BAN, now));
    }

    @Test
    @DisplayName("不同玩家之间互不影响")
    void playersAreIndependent() {
        DecisionActionCenter center = new DecisionActionCenter();
        long now = 3_000_000L;

        assertTrue(center.acquireActionSlot(PLAYER, ActionLevel.TEMP_BAN, now));
        assertTrue(center.acquireActionSlot(OTHER, ActionLevel.TEMP_BAN, now),
                "另一名玩家不应被第一名玩家的冷却影响");
    }

    @Test
    @DisplayName("永久封禁对同一玩家一生只执行一次")
    void permBanHappensAtMostOnce() {
        DecisionActionCenter center = new DecisionActionCenter();

        assertTrue(center.acquireActionSlot(PLAYER, ActionLevel.PERM_BAN, 0L));
        assertFalse(center.acquireActionSlot(PLAYER, ActionLevel.PERM_BAN, Long.MAX_VALUE / 4),
                "永久封禁不得因时间推移而重复执行");
    }

    @Test
    @DisplayName("档位越重，动作冷却越长")
    void heavierLevelsHaveLongerCooldown() {
        long monitor = DecisionActionCenter.actionCooldownMs(ActionLevel.MONITOR);
        long captcha = DecisionActionCenter.actionCooldownMs(ActionLevel.CAPTCHA);
        long tempBan = DecisionActionCenter.actionCooldownMs(ActionLevel.TEMP_BAN);
        long permBan = DecisionActionCenter.actionCooldownMs(ActionLevel.PERM_BAN);

        assertTrue(monitor < captcha, "验证码冷却应长于监控");
        assertTrue(captcha < tempBan, "临时封禁冷却应长于验证码");
        assertEquals(Long.MAX_VALUE, permBan, "永久封禁冷却应为无限（只执行一次）");
    }

    @Test
    @DisplayName("回归：10Hz 检查循环下，验证码在 120 秒内只被真正发起一次")
    void captchaIsNotAmplifiedByFastCheckLoop() {
        DecisionActionCenter center = new DecisionActionCenter();
        long t0 = 5_000_000L;
        long cooldown = DecisionActionCenter.actionCooldownMs(ActionLevel.CAPTCHA);

        int issued = 0;
        // 模拟 cooldown 时长内每 100ms 一次的检查循环
        int ticks = (int) (cooldown / 100L);
        for (int i = 0; i < ticks; i++) {
            if (center.acquireActionSlot(PLAYER, ActionLevel.CAPTCHA, t0 + i * 100L)) {
                issued++;
            }
        }
        assertEquals(1, issued,
                "1200 次检查只应发起 1 次验证码；无门控时会被放大成 " + ticks + " 次");
    }

    // ==================== 连续次数语义 ====================

    @Test
    @DisplayName("连续 3 次验证码仍未纠正行为 → 升级为临时封禁")
    void consecutiveCaptchaEscalatesToTempBan() {
        DecisionActionCenter center = new DecisionActionCenter();
        double captchaRcp = 0.80; // 落在 CAPTCHA 档 [0.75, 0.95)

        assertEquals(ActionLevel.CAPTCHA, center.decide(PLAYER, captchaRcp));
        center.noteCaptchaIssued(PLAYER);

        assertEquals(ActionLevel.CAPTCHA, center.decide(PLAYER, captchaRcp));
        center.noteCaptchaIssued(PLAYER);

        assertEquals(ActionLevel.CAPTCHA, center.decide(PLAYER, captchaRcp));
        center.noteCaptchaIssued(PLAYER);

        assertEquals(ActionLevel.TEMP_BAN, center.decide(PLAYER, captchaRcp),
                "第 3 次验证码之后应升级为临时封禁");
    }

    @Test
    @DisplayName("未达阈值时不升级：计数为 2 仍判验证码")
    void belowThresholdStaysCaptcha() {
        DecisionActionCenter center = new DecisionActionCenter();
        center.noteCaptchaIssued(PLAYER);
        center.noteCaptchaIssued(PLAYER);

        assertEquals(2, center.getConsecutiveActionCount(PLAYER));
        assertEquals(ActionLevel.CAPTCHA, center.decide(PLAYER, 0.80));
    }

    @Test
    @DisplayName("resetConsecutiveActions 清零后不再升级")
    void resetClearsEscalation() {
        DecisionActionCenter center = new DecisionActionCenter();
        center.noteCaptchaIssued(PLAYER);
        center.noteCaptchaIssued(PLAYER);
        center.noteCaptchaIssued(PLAYER);
        assertEquals(ActionLevel.TEMP_BAN, center.decide(PLAYER, 0.80));

        center.resetConsecutiveActions(PLAYER);
        assertEquals(0, center.getConsecutiveActionCount(PLAYER));
        assertEquals(ActionLevel.CAPTCHA, center.decide(PLAYER, 0.80));
    }

    @Test
    @DisplayName("clearPlayerAction 清空运行状态（但保留处罚门控以防重复封禁）")
    void clearPlayerActionResetsRuntimeState() {
        DecisionActionCenter center = new DecisionActionCenter();
        long now = 4_000_000L;

        center.decide(PLAYER, 0.80);
        center.noteCaptchaIssued(PLAYER);
        center.acquireActionSlot(PLAYER, ActionLevel.PERM_BAN, now);

        center.clearPlayerAction(PLAYER);

        assertNull(center.getCurrentAction(PLAYER), "当前档位应被清空");
        assertEquals(0, center.getConsecutiveActionCount(PLAYER), "连续次数应被清空");
        assertEquals(0, center.getLatestRCP(PLAYER), 1e-9, "历史 RCP 应被清空");
        assertFalse(center.acquireActionSlot(PLAYER, ActionLevel.PERM_BAN, now + 1),
                "永久封禁门控必须跨 clearPlayerAction 保持，否则玩家重登即可被重复封禁");
    }

    @Test
    @DisplayName("无参构造（无插件实例）可用：所有状态操作不依赖 Bukkit")
    void worksWithoutPluginInstance() {
        DecisionActionCenter center = new DecisionActionCenter();
        assertEquals(ActionLevel.MONITOR, center.decide(PLAYER, 0.60));
        assertEquals(ActionLevel.MONITOR, center.getCurrentAction(PLAYER));
        assertEquals(0.60, center.getLatestRCP(PLAYER), 1e-9);
        assertTrue(center.getTimeSinceLastAction(PLAYER) >= 0);
    }
}
