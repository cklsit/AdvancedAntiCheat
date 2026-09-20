package com.anticheat.core.util.update

/**
 * 一次手持槽位切换（`HELD_ITEM_CHANGE`）。
 *
 * <p>[previousSlot] 是本次切换前的槽位。原版客户端**只在槽位真的变化时**才发这个包，
 * 因此「连续两次携带同一个 slot」就是协议层异常——这正是
 * [com.anticheat.core.check.impl.badpackets.BadPacketsD] 的判据。</p>
 */
class HeldItemUpdate(val slot: Int, val previousSlot: Int) {

    override fun toString(): String = "HeldItemUpdate(" + previousSlot + " -> " + slot + ")"
}
