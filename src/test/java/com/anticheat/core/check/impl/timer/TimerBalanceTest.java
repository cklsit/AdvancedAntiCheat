package com.anticheat.core.check.impl.timer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Timer 余额法单测 —— 本项目里最需要精确验证的一段算法。
 *
 * <p>为什么它值得单测：这是「计时器加速」检测的全部判据。余额的符号方向、
 * 边界裁剪、还账速率三者只要有一个写反，检测就会退化成
 * **永远不触发**（线上完全静默）或者**疯狂误报**（把卡顿玩家全踢了），
 * 而这两种故障在真机上都不容易复现与定位。</p>
 */
class TimerBalanceTest {

    private static final long MS = 1_000_000L;

    @Test
    @DisplayName("原版节奏（每 50ms 一个包）永远不溢出")
    void vanillaCadenceNeverOverflows() {
        TimerBalance balance = new TimerBalance();
        long now = 0L;

        // 600 个包 = 30 秒
        for (int i = 0; i < 600; i++) {
            now += 50 * MS;
            balance.onMovementPacket(now);
            assertFalse(balance.isOverflowing(),
                    "第 " + i + " 个包就溢出了：余额=" + balance.getBalanceMillis());
            balance.onPass();
        }
        assertEquals(0.0, balance.getBalanceMillis(), 1e-9,
                "严格 50ms 节奏的余额必须停在 0");
    }

    @Test
    @DisplayName("10% 加速会在几十个包内溢出（灵敏度）")
    void acceleratedCadenceOverflows() {
        TimerBalance balance = new TimerBalance();
        long now = 0L;
        int packets = 0;

        for (int i = 0; i < 500; i++) {
            now += 45 * MS; // 45ms = 比原版快 10%
            packets++;
            balance.onMovementPacket(now);
            if (balance.isOverflowing()) {
                break;
            }
            balance.onPass();
        }

        assertTrue(balance.isOverflowing(), "10% 加速必须在 500 个包内被检出");
        // 每包净积累 5ms、通过时扣 0.75ms ≈ 4.25ms；溢出线 100ms -> 约 24 个包
        assertTrue(packets < 60, "检出耗时过长（" + packets + " 个包），灵敏度不足");
        assertTrue(balance.ticksAhead() > 2.0, "溢出时的 tick 领先量应大于 2");
    }

    @Test
    @DisplayName("余额方向：发包慢 -> 负余额（欠账），发包快 -> 正余额")
    void balanceSignFollowsPacketRate() {
        TimerBalance balance = new TimerBalance();
        balance.onMovementPacket(0L);

        // 慢一拍：100ms 才发
        balance.onMovementPacket(100 * MS);
        assertTrue(balance.getBalanceMillis() < 0, "发包慢必须产生负余额");

        TimerBalance fast = new TimerBalance();
        fast.onMovementPacket(0L);
        fast.onMovementPacket(10 * MS);
        assertTrue(fast.getBalanceMillis() > 0, "发包快必须产生正余额");
    }

    @Test
    @DisplayName("单次卡顿被缓冲吸收，不判违规")
    void lagSpikeIsAbsorbedByBuffer() {
        TimerBalance balance = new TimerBalance();
        long now = 0L;
        for (int i = 0; i < 100; i++) {
            now += 50 * MS;
            balance.onMovementPacket(now);
            balance.onPass();
        }

        now += 800 * MS; // 一次 800ms 的网络卡顿
        balance.onMovementPacket(now);

        assertFalse(balance.isOverflowing(), "单次卡顿不能被判成加速");
        assertTrue(balance.getBalanceMillis() < 0, "卡顿应记成欠账");
        assertTrue(balance.getBalanceMillis() >= -balance.getBufferMillis() - 1e-9,
                "欠账不得突破缓冲下界，否则余额会永久卡死在下界");
    }

    @Test
    @DisplayName("余额被裁剪在 [-buffer, +max] 之间")
    void balanceIsClampedToBounds() {
        TimerBalance balance = new TimerBalance();
        balance.setBufferMillis(100.0);
        long now = 0L;

        // 极端加速（每 1ms 一个包）不能把余额顶穿上限
        for (int i = 0; i < 1000; i++) {
            now += MS;
            balance.onMovementPacket(now);
        }
        assertEquals(TimerBalance.DEFAULT_MAX_MS, balance.getBalanceMillis(), 1e-9,
                "余额必须被裁剪到上限，否则它会一直膨胀且无法回落");

        // 极端卡顿不能把余额压穿下界
        TimerBalance stalled = new TimerBalance();
        stalled.setBufferMillis(100.0);
        stalled.onMovementPacket(0L);
        stalled.onMovementPacket(500_000 * MS);
        assertEquals(-100.0, stalled.getBalanceMillis(), 1e-9);
    }

    @Test
    @DisplayName("第一个包只定基准，不产生余额变化")
    void firstPacketOnlyPrimesBaseline() {
        TimerBalance balance = new TimerBalance();
        assertFalse(balance.getPrimed());

        balance.onMovementPacket(999_999_999_999L);
        assertTrue(balance.getPrimed());
        assertEquals(0.0, balance.getBalanceMillis(), 1e-9,
                "首个包没有间隔可比，余额必须保持 0");
    }

    @Test
    @DisplayName("同一纳秒或时钟回退不产生信息，不能被当成异常")
    void nonPositiveDeltaIsIgnored() {
        TimerBalance balance = new TimerBalance();
        balance.onMovementPacket(1_000_000_000L);
        double before = balance.getBalanceMillis();

        balance.onMovementPacket(1_000_000_000L); // 同一纳秒
        assertEquals(before, balance.getBalanceMillis(), 1e-9,
                "同一纳秒的两个包不能当成 +50ms 的加速");

        balance.onMovementPacket(500_000_000L); // 时钟回退
        assertEquals(before, balance.getBalanceMillis(), 1e-9);
    }

    @Test
    @DisplayName("forgive 削掉余额，避免同一次超发被反复计数")
    void forgiveReducesBalance() {
        TimerBalance balance = new TimerBalance();
        balance.onMovementPacket(0L);
        balance.onMovementPacket(10 * MS); // 大幅领先
        double before = balance.getBalanceMillis();

        balance.forgive(15.0);
        assertEquals(before - 15.0, balance.getBalanceMillis(), 1e-9);
    }

    @Test
    @DisplayName("onTeleport 归还一个 tick 的额度")
    void teleportRefundsOneTick() {
        TimerBalance balance = new TimerBalance();
        balance.onMovementPacket(0L);
        balance.onMovementPacket(50 * MS);
        double before = balance.getBalanceMillis();

        balance.onTeleport();
        assertEquals(before - TimerBalance.DEFAULT_EXPECTED_INTERVAL_MS,
                balance.getBalanceMillis(), 1e-9);
    }

    @Test
    @DisplayName("reset 清空余额并让下一个包重新定基准")
    void resetClearsPriming() {
        TimerBalance balance = new TimerBalance();
        balance.onMovementPacket(0L);
        balance.onMovementPacket(1000 * MS);
        assertTrue(balance.getPrimed());

        balance.reset();
        assertFalse(balance.getPrimed());
        assertEquals(0.0, balance.getBalanceMillis(), 1e-9);

        // 重置后的第一个包只定基准：传送/重生后的巨大间隔不该被算成欠账
        balance.onMovementPacket(5000 * MS);
        assertEquals(0.0, balance.getBalanceMillis(), 1e-9);
        assertTrue(balance.getPrimed());
    }

    @Test
    @DisplayName("ticksAhead 告警文案用的领先量恒为正")
    void ticksAheadIsAlwaysReadable() {
        TimerBalance balance = new TimerBalance();
        assertEquals(0.01, balance.ticksAhead(), 1e-9,
                "余额为 0 时也要给出正数，否则告警会显示 '领先 0.00 tick'");
    }

    @Test
    @DisplayName("还账速率决定容差：1.5% 以内的持续加速会被吸收")
    void releaseRateDefinesTolerance() {
        // 每包净积累 = 50 - 期望间隔；还账 0.75ms/包
        // 期望间隔 49.25ms 时净积累为 0 -> 恰好被吸收
        TimerBalance balance = new TimerBalance();
        long now = 0L;
        for (int i = 0; i < 2000; i++) {
            now += 49_250_000L; // 49.25ms
            balance.onMovementPacket(now);
            if (balance.isOverflowing()) {
                break;
            }
            balance.onPass();
        }
        assertFalse(balance.isOverflowing(),
                "1.5% 的加速应当被还账速率吸收（这正是刻意留出的容差），实际余额="
                        + balance.getBalanceMillis());
    }
}
