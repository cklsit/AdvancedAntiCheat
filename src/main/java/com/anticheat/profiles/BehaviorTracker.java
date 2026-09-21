package com.anticheat.profiles;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class BehaviorTracker {

    private final AdvancedAntiCheat plugin;
    private final Map<UUID, PlayerBehaviorData> playerData;
    private final Map<UUID, PlayerProfile> profiles;

    /**
     * 长程行为分析器。四者都是按 UUID 索引的有状态分析器（非玩家内嵌结构），
     * 因此由本类持有单实例，随玩家上下线填充 / 清理。
     *
     * <p><b>只作为画像上下文输出描述性指标，不参与违规判定。</b>
     * 这些类的 {@code isAimbot()} / {@code isTimerAnomaly()} / {@code isAutoMiner()}
     * 阈值从未在真机上标定过，且样本不足时会朝「命中」方向失效
     * （如 stdDev 缺省 0.0 < 0.1 即被判为机器人）。接入核心层的判决链会直接产生误封。
     */
    private final AimAnalysis aimAnalysis = new AimAnalysis();
    private final TimerDetection timerDetection = new TimerDetection();
    private final MiningPatternAnalyzer miningPatternAnalyzer = new MiningPatternAnalyzer();
    private final InventoryStateMachine inventoryStateMachine = new InventoryStateMachine();

    private static final long CPS_WINDOW_MS = 1000;
    private static final long WALK_STAY_CHECK_INTERVAL = 60000;
    private static final long MOVE_CHECK_INTERVAL = 100;
    private static final int MAX_CLICK_BUFFER = 50;

    public BehaviorTracker(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.playerData = new ConcurrentHashMap<>();
        this.profiles = new ConcurrentHashMap<>();
    }

    public void onPlayerJoin(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerProfile profile = plugin.getDatabaseManager().loadPlayerProfile(uuid);
        if (profile == null) {
            profile = new PlayerProfile(uuid, player.getName());
        }
        profiles.put(uuid, profile);
        playerData.put(uuid, new PlayerBehaviorData());
    }

    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        saveProfile(uuid);
        playerData.remove(uuid);
        profiles.remove(uuid);
        aimAnalysis.clearPlayerData(uuid);
        timerDetection.clearPlayerData(uuid);
        miningPatternAnalyzer.clearPlayerData(uuid);
        inventoryStateMachine.clearPlayerData(uuid);
    }

    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!event.getAction().name().contains("LEFT")) return;
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        PlayerBehaviorData data = playerData.get(uuid);
        if (data == null) return;

        long now = System.currentTimeMillis();
        data.clickTimestamps.add(now);
        
        while (data.clickTimestamps.size() > MAX_CLICK_BUFFER) {
            data.clickTimestamps.poll();
        }

        if (now - data.lastCPSUpdate > 500) {
            double cps = calculateCPS(data);
            if (cps > 0) {
                PlayerProfile profile = profiles.get(uuid);
                if (profile != null) {
                    profile.updateCPS(cps);
                }
            }
            data.lastCPSUpdate = now;
        }

        data.interfaceActionsThisMinute.incrementAndGet();
    }

    public void onPlayerAnimation(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        PlayerBehaviorData data = playerData.get(uuid);
        if (data == null) return;

        long now = System.currentTimeMillis();
        timerDetection.recordAction(uuid, now);

        long lastSwing = data.lastArmSwing.get();
        if (lastSwing > 0) {
            double interval = (now - lastSwing) / 1000.0;
            if (interval > 0.1 && interval < 10) {
                data.recentJumpInterval = (data.recentJumpInterval * 0.7 + interval * 0.3);
                
                if (now - data.lastJumpUpdate > 1000) {
                    PlayerProfile profile = profiles.get(uuid);
                    if (profile != null) {
                        profile.updateJumpInterval(data.recentJumpInterval);
                    }
                    data.lastJumpUpdate = now;
                }
            }
        }
        data.lastArmSwing.set(now);
    }

    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.isCancelled()) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (event.getFrom().getX() == event.getTo().getX() &&
            event.getFrom().getZ() == event.getTo().getZ()) {
            return;
        }

        PlayerBehaviorData data = playerData.get(uuid);
        if (data == null) return;

        long now = System.currentTimeMillis();
        if (now - data.lastMoveCheck.get() < MOVE_CHECK_INTERVAL) {
            return;
        }
        data.lastMoveCheck.set(now);

        data.walkTimeThisPeriod.incrementAndGet();

        float yawDiff = Math.abs(event.getFrom().getYaw() - event.getTo().getYaw());
        if (yawDiff > 180) yawDiff = 360 - yawDiff;

        float pitchDiff = Math.abs(event.getFrom().getPitch() - event.getTo().getPitch());

        // 含 0 增量一起记：冻结朝向本身就是自瞄的特征之一。
        // 采样受 MOVE_CHECK_INTERVAL 节流，且方法开头的位移早退使「原地转身」不入样。
        aimAnalysis.recordLook(player,
            event.getFrom().getYaw(), event.getTo().getYaw(),
            event.getFrom().getPitch(), event.getTo().getPitch(), now);

        if (yawDiff > 0.5 || pitchDiff > 0.5) {
            double turnSpeed = Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);
            data.recentTurnSpeed = (data.recentTurnSpeed * 0.8 + turnSpeed * 0.2);
            
            if (now - data.lastTurnUpdate > 500) {
                PlayerProfile profile = profiles.get(uuid);
                if (profile != null) {
                    profile.updateTurnSpeed(data.recentTurnSpeed);
                }
                data.lastTurnUpdate = now;
            }
        }
    }

    public void checkWalkStayRatio(UUID uuid) {
        PlayerBehaviorData data = playerData.get(uuid);
        if (data == null) return;

        long now = System.currentTimeMillis();
        long lastCheck = data.lastWalkStayCheck.get();
        if (now - lastCheck < WALK_STAY_CHECK_INTERVAL) return;

        if (!data.lastWalkStayCheck.compareAndSet(lastCheck, now)) {
            return;
        }

        int walkTime = data.walkTimeThisPeriod.getAndSet(0);
        int totalSeconds = (int) ((now - lastCheck) / 1000);
        int stayTime = totalSeconds - walkTime;
        if (stayTime < 0) stayTime = 0;

        int total = walkTime + stayTime;
        if (total > 0) {
            double ratio = (double) walkTime / total;
            PlayerProfile profile = profiles.get(uuid);
            if (profile != null) {
                profile.updateWalkStayRatio(ratio);
            }
        }
    }

    public void checkInterfaceActions(UUID uuid) {
        PlayerBehaviorData data = playerData.get(uuid);
        if (data == null) return;

        long now = System.currentTimeMillis();
        long lastCheck = data.lastInterfaceCheck.get();
        if (now - lastCheck < 60000) return;

        if (!data.lastInterfaceCheck.compareAndSet(lastCheck, now)) {
            return;
        }

        int actions = data.interfaceActionsThisMinute.getAndSet(0);
        double actionsPerMinute = actions;

        PlayerProfile profile = profiles.get(uuid);
        if (profile != null) {
            profile.updateInterfaceAction(actionsPerMinute);
        }
    }

    private double calculateCPS(PlayerBehaviorData data) {
        long now = System.currentTimeMillis();
        long cutoff = now - CPS_WINDOW_MS;

        // null-safe：peek() 与 isEmpty() 之间存在竞态（异步检查线程与主线程事件处理器并发），
        // 必须用 peek() != null 守卫，避免 auto-unbox null 导致 NPE。
        Long ts;
        while ((ts = data.clickTimestamps.peek()) != null && ts < cutoff) {
            data.clickTimestamps.poll();
        }

        int clicks = data.clickTimestamps.size();
        if (clicks == 0) return 0;

        return clicks * (1000.0 / CPS_WINDOW_MS);
    }

    public PlayerProfile getProfile(UUID uuid) {
        return profiles.get(uuid);
    }

    // ---------------- 长程画像分析器 ----------------

    /**
     * 挖掘模块回报一次完整破坏：耗时（毫秒）+ 方块类型。
     * 只接受仍在线且被跟踪的玩家，避免离线 UUID 把 Map 撑住。
     */
    public void recordBlockBreak(UUID uuid, long breakDurationMs, String blockType) {
        if (!playerData.containsKey(uuid)) return;
        miningPatternAnalyzer.recordBreakTime(uuid, breakDurationMs, blockType);
    }

    /** 背包模块回报一次物品栏状态转移。 */
    public void recordInventoryTransition(UUID uuid, InventoryStateMachine.TransitionType type,
                                          int fromSlot, int toSlot, String itemType) {
        if (!playerData.containsKey(uuid)) return;
        inventoryStateMachine.recordTransition(uuid,
            new InventoryStateMachine.InventoryTransition(
                type, fromSlot, toSlot, System.currentTimeMillis(), itemType));
    }

    /** 战斗模块回报一次玩家发起的攻击命中。 */
    public void recordAttackHit(UUID uuid) {
        if (!playerData.containsKey(uuid)) return;
        aimAnalysis.recordHit(uuid);
    }

    /**
     * 画像摘要：仅输出描述性统计，且每项都要先过样本量下限。
     *
     * <p>刻意不输出「是否自瞄 / 是否矿机」这类结论——见字段注释里的阈值未标定问题。
     *
     * @return 没有任何一项攒够样本时返回 null
     */
    public String getProfileDigest(UUID uuid) {
        if (!playerData.containsKey(uuid)) return null;

        List<String> parts = new ArrayList<>();

        if (aimAnalysis.getLookDataCount(uuid) >= 10) {
            parts.add(String.format("转向平滑度 %.3f（增量方差 %.3f，命中 %d 次）",
                aimAnalysis.calculateSmoothness(uuid),
                aimAnalysis.calculateVariance(uuid),
                aimAnalysis.getHitCount(uuid)));
        }
        if (timerDetection.hasEnoughData(uuid)) {
            parts.add(String.format("挥手节奏 间隔 %.0fms（离散系数 %.3f）",
                timerDetection.getIntervalMean(uuid),
                timerDetection.getNormalizedStdDev(uuid)));
        }
        if (miningPatternAnalyzer.hasEnoughData(uuid)) {
            parts.add(String.format("挖掘节奏 平均 %.0fms（离散系数 %.3f）",
                miningPatternAnalyzer.calculateMeanBreakTime(uuid),
                miningPatternAnalyzer.getNormalizedStdDev(uuid)));
        }
        if (inventoryStateMachine.hasEnoughData(uuid)) {
            int swaps = inventoryStateMachine.getTransitionDistribution(uuid)
                .getOrDefault(InventoryStateMachine.TransitionType.SWAP_HANDS, 0);
            parts.add(String.format("背包操作 %d 次（副手切换 %d 次）",
                inventoryStateMachine.getTransitionCount(uuid), swaps));
        }

        if (parts.isEmpty()) return null;
        return "画像上下文: " + String.join("；", parts);
    }

    public void saveProfile(UUID uuid) {
        PlayerProfile profile = profiles.get(uuid);
        if (profile != null) {
            profile.updateLastSeen();
            plugin.getDatabaseManager().savePlayerProfile(profile);
        }
    }

    public void saveAllProfiles() {
        for (UUID uuid : profiles.keySet()) {
            saveProfile(uuid);
        }
    }

    public boolean hasEnoughData(UUID uuid) {
        PlayerProfile profile = profiles.get(uuid);
        return profile != null && profile.hasEnoughSamples();
    }

    public String getAnomalyReport(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerProfile profile = profiles.get(uuid);
        PlayerBehaviorData data = playerData.get(uuid);

        if (profile == null || data == null) {
            return null;
        }

        double currentCPS = calculateCPS(data);
        double currentTurnSpeed = data.recentTurnSpeed;
        double currentJumpInterval = data.recentJumpInterval;
        double currentInterfaceActions = data.interfaceActionsThisMinute.get();
        double currentWalkStayRatio = data.walkTimeThisPeriod.get() > 0 ? 0.8 : 0.2;

        return profile.getAnomalyReport(currentCPS, currentTurnSpeed,
            currentJumpInterval, currentInterfaceActions, currentWalkStayRatio);
    }

    public boolean detectAnomaly(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerProfile profile = profiles.get(uuid);

        if (profile == null || !profile.hasEnoughSamples()) {
            return false;
        }

        PlayerBehaviorData data = playerData.get(uuid);
        if (data == null) return false;

        double currentCPS = calculateCPS(data);

        return profile.isCPSAnomaly(currentCPS) ||
               profile.isTurnSpeedAnomaly(data.recentTurnSpeed) ||
               profile.isJumpIntervalAnomaly(data.recentJumpInterval) ||
               profile.isInterfaceActionAnomaly(data.interfaceActionsThisMinute.get()) ||
               profile.isWalkStayRatioAnomaly(data.walkTimeThisPeriod.get() > 0 ? 0.8 : 0.2) ||
               profile.detectBehaviorShift();
    }

    private static class PlayerBehaviorData {
        final Queue<Long> clickTimestamps = new ConcurrentLinkedQueue<>();
        final AtomicInteger interfaceActionsThisMinute = new AtomicInteger(0);
        final AtomicInteger walkTimeThisPeriod = new AtomicInteger(0);
        final AtomicLong lastArmSwing = new AtomicLong(0);
        final AtomicLong lastWalkStayCheck = new AtomicLong(0);
        final AtomicLong lastInterfaceCheck = new AtomicLong(0);
        final AtomicLong lastMoveCheck = new AtomicLong(0);
        
        volatile double recentTurnSpeed = 0;
        volatile double recentJumpInterval = 1.0;
        volatile long lastCPSUpdate = 0;
        volatile long lastTurnUpdate = 0;
        volatile long lastJumpUpdate = 0;
    }
}
