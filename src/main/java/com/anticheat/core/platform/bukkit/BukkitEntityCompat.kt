package com.anticheat.core.platform.bukkit

import org.bukkit.entity.Entity
import java.lang.reflect.Method

/**
 * 只在部分版本上存在的 Bukkit 实体方法，用**反射 + 一次性探测**兜住。
 *
 * <p>为什么不用 `try/catch(Throwable)` 就地包住直接调用：直接调用会生成
 * `invokeinterface Entity.isGliding` 字节码，1.8.8 上每次调用都抛
 * `NoSuchMethodError`；靠 catch 兜住意味着**每 tick 每玩家**都要构造一次异常，
 * 那是纯粹的浪费。这里在类初始化时探测一次，之后只是"有没有这个方法"的分支。</p>
 *
 * <p>顺带的好处：反射调用不会出现在字节码里，双版本审计
 * （`tools/audit_dual_version.py` 第 2 项）不会误报它为"1.8 不存在的方法"。</p>
 */
internal object BukkitEntityCompat {

    /**
     * `Entity#isGliding()` 是 1.9 加入的（鞘翅）。
     * 1.8.8 上探测失败，[isGliding] 恒返回 false——而 1.8 本来也没有鞘翅，语义正确。
     */
    private val isGlidingMethod: Method? = runCatching {
        Entity::class.java.getMethod("isGliding")
    }.getOrNull()

    /** 是否支持鞘翅状态查询（用于日志/排障时说明为什么该让路分支不生效）。 */
    val supportsGliding: Boolean get() = isGlidingMethod != null

    fun isGliding(entity: Entity): Boolean {
        val method = isGlidingMethod ?: return false
        return runCatching { method.invoke(entity) as? Boolean ?: false }.getOrDefault(false)
    }
}
