package com.anticheat.core.util.update

import com.github.retrooper.packetevents.protocol.player.InteractionHand
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity

/**
 * 一次实体交互（含攻击）。
 *
 * <p>攻击与「右键交互」走同一个包，靠 [action] 区分——所以攻击类检测必须先在
 * 这里分流，否则把右键也当攻击会立刻产生大量假阳性。</p>
 *
 * <p>[hitPosition] 只有 `INTERACT_AT`（右键精确交互）才带；
 * `ATTACK` 在 1.17 之前不带该字段，此时为 null。**不要**假设它非空。</p>
 */
class AttackUpdate(
    val targetEntityId: Int,
    val action: WrapperPlayClientInteractEntity.InteractAction,
    val hand: InteractionHand,
    val hitPosition: Vector3d?
) {

    /** 是否是真正的攻击（左键命中实体）。 */
    val isAttack: Boolean = action == WrapperPlayClientInteractEntity.InteractAction.ATTACK

    override fun toString(): String =
        "AttackUpdate(entity=" + targetEntityId + ", action=" + action + ", hand=" + hand + ")"
}
