package com.anticheat.captcha.tasks;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.captcha.dtw.DtwMatcher;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动作模仿验证码（/captcha 随机检测项之一）。
 *
 * <p>流程：
 * <ol>
 *   <li>随机生成一组动作序列（跳跃 / 左转 / 右转 / 疾跑一小段 / 潜行），先后顺序随机；</li>
 *   <li>把序列**直接以文字形式发到聊天框**（不依赖任何实体演示）；</li>
 *   <li>采集玩家在接下来若干秒内的动作输入序列（每项带起止时间戳）；</li>
 *   <li>用动态时间扭曲（DTW）与模板序列对齐比较 —— 允许人类反应/节奏误差，
 *       但对"做错动作 / 漏做动作 / 多做动作"给出相应代价。</li>
 * </ol>
 *
 * <p>为什么是"输入序列 + DTW"而不是"逐点时间对齐"：人类做一串动作的节奏天然是
 * 抖动的（第一步可能慢半拍、最后一步可能抢拍），逐点比对会把正常人类判成机器人。
 *
 * <p>本类所有状态都走主线程（BukkitRunnable + 事件回调），不做跨线程同步。
 * 玩家输入采用"分段"而不是"逐 tick"记录：一次连续转身=一个 TURN 动作，
 * 一次连续位移=一个 SPRINT/WALK 动作，一次按下潜行到松开=一个 SNEAK 动作。
 */
public class TypeB_MotionMimicry extends CaptchaTask {

    /** 动作词表。题目与玩家输入共用同一套语义。 */
    public enum ActionType {
        JUMP("跳跃"),
        TURN_LEFT("左转"),
        TURN_RIGHT("右转"),
        SPRINT("疾跑"),
        WALK("行走"),
        SNEAK("潜行");

        private final String label;

        ActionType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    // ================================================================
    // 可调参数（config.yml -> captcha.tasks.motion-mimicry）
    // ================================================================

    private boolean enabled = true;
    private int minActions = 3;
    private int maxActions = 4;
    private int maxAttempts = 2;
    private double maxDistance = 0.17;
    private double durationWeight = 0.25;
    private double missingPenalty = 1.0;
    private double extraPenalty = 0.5;
    private double turnCommitDegrees = 45.0;
    private double turnRateThreshold = 1.0;
    private long minActionMs = 250L;
    private double minMoveBlocks = 0.6;
    private double windowMultiplier = 2.2;
    private long windowMinMs = 6000L;
    private long windowMaxMs = 14000L;

    /** 题目落聊天框后留一点阅读时间再开采集窗口。 */
    private static final long READ_LEAD_IN_TICKS = 40L;
    private static final long RETRY_DELAY_TICKS = 40L;
    private static final int MAX_RECORDED_INPUTS = 40;

    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final Random random = new Random();

    public TypeB_MotionMimicry(AdvancedAntiCheat plugin) {
        super(plugin);
    }

    // ================================================================
    // CaptchaTask
    // ================================================================

    @Override
    public void start(Player player, Location location) {
        loadConfig();
        if (!enabled) {
            // 配置关闭时本不该被分配到；兜底直接放行，避免玩家卡在验证码里出不去
            plugin.getCaptchaManager().completeTask(player);
            return;
        }

        State state = new State(player.getUniqueId());
        state.platformCenter = location.clone();
        state.template = buildTemplate();
        states.put(state.uuid, state);

        announceInstructions(player);
        announceTemplate(player, state);
        player.sendMessage("§7请在 §e2 §7秒后按 **从上到下** 的顺序完成这些动作。");

        startWindow(player, state, READ_LEAD_IN_TICKS);
    }

    @Override
    public void cleanup(Player player) {
        State state = player == null ? null : states.remove(player.getUniqueId());
        if (state == null) {
            return;
        }
        state.recording = false;
        cancel(state.windowTask);
        state.windowTask = null;
    }

    @Override
    public String getTaskDescription() {
        return "动作模仿任务";
    }

    @Override
    public boolean isCompleted(Player player) {
        return false;
    }

    // ================================================================
    // 题目生成与播报
    // ================================================================

    /**
     * 随机生成动作序列。
     * 约束：动作数 [minActions, maxActions]；不出现连续重复动作；位移类动作（疾跑/行走）最多 1 个
     * （验证码平台只有 10×10，连续位移会把玩家顶到屏障上）。
     */
    private List<Step> buildTemplate() {
        int lo = Math.max(2, Math.min(minActions, maxActions));
        int hi = Math.max(lo, Math.max(minActions, maxActions));
        int count = lo + (hi > lo ? random.nextInt(hi - lo + 1) : 0);

        List<Step> steps = new ArrayList<>();
        ActionType previous = null;
        boolean movementUsed = false;

        for (int i = 0; i < count; i++) {
            ActionType type = pickAction(previous, movementUsed);
            if (type == null) {
                break;
            }
            if (type == ActionType.SPRINT || type == ActionType.WALK) {
                movementUsed = true;
            }
            long base = baseDuration(type);
            long jittered = base + (long) ((random.nextDouble() - 0.5) * 0.3 * base);
            steps.add(new Step(type, Math.max(350L, jittered)));
            previous = type;
        }
        if (steps.isEmpty()) {
            steps.add(new Step(ActionType.JUMP, baseDuration(ActionType.JUMP)));
        }
        return steps;
    }

    private ActionType pickAction(ActionType previous, boolean movementUsed) {
        for (int attempt = 0; attempt < 12; attempt++) {
            ActionType candidate = ActionType.values()[random.nextInt(ActionType.values().length)];
            if (candidate == previous) {
                continue;
            }
            if (movementUsed && (candidate == ActionType.SPRINT || candidate == ActionType.WALK)) {
                continue;
            }
            // 直立动作里也别出现"跳完立刻跳"以外的语义冲突；这里只需避开连续重复
            return candidate;
        }
        return null;
    }

    private long baseDuration(ActionType type) {
        switch (type) {
            case JUMP:
                return 700L;
            case TURN_LEFT:
            case TURN_RIGHT:
                return 800L;
            case SPRINT:
                return 900L;
            case WALK:
                return 1100L;
            case SNEAK:
            default:
                return 800L;
        }
    }

    /** 动作 → 玩家可执行操作的对照说明。 */
    private void announceInstructions(Player player) {
        player.sendMessage("§7动作对照：§b向上跳§7=跳跃　§b左/右转身 90°§7=左转/右转　"
                + "§b按住 §fW §7快跑一小段§7=疾跑　§b蹲下再站起§7=潜行");
    }

    /** 把随机动作序列直接打在聊天框里，作为玩家的题目。 */
    private void announceTemplate(Player player, State state) {
        sendInstruction(player, "请按以下顺序依次完成动作（共 " + state.template.size() + " 个）:");
        for (int i = 0; i < state.template.size(); i++) {
            Step step = state.template.get(i);
            player.sendMessage("  §e" + (i + 1) + ". §f" + step.type.label()
                    + "§8（约 " + String.format("%.1f", step.durationMs / 1000.0d) + " 秒）");
        }
    }

    // ================================================================
    // 采集窗口
    // ================================================================

    private void startWindow(Player player, State state, long delayTicks) {
        State live = states.get(state.uuid);
        if (live != state) {
            return;
        }
        state.recording = false;

        new BukkitRunnable() {
            @Override
            public void run() {
                Player current = Bukkit.getPlayer(state.uuid);
                if (current == null || !current.isOnline() || states.get(state.uuid) != state) {
                    return;
                }
                long total = 0L;
                for (Step step : state.template) {
                    total += step.durationMs;
                }
                long window = Math.max(windowMinMs,
                        Math.min(windowMaxMs, (long) (total * windowMultiplier) + 2500L));

                resetInputTracking(state);
                state.inputs.clear();
                state.recordStartMs = System.currentTimeMillis();
                state.recordEndMs = state.recordStartMs + window;
                state.lastInputMs = 0L;
                state.recording = true;

                current.sendMessage("§a§l[开始] §f计时开始（§e" + (window / 1000) + " 秒§f），请依次完成上述动作");
                scheduleWindowEnd(current, state);
            }
        }.runTaskLater(plugin, Math.max(1L, delayTicks));
    }

    private void scheduleWindowEnd(Player player, State state) {
        cancel(state.windowTask);
        state.windowTask = new BukkitRunnable() {
            @Override
            public void run() {
                Player current = Bukkit.getPlayer(state.uuid);
                State live = states.get(state.uuid);
                if (current == null || !current.isOnline() || live != state || !state.recording) {
                    cancel();
                    return;
                }
                long now = System.currentTimeMillis();
                tickMaintenance(state, now);
                boolean timeout = now >= state.recordEndMs;
                boolean settled = state.inputs.size() >= state.template.size()
                        && state.lastInputMs > 0L && now - state.lastInputMs >= 1500L;
                boolean overflow = state.inputs.size() > state.template.size() + 2;
                if (timeout || settled || overflow) {
                    cancel();
                    state.windowTask = null;
                    evaluate(current, state);
                }
            }
        };
        state.windowTask.runTaskTimer(plugin, 10L, 10L);
    }

    /** 采集窗口结束：DTW 比对模板与玩家输入序列。 */
    private void evaluate(Player player, State state) {
        if (!state.recording) {
            return;
        }
        state.recording = false;
        flushPending(state, System.currentTimeMillis());

        List<Input> observed = new ArrayList<>(state.inputs);
        double distance = computeDistance(state.template, observed);
        boolean pass = distance <= maxDistance;

        plugin.getLogger().info("[Captcha] 动作模仿判定 " + player.getName()
                + " player=" + player.getUniqueId()
                + " dtw=" + String.format("%.3f", distance)
                + " 阈值=" + String.format("%.2f", maxDistance)
                + " 题目=[" + describeSteps(state.template) + "]"
                + " 输入=[" + describeInputs(observed) + "]"
                + " 第" + (state.attempts + 1) + "次 => " + (pass ? "通过" : "不通过"));

        if (pass) {
            state.finished = true;
            cleanup(player);
            plugin.getCaptchaManager().completeTask(player);
            return;
        }

        state.attempts++;
        if (state.attempts >= maxAttempts) {
            player.sendMessage("§c§l[验证失败] §f动作序列与要求不一致。");
            plugin.getCaptchaManager().failCaptcha(player);
            return;
        }

        player.sendMessage("§c动作序列不匹配（已用 " + state.attempts + "/" + maxAttempts + " 次机会）");
        player.sendMessage("§e重新出题，请注意看聊天框…");
        final State target = state;
        new BukkitRunnable() {
            @Override
            public void run() {
                Player current = Bukkit.getPlayer(target.uuid);
                if (current == null || !current.isOnline() || states.get(target.uuid) != target) {
                    return;
                }
                target.template = buildTemplate();
                announceTemplate(current, target);
                current.sendMessage("§7请在 §e2 §7秒后按 **从上到下** 的顺序完成这些动作。");
                startWindow(current, target, READ_LEAD_IN_TICKS);
            }
        }.runTaskLater(plugin, RETRY_DELAY_TICKS);
    }

    // ================================================================
    // 玩家输入采集（由 CaptchaListener 转发）
    // ================================================================

    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        State state = states.get(player.getUniqueId());
        if (state == null || !state.recording) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from == null || to == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now > state.recordEndMs + 500L) {
            return;
        }

        // ---- 转向：滑动窗口累积，转向"停下来"才结算成一个动作 ----
        double deltaYaw = normalizeDelta(to.getYaw() - from.getYaw());
        if (Math.abs(deltaYaw) >= turnRateThreshold) {
            if (!state.turning) {
                state.turning = true;
                state.turnTotal = 0d;
                state.turnStartMs = now;
            }
            state.turnTotal += deltaYaw;
            state.lastTurnMs = now;
        } else if (state.turning && now - state.lastTurnMs >= 120L) {
            closeTurn(state, now);
        }

        // ---- 跳跃：一次上升沿算一次，落地/超时后解锁 ----
        double deltaY = to.getY() - from.getY();
        if (!state.airborne && deltaY > 0.12d && !player.isFlying() && !player.isInsideVehicle()) {
            addInput(state, ActionType.JUMP, now, now + 250L);
            state.airborne = true;
            state.airborneUntilMs = now + 350L;
        }
        if (state.airborne && now > state.airborneUntilMs && player.isOnGround()) {
            state.airborne = false;
        }

        // ---- 位移：一段连续位移结算成一个 SPRINT/WALK ----
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal > 0.02d) {
            if (!state.moveActive) {
                state.moveActive = true;
                state.moveStartMs = now;
                state.moveDist = 0d;
                state.moveSprint = player.isSprinting();
            }
            state.moveDist += horizontal;
            if (player.isSprinting()) {
                state.moveSprint = true;
            }
            state.moveLastMs = now;
        } else if (state.moveActive && now - state.moveLastMs >= 150L) {
            closeMove(state, now);
        }
    }

    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        State state = states.get(player.getUniqueId());
        if (state == null || !state.recording) {
            return;
        }
        long now = System.currentTimeMillis();
        if (event.isSneaking()) {
            if (!state.sneakActive) {
                state.sneakActive = true;
                state.sneakStartMs = now;
            }
        } else if (state.sneakActive) {
            state.sneakActive = false;
            if (now - state.sneakStartMs >= minActionMs) {
                addInput(state, ActionType.SNEAK, state.sneakStartMs, now);
            }
        }
    }

    /** 周期性收尾：把"已经停下来但事件没再触发"的位移/转向段结算掉。 */
    private void tickMaintenance(State state, long now) {
        if (state.moveActive && now - state.moveLastMs >= 150L) {
            closeMove(state, now);
        }
        if (state.turning && now - state.lastTurnMs >= 120L) {
            closeTurn(state, now);
        }
    }

    private void closeMove(State state, long now) {
        if (!state.moveActive) {
            return;
        }
        state.moveActive = false;
        long end = state.moveLastMs > 0L ? state.moveLastMs : now;
        long duration = end - state.moveStartMs;
        if (state.moveDist >= minMoveBlocks && duration >= minActionMs) {
            addInput(state, state.moveSprint ? ActionType.SPRINT : ActionType.WALK, state.moveStartMs, end);
        }
    }

    private void closeTurn(State state, long now) {
        if (!state.turning) {
            return;
        }
        state.turning = false;
        if (Math.abs(state.turnTotal) >= turnCommitDegrees) {
            long end = Math.max(state.turnStartMs + 50L, state.lastTurnMs);
            addInput(state, state.turnTotal > 0 ? ActionType.TURN_RIGHT : ActionType.TURN_LEFT,
                    state.turnStartMs, end);
        }
    }

    private void flushPending(State state, long now) {
        long end = Math.min(now, state.recordEndMs);
        if (state.moveActive && state.moveLastMs > 0L) {
            state.moveLastMs = Math.min(state.moveLastMs, end);
            closeMove(state, end);
        }
        if (state.turning) {
            state.lastTurnMs = Math.min(Math.max(state.lastTurnMs, state.turnStartMs + 50L), end);
            closeTurn(state, end);
        }
        if (state.sneakActive) {
            state.sneakActive = false;
            long duration = end - state.sneakStartMs;
            if (duration >= minActionMs) {
                addInput(state, ActionType.SNEAK, state.sneakStartMs, end);
            }
        }
    }

    private void addInput(State state, ActionType type, long startMs, long endMs) {
        if (state.inputs.size() >= MAX_RECORDED_INPUTS) {
            return;
        }
        long safeStart = Math.min(startMs, state.recordEndMs);
        long safeEnd = Math.max(safeStart + 1L, Math.min(endMs, state.recordEndMs));
        state.inputs.add(new Input(type, safeStart, safeEnd));
        state.lastInputMs = safeEnd;
    }

    private void resetInputTracking(State state) {
        state.turning = false;
        state.turnTotal = 0d;
        state.turnStartMs = 0L;
        state.lastTurnMs = 0L;
        state.airborne = false;
        state.airborneUntilMs = 0L;
        state.moveActive = false;
        state.moveStartMs = 0L;
        state.moveLastMs = 0L;
        state.moveDist = 0d;
        state.moveSprint = false;
        state.sneakActive = false;
        state.sneakStartMs = 0L;
    }

    // ================================================================
    // DTW 判定
    // ================================================================

    private double computeDistance(final List<Step> template, final List<Input> observed) {
        if (template.isEmpty()) {
            return 0d;
        }
        if (observed.isEmpty()) {
            return 1d;
        }

        long templateTotal = 0L;
        for (Step step : template) {
            templateTotal += step.durationMs;
        }
        long observedTotal = 0L;
        for (Input input : observed) {
            observedTotal += input.durationMs();
        }
        if (templateTotal <= 0L || observedTotal <= 0L) {
            return 1d;
        }

        final double[] templateShare = new double[template.size()];
        for (int i = 0; i < template.size(); i++) {
            templateShare[i] = (double) template.get(i).durationMs / templateTotal;
        }
        final double[] observedShare = new double[observed.size()];
        for (int j = 0; j < observed.size(); j++) {
            observedShare[j] = (double) observed.get(j).durationMs() / observedTotal;
        }

        return DtwMatcher.distance(template.size(), observed.size(), new DtwMatcher.Cost() {
            @Override
            public double cost(int i, int j) {
                double c = actionCost(template.get(i).type, observed.get(j).type);
                // 时长占比按各自序列归一化后比较 → 天然免疫"整体做得比题目慢/快"
                c += durationWeight * Math.abs(templateShare[i] - observedShare[j]);
                return Math.min(1d, c);
            }
        }, missingPenalty, extraPenalty);
    }

    /**
     * 动作对之间的代价。
     * <ul>
     *   <li>同一动作 = 0</li>
     *   <li>疾跑 / 行走 = 0.30（同向位移，只差速度，软惩罚，避免误杀不会冲刺的玩家）</li>
     *   <li>左转 / 右转 = 0.75（同为转身但方向反了）</li>
     *   <li>其余 = 1.0（完全做错）</li>
     * </ul>
     */
    private static double actionCost(ActionType expected, ActionType actual) {
        if (expected == actual) {
            return 0d;
        }
        boolean movementPair = (expected == ActionType.SPRINT && actual == ActionType.WALK)
                || (expected == ActionType.WALK && actual == ActionType.SPRINT);
        if (movementPair) {
            return 0.30d;
        }
        boolean turnPair = (expected == ActionType.TURN_LEFT && actual == ActionType.TURN_RIGHT)
                || (expected == ActionType.TURN_RIGHT && actual == ActionType.TURN_LEFT);
        if (turnPair) {
            return 0.75d;
        }
        return 1d;
    }

    // ================================================================
    // 工具
    // ================================================================

    private static double normalizeDelta(double delta) {
        double result = delta % 360d;
        if (result < -180d) {
            result += 360d;
        } else if (result >= 180d) {
            result -= 360d;
        }
        return result;
    }

    private void cancel(BukkitRunnable task) {
        if (task == null) {
            return;
        }
        try {
            task.cancel();
        } catch (Throwable ignored) {
        }
    }

    private String describeSteps(List<Step> steps) {
        StringBuilder sb = new StringBuilder();
        for (Step step : steps) {
            if (sb.length() > 0) {
                sb.append(" > ");
            }
            sb.append(step.type.label()).append('(').append(step.durationMs).append("ms)");
        }
        return sb.toString();
    }

    private String describeInputs(List<Input> inputs) {
        StringBuilder sb = new StringBuilder();
        for (Input input : inputs) {
            if (sb.length() > 0) {
                sb.append(" > ");
            }
            sb.append(input.type.label()).append('(').append(input.durationMs()).append("ms)");
        }
        return sb.toString();
    }

    private void loadConfig() {
        try {
            String base = "captcha.tasks.motion-mimicry.";
            enabled = plugin.getConfig().getBoolean(base + "enabled", true);
            minActions = plugin.getConfig().getInt(base + "min-actions", 3);
            maxActions = plugin.getConfig().getInt(base + "max-actions", 4);
            maxAttempts = Math.max(1, plugin.getConfig().getInt(base + "max-attempts", 2));
            maxDistance = plugin.getConfig().getDouble(base + "max-distance", 0.17);
            durationWeight = plugin.getConfig().getDouble(base + "duration-weight", 0.25);
            missingPenalty = plugin.getConfig().getDouble(base + "missing-penalty", 1.0);
            extraPenalty = plugin.getConfig().getDouble(base + "extra-penalty", 0.5);
            turnCommitDegrees = plugin.getConfig().getDouble(base + "turn-commit-degrees", 45.0);
            turnRateThreshold = plugin.getConfig().getDouble(base + "turn-rate-threshold", 1.0);
            minActionMs = plugin.getConfig().getLong(base + "min-action-ms", 250L);
            minMoveBlocks = plugin.getConfig().getDouble(base + "min-move-blocks", 0.6);
            windowMultiplier = plugin.getConfig().getDouble(base + "window-multiplier", 2.2);
            windowMinMs = plugin.getConfig().getLong(base + "window-min-ms", 6000L);
            windowMaxMs = plugin.getConfig().getLong(base + "window-max-ms", 14000L);
        } catch (Throwable t) {
            plugin.getLogger().warning("[Captcha] 读取动作模仿配置失败，使用默认值: " + t.getMessage());
        }
    }

    // ================================================================
    // 数据结构
    // ================================================================

    private static final class Step {
        private final ActionType type;
        private final long durationMs;

        private Step(ActionType type, long durationMs) {
            this.type = type;
            this.durationMs = durationMs;
        }
    }

    private static final class Input {
        private final ActionType type;
        private final long startMs;
        private final long endMs;

        private Input(ActionType type, long startMs, long endMs) {
            this.type = type;
            this.startMs = startMs;
            this.endMs = endMs;
        }

        private long durationMs() {
            return Math.max(1L, endMs - startMs);
        }
    }

    private static final class State {
        private final UUID uuid;
        private List<Step> template = new ArrayList<>();
        private final List<Input> inputs = new ArrayList<>();
        private Location platformCenter;

        private boolean recording;
        private boolean finished;
        private int attempts;
        private long recordStartMs;
        private long recordEndMs;
        private long lastInputMs;

        private BukkitRunnable windowTask;

        private boolean turning;
        private double turnTotal;
        private long turnStartMs;
        private long lastTurnMs;

        private boolean airborne;
        private long airborneUntilMs;

        private boolean moveActive;
        private long moveStartMs;
        private long moveLastMs;
        private double moveDist;
        private boolean moveSprint;

        private boolean sneakActive;
        private long sneakStartMs;

        private State(UUID uuid) {
            this.uuid = uuid;
        }
    }
}
