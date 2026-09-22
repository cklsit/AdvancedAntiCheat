package com.anticheat.bounty;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.core.AntiCheatCore;
import com.anticheat.core.bounty.AnomalyResult;
import com.anticheat.core.bounty.BaselineLearner;
import com.anticheat.core.bounty.BaselineModel;
import com.anticheat.core.bounty.BountyMetrics;
import com.anticheat.core.bounty.BountySample;
import com.anticheat.core.bounty.BountyTuning;
import com.anticheat.core.bounty.JudgeResult;
import com.anticheat.core.bounty.MetricBaseline;
import com.anticheat.core.db.BountyBaselineRow;
import com.anticheat.core.db.BountyCaseRow;
import com.anticheat.core.db.BountyRankRow;
import com.anticheat.core.db.BountyRepository;
import com.anticheat.core.db.BountyWalletRow;
import com.anticheat.core.db.DatabaseService;
import com.anticheat.core.db.Sql;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 赏金沙箱总控。
 *
 * <h3>本次重构修掉的三件事</h3>
 * 1. **每日限额**：原实现用一个只增不减的内存累加器，于是"每天 30 分钟"实际是
 *    "累计 30 分钟之后永久无法再进"，且重启即清零。现在按 (玩家, 天) 落库，
 *    跨天自然重新计算（[BountyRepository#secondsUsed]）。
 * 2. **沙箱内不再被处罚**：进入时打沙箱标记（[com.anticheat.core.bounty.BountyHooks]），
 *    检测照常打分但不处罚、不落生产库、不拉回。
 * 3. **判定不再是假的**：原 `recordDetection`/`recordSuspicious` 在全仓**没有任何调用点**，
 *    于是 `evaluateResult()` 恒返回"绕过成功"、每次任务都全服广播。
 *    现在证据来自核心层的检测回调，判定走可单测的 [com.anticheat.core.bounty.BountyJudge]。
 *
 * <h3>人类基线从哪来</h3>
 * 文档要求"与正常人类玩家的历史基线库对比"。基线由**主世界玩家**（非沙箱）提供：
 * 每个被采样玩家攒满一个 [BountyBaseline#windowTicks] 长的窗口后算出一组指标、
 * 喂给 [BaselineLearner]，然后清空缓冲重新攒。
 *
 * <p>**样本独立性是刻意的**：不重叠窗口避免了相邻观测高度相关（那会让人误以为
 * "样本很多"）。代价是攒得慢（10 秒窗口 → 每 10 秒一个观测），
 * 所以基线在开服早期是"未就绪"状态，此时判定会显式回落成"只看检测证据"
 * 并标注低置信度，而不是把"不知道"当成"正常"。</p>
 */
public class BountyManager {

    /** 一次任务的时长上限（秒），与每日总额度配合使用。 */
    private static final long SESSION_CAP_SECONDS = 1800L;

    /** 每日额度内至少要有这么多剩余时间才允许进入（避免进去就被踢）。 */
    private static final long MIN_SESSION_SECONDS = 60L;

    private final AdvancedAntiCheat plugin;
    /**
     * 沙箱世界。**懒建**：`bounty.enabled: false` 的服不该凭空多出一个世界
     * （那会白占内存与磁盘，而这个世界的唯一用途就是赏金）。启用时在构造末尾先建好，
     * 这样第一次 `/bounty enter` 不会在玩家面前卡一下。
     */
    private BountyWorld bountyWorld;

    private final Map<UUID, BountySession> activeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Location> pendingRespawnBackup = new ConcurrentHashMap<>();

    // ---- 基线 ----
    private final BaselineLearner learner = new BaselineLearner();
    private final Map<UUID, Deque<BountySample>> baselineWindows = new ConcurrentHashMap<>();
    private final Map<UUID, Long> baselineSeq = new ConcurrentHashMap<>();
    private volatile BaselineModel cachedModel = new BaselineModel(java.util.Collections.emptyMap(), 0L, 0, 1.0);

    private BukkitTask tickTask;
    private BukkitTask saveTask;
    private volatile boolean baselinesLoaded;
    private long lastBaselineSave;
    private long tickCounter;

    // ---- 配置 ----
    /** 沙箱内允许执行的命令（小写，不含斜杠）。 */
    private final java.util.Set<String> allowedCommands = new java.util.HashSet<>();

    private boolean enabled;
    private boolean isolateChat;
    private long dailyLimitSeconds;
    private boolean boardOnEnter;
    private boolean baselineEnabled;
    private long baselineSampleIntervalTicks;
    private int baselineWindowTicks;
    private long baselineSaveIntervalSeconds;
    private int baselineMaxTrackedPlayers;
    private BountyTuning tuning = new BountyTuning();
    private BaselineModel baselineModelTemplate;

    public BountyManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        loadConfig();
        // 启用就先建好（首访无卡顿）；关闭则完全不碰
        if (enabled) {
            getBountyWorld();
        }
    }

    // ------------------------------------------------------------------ 配置

    public void loadConfig() {
        org.bukkit.configuration.file.FileConfiguration config = plugin.getConfig();
        this.enabled = config.getBoolean("bounty.enabled", true);
        // daily-limit-seconds 是本轮新增的键；老配置里只有 default-time-limit-minutes，
        // 为它保留回退路径，避免升级后"每日额度突然变成 0 分钟"。
        long fallback = Math.max(1L, config.getLong("bounty.default-time-limit-minutes", 30L)) * 60L;
        this.dailyLimitSeconds = Math.max(60L, config.getLong("bounty.daily-limit-seconds", fallback));
        this.boardOnEnter = config.getBoolean("bounty.board-on-enter", true);
        this.isolateChat = config.getBoolean("bounty.isolate-chat", true);

        // 命令白名单只在这里读一次，监听器向本类查询——
        // 同一份配置被两个类各存一份，是"改了配置但只生效一半"的经典来源。
        allowedCommands.clear();
        List<String> configured = config.getStringList("bounty.allowed-commands");
        if (configured == null || configured.isEmpty()) {
            configured = java.util.Arrays.asList("bounty", "help", "list", "msg", "tell", "r", "reply", "who");
        }
        for (String raw : configured) {
            if (raw == null) continue;
            String name = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (!name.isEmpty()) allowedCommands.add(name);
        }
        // 永远允许 /bounty：否则玩家进了沙箱就出不来
        allowedCommands.add("bounty");

        this.tuning = new BountyTuning(
                config.getDouble("bounty.tuning.detected-min-vl", 8.0),
                config.getDouble("bounty.tuning.bypass-anomaly", 55.0),
                config.getDouble("bounty.tuning.zero-day-anomaly", 80.0),
                config.getInt("bounty.reward.base", 1),
                config.getDouble("bounty.reward.bypass-multiplier", 1.0),
                config.getInt("bounty.reward.zero-day", 500)
        );

        this.baselineEnabled = config.getBoolean("bounty.baseline.enabled", true);
        this.baselineSampleIntervalTicks = Math.max(1L, config.getLong("bounty.baseline.sample-interval-ticks", 20L));
        this.baselineWindowTicks = (int) Math.max(40L, config.getLong("bounty.baseline.window-ticks", 200L));
        this.baselineSaveIntervalSeconds = Math.max(30L, config.getLong("bounty.baseline.save-interval-seconds", 300L));
        this.baselineMaxTrackedPlayers = (int) Math.max(1L, config.getLong("bounty.baseline.max-tracked-players", 40L));

        long minSamples = Math.max(10L, config.getLong("bounty.baseline.min-samples", 200L));
        int minReadyMetrics = (int) Math.max(1L, config.getLong("bounty.baseline.min-ready-metrics", 2L));
        double fullScale = Math.max(1.0, config.getDouble("bounty.baseline.full-scale-surprise", 12.0));
        this.baselineModelTemplate = new BaselineModel(
                java.util.Collections.emptyMap(), minSamples, minReadyMetrics, fullScale);

        rebuildModel();
    }

    private void rebuildModel() {
        this.cachedModel = new BaselineModel(
                learner.toModel(baselineModelTemplate.getMinSamples()).getBaselines(),
                baselineModelTemplate.getMinSamples(),
                baselineModelTemplate.getMinReadyMetrics(),
                baselineModelTemplate.getFullScaleSurprise());
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 沙箱内是否允许执行该命令（[command] 为小写、不含斜杠的名字）。 */
    public boolean isCommandAllowed(String command) {
        return command != null && allowedCommands.contains(command.toLowerCase(java.util.Locale.ROOT));
    }

    /** 是否隔离聊天频道。 */
    public boolean isChatIsolated() {
        return isolateChat;
    }

    public BountyTuning tuning() {
        return tuning;
    }

    public BaselineModel baselineModel() {
        return cachedModel;
    }

    public BountyWorld getBountyWorld() {
        BountyWorld current = bountyWorld;
        if (current != null) return current;
        synchronized (this) {
            if (bountyWorld == null) {
                bountyWorld = new BountyWorld(plugin);
            }
            return bountyWorld;
        }
    }

    // ------------------------------------------------------------------ 生命周期

    public void start() {
        // 从库读回基线（续用历史统计，而不是每次重启从零开始）
        if (baselineEnabled) {
            loadBaselines();
        }

        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::maybeSaveBaseline, 200L, 200L);
        plugin.getLogger().info("[Bounty] 赏金模块已启动（每日额度 " + (dailyLimitSeconds / 60) + " 分钟，基线"
                + (baselineEnabled ? "开启" : "关闭") + "）");
    }

    public void onDisable() {
        if (tickTask != null) tickTask.cancel();
        if (saveTask != null) saveTask.cancel();
        for (BountySession session : new ArrayList<>(activeSessions.values())) {
            try {
                session.end();
            } catch (Throwable t) {
                plugin.getLogger().warning("[Bounty] 关闭时结束会话失败: " + t.getMessage());
            }
        }
        activeSessions.clear();
        saveBaselineNow();
    }

    // ------------------------------------------------------------------ 每 tick

    private void tick() {
        tickCounter++;

        // 1) 沙箱会话：每 tick 采样与目标追踪
        for (BountySession session : new ArrayList<>(activeSessions.values())) {
            try {
                session.tick();
            } catch (Throwable t) {
                plugin.getLogger().warning("[Bounty] 采样异常（已跳过本次）: " + t.getMessage());
            }
        }

        // 2) 主世界基线采样（低频率，且只取非沙箱玩家）
        if (baselineEnabled && tickCounter % baselineSampleIntervalTicks == 0) {
            sampleBaseline();
        }
    }

    private void sampleBaseline() {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (online.isEmpty()) return;
        int tracked = 0;
        for (Player player : online) {
            if (tracked >= baselineMaxTrackedPlayers) break;
            UUID uuid = player.getUniqueId();
            if (activeSessions.containsKey(uuid)) continue;      // 沙箱玩家不进基线
            BountyWorld sandbox = bountyWorld;
            if (sandbox != null && sandbox.getWorld() != null
                    && sandbox.getWorld().equals(player.getWorld())) continue;
            tracked++;

            long seq = baselineSeq.merge(uuid, 1L, Long::sum);
            Location location = player.getLocation();
            BountySample sample = new BountySample(seq, location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch(), player.isOnGround(), false);
            Deque<BountySample> window = baselineWindows.computeIfAbsent(uuid, k -> new ArrayDeque<>());
            window.addLast(sample);
            if (window.size() >= baselineWindowTicks) {
                // 一个窗口攒满 → 算一次指标喂给学习器，然后清空重新攒。
                // 不重叠窗口是为了样本独立：滚动窗口会让相邻观测高度相关，
                // 于是"样本数"看起来涨得很快，但信息量并没有那么多。
                learner.observeAll(BountyMetrics.compute(new ArrayList<>(window)));
                window.clear();
                rebuildModel();
            }
        }
        // 离线玩家的窗口与序号要清掉，否则长期运行会积成内存泄漏
        if (baselineWindows.size() > baselineMaxTrackedPlayers) {
            List<UUID> stale = new ArrayList<>();
            for (UUID uuid : baselineWindows.keySet()) {
                if (Bukkit.getPlayer(uuid) == null) stale.add(uuid);
            }
            for (UUID uuid : stale) {
                baselineWindows.remove(uuid);
                baselineSeq.remove(uuid);
            }
        }
    }

    private void maybeSaveBaseline() {
        // 启动时数据库常常还没连上（DatabaseInit 是异步重试的），
        // 所以这里顺便做一次"补载"：一旦库可用就把基线读回来，
        // 而不是等到下次重启才用上历史统计。
        if (!baselinesLoaded && baselineEnabled) {
            loadBaselines();
        }
        long now = System.currentTimeMillis();
        if (now - lastBaselineSave < baselineSaveIntervalSeconds * 1000L) return;
        lastBaselineSave = now;
        saveBaselineNow();
    }

    /** 从库里读回基线（可重复调用；读到之后置位，避免反复查询）。 */
    private void loadBaselines() {
        List<BountyBaselineRow> rows = db().loadBaselines();
        if (rows.isEmpty()) return;
        List<MetricBaseline> loaded = new ArrayList<>(rows.size());
        for (BountyBaselineRow row : rows) {
            loaded.add(new MetricBaseline(row.getMetricKey(), row.getMean(), row.getSd(),
                    row.getSamples(), com.anticheat.core.bounty.MetricDirection.LOWER_SUSPICIOUS));
        }
        learner.seed(loaded);
        rebuildModel();
        baselinesLoaded = true;
        plugin.getLogger().info("[Bounty] 人类基线已载入：" + loaded.size() + " 个指标（就绪="
                + cachedModel.ready() + "）");
    }

    /** 供 `/ac reload` 用。 */
    public void reload() {
        loadConfig();
    }

    private void saveBaselineNow() {
        if (!baselineEnabled) return;
        List<MetricBaseline> snapshot = learner.snapshot();
        if (snapshot.isEmpty()) return;
        List<BountyBaselineRow> rows = new ArrayList<>(snapshot.size());
        for (MetricBaseline baseline : snapshot) {
            rows.add(new BountyBaselineRow(baseline.getKey(), baseline.getMean(), baseline.getSd(),
                    baseline.getSamples(), baseline.getDirection().name()));
        }
        db().saveBaselines(rows);
    }

    // ------------------------------------------------------------------ 进入 / 离开

    public void enterBounty(Player player) {
        if (!enabled) {
            player.sendMessage(ChatColor.RED + "漏洞赏金计划当前未启用");
            return;
        }
        UUID uuid = player.getUniqueId();
        if (activeSessions.containsKey(uuid)) {
            player.sendMessage(ChatColor.RED + "你已经在漏洞赏金沙箱中了");
            return;
        }
        if (!bountyWorld.isUsable()) {
            player.sendMessage(ChatColor.RED + "沙箱世界不可用（请检查服务端日志）");
            return;
        }

        long budget = remainingBudget(player);
        if (budget < MIN_SESSION_SECONDS) {
            player.sendMessage(ChatColor.RED + "你今天的沙箱额度已用完（每个玩家每天 "
                    + (dailyLimitSeconds / 60) + " 分钟，跨天自动重置）");
            return;
        }

        long sessionSeconds = Math.min(budget, SESSION_CAP_SECONDS);
        BountySession session = new BountySession(plugin, this, player, sessionSeconds);
        activeSessions.put(uuid, session);
        session.start();
        player.sendMessage(ChatColor.GRAY + "本次可用时长：" + (sessionSeconds / 60) + " 分钟"
                + "（今日剩余额度 " + (budget / 60) + " 分钟）");

        if (boardOnEnter) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && activeSessions.containsKey(uuid)) {
                    BountyBoard.open(plugin, this, player);
                }
            }, 20L);
        }
    }

    /** 今日剩余额度（秒）；有 `anticheat.bounty.unlimited` 权限的人不受限。 */
    public long remainingBudget(Player player) {
        if (player.hasPermission("anticheat.bounty.unlimited")) {
            return dailyLimitSeconds;
        }
        long today = Sql.dayBucket(System.currentTimeMillis());
        long used = db().secondsUsed(player.getUniqueId(), today);
        return Math.max(0L, dailyLimitSeconds - used);
    }

    public void leaveBounty(Player player) {
        UUID uuid = player.getUniqueId();
        BountySession session = activeSessions.remove(uuid);
        if (session == null) {
            player.sendMessage(ChatColor.RED + "你不在漏洞赏金沙箱中");
            return;
        }
        try {
            session.end();
        } catch (Throwable t) {
            plugin.getLogger().severe("[Bounty] 退出沙箱时出错（物品快照仍在内存中）: " + t.getMessage());
        }
    }

    /**
     * 会话结束时把用时累加到"当天"（跨天重置靠 (uuid, day) 主键自然实现）。
     *
     * <p>走异步：退出沙箱的路径上还有背包还原与传送，不该再叠一次数据库往返。</p>
     */
    public void recordSessionTime(BountySession session) {
        long today = Sql.dayBucket(System.currentTimeMillis());
        long seconds = Math.max(0L, session.getTimeSpentSeconds());
        if (seconds <= 0) return;
        UUID uuid = session.getUuid();
        runAsync(() -> db().addSeconds(uuid, today, seconds));
    }

    public void startTask(Player player, BountyTaskType task) {
        BountySession session = activeSessions.get(player.getUniqueId());
        if (session == null) {
            player.sendMessage(ChatColor.RED + "你不在漏洞赏金沙箱中（先用 /bounty enter 进入）");
            return;
        }
        session.startTask(task);
    }

    // ------------------------------------------------------------------ 判定结算

    /**
     * 一次任务结果的快照（交给异步线程落盘与落库）。
     *
     * <p>做成不可变值对象而不是把 [BountySession] 传过去：会话在判定之后会被立刻重置
     * （采样缓冲清空、任务置空），异步线程去读活动对象必然读到半清空的中间态。</p>
     */
    public static final class TaskOutcome {
        final UUID uuid;
        final String name;
        final BountyTaskType task;
        final JudgeResult result;
        final AnomalyResult anomaly;
        final Map<String, Double> metrics;
        final Map<String, String> baselineText;
        final int flags;
        final double maxVl;
        final int samples;
        final BountyEvidence evidence;

        TaskOutcome(UUID uuid, String name, BountyTaskType task, JudgeResult result, AnomalyResult anomaly,
                    Map<String, Double> metrics, Map<String, String> baselineText,
                    int flags, double maxVl, int samples, BountyEvidence evidence) {
            this.uuid = uuid;
            this.name = name;
            this.task = task;
            this.result = result;
            this.anomaly = anomaly;
            this.metrics = metrics;
            this.baselineText = baselineText;
            this.flags = flags;
            this.maxVl = maxVl;
            this.samples = samples;
            this.evidence = evidence;
        }
    }

    /**
     * 落盘证据 + 落库案例 + 发代币，**全部在异步线程**。
     *
     * <p>为什么必须异步：证据包是一个最多上万行的 CSV，加上一次数据库往返，
     * 放在主线程就是一次可观测的卡顿（本项目对"主线程做 IO"有明确纪律）。
     * 判定本身（纯计算）仍在主线程完成，因为它要读会话状态。</p>
     *
     * <p>顺序刻意是"先写案例、再发代币"：案例是审计真相，代币是它的结果。
     * 反过来（先发钱后记录）一旦记录失败，账上就多了一笔无法解释的收入。</p>
     */
    public void persistTaskResult(TaskOutcome outcome) {
        runAsync(() -> {
            try {
                String relative = outcome.evidence.write(
                        plugin, outcome.name, outcome.task.getId(), System.currentTimeMillis());
                String summary = BountyEvidence.buildSummary(
                        outcome.name, outcome.task.getDisplayName(),
                        outcome.result.getVerdict().getDisplayName()
                                + "（置信度 " + outcome.result.getConfidence().getDisplayName() + "）",
                        outcome.result.getReason(), outcome.metrics, outcome.baselineText,
                        outcome.samples, outcome.anomaly.getScore(), outcome.anomaly.getReady());
                outcome.evidence.writeSummary(plugin, relative, summary);

                String status = outcome.result.getVerdict().isFinding()
                        ? BountyRepository.STATUS_PENDING
                        : BountyRepository.STATUS_LOGGED;

                long id = db().insertCase(
                        outcome.uuid, outcome.name, outcome.task.getId(),
                        outcome.result.getVerdict().name(), outcome.result.getConfidence().name(),
                        outcome.result.getReward(), outcome.flags, outcome.maxVl,
                        outcome.anomaly.getScore(), outcome.anomaly.getReady(), outcome.samples,
                        outcome.result.getReason(), truncate(summary, 1000), relative, status);

                if (outcome.result.getReward() > 0) {
                    long balance = db().addTokens(outcome.uuid, outcome.name, outcome.result.getReward());
                    plugin.getLogger().info("[Bounty] " + outcome.name + " 任务 " + outcome.task.getId()
                            + " 判定 " + outcome.result.getVerdict().name() + "，发放 "
                            + outcome.result.getReward() + " 代币（余额 " + balance + "）案例#" + id);
                }

                if (outcome.result.getVerdict().isFinding()) {
                    broadcastFinding(outcome, id);
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("[Bounty] 落盘/落库失败（判定结果不会重试，请人工核对）: "
                        + t.getMessage());
            }
        });
    }

    private void broadcastFinding(TaskOutcome outcome, long caseId) {
        final String message;
        if (outcome.result.getVerdict() == com.anticheat.core.bounty.BountyVerdict.ZERO_DAY) {
            message = ChatColor.RED + "" + ChatColor.BOLD + "[漏洞赏金] " + ChatColor.YELLOW
                    + outcome.name + " 发现了一个高危零日绕过！案例 #" + caseId
                    + "，赏金 " + outcome.result.getReward() + " 代币已发放。该漏洞将进入人工复核。";
        } else {
            message = ChatColor.RED + "" + ChatColor.BOLD + "[漏洞赏金] " + ChatColor.GREEN
                    + outcome.name + " 在「" + outcome.task.getDisplayName() + "」中实现了潜在绕过！案例 #"
                    + caseId + "，赏金 " + outcome.result.getReward() + " 代币已发放，等待人工审核。";
        }
        // 广播必须回主线程（本方法此刻在异步线程上）
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.broadcastMessage(message));
    }

    /**
     * 调度一个异步任务。
     *
     * <p>**插件正在关闭时不能再调度**（会抛 `IllegalPluginAccessException:
     * Plugin attempted to register task while disabled`——本项目在 onDisable 上踩过这个坑，
     * 表现为停服日志里一排异步任务异常）。所以调度失败时退回同步执行：
     * 此刻已经没有 tick 可影响，把最后一批写入做完才对。</p>
     */
    private void runAsync(Runnable task) {
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        } catch (Throwable t) {
            try {
                task.run();
            } catch (Throwable inner) {
                plugin.getLogger().warning("[Bounty] 关服期间的收尾写入失败: " + inner.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ 代币 / 商城

    public long tokensOf(Player player) {
        return db().tokens(player.getUniqueId());
    }

    public boolean hasPurchased(Player player, BountyShop.ShopItem item) {
        return db().hasPurchased(player.getUniqueId(), item.getId());
    }

    public boolean purchase(Player player, BountyShop.ShopItem item) {
        return db().purchase(player.getUniqueId(), player.getName(), item.getId(),
                item.getCost(), item.isOneTime());
    }

    // ------------------------------------------------------------------ 展示

    public void showLeaderboard(Player player) {
        List<BountyRankRow> rows = db().top(10);
        player.sendMessage(ChatColor.GOLD + "======== 赏金猎人排行（按累计获得）========");
        if (rows.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + "还没有记录（数据库不可用或暂无数据）");
        } else {
            int rank = 1;
            for (BountyRankRow row : rows) {
                player.sendMessage(ChatColor.YELLOW + "" + rank + ". " + ChatColor.WHITE + row.getName()
                        + ChatColor.GRAY + " 累计 " + row.getEarned() + " · 余额 " + row.getTokens());
                rank++;
            }
        }
        player.sendMessage(ChatColor.GOLD + "================================");
    }

    public void showMyCases(Player player) {
        List<BountyCaseRow> rows = db().casesOf(player.getUniqueId(), 10);
        player.sendMessage(ChatColor.GOLD + "======== 我的提交记录 ========");
        if (rows.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + "还没有提交记录");
        } else {
            for (BountyCaseRow row : rows) {
                player.sendMessage(ChatColor.GRAY + "#" + row.getId() + " " + ChatColor.WHITE + row.getTask()
                        + ChatColor.GRAY + " → " + ChatColor.YELLOW + row.getVerdict()
                        + ChatColor.GRAY + "（" + row.getStatus() + "，赏金 " + row.getTokens() + "）");
            }
        }
        player.sendMessage(ChatColor.GOLD + "==============================");
    }

    public void showPendingCases(Player player, int limit) {
        List<BountyCaseRow> rows = db().cases(BountyRepository.STATUS_PENDING, limit);
        player.sendMessage(ChatColor.GOLD + "======== 待复核案例（" + rows.size() + "）========");
        if (rows.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + "没有待复核的案例");
        } else {
            for (BountyCaseRow row : rows) {
                player.sendMessage(ChatColor.GRAY + "#" + row.getId() + " " + ChatColor.WHITE + row.getName()
                        + ChatColor.GRAY + " / " + row.getTask() + " → " + ChatColor.YELLOW + row.getVerdict()
                        + ChatColor.GRAY + " 异常分 " + String.format("%.1f", row.getAnomalyScore())
                        + (row.getEvidencePath() == null ? "" : " 证据: " + row.getEvidencePath()));
            }
        }
        player.sendMessage(ChatColor.GOLD + "用 /bounty accept <案例ID> 采纳，/bounty reject <案例ID> 驳回");
    }

    /**
     * 审核一条案例（文档第六节："特征库污染防护"）。
     *
     * <p>**采纳不会自动改检测**：这里只把状态落库。管理员据此人工决定是否更新规则——
     * 让沙箱数据自动流入生产基线，正是文档明确禁止的"特征库污染"。</p>
     */
    public boolean reviewCase(Player reviewer, long caseId, boolean accept) {
        String status = accept ? BountyRepository.STATUS_ACCEPTED : BountyRepository.STATUS_REJECTED;
        int changed = db().review(caseId, status, reviewer.getName());
        if (changed == 0) {
            reviewer.sendMessage(ChatColor.RED + "案例 #" + caseId + " 不存在或已审核过");
            return false;
        }
        BountyCaseRow row = db().caseById(caseId);
        reviewer.sendMessage(ChatColor.GREEN + "已" + (accept ? "采纳" : "驳回") + "案例 #" + caseId
                + (row == null ? "" : "（" + row.getName() + " / " + row.getTask() + "）"));
        plugin.getLogger().info("[Bounty] " + reviewer.getName() + " " + status + " 案例 #" + caseId);
        return true;
    }

    public void showStatus(Player player) {
        BountySession session = activeSessions.get(player.getUniqueId());
        player.sendMessage(ChatColor.GOLD + "======== 赏金沙箱状态 ========");
        if (session != null) {
            player.sendMessage(ChatColor.WHITE + session.describe());
        } else {
            player.sendMessage(ChatColor.GRAY + "你不在沙箱中");
        }
        long today = Sql.dayBucket(System.currentTimeMillis());
        long used = db().secondsUsed(player.getUniqueId(), today);
        player.sendMessage(ChatColor.GRAY + "今日已用 " + (used / 60) + " 分钟 / 上限 "
                + (dailyLimitSeconds / 60) + " 分钟");
        player.sendMessage(ChatColor.GRAY + "代币余额 " + tokensOf(player)
                + " · 待复核案例 " + db().pendingCount());
        player.sendMessage(ChatColor.GRAY + "人类基线：" + (cachedModel.ready() ? "已就绪" : "未就绪（样本不足）")
                + " · " + cachedModel.describe());
        player.sendMessage(ChatColor.GOLD + "==============================");
    }

    // ------------------------------------------------------------------ 其他

    public void invitePlayer(Player inviter, Player target) {
        if (!inviter.hasPermission("anticheat.bounty.admin")) {
            inviter.sendMessage(ChatColor.RED + "你没有权限邀请玩家");
            return;
        }
        target.sendMessage(ChatColor.GOLD + "管理员 " + inviter.getName() + " 邀请你进入漏洞赏金沙箱！");
        target.sendMessage(ChatColor.YELLOW + "使用 /bounty enter 加入（每天有额度限制）");
        inviter.sendMessage(ChatColor.GREEN + "已向 " + target.getName() + " 发送邀请");
    }

    /** 玩家主动提交的发现（/bounty report）：写进当前会话的证据流。 */
    public void reportFinding(Player player, String description) {
        BountySession session = activeSessions.get(player.getUniqueId());
        if (session == null) {
            player.sendMessage(ChatColor.RED + "你不在漏洞赏金沙箱中");
            return;
        }
        session.getEvidence().event("[REPORT] " + description);
        player.sendMessage(ChatColor.GREEN + "你的报告已记入本次证据包（任务结束后一并落盘）");
    }

    public boolean isInBounty(Player player) {
        return activeSessions.containsKey(player.getUniqueId());
    }

    public BountySession getSession(Player player) {
        return player == null ? null : activeSessions.get(player.getUniqueId());
    }

    public int sessionCount() {
        return activeSessions.size();
    }

    public void putPendingRespawnBackup(UUID uuid, Location originalLocation) {
        if (uuid == null || originalLocation == null) return;
        pendingRespawnBackup.put(uuid, originalLocation);
    }

    public Location pollPendingRespawnBackup(UUID uuid) {
        return uuid == null ? null : pendingRespawnBackup.remove(uuid);
    }

    /** 玩家离线：清基线窗口，避免内存滞留。 */
    public void onPlayerQuit(Player player) {
        if (isInBounty(player)) {
            leaveBounty(player);
        }
        UUID uuid = player.getUniqueId();
        baselineWindows.remove(uuid);
        baselineSeq.remove(uuid);
    }

    public String describeState() {
        return "会话 " + activeSessions.size() + " / 基线窗口 " + baselineWindows.size()
                + " / 基线就绪 " + cachedModel.ready() + " / 观测 " + learner.totalObservations();
    }

    /**
     * 数据库访问的空安全包装。
     *
     * <p>不做"造一个假的 DatabaseService 当替身"——那会真的去建连接池。
     * 只把可空引用挡在一处，调用点写起来和单后端时代一样直。</p>
     *
     * <p>注意：这里只管 `AntiCheatCore.database` 是否为 null（数据库被禁用）；
     * "已启用但连不上"由 `DatabaseService` 自己按未就绪返回缺省值。</p>
     */
    private static final class Db {
        private final DatabaseService delegate;

        Db(DatabaseService delegate) {
            this.delegate = delegate;
        }

        long tokens(UUID uuid) {
            if (delegate == null) return 0L;
            BountyWalletRow wallet = delegate.bountyWallet(uuid);
            return wallet == null ? 0L : wallet.getTokens();
        }

        boolean hasPurchased(UUID uuid, String itemId) {
            return delegate != null && delegate.bountyHasPurchased(uuid, itemId);
        }

        boolean purchase(UUID uuid, String name, String itemId, long cost, boolean oneTime) {
            return delegate != null && delegate.bountyPurchase(uuid, name, itemId, cost, oneTime);
        }

        List<BountyRankRow> top(int limit) {
            return delegate == null ? java.util.Collections.<BountyRankRow>emptyList() : delegate.bountyTop(limit);
        }

        List<BountyCaseRow> cases(String status, int limit) {
            return delegate == null ? java.util.Collections.<BountyCaseRow>emptyList()
                    : delegate.bountyCases(status, limit);
        }

        List<BountyCaseRow> casesOf(UUID uuid, int limit) {
            return delegate == null ? java.util.Collections.<BountyCaseRow>emptyList()
                    : delegate.bountyCasesOf(uuid, limit);
        }

        BountyCaseRow caseById(long id) {
            return delegate == null ? null : delegate.bountyCase(id);
        }

        int review(long id, String status, String by) {
            return delegate == null ? 0 : delegate.bountyReviewCase(id, status, by);
        }

        long pendingCount() {
            return delegate == null ? 0L : delegate.bountyCountCases(BountyRepository.STATUS_PENDING);
        }

        long secondsUsed(UUID uuid, long dayMillis) {
            return delegate == null ? 0L : delegate.bountySecondsUsed(uuid, dayMillis);
        }

        void addSeconds(UUID uuid, long dayMillis, long seconds) {
            if (delegate != null) delegate.bountyAddSessionSeconds(uuid, dayMillis, seconds);
        }

        /** @return 发放后的余额（库不可用时为 0） */
        long addTokens(UUID uuid, String name, long amount) {
            return delegate == null ? 0L : delegate.bountyAddTokens(uuid, name, amount);
        }

        long insertCase(UUID uuid, String name, String task, String verdict, String confidence,
                        long tokens, int flags, double maxVl, double anomalyScore, boolean baselineReady,
                        int samples, String reason, String summary, String evidencePath, String status) {
            if (delegate == null) return -1L;
            return delegate.bountyInsertCase(uuid, name, task, verdict, confidence, tokens, flags, maxVl,
                    anomalyScore, baselineReady, samples, reason, summary, evidencePath, status);
        }

        List<BountyBaselineRow> loadBaselines() {
            return delegate == null ? java.util.Collections.<BountyBaselineRow>emptyList()
                    : delegate.bountyLoadBaselines();
        }

        void saveBaselines(List<BountyBaselineRow> rows) {
            if (delegate != null) delegate.bountySaveBaselines(rows);
        }
    }

    private static Db db() {
        return new Db(AntiCheatCore.getDatabase());
    }

    private static String truncate(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** 供命令补全用的任务 id 列表。 */
    public static List<String> taskIds() {
        List<String> ids = new ArrayList<>();
        for (BountyTaskType type : BountyTaskType.values()) {
            ids.add(type.getId());
        }
        return ids;
    }

    /** 未使用但保留：便于将来把排行榜做成分页 GUI。 */
    public Map<String, Long> baselineSnapshotView() {
        Map<String, Long> view = new LinkedHashMap<>();
        for (MetricBaseline baseline : learner.snapshot()) {
            view.put(baseline.getKey(), baseline.getSamples());
        }
        return view;
    }
}
