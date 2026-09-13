package com.anticheat.ai;

import com.anticheat.ai.baseline.OnlineKMeans;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个玩家的 AI 实验室全部状态（目标占用 &lt; 5KB）。
 * <p>
 * 内存布局：
 * <ul>
 *   <li>主线程快照环形缓冲：30 样本 × 8 值 ≈ 1.9KB</li>
 *   <li>事件时间戳环形缓冲：3 × 16 × 8B ≈ 0.4KB</li>
 *   <li>K-Means 个人基线中心：3 中心 × 48 维 × 8B ≈ 1.2KB</li>
 *   <li>最新特征向量：48 × 8B ≈ 0.4KB</li>
 * </ul>
 * <p>
 * 线程约定：快照/计数器由主线程写、异步线程读（volatile 发布）；
 * K-Means 仅由异步特征线程访问；分数字段由异步线程写、Web 线程读。
 */
public class PlayerAIState {

    /** 快照值布局下标。 */
    public static final int SNAP_DX = 0, SNAP_DY = 1, SNAP_DZ = 2, SNAP_YAW = 3,
            SNAP_PITCH = 4, SNAP_GROUND = 5, SNAP_SPRINT = 6, SNAP_SNEAK = 7;
    public static final int SNAP_VALUES = 8;
    public static final int SNAP_CAPACITY = 30;

    /** 玩家 UUID。 */
    private final String uuid;
    /** 玩家名（join 时刷新）。 */
    private volatile String name;

    // ===== 主线程写入区 =====
    /** 快照环形缓冲（扁平数组，[idx * SNAP_VALUES + field]）。 */
    private final double[] snapshots = new double[SNAP_CAPACITY * SNAP_VALUES];
    /** 最近一次快照时间戳。 */
    private volatile long lastSnapshotAt;
    /** 有效快照数（<= SNAP_CAPACITY）。 */
    private volatile int snapCount;
    /** 写指针。 */
    private volatile int snapWriteIdx;
    /** 上一次 onGround 状态（跳跃检测）。 */
    private boolean prevGround = true;
    /** 上一次潜行/疾跑状态。 */
    private boolean prevSneak = false;
    private boolean prevSprint = false;

    /** 事件计数器（1 秒窗口，由异步线程消费后清零）。 */
    final AtomicLong cAttack = new AtomicLong();
    final AtomicLong cHit = new AtomicLong();
    final AtomicLong cBlockBreak = new AtomicLong();
    final AtomicLong cBlockPlace = new AtomicLong();
    final AtomicLong cInvClick = new AtomicLong();
    final AtomicLong cInvShiftClick = new AtomicLong();
    final AtomicLong cContainerOpen = new AtomicLong();
    final AtomicLong cChat = new AtomicLong();
    final AtomicLong cCommand = new AtomicLong();
    final AtomicLong cJump = new AtomicLong();
    final AtomicLong cSneakToggle = new AtomicLong();
    final AtomicLong cSprintToggle = new AtomicLong();
    final AtomicLong cAttackMoving = new AtomicLong();
    final AtomicLong cMoveEvents = new AtomicLong();

    /** 主线程快照辅助状态。 */
    volatile double prevYRef = Double.NaN;
    volatile double prevYawRef = Double.NaN;

    /** 挖掘/放置累计（主线程写、异步读改写，宽松一致性可接受）。 */
    volatile double lastBreakY;
    volatile double breakYSum;
    volatile long breakYCount;
    volatile double lastPlaceRelY;
    volatile double placeRelYSum;
    volatile long placeRelYCount;

    /** join 时间（会话时长特征）。 */
    volatile long joinedAt = System.currentTimeMillis();
    /** 最近一秒 move 事件数（网络密度代理）。 */
    volatile long moveEventRate;

    /** 攻击/破坏/放置时间戳环形缓冲（毫秒）。 */
    final long[] attackTimes = new long[16];
    final long[] breakTimes = new long[16];
    final long[] placeTimes = new long[16];
    volatile int attackTimesIdx, breakTimesIdx, placeTimesIdx;
    volatile int attackTimesN, breakTimesN, placeTimesN;

    /** 攻击目标切换统计：上次目标实体 ID。 */
    volatile int lastTargetId = -1;
    volatile int targetSwitchCount;
    /** 攻击角度/距离累计（本次窗口）。 */
    volatile double attackAngleSum, attackDistanceSum;

    // ===== 异步线程写入 / 多线程读 =====
    private volatile FeatureVector lastFeatures;
    private volatile OnlineKMeans kmeans;
    /** 个人异常分 ∈ [0,1]，-1 = 样本不足（warmup）。 */
    private volatile double personalScore = -1.0;
    /** 全局异常分 ∈ [0,1]。 */
    private volatile double globalScore = 0.0;
    /** 监督模型作弊概率 ∈ [0,1]，-1 = 无活跃模型。 */
    private volatile double supervisedScore = -1.0;
    /** 融合 AI 分 ∈ [0,1]。 */
    private volatile double fusedScore = 0.0;
    /** 静默观察标记（融合分进入 0.5~0.9 区间）。 */
    private volatile boolean watchlisted;
    /** 最近一次全局评分时间。 */
    private volatile long lastScoredAt;

    public PlayerAIState(String uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    // ===== 主线程快照接口 =====

    /**
     * 追加一次 1 秒快照（主线程调用）。
     *
     * @param dx dy dz 位移分量（本秒累计）
     * @param yawDelta   本秒累计 yaw 变化绝对值
     * @param pitch      当前 pitch
     * @param onGround   是否在地面
     * @param sprinting  是否疾跑
     * @param sneaking   是否潜行
     */
    public synchronized void pushSnapshot(double dx, double dy, double dz,
                                          double yawDelta, double pitch,
                                          boolean onGround, boolean sprinting, boolean sneaking) {
        int idx = snapWriteIdx;
        int base = idx * SNAP_VALUES;
        snapshots[base + SNAP_DX] = dx;
        snapshots[base + SNAP_DY] = dy;
        snapshots[base + SNAP_DZ] = dz;
        snapshots[base + SNAP_YAW] = yawDelta;
        snapshots[base + SNAP_PITCH] = pitch;
        snapshots[base + SNAP_GROUND] = onGround ? 1 : 0;
        snapshots[base + SNAP_SPRINT] = sprinting ? 1 : 0;
        snapshots[base + SNAP_SNEAK] = sneaking ? 1 : 0;
        // 地面→空中 转变计为一次跳跃
        if (prevGround && !onGround) {
            cJump.incrementAndGet();
        }
        if (!prevSneak && sneaking || prevSneak && !sneaking) {
            cSneakToggle.incrementAndGet();
        }
        if (!prevSprint && sprinting || prevSprint && !sprinting) {
            cSprintToggle.incrementAndGet();
        }
        prevGround = onGround;
        prevSneak = sneaking;
        prevSprint = sprinting;

        snapWriteIdx = (idx + 1) % SNAP_CAPACITY;
        snapCount = Math.min(SNAP_CAPACITY, snapCount + 1);
        lastSnapshotAt = System.currentTimeMillis();
    }

    /**
     * 读取快照缓冲的一致性视图（异步线程调用）。
     * 返回按时间序排列的 [n][SNAP_VALUES] 二维数组拷贝。
     */
    public synchronized double[][] readSnapshots() {
        int n = snapCount;
        double[][] out = new double[n][SNAP_VALUES];
        int start = (snapWriteIdx - n + SNAP_CAPACITY) % SNAP_CAPACITY;
        for (int i = 0; i < n; i++) {
            int src = ((start + i) % SNAP_CAPACITY) * SNAP_VALUES;
            out[i] = Arrays.copyOfRange(snapshots, src, src + SNAP_VALUES);
        }
        return out;
    }

    public synchronized void recordAttackTime(long ts) {
        attackTimes[attackTimesIdx] = ts;
        attackTimesIdx = (attackTimesIdx + 1) % attackTimes.length;
        if (attackTimesN < attackTimes.length) attackTimesN++;
    }

    public synchronized void recordBreakTime(long ts) {
        breakTimes[breakTimesIdx] = ts;
        breakTimesIdx = (breakTimesIdx + 1) % breakTimes.length;
        if (breakTimesN < breakTimes.length) breakTimesN++;
    }

    public synchronized void recordPlaceTime(long ts) {
        placeTimes[placeTimesIdx] = ts;
        placeTimesIdx = (placeTimesIdx + 1) % placeTimes.length;
        if (placeTimesN < placeTimes.length) placeTimesN++;
    }

    /** 复制事件时间戳环形缓冲（按时间序）。 */
    public synchronized long[] readRing(long[] ring, int idx, int n) {
        long[] out = new long[n];
        int start = (idx - n + ring.length) % ring.length;
        for (int i = 0; i < n; i++) {
            out[i] = ring[(start + i) % ring.length];
        }
        return out;
    }

    /** 每秒特征计算完成后由异步线程清空窗口内计数（读改写需同步）。 */
    public synchronized void resetWindowCounters() {
        lastTargetId = -1;
        targetSwitchCount = 0;
        attackAngleSum = 0;
        attackDistanceSum = 0;
    }

    // ===== getters / setters =====

    public String getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public FeatureVector getLastFeatures() {
        return lastFeatures;
    }

    public void setLastFeatures(FeatureVector v) {
        this.lastFeatures = v;
    }

    public OnlineKMeans getKmeans() {
        return kmeans;
    }

    public void setKmeans(OnlineKMeans k) {
        this.kmeans = k;
    }

    public double getPersonalScore() {
        return personalScore;
    }

    public void setPersonalScore(double v) {
        this.personalScore = v;
    }

    public double getGlobalScore() {
        return globalScore;
    }

    public void setGlobalScore(double v) {
        this.globalScore = v;
    }

    public double getSupervisedScore() {
        return supervisedScore;
    }

    public void setSupervisedScore(double v) {
        this.supervisedScore = v;
    }

    public double getFusedScore() {
        return fusedScore;
    }

    public void setFusedScore(double v) {
        this.fusedScore = v;
    }

    public boolean isWatchlisted() {
        return watchlisted;
    }

    public void setWatchlisted(boolean v) {
        this.watchlisted = v;
    }

    public long getLastScoredAt() {
        return lastScoredAt;
    }

    public void setLastScoredAt(long t) {
        this.lastScoredAt = t;
    }

    /** 个人基线状态快照（Web 展示用）。 */
    public static final class KmeansSnapshot {
        public final boolean warmedUp;
        public final long warmupProgress;
        public final long warmupTarget;
        public final long updates;

        public KmeansSnapshot(boolean warmedUp, long warmupProgress, long warmupTarget, long updates) {
            this.warmedUp = warmedUp;
            this.warmupProgress = warmupProgress;
            this.warmupTarget = warmupTarget;
            this.updates = updates;
        }

        public java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("warmedUp", warmedUp);
            m.put("warmupProgress", warmupProgress);
            m.put("warmupTarget", warmupTarget);
            m.put("updates", updates);
            return m;
        }
    }
}
