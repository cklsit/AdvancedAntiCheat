package com.anticheat.core.check.type

import com.anticheat.core.util.update.AttackUpdate

/**
 * 攻击（实体交互）监听。
 *
 * <p>运行在 **Netty 网络线程**：禁止直接调用 Bukkit 实体/世界 API。
 * 需要读实体位置、判断命中，必须经 `AntiCheatCore.scheduler.runOnMainThread` 回主线程。</p>
 *
 * <p>注意 [AttackUpdate.isAttack] 为 false 时是右键交互，不是攻击——
 * 攻击类检测必须先看这个标志。</p>
 */
interface AttackListener {

    fun onAttack(update: AttackUpdate)
}
