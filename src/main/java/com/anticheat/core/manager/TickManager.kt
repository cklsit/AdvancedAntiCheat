package com.anticheat.core.manager

/**
 * 服务端 tick 计数器。
 *
 * <p>反作弊里所有「多久没动作」「距上次收到包过了几 tick」的判断都必须基于 tick 而不是墙钟：
 * 墙钟在服务器卡顿时会失准，而卡顿本身正是假阳性高发期。</p>
 */
class TickManager {

    @Volatile
    var currentTick: Long = 0L
        private set

    fun nextTick(): Long {
        currentTick++
        return currentTick
    }

    fun reset() {
        currentTick = 0L
    }
}
