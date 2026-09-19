package com.anticheat.core.util

import org.bukkit.Bukkit
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 核心层日志。
 *
 * <p>刻意不直接暴露 `plugin.logger`：核心层高频路径（每 tick / 每包）一旦误留 info 级输出，
 * 会瞬间刷屏（本项目历史上出过两次刷屏事故）。这里把「可刷屏」的输出统一收口到
 * [debug] 并默认丢弃，只有显式打开 `core.debug` 才会真正落盘。</p>
 */
object CoreLog {

    private val logger: Logger
        get() = Bukkit.getLogger()

    @Volatile
    var debugEnabled: Boolean = false

    fun info(message: String) {
        logger.info("[AAC-Core] $message")
    }

    fun warn(message: String) {
        logger.warning("[AAC-Core] $message")
    }

    fun error(message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            logger.severe("[AAC-Core] $message")
        } else {
            logger.log(Level.SEVERE, "[AAC-Core] $message", throwable)
        }
    }

    /** 高频路径专用：默认静默，仅在 core.debug=true 时输出。 */
    fun debug(message: String) {
        if (debugEnabled) {
            logger.info("[AAC-Core][debug] $message")
        }
    }
}
