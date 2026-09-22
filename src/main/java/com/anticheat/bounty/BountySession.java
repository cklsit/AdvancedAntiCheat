package com.anticheat.bounty;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.captcha.CaptchaInventoryBackup;
import com.anticheat.core.bounty.AnomalyResult;
import com.anticheat.core.bounty.BaselineModel;
import com.anticheat.core.bounty.BountyConfidence;
import com.anticheat.core.bounty.BountyHooks;
import com.anticheat.core.bounty.BountyJudge;
import com.anticheat.core.bounty.BountyMetrics;
import com.anticheat.core.bounty.BountySample;
import com.anticheat.core.bounty.BountyTuning;
import com.anticheat.core.bounty.BountyVerdict;
import com.anticheat.core.bounty.JudgeInput;
import com.anticheat.core.bounty.JudgeResult;
import com.anticheat.core.bounty.MetricBaseline;
import com.anticheat.utils.VersionUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一次赏金沙箱会话。
 *
 * <h3>三件必须按顺序做的事</h3>
 * 1. **进沙箱前先暂存、再清空**（[CaptchaInventoryBackup]）。原实现直接
 *    `inventory.clear()` 且从不还原 —— 玩家执行一次 `/bounty enter` 就永久失去
 *    全部家当。这是本次重构修掉的头号缺陷。
 * 2. **打沙箱标记**（[BountyHooks.enterSandbox]）：检测照常打分，但不处罚、不落生产库、不拉回。
 * 3. **离开时用暂存覆盖**：文档要求"沙箱内获得的物品/经验/进度全部销毁、不带回主世界"，
 *    而"用快照覆盖当前状态"恰好同时满足"销毁沙箱所得"与"归还原物"。
 *
 * <h3>采样与判定的分工</h3>
 * 采样（每 tick 一条，有界环形缓冲）→ 指标（[BountyMetrics]，纯计算）→
 * 与人类基线对比得到异常分（[BaselineModel]）→ 判定（[BountyJudge]）。
 * 会话只负责"喂数据"，所有判定逻辑都在可离线单测的纯类里。
 */
public class BountySession implements BountyHooks.SandboxDetectionListener {

    /** 到达判定半径（格）。 */
    public static final double REACH_POINT_RADIUS = 3.0;

    /** 采样缓冲容量（60 秒 @ 20tps）。指标只需要最近一段窗口。 */
    private static final int SAMPLE_CAPACITY = 1200;

    /** 空中直角变向：连续离地 tick 下限。 */
    private static final int AIR_TURN_MIN_AIR_TICKS = 10;

    /** 空中直角变向：期间偏航角变化下限（度）。 */
    private static final double AIR_TURN_MIN_DEGREES = 60.0;

    /** 幽灵锁定：准星与目标方向的夹角上限（度）。 */
    private static final double GHOST_LOCK_MAX_ANGLE = 5.0;

    /** 幽灵锁定：累计锁定 tick（3 秒）。 */
    private static final int GHOST_LOCK_TICKS = 60;

    /** 傀儡数量。 */
    private static final int PUPPET_COUNT = 5;

    /** 击杀窗口（tick，10 秒）。 */
    private static final int KILL_WINDOW_TICKS = 200;

    /** 快速换装：窗口（tick，3 秒）与所需交互次数。 */
    private static final int FAST_SWAP_WINDOW_TICKS = 60;
    private static final int FAST_SWAP_CLICKS = 8;

    private final AdvancedAntiCheat plugin;
    private final BountyManager manager;
    private final Player player;
    private final UUID uuid;

    private final Location originalLocation;
    private final Location originalBedSpawn;
    private final CaptchaInventoryBackup inventoryBackup;

    private final long startMillis;
    private long timeSpentSeconds;
    private final long maxSeconds;

    private BountyTaskType currentTask;
    private long taskStartTick;
    /**
     * 本次任务的证据缓冲。
     *
     * <p>不是 final：任务结束时会**整个交给异步线程落盘**，并立刻换一个新的给下一个任务。
     * 这样异步写文件期间玩家开下一个任务也不会往同一份缓冲里追加内容。</p>
     */
    private BountyEvidence evidence = new BountyEvidence();

    /** 采样环形缓冲（指标窗口）。 */
    private final Deque<BountySample> samples = new ArrayDeque<>(SAMPLE_CAPACITY);
    private long sampleSeq;

    /** 检测证据（由核心层回调写入）。 */
    private int detectionFlags;
    private double maxVl;
    private final List<String> detectedChecks = new ArrayList<>();

    // ---- 目标状态 ----
    private Location pointA;
    private Location pointB;
    private boolean objectiveMet;
    private int airTicks;
    private float airStartYaw;
    private double maxAirTurn;
    private int kills;
    private long firstKillTick = -1L;
    private Entity ghost;
    private int ghostLockTicks;
    private final Deque<Long> swapClicks = new ArrayDeque<>();
    private final List<Entity> spawnedEntities = new ArrayList<>();

    private boolean dirty = true;

    public BountySession(AdvancedAntiCheat plugin, BountyManager manager, Player player, long maxSeconds) {
        this.plugin = plugin;
        this.manager = manager;
        this.player = player;
        this.uuid = player.getUniqueId();
        this.originalLocation = player.getLocation().clone();
        this.originalBedSpawn = safeBedSpawn(player);
        // 必须在任何清空动作之前采集
        this.inventoryBackup = CaptchaInventoryBackup.capture(player);
        this.startMillis = System.currentTimeMillis();
        this.maxSeconds = Math.max(60L, maxSeconds);
    }

    // ------------------------------------------------------------------ 生命周期

    /** 进入沙箱。 */
    public void start() {
        BountyWorld world = manager.getBountyWorld();
        Location spawn = world.getSpawnLocation();

        // 先设置赏金世界为重生点：玩家在沙箱内死亡/断线后复活不该回到主世界的床
        applyBedSpawn(spawn);

        // 打沙箱标记（检测照常打分，但不处罚/不落库/不拉回），并登记检测回调
        boolean hooked = BountyHooks.enterSandbox(uuid, this);
        if (!hooked) {
            // 核心层没这个玩家（正常不会发生）：至少保证不处罚
            plugin.getLogger().warning("[Bounty] 无法为 " + player.getName() + " 打沙箱标记（核心层未持有该玩家）");
        }

        // 清空状态（背包此刻起由快照兜底）
        world.preparePlayer(player);

        player.teleport(spawn);
        player.setFallDistance(0f);

        player.sendMessage(ChatColor.GOLD + "================================================");
        player.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "欢迎来到漏洞赏金沙箱");
        player.sendMessage(ChatColor.GOLD + "================================================");
        player.sendMessage(ChatColor.GOLD + "你已被授权在此区域使用任何第三方工具，你的所有行为将被完整记录。");
        player.sendMessage(ChatColor.GRAY + "沙箱内不会触发踢出或封禁；检测仍在运行，用于评估你的操作。");
        player.sendMessage(ChatColor.GRAY + "背包与经验已在进入时暂存，离开时原样归还（沙箱内所得会被销毁）。");
        player.sendMessage(ChatColor.YELLOW + "用 /bounty board 打开任务板，或 /bounty leave 退出沙箱。");

        evidence.event("[SESSION] " + player.getName() + " 进入沙箱（上限 " + maxSeconds + " 秒）");
        evidence.event("[SESSION] 暂存背包: " + inventoryBackup.summary());
    }

    /** 每 tick 由 [BountyManager] 驱动。 */
    public void tick() {
        sampleSeq++;
        timeSpentSeconds = (System.currentTimeMillis() - startMillis) / 1000L;

        if (timeSpentSeconds >= maxSeconds) {
            player.sendMessage(ChatColor.RED + "你的沙箱时长已用完，正在退出…");
            manager.leaveBounty(player);
            return;
        }
        if (timeSpentSeconds > 0 && timeSpentSeconds % 300 == 0 && dirty) {
            dirty = false;
            player.sendMessage(ChatColor.YELLOW + "沙箱剩余时长：" + Math.max(0, (maxSeconds - timeSpentSeconds) / 60) + " 分钟");
        }
        if (timeSpentSeconds % 300 != 0) dirty = true;

        sample();
        trackObjective();

        // 任务到时：自动结算（原实现只在玩家离开沙箱时才结算，于是"任务超时"没有结果）
        if (currentTask != null) {
            long elapsed = sampleSeq - taskStartTick;
            if (elapsed >= currentTask.getDurationSeconds() * 20L) {
                player.sendMessage(ChatColor.YELLOW + "任务时间到，正在评估…");
                finishTask("任务超时");
            }
        }
    }

    // ------------------------------------------------------------------ 采样

    private void sample() {
        Location location = player.getLocation();
        float yaw = location.getYaw();
        float pitch = location.getPitch();
        boolean attacking = lastTickAttacked;
        lastTickAttacked = false;

        BountySample sample = new BountySample(
                sampleSeq,
                location.getX(), location.getY(), location.getZ(),
                yaw, pitch, player.isOnGround(), attacking
        );
        samples.addLast(sample);
        while (samples.size() > SAMPLE_CAPACITY) {
            samples.pollFirst();
        }
        if (currentTask != null) {
            evidence.sample(sampleSeq, location.getX(), location.getY(), location.getZ(),
                    yaw, pitch, player.isOnGround(), attacking);
        }
    }

    private boolean lastTickAttacked;

    /** 由监听器在本 tick 观察到攻击时调用。 */
    public void noteAttack() {
        lastTickAttacked = true;
    }

    // ------------------------------------------------------------------ 任务

    public void startTask(BountyTaskType task) {
        if (currentTask != null) {
            player.sendMessage(ChatColor.RED + "请先完成或放弃当前任务");
            return;
        }
        this.currentTask = task;
        this.taskStartTick = sampleSeq;
        resetObjectiveState();

        player.sendMessage(ChatColor.GREEN + "开始任务：" + ChatColor.GOLD + task.getDisplayName());
        player.sendMessage(ChatColor.GRAY + task.getObjective());
        player.sendMessage(ChatColor.GRAY + "赏金 " + task.getBounty() + " 代币 · 时长 " + task.getDurationMinutes() + " 分钟");
        evidence.event("[TASK] 开始 " + task.name() + "（" + task.getObjective() + "）");

        prepareTaskResources(task);
    }

    private void resetObjectiveState() {
        objectiveMet = false;
        airTicks = 0;
        maxAirTurn = 0.0;
        kills = 0;
        firstKillTick = -1L;
        ghostLockTicks = 0;
        swapClicks.clear();
        clearSpawnedEntities();
        if (ghost != null) {
            try {
                ghost.remove();
            } catch (Throwable ignored) {
            }
            ghost = null;
        }
    }

    private void prepareTaskResources(BountyTaskType task) {
        World world = manager.getBountyWorld().getWorld();
        switch (task) {
            case MOVE_BASIC: {
                pointA = new Location(world, -30, BountyWorld.PLATFORM_HEIGHT, -30);
                pointB = new Location(world, 30, BountyWorld.PLATFORM_HEIGHT, 30);
                // 1.8 无彩色羊毛枚举：走 VersionUtil 的兼容取值
                markPoint(pointA, VersionUtil.compatColoredWoolMaterial("RED_WOOL", DyeColor.RED), DyeColor.RED, "A");
                markPoint(pointB, VersionUtil.compatColoredWoolMaterial("GREEN_WOOL", DyeColor.GREEN), DyeColor.GREEN, "B");
                player.sendMessage(ChatColor.GRAY + "起点 A " + fmt(pointA) + " · 终点 B " + fmt(pointB));
                player.sendMessage(ChatColor.GRAY + "目标：30 秒内到达 B 点（步行无法完成）");
                break;
            }
            case COMBAT_BASIC: {
                player.getInventory().addItem(new ItemStack(Material.DIAMOND_SWORD));
                for (int i = 0; i < PUPPET_COUNT; i++) {
                    Location location = new Location(world,
                            (Math.random() - 0.5) * 20, BountyWorld.PLATFORM_HEIGHT + 1, (Math.random() - 0.5) * 20);
                    Zombie zombie = (Zombie) world.spawnEntity(location, EntityType.ZOMBIE);
                    zombie.setCustomName(ChatColor.RED + "测试傀儡 " + (i + 1));
                    zombie.setCustomNameVisible(true);
                    // 让傀儡跑起来：站桩目标无法区分"杀戮光环"与"站着不动被打"
                    try {
                        zombie.setTarget(player);
                    } catch (Throwable ignored) {
                    }
                    spawnedEntities.add(zombie);
                }
                player.sendMessage(ChatColor.GRAY + "已生成 " + PUPPET_COUNT + " 个傀儡。目标：10 秒内全部击杀");
                break;
            }
            case COMBAT_ADVANCED: {
                player.getInventory().addItem(new ItemStack(Material.DIAMOND_SWORD));
                Location location = new Location(world, 0.5, BountyWorld.PLATFORM_HEIGHT + 1, 6.5);
                try {
                    ArmorStand stand = (ArmorStand) world.spawnEntity(location, EntityType.ARMOR_STAND);
                    stand.setVisible(false);
                    stand.setCustomName(ChatColor.GRAY + "幽灵实体");
                    try {
                        stand.setGravity(false);
                    } catch (Throwable ignored) {
                        // 老版本没有 setGravity，位置固定与否不影响锁定判定
                    }
                    ghost = stand;
                    spawnedEntities.add(stand);
                    player.sendMessage(ChatColor.GRAY + "幽灵实体已生成（不可见）。目标：准星锁定累计 3 秒");
                } catch (Throwable t) {
                    plugin.getLogger().warning("[Bounty] 生成幽灵实体失败: " + t.getMessage());
                    player.sendMessage(ChatColor.RED + "本服务端无法生成幽灵实体，该任务无法完成（请换用其他任务）");
                }
                break;
            }
            case INVENTORY_CHALLENGE: {
                player.getInventory().addItem(new ItemStack(VersionUtil.compatTotemOrFallback()));
                player.sendMessage(ChatColor.GRAY + "目标：3 秒内完成 " + FAST_SWAP_CLICKS + " 次背包交互");
                break;
            }
            case MOVE_ADVANCED:
                player.sendMessage(ChatColor.GRAY + "目标：在空中完成一次直角变向（离地 ≥ "
                        + AIR_TURN_MIN_AIR_TICKS + " tick 且偏航变化 ≥ " + (int) AIR_TURN_MIN_DEGREES + "°）");
                break;
            case FREE_TEST:
            default:
                player.getInventory().addItem(new ItemStack(Material.DIAMOND_SWORD));
                player.getInventory().addItem(new ItemStack(Material.BOW));
                player.getInventory().addItem(new ItemStack(Material.ARROW, 64));
                player.getInventory().addItem(new ItemStack(VersionUtil.compatTotemOrFallback()));
                player.getInventory().addItem(new ItemStack(Material.GOLDEN_APPLE, 5));
                for (int i = 0; i < 3; i++) {
                    Location location = new Location(world,
                            (Math.random() - 0.5) * 15, BountyWorld.PLATFORM_HEIGHT + 1, (Math.random() - 0.5) * 15);
                    spawnedEntities.add(world.spawnEntity(location, EntityType.ZOMBIE));
                }
                player.sendMessage(ChatColor.GRAY + "自由尝试任何作弊功能；出现系统未记录的异常模式会被判为高危发现");
                break;
        }
    }

    private void markPoint(Location location, Material material, DyeColor dye, String label) {
        World world = location.getWorld();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Block block = world.getBlockAt(location.getBlockX() + dx,
                        location.getBlockY() - 1, location.getBlockZ() + dz);
                block.setType(material);
                // 1.8 的 WOOL 需要 setData 上色；高版本已是彩色枚举，内部自动跳过
                VersionUtil.applyDyeColorIfLegacyWool(block, material, dye);
            }
        }
        player.sendMessage(ChatColor.GOLD + "[" + label + "] " + fmt(location));
    }

    // ------------------------------------------------------------------ 目标追踪

    private void trackObjective() {
        if (currentTask == null || objectiveMet) return;
        switch (currentTask.getObjectiveType()) {
            case REACH_POINT:
                if (pointB != null && sameWorld(pointB)
                        && horizontalDistance(player.getLocation(), pointB) <= REACH_POINT_RADIUS) {
                    objectiveMet = true;
                    evidence.event("[OBJECTIVE] 已到达 B 点");
                }
                break;
            case AIR_TURN: {
                if (!player.isOnGround()) {
                    if (airTicks == 0) airStartYaw = player.getLocation().getYaw();
                    airTicks++;
                    double turn = Math.abs(normalizeDegrees(player.getLocation().getYaw() - airStartYaw));
                    if (turn > maxAirTurn) maxAirTurn = turn;
                    if (airTicks >= AIR_TURN_MIN_AIR_TICKS && maxAirTurn >= AIR_TURN_MIN_DEGREES) {
                        objectiveMet = true;
                        evidence.event("[OBJECTIVE] 空中变向 " + Math.round(maxAirTurn) + "° / 离地 " + airTicks + " tick");
                    }
                }
                // 落地即重新计（"空中直角变向"必须是同一次滞空里完成的）
                if (player.isOnGround()) {
                    airTicks = 0;
                    maxAirTurn = 0.0;
                }
                break;
            }
            case KILL_ALL:
                if (kills >= PUPPET_COUNT) {
                    long elapsed = firstKillTick < 0 ? 0 : sampleSeq - firstKillTick;
                    if (elapsed <= KILL_WINDOW_TICKS) {
                        objectiveMet = true;
                        evidence.event("[OBJECTIVE] 击杀 " + kills + " 个傀儡，用时 " + (elapsed / 20.0) + " 秒");
                    } else {
                        evidence.event("[OBJECTIVE] 击杀完成但超过 10 秒窗口（" + (elapsed / 20.0) + " 秒）");
                    }
                }
                break;
            case LOCK_GHOST:
                if (ghost != null && !ghost.isDead() && isLookingAt(ghost)) {
                    ghostLockTicks++;
                    if (ghostLockTicks >= GHOST_LOCK_TICKS) {
                        objectiveMet = true;
                        evidence.event("[OBJECTIVE] 锁定幽灵实体 " + (ghostLockTicks / 20.0) + " 秒");
                    }
                }
                break;
            case FAST_SWAP:
                while (!swapClicks.isEmpty() && sampleSeq - swapClicks.peekFirst() > FAST_SWAP_WINDOW_TICKS) {
                    swapClicks.pollFirst();
                }
                if (swapClicks.size() >= FAST_SWAP_CLICKS) {
                    objectiveMet = true;
                    evidence.event("[OBJECTIVE] " + FAST_SWAP_WINDOW_TICKS / 20 + " 秒内完成 " + swapClicks.size() + " 次背包交互");
                }
                break;
            case NONE:
            default:
                break;
        }
    }

    /** 监听器转发的击杀事件。 */
    public void onKill(Entity entity) {
        if (currentTask == null) return;
        if (spawnedEntities.contains(entity)) {
            if (kills == 0) firstKillTick = sampleSeq;
            kills++;
            evidence.event("[KILL] " + entity.getType().name() + "（累计 " + kills + "）");
        }
    }

    /** 监听器转发的背包点击。 */
    public void onInventoryClick() {
        if (currentTask == null) return;
        if (currentTask.getObjectiveType() != BountyTaskType.Objective.FAST_SWAP) return;
        swapClicks.addLast(sampleSeq);
    }

    /** 核心层回调：沙箱内一次检测命中。 */
    @Override
    public void onDetection(UUID playerUuid, String checkName, double vl, double delta) {
        detectionFlags++;
        if (vl > maxVl) maxVl = vl;
        if (detectedChecks.size() < 32) detectedChecks.add(checkName);
        evidence.event("[DETECT] " + checkName + " VL=" + String.format("%.2f", vl)
                + " delta=" + String.format("%.2f", delta));
    }

    // ------------------------------------------------------------------ 判定

    /** 任务到时（或玩家主动结束）：判定并结算。 */
    public void finishTask(String reason) {
        if (currentTask == null) return;
        BountyTaskType task = currentTask;

        Map<String, Double> metrics = BountyMetrics.compute(new ArrayList<>(samples));
        BaselineModel model = manager.baselineModel();
        AnomalyResult anomaly = model.anomaly(metrics);

        Boolean objective = task.getObjectiveType() == BountyTaskType.Objective.NONE
                ? null : Boolean.valueOf(objectiveMet);
        BountyTuning tuning = manager.tuning();
        JudgeResult result = BountyJudge.judge(
                new JudgeInput(detectionFlags, maxVl, objective, anomaly), tuning, task.getBounty());

        evidence.event("[JUDGE] " + result.getVerdict().name() + "（" + result.getReason() + "）");
        evidence.event("[JUDGE] 采样 " + samples.size() + " 条，命中检测 " + detectionFlags
                + " 次，最高 VL " + String.format("%.2f", maxVl));

        // 事件要在落盘**之前**写：写完之后再加的行不会出现在文件里。
        evidence.event("[SESSION] 结束任务，原因：" + reason);

        // 证据落盘（最多上万行 CSV）与案例落库都交给异步线程：
        // 主线程做文件 IO + 数据库往返会直接吃掉 tick，而这里正好在战斗任务的收尾路径上。
        // 交给异步的是**本任务专属**的 evidence 对象（下面立刻换一个新的），
        // 所以之后会话再追加的事件不会串进已经交付的那份。
        BountyEvidence finished = evidence;
        evidence = new BountyEvidence();
        manager.persistTaskResult(new BountyManager.TaskOutcome(
                uuid, player.getName(), task, result, anomaly, metrics,
                baselineText(model, metrics), detectionFlags, maxVl, samples.size(), finished));

        announce(task, result);
        // 清理现场
        clearSpawnedEntities();
        if (ghost != null) {
            try {
                ghost.remove();
            } catch (Throwable ignored) {
            }
            ghost = null;
        }
        currentTask = null;
        pointA = null;
        pointB = null;
        resetObjectiveState();
        samples.clear();
        sampleSeq = 0;
        detectionFlags = 0;
        maxVl = 0.0;
        detectedChecks.clear();
    }

    private void announce(BountyTaskType task, JudgeResult result) {
        BountyVerdict verdict = result.getVerdict();
        switch (verdict) {
            case DETECTED:
                player.sendMessage(ChatColor.YELLOW + "你的操作已被现有检测识别（" + result.getReason()
                        + "）。感谢参与，保底赏金 " + result.getReward() + " 代币已发放。");
                break;
            case BYPASSED:
            case ZERO_DAY:
                player.sendMessage(ChatColor.GREEN + "恭喜！判定为「" + verdict.getDisplayName()
                        + "」，赏金 " + result.getReward() + " 代币已发放。");
                player.sendMessage(ChatColor.GRAY + "依据：" + result.getReason());
                break;
            case INCONCLUSIVE:
            default:
                player.sendMessage(ChatColor.GRAY + "本次没有产生结论（" + result.getReason() + "），未发放赏金。");
                break;
        }
    }

    private static Map<String, String> baselineText(BaselineModel model, Map<String, Double> metrics) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        Map<String, MetricBaseline> baselines = model.getBaselines();
        for (String key : metrics.keySet()) {
            MetricBaseline baseline = baselines.get(key);
            if (baseline == null) continue;
            out.put(key, String.format("%.5f ± %.5f（n=%d）",
                    baseline.getMean(), baseline.getSd(), baseline.getSamples()));
        }
        return out;
    }

    // ------------------------------------------------------------------ 退出

    /** 退出沙箱：恢复暂存、解除标记、送回原位置。 */
    public void end() {
        if (currentTask != null) {
            finishTask("玩家离开沙箱");
        }

        // 1) 解除沙箱标记（必须在恢复/传送之前：之后的状态变化不再算沙箱行为）
        BountyHooks.leaveSandbox(uuid);

        // 2) 还原床重生点
        restoreBedSpawn();

        // 3) 用快照覆盖当前状态：同时完成"销毁沙箱所得"与"归还原物"
        try {
            inventoryBackup.restore(player);
        } catch (Throwable t) {
            plugin.getLogger().severe("[Bounty] 还原 " + player.getName() + " 的背包失败，物品仍在快照中: " + t.getMessage());
            player.sendMessage(ChatColor.RED + "背包还原出错，请联系管理员（你的物品已被安全暂存）");
        }

        // 4) 二次清洗：把生命值/饥饿/着火/效果全部重置，
        //    避免沙箱内的临时状态残留到主世界
        try {
            player.setFireTicks(0);
            player.setFallDistance(0f);
            player.setFoodLevel(20);
            player.setSaturation(5f);
            player.setHealth(Math.min(player.getMaxHealth(), 20.0));
            player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
            player.setAllowFlight(false);
            player.setFlying(false);
            player.setWalkSpeed(0.2f);
            player.setFlySpeed(0.1f);
            player.updateInventory();
        } catch (Throwable ignored) {
        }

        // 5) 送回原位（死亡时 teleport 会被服务端取消，交给监听器在下一次复活时补送）
        boolean dead;
        try {
            dead = player.isDead() || player.getHealth() <= 0;
        } catch (Throwable t) {
            dead = false;
        }
        if (dead) {
            manager.putPendingRespawnBackup(uuid, originalLocation);
        } else {
            player.teleport(originalLocation);
            player.setFallDistance(0f);
        }

        clearSpawnedEntities();
        manager.recordSessionTime(this);
        evidence.event("[SESSION] 退出沙箱，用时 " + timeSpentSeconds + " 秒");
        player.sendMessage(ChatColor.GREEN + "已退出漏洞赏金沙箱，背包与经验已还原。");
    }

    private void clearSpawnedEntities() {
        for (Entity entity : new ArrayList<>(spawnedEntities)) {
            try {
                entity.remove();
            } catch (Throwable ignored) {
            }
        }
        spawnedEntities.clear();
    }

    // ------------------------------------------------------------------ 工具

    private static Location safeBedSpawn(Player player) {
        try {
            return player.getBedSpawnLocation();
        } catch (Throwable t) {
            return null;
        }
    }

    private void applyBedSpawn(Location location) {
        try {
            player.setBedSpawnLocation(location, true);
        } catch (Throwable t) {
            try {
                player.setBedSpawnLocation(location);
            } catch (Throwable ignored) {
                // 1.8 的某些实现只接受无 force 版本；都失败就不设，影响很小
            }
        }
    }

    private void restoreBedSpawn() {
        try {
            if (originalBedSpawn != null) {
                applyBedSpawn(originalBedSpawn);
            } else {
                // 原本没有床重生点：清成 null（高版本支持），失败则退回主世界默认点
                try {
                    player.setBedSpawnLocation(null, true);
                } catch (Throwable t) {
                    try {
                        player.setBedSpawnLocation(null);
                    } catch (Throwable ignored) {
                        Location fallback = Bukkit.getWorlds().isEmpty()
                                ? null : Bukkit.getWorlds().get(0).getSpawnLocation();
                        if (fallback != null) applyBedSpawn(fallback);
                    }
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[Bounty] 恢复重生点失败: " + t.getMessage());
        }
    }

    private boolean sameWorld(Location other) {
        return other.getWorld() != null && other.getWorld().equals(player.getWorld());
    }

    private static double horizontalDistance(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double normalizeDegrees(double value) {
        double v = value % 360.0;
        if (v >= 180.0) v -= 360.0;
        if (v < -180.0) v += 360.0;
        return v;
    }

    /** 准星是否对着目标（夹角小于阈值）。 */
    private boolean isLookingAt(Entity target) {
        try {
            Location eye = player.getEyeLocation();
            Vector look = eye.getDirection();
            Vector toTarget = target.getLocation().add(0, 0.9, 0).toVector().subtract(eye.toVector());
            if (look.lengthSquared() < 1.0e-6 || toTarget.lengthSquared() < 1.0e-6) return false;
            double degrees = Math.toDegrees(look.angle(toTarget));
            return degrees <= GHOST_LOCK_MAX_ANGLE;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String fmt(Location location) {
        return "(" + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ() + ")";
    }

    // ------------------------------------------------------------------ 读取

    public Player getPlayer() {
        return player;
    }

    public UUID getUuid() {
        return uuid;
    }

    public BountyTaskType getCurrentTask() {
        return currentTask;
    }

    public boolean isInTask() {
        return currentTask != null;
    }

    public long getTimeSpentSeconds() {
        return timeSpentSeconds;
    }

    public long getStartMillis() {
        return startMillis;
    }

    public Location getOriginalLocation() {
        return originalLocation;
    }

    public CaptchaInventoryBackup getInventoryBackup() {
        return inventoryBackup;
    }

    public BountyEvidence getEvidence() {
        return evidence;
    }

    public int getDetectionFlags() {
        return detectionFlags;
    }

    public double getMaxVl() {
        return maxVl;
    }

    public List<String> getDetectedChecks() {
        return new ArrayList<>(detectedChecks);
    }

    public int getSampleCount() {
        return samples.size();
    }

    public List<BountySample> snapshotSamples() {
        return new ArrayList<>(samples);
    }

    /** 供 `/bounty status` 展示。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(player.getName()).append("：已用 ").append(timeSpentSeconds).append("s/").append(maxSeconds).append("s");
        sb.append("，采样 ").append(samples.size());
        sb.append("，命中检测 ").append(detectionFlags).append(" 次（最高 VL ")
                .append(String.format("%.2f", maxVl)).append("）");
        if (currentTask != null) {
            sb.append("，任务 ").append(currentTask.getDisplayName())
                    .append(objectiveMet ? "（目标已达成）" : "（目标未达成）");
        }
        return sb.toString();
    }

    /** 数据目录（证据用）。 */
    public AdvancedAntiCheat plugin() {
        return plugin;
    }
}
