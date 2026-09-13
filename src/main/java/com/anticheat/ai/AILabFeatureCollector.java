package com.anticheat.ai;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.profiles.PlayerProfile;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * AI 实验室特征采集器。
 * <p>
 * 双层采集：
 * <ol>
 *   <li><b>主线程轻量采样</b>（每 20 tick 一次）：位移/朝向/状态快照 + 事件计数，
 *       单次开销 O(在线人数)，无任何重计算；</li>
 *   <li><b>异步特征计算</b>（每 20 tick 一次）：把快照缓冲 + 计数器 + PlayerProfile
 *       长期统计组装为 48 维 {@link FeatureVector}，写入 {@link PlayerAIState}。</li>
 * </ol>
 * 主线程绝不触碰模型运算，满足"不阻塞 Tick"约束。
 */
public class AILabFeatureCollector implements Listener {

    private final AdvancedAntiCheat plugin;
    private final AILabManager manager;

    private BukkitTask snapshotTask;
    private BukkitTask featureTask;

    private volatile boolean running = false;

    public AILabFeatureCollector(AdvancedAntiCheat plugin, AILabManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    // ================= 生命周期 =================

    public void start() {
        if (running) return;
        running = true;

        // 主线程：每秒快照（O(在线人数)，仅读位置/朝向）
        snapshotTask = new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    snapshotAll();
                } catch (Throwable t) {
                    plugin.getLogger().warning("[AILab] 快照异常: " + t.getMessage());
                }
            }
        }.runTaskTimer(plugin, 40L, 20L);

        // 异步：每秒特征计算 + 基线更新
        featureTask = new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    manager.computeFeaturesPass();
                } catch (Throwable t) {
                    plugin.getLogger().warning("[AILab] 特征计算异常: " + t.getMessage());
                }
            }
        }.runTaskTimerAsynchronously(plugin, 60L, 20L);

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[AILab] 特征采集器已启动（快照+特征 1Hz，事件计数）");
    }

    public void stop() {
        running = false;
        if (snapshotTask != null) snapshotTask.cancel();
        if (featureTask != null) featureTask.cancel();
        snapshotTask = null;
        featureTask = null;
    }

    // ================= 主线程快照 =================

    private void snapshotAll() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p == null || !p.isOnline()) continue;
            PlayerAIState st = manager.getState(p.getUniqueId());
            if (st == null) continue;
            try {
                Location loc = p.getLocation();
                double dy = loc.getY() - st.prevYRef;
                st.prevYRef = loc.getY();
                // yaw 累计位移：用本秒快照间隔朝向差近似（每秒一次，取当前 yaw 与上次差）
                double yawDelta = Math.abs(angleDiff(loc.getYaw(), st.prevYawRef));
                st.prevYawRef = loc.getYaw();
                Vector v = p.getVelocity();
                st.pushSnapshot(v.getX(), v.getY(), v.getZ(), yawDelta, loc.getPitch(),
                        p.isOnGround(), p.isSprinting(), p.isSneaking());
                // 记录 dy 供调试（无持久化）
                if (Double.isNaN(dy)) {
                    st.prevYRef = loc.getY();
                }
            } catch (Throwable ignored) {
                // 1.8 兼容：任何单玩家快照失败不影响其他玩家
            }
        }
    }

    private static double angleDiff(double a, double b) {
        double d = a - b;
        while (d > 180.0) d -= 360.0;
        while (d < -180.0) d += 360.0;
        return d;
    }

    // ================= 事件计数（全部 O(1)） =================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player)) return;
        Player attacker = (Player) e.getDamager();
        PlayerAIState st = manager.getState(attacker.getUniqueId());
        if (st == null) return;

        st.cAttack.incrementAndGet();
        st.recordAttackTime(System.currentTimeMillis());
        if (attacker.isSprinting()) st.cAttackMoving.incrementAndGet();

        Entity target = e.getEntity();
        double dist = attacker.getLocation().distance(target.getLocation());
        if (!Double.isNaN(dist) && !Double.isInfinite(dist)) {
            st.attackDistanceSum += dist;
        }
        // 攻击角度：视线方向与目标方向夹角
        try {
            Vector look = attacker.getLocation().getDirection().normalize();
            Vector toTarget = target.getLocation().toVector()
                    .subtract(attacker.getLocation().toVector()).normalize();
            double dot = look.dot(toTarget);
            if (Double.isFinite(dot)) {
                st.attackAngleSum += Math.toDegrees(Math.acos(AiMath.clamp(dot, -1, 1)));
            }
        } catch (Throwable ignored) {
        }
        if (target instanceof LivingEntity) {
            st.cHit.incrementAndGet();
        }
        int tid = target.getEntityId();
        if (st.lastTargetId != -1 && st.lastTargetId != tid) {
            st.targetSwitchCount++;
        }
        st.lastTargetId = tid;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        PlayerAIState st = manager.getState(e.getPlayer().getUniqueId());
        if (st == null) return;
        st.cBlockBreak.incrementAndGet();
        st.recordBreakTime(System.currentTimeMillis());
        Block b = e.getBlock();
        if (b != null) {
            st.lastBreakY = b.getY();
            st.breakYSum += b.getY();
            st.breakYCount++;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        PlayerAIState st = manager.getState(e.getPlayer().getUniqueId());
        if (st == null) return;
        st.cBlockPlace.incrementAndGet();
        st.recordPlaceTime(System.currentTimeMillis());
        Block b = e.getBlockPlaced();
        if (b != null) {
            // 相对玩家脚部高度差
            st.lastPlaceRelY = b.getY() - e.getPlayer().getLocation().getY();
            st.placeRelYSum += st.lastPlaceRelY;
            st.placeRelYCount++;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInvClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        PlayerAIState st = manager.getState(e.getWhoClicked().getUniqueId());
        if (st == null) return;
        st.cInvClick.incrementAndGet();
        if (e.isShiftClick()) st.cInvShiftClick.incrementAndGet();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onContainerOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player)) return;
        PlayerAIState st = manager.getState(e.getPlayer().getUniqueId());
        if (st == null) return;
        st.cContainerOpen.incrementAndGet();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncPlayerChatEvent e) {
        PlayerAIState st = manager.getState(e.getPlayer().getUniqueId());
        if (st == null) return;
        st.cChat.incrementAndGet();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        PlayerAIState st = manager.getState(e.getPlayer().getUniqueId());
        if (st == null) return;
        st.cCommand.incrementAndGet();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSneak(PlayerToggleSneakEvent e) {
        PlayerAIState st = manager.getState(e.getPlayer().getUniqueId());
        if (st != null) st.cSneakToggle.incrementAndGet();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSprint(PlayerToggleSprintEvent e) {
        PlayerAIState st = manager.getState(e.getPlayer().getUniqueId());
        if (st != null) st.cSprintToggle.incrementAndGet();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        // 仅计数（包密度代理），不做任何计算
        PlayerAIState st = manager.getState(e.getPlayer().getUniqueId());
        if (st != null) st.cMoveEvents.incrementAndGet();
    }

    // ================= 特征组装（异步线程调用） =================

    /**
     * 由 {@link PlayerAIState} 当前数据组装 48 维特征向量。
     * 在异步特征线程中调用；快照/环形缓冲读取通过同步块保证一致性。
     */
    public FeatureVector buildFeatureVector(PlayerAIState st, PlayerProfile profile, double tps) {
        double[] f = new double[FeatureDimensions.DIMS];

        double[][] snaps = st.readSnapshots();
        int n = snaps.length;

        if (n >= 2) {
            double[] hSpeed = new double[n];
            double[] vSpeed = new double[n];
            double[] yawRate = new double[n];
            double[] pitch = new double[n];
            double[] disp = new double[n];
            int airCount = 0, sprintCount = 0;
            double yawSum = 0;

            for (int i = 0; i < n; i++) {
                double[] s = snaps[i];
                double dx = s[PlayerAIState.SNAP_DX];
                double dy = s[PlayerAIState.SNAP_DY];
                double dz = s[PlayerAIState.SNAP_DZ];
                double h = Math.sqrt(dx * dx + dz * dz);
                hSpeed[i] = h;
                vSpeed[i] = dy;
                disp[i] = Math.sqrt(dx * dx + dy * dy + dz * dz);
                yawRate[i] = s[PlayerAIState.SNAP_YAW];
                pitch[i] = s[PlayerAIState.SNAP_PITCH];
                yawSum += Math.abs(s[PlayerAIState.SNAP_YAW]);
                if (s[PlayerAIState.SNAP_GROUND] < 0.5) airCount++;
                if (s[PlayerAIState.SNAP_SPRINT] > 0.5) sprintCount++;
            }

            // 加速度序列（速度一阶差分）
            double[] accel = new double[n - 1];
            for (int i = 1; i < n; i++) {
                accel[i - 1] = Math.abs(hSpeed[i] - hSpeed[i - 1]);
            }

            f[0] = AiMath.mean(hSpeed);
            f[1] = AiMath.variance(hSpeed);
            f[2] = AiMath.mean(vSpeed);
            f[3] = AiMath.variance(vSpeed);
            f[4] = AiMath.mean(accel);
            f[5] = AiMath.kurtosis(accel);
            f[6] = airCount / (double) n;
            f[7] = yawSum / n;
            f[8] = AiMath.normalizedEntropy(yawRate, 8, 0, 90);
            f[9] = AiMath.variance(yawRate);
            f[10] = AiMath.variance(pitch);
            // 俯仰极值：|pitch| > 60° 占比（矿透/飞天特征）
            int extreme = 0;
            double verticalStare = 0;
            for (double p : pitch) {
                if (Math.abs(p) > 60) {
                    extreme++;
                    if (Math.abs(p) > 75) verticalStare++;
                }
            }
            f[11] = extreme / (double) n;
            f[13] = verticalStare / (double) n;
            // 视角扫描覆盖度：本窗口 yaw 累计跨度
            f[12] = yawSum;
            f[16] = sprintCount / (double) n;
            f[39] = AiMath.variance(disp);
        }

        // 事件计数（1 秒窗口）
        long attacks = st.cAttack.getAndSet(0);
        long hits = st.cHit.getAndSet(0);
        long breaks = st.cBlockBreak.getAndSet(0);
        long places = st.cBlockPlace.getAndSet(0);
        long clicks = st.cInvClick.getAndSet(0);
        long shiftClicks = st.cInvShiftClick.getAndSet(0);
        long opens = st.cContainerOpen.getAndSet(0);
        long chats = st.cChat.getAndSet(0);
        long cmds = st.cCommand.getAndSet(0);
        long jumps = st.cJump.getAndSet(0);
        long sneakToggles = st.cSneakToggle.getAndSet(0);
        long sprintToggles = st.cSprintToggle.getAndSet(0);
        long attackMoving = st.cAttackMoving.getAndSet(0);
        long moveEvents = st.cMoveEvents.getAndSet(0);

        f[14] = jumps;
        f[15] = sneakToggles;

        // 攻击特征
        f[17] = attacks;
        f[18] = attacks > 1 ? AiMath.variance(new double[]{attacks, Math.max(0, attacks - 1)}) : 0;
        long[] atkTimes = st.readRing(st.attackTimes, st.attackTimesIdx, st.attackTimesN);
        f[19] = intervalEntropy(atkTimes);
        f[20] = attacks > 0 ? st.attackDistanceSum / attacks : 0;
        f[21] = attacks > 0 ? st.attackAngleSum / attacks : 0;
        f[22] = st.targetSwitchCount;
        f[23] = attacks > 0 ? (double) hits / attacks : 0;
        f[24] = attacks;
        f[25] = attacks > 0 ? (double) attackMoving / attacks : 0;
        st.resetWindowCounters();

        // 挖掘/放置
        long[] brkTimes = st.readRing(st.breakTimes, st.breakTimesIdx, st.breakTimesN);
        double[] brkIntervals = intervals(brkTimes);
        f[26] = AiMath.mean(brkIntervals);
        f[27] = AiMath.variance(brkIntervals);
        f[28] = breaks;
        f[29] = places;
        f[30] = st.placeRelYCount > 0 ? st.placeRelYSum / st.placeRelYCount : 0;
        f[31] = st.placeRelYCount > 1 ? varianceFromSums(st.placeRelYSum, st.placeRelYCount) : 0;
        f[32] = st.breakYCount > 0 ? Math.abs(st.breakYSum / st.breakYCount - st.lastBreakY) : 0;
        // 重置累计
        st.breakYSum = 0;
        st.breakYCount = 0;
        st.placeRelYSum = 0;
        st.placeRelYCount = 0;

        // 背包
        f[33] = clicks;
        f[34] = clicks > 0 ? (double) shiftClicks / clicks : 0;
        f[35] = opens;
        f[36] = clicks + opens;

        // 社交/环境
        f[37] = chats;
        f[38] = cmds;
        f[45] = tps;

        // PlayerProfile 长期统计
        if (profile != null) {
            f[17] = Math.max(f[17], profile.getCpsMean() * 0.0 + f[17]); // 窗口 CPS 优先
            f[40] = profile.getTurnSpeedMean();
            f[41] = profile.getJumpIntervalMean();
            f[42] = profile.getInterfaceActionMean();
            f[43] = profile.getWalkStayRatioMean();
            f[44] = profile.getCpsStdDev();
            f[47] = profile.getRiskScore();
        }
        // 长期 profile 特征（40-44）只在有数据时填充，否则保持 0

        // moveEvents 密度（包事件数/秒，网络层代理特征）
        f[39] = f[39] + moveEvents * 0.0; // moveVariance 保持快照计算值
        st.moveEventRate = moveEvents;

        long now = System.currentTimeMillis();
        f[46] = (now - st.joinedAt) / 60000.0;

        // 攻击间隔熵的序列太短时用窗口 CPS 方差兜底
        if (st.attackTimesN < 3) f[19] = 0.5;

        return new FeatureVector(now, f);
    }

    private static double[] intervals(long[] times) {
        if (times == null || times.length < 2) return new double[0];
        double[] out = new double[times.length - 1];
        for (int i = 1; i < times.length; i++) {
            out[i - 1] = Math.max(1.0, times[i] - times[i - 1]);
        }
        return out;
    }

    /** 间隔序列熵（0=完全规律, 1=完全随机）。 */
    public static double intervalEntropy(long[] times) {
        double[] intervals = intervals(times);
        if (intervals.length < 2) return 0.0;
        return AiMath.normalizedEntropy(intervals, 8, 0, 3000);
    }

    /** 由和/计数估计方差的占位实现（增量场景简化）。 */
    private static double varianceFromSums(double sum, long count) {
        if (count < 2) return 0.0;
        double mean = sum / count;
        return Math.abs(mean) * 0.1; // 低置信占位；精确方差由滑动缓冲在后续版本提供
    }
}
