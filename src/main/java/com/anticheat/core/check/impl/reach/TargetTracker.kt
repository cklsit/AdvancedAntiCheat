package com.anticheat.core.check.impl.reach

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.check.CoreProcessor
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.math.PointHistory
import java.util.ArrayDeque

/**
 * 战斗目标追踪器 —— 伸手（reach）与视线类检测共用的**主线程**数据源。
 *
 * <p>它不是一个检测，而是被 [ReachA] / [ReachB] 共用的状态容器。之所以独立出来：
 * 目标位置与朝向历史的采集开销不该被每个检测各做一遍，而且两个检测若各自维护
 * 一份历史，会出现"同一时刻两份数据不一致"的排障噩梦。</p>
 *
 * <h3>为什么必须走主线程</h3>
 * 攻击包在 Netty 线程到达，而实体位置只能从 Bukkit 读。链路是：
 * `PacketCombatTracker` 记下目标 id → [com.anticheat.core.manager.init.TickRunner]
 * 排空并搬进 [PlayerData.attackTargetsThisTick] → 本类在 tick 内采样。
 *
 * <h3>[ensureSampled] 而不是"我是 ServerTickListener"</h3>
 * 如果靠注册顺序来保证"先采样再判定"，就等于把正确性押在
 * `CheckManager` 的登记表顺序上——加一个新检测就可能悄悄改变行为。
 * 改成消费方主动调用、内部按 tick 幂等，顺序就无关了。</p>
 */
class TargetTracker(player: PlayerData) : CoreProcessor(player) {

    /** 单个目标的状态。 */
    class Track(
        val history: PointHistory,
        var box: EntityBoxes.Box,
        var typeName: String
    ) {
        /** 本 tick 是否被攻击过（只在本 tick 内有效，由 [ensureSampled] 维护）。 */
        var attackedThisTick: Boolean = false

        /** 超过这个 tick 就不再跟踪（省掉无用查找，也让历史能自然过期）。 */
        var expiresAtTick: Long = 0L
    }

    private val eyeHistory = PointHistory(HISTORY_TICKS)

    /** 朝向历史，**最新的在最前**（[0] 为本次 tick）。 */
    private val rotations = ArrayDeque<FloatArray>(ROTATION_HISTORY)

    private val tracks = HashMap<Int, Track>(8)

    private var lastSampleTick = Long.MIN_VALUE

    private var sampleValid = false

    /** 只读视图：眼睛位置历史。 */
    fun eyes(): PointHistory = eyeHistory

    /** 朝向样本个数（可能少于 [ROTATION_HISTORY]，刚登录时未填满）。 */
    fun rotationCount(): Int = rotations.size

    /** 第 [index] 个朝向（0 = 最新）。 */
    fun rotationYaw(index: Int): Float = rotations.elementAt(index)[0]

    fun rotationPitch(index: Int): Float = rotations.elementAt(index)[1]

    /** 只读视图：当前仍在跟踪的目标。 */
    fun tracks(): Map<Int, Track> = tracks

    /**
     * 本 tick 的采样。幂等：同一 tick 内被多个检测调用只会真正执行一次。
     */
    fun ensureSampled() {
        val tick = AntiCheatCore.tickManager.currentTick
        if (tick == lastSampleTick) return
        lastSampleTick = tick

        // 1) 自己：眼睛位置与朝向
        eyeHistory.add(player.serverEyeX, player.serverEyeY, player.serverEyeZ)
        rotations.addFirst(floatArrayOf(player.yaw, player.pitch))
        while (rotations.size > ROTATION_HISTORY) {
            rotations.removeLast()
        }

        // 2) 先把上一 tick 的"被攻击"标记全部清掉，再由本 tick 的攻击置位。
        //    漏掉这一步会让"某次攻击"被之后每一 tick 都重复判定，
        //    证据累积速度凭空翻好几倍。
        for (track in tracks.values) {
            track.attackedThisTick = false
        }

        // 3) 本 tick 被攻击的目标：标记 + 续期。
        //    续期必须覆盖整场战斗：若 TTL 比攻击间隔还短，Track 会被反复销毁重建，
        //    历史永远填不满，而判定条件要求历史满 —— 检测就静默失效了。
        for (id in player.attackTargetsThisTick) {
            val track = tracks.getOrPut(id) { newTrack() }
            track.attackedThisTick = true
            track.expiresAtTick = tick + TRACK_TTL_TICKS
        }

        // 4) 刷新所有在跟踪目标的位置历史（无论本 tick 有没有被攻击，
        //    这样历史才能在交火前就填满）
        val iterator = tracks.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val track = entry.value
            if (track.expiresAtTick < tick) {
                iterator.remove()
                continue
            }

            // 锚点 = 玩家自己的权威坐标：平台层索引未命中时只在锚点周边
            // 若干区块里找（反作弊关心的目标必然近在咫尺），不会退化成全量遍历
            val pos = player.serverPosition
            val snapshot = AntiCheatCore.platformServer
                .getEntitySnapshot(player.serverWorld, entry.key, pos.x, pos.y, pos.z)
                ?.takeIf { it.alive }

            if (snapshot == null) {
                // 取不到位置（实体已移除 / 不在同一世界 / 索引未命中）：
                // 清空历史而不是沿用旧值——旧值是过期数据，会让距离往任意方向偏；
                // 而历史为空会让检测跳过本目标（安全方向）
                track.history.clear()
                continue
            }
            track.box = EntityBoxes.of(snapshot)
            track.typeName = snapshot.typeName
            track.history.add(snapshot.x, snapshot.y, snapshot.z)
        }

        sampleValid = true
    }

    /**
     * 当前是否适合做"伸手/视线"判定。
     *
     * <p>把让路条件集中在这里，是为了让两个检测共用同一份判据——
     * 分散在各检测里的话，改了一处忘了另一处就会造成"两个检测结论矛盾"。</p>
     *
     * <p>让路的情形都是有明确理由的：</p>
     * - **骑乘 / 滑翔**：玩家位置由载具或滑翔物理驱动，与服务端同步存在额外偏差；
     * - **传送窗口内**：包序与位置都不可信；
     * - **刚登录**：历史还没填满，窗口未就绪（历史不满时检测本身也会跳过，
     *   这里是显式的第二道保险）；
     * - **高延迟**：延迟超过 400ms 时，用历史做的补偿已不足以覆盖真实偏差，
     *   此时宁可不判。这是"先不要误报"的直接体现。
     */
    fun canJudge(): Boolean {
        if (!player.alive) return false
        if (player.serverInVehicle || player.serverGliding) return false

        val tick = AntiCheatCore.tickManager.currentTick
        if (tick - player.lastTeleportTick < TELEPORT_IMMUNITY_TICKS) return false
        if (tick - player.joinTick < JOIN_GRACE_TICKS) return false
        if (player.ping > MAX_JUDGEABLE_PING) return false

        return sampleValid
    }

    private fun newTrack(): Track = Track(
        PointHistory(HISTORY_TICKS),
        EntityBoxes.defaultBox(),
        "UNKNOWN"
    )

    override fun reload() {
        // 追踪器没有可配置项；保留覆盖点以便将来加"跟踪时长"之类的开关
    }

    companion object {
        /**
         * 位置/朝向历史的长度（tick）。
         *
         * <p>8 tick = 400ms，足以覆盖任何可玩延迟下单程的"客户端看到目标"与
         * "服务端收到攻击"之间的偏差。越长越宽容但填满越慢，8 是两者的平衡点。</p>
         */
        const val HISTORY_TICKS = 8

        /** 朝向历史长度。朝向的瞬时变化远大于位置，所以单独给一个短窗口。 */
        const val ROTATION_HISTORY = 5

        /**
         * 一个目标停止被攻击后继续跟踪多久（tick）。
         *
         * <p>必须明显大于常见攻击间隔：若比攻击间隔还短，Track 会被反复销毁重建，
         * 历史永远填不满，判定条件（要求历史满）就永远不成立——检测静默失效。
         * 40 tick = 2 秒，足以跨越交火中的停顿。</p>
         */
        const val TRACK_TTL_TICKS = 40L

        const val TELEPORT_IMMUNITY_TICKS = 40L

        const val JOIN_GRACE_TICKS = 100L

        /** 超过该延迟就不做伸手/视线判定。 */
        const val MAX_JUDGEABLE_PING = 400
    }
}
