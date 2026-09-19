package com.anticheat.core.platform

/**
 * 运行平台。当前仅 Bukkit 系（Spigot / Paper / Folia 都走同一条路径）。
 *
 * <p>保留枚举是为了对齐 Grim 的分层：核心层只依赖 [PlatformLoader] 抽象，
 * 平台差异（调度器、发送者、玩家视图）全部收敛在 platform.bukkit 包里。</p>
 */
enum class Platform {
    BUKKIT
}
