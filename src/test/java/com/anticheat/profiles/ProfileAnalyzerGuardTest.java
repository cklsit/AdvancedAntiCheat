package com.anticheat.profiles;

import com.anticheat.profiles.InventoryStateMachine.InventoryTransition;
import com.anticheat.profiles.InventoryStateMachine.TransitionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 画像分析器「样本不足即误判」陷阱的回归锁定。
 *
 * <p>为什么需要这个测试：这四个分析器长期没有任何调用方，判决方法的阈值从未在真机上跑过。
 * 它们的实现是「先算 stdDev，再与阈值比较」，而 {@code calculateXxxStdDev} 在样本不足时
 * 返回 {@code 0.0} 作为缺省值——{@code 0.0 < 阈值} 意味着<b>一个从未挖过方块的新玩家
 * 会被判为自动矿机</b>。背包状态机的 {@code isAutoTotem} 同理：计数只增不减，
 * 玩家手动切满 10 次副手后就永久命中。
 *
 * <p>本文件把这些行为<b>固定为已知事实</b>（而不是修正它），因为当前接线刻意只输出
 * 描述性指标、不调用任何判决方法。一旦后来者想把 {@code is*()} 接进违规链，
 * 这个测试会先于线上误封提醒他：判决前必须先过 {@code hasEnoughData()}。
 *
 * <p>本测试不触碰任何 Bukkit API，可脱离服务端运行。</p>
 */
class ProfileAnalyzerGuardTest {

    private final UUID player = UUID.randomUUID();

    @Test
    @DisplayName("无任何样本时，判决方法一律命中——因此禁止未经 hasEnoughData 守卫就调用")
    void verdictsFireOnEmptyState() {
        MiningPatternAnalyzer mining = new MiningPatternAnalyzer();
        TimerDetection timer = new TimerDetection();
        InventoryStateMachine inventory = new InventoryStateMachine();

        assertFalse(mining.hasEnoughData(player), "无挖掘记录时不该认为样本足够");
        assertFalse(timer.hasEnoughData(player), "无挥手记录时不该认为样本足够");
        assertFalse(inventory.hasEnoughData(player), "无背包操作时不该认为样本足够");

        assertTrue(mining.isAutoMiner(player), "空数据的 stdDev 缺省 0.0 会命中矿机判据");
        assertTrue(timer.isTimerAnomaly(player), "空数据的 stdDev 缺省 0.0 会命中计时异常判据");
        assertFalse(inventory.isAutoTotem(player), "副手计数从 0 起算，尚未达阈值");
    }

    @Test
    @DisplayName("TOTEM_SWAP 计数只增不减，达阈值后即为永久命中")
    void autoTotemCountNeverDecays() {
        InventoryStateMachine inventory = new InventoryStateMachine();

        // 只有 TOTEM_SWAP 会累加 totemSwapCount；SWAP_HANDS 不会。
        // 当前接线因 1.8 无法安全读取副手物品，只回报 SWAP_HANDS，故 isAutoTotem 恒为 false。
        for (int i = 0; i < 10; i++) {
            inventory.recordTransition(player,
                new InventoryTransition(TransitionType.SWAP_HANDS, -1, -1, 1000L + i * 500L, null));
        }
        assertFalse(inventory.isAutoTotem(player), "副手切换不计入图腾计数");

        for (int i = 0; i < 10; i++) {
            inventory.recordTransition(player,
                new InventoryTransition(TransitionType.TOTEM_SWAP, -1, -1, 5000L + i * 500L, "TOTEM_OF_UNDYING"));
        }

        assertTrue(inventory.isAutoTotem(player));
        // 此后无论是否继续操作，判决都会一直返回 true —— 正是不能接进违规链的原因。
        assertTrue(inventory.isAutoTotem(player));
    }

    @Test
    @DisplayName("攒够样本后，恒定节奏与真人抖动可被归一化标准差区分")
    void constantRhythmSeparatesFromHumanJitter() {
        MiningPatternAnalyzer robot = new MiningPatternAnalyzer();
        for (int i = 0; i < 30; i++) {
            robot.recordBreakTime(player, 1500L, "STONE");
        }
        assertTrue(robot.hasEnoughData(player));
        assertEquals(0.0, robot.getNormalizedStdDev(player), 1e-9, "完全等长的破坏耗时应无离散");
        assertTrue(robot.isAutoMiner(player), "有足够样本时，恒定节奏命中矿机判据是符合预期的");

        MiningPatternAnalyzer human = new MiningPatternAnalyzer();
        UUID humanUuid = UUID.randomUUID();
        for (int i = 0; i < 30; i++) {
            human.recordBreakTime(humanUuid, i % 2 == 0 ? 900L : 2100L, "STONE");
        }
        assertTrue(human.getNormalizedStdDev(humanUuid) > 0.1, "交替耗时的归一化标准差应远超阈值");
        assertFalse(human.isAutoMiner(humanUuid), "真人抖动不该被判为矿机");
    }

    @Test
    @DisplayName("状态机按转移类型累计分布，供画像摘要读取")
    void transitionDistributionCountsByType() {
        InventoryStateMachine inventory = new InventoryStateMachine();
        for (int i = 0; i < 20; i++) {
            inventory.recordTransition(player, new InventoryTransition(
                TransitionType.CLICK_SLOT, i, i + 1, 2000L + i * 100L, "STONE"));
        }
        inventory.recordTransition(player, new InventoryTransition(
            TransitionType.CLOSE_CONTAINER, -1, -1, 9000L, null));

        assertEquals(20, inventory.getTransitionDistribution(player).get(TransitionType.CLICK_SLOT).intValue());
        assertEquals(1, inventory.getTransitionDistribution(player).get(TransitionType.CLOSE_CONTAINER).intValue());
        assertEquals(21, inventory.getTransitionCount(player));
        assertTrue(inventory.hasEnoughData(player));
    }
}
