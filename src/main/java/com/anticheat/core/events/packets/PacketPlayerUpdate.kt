package com.anticheat.core.events.packets

import com.anticheat.core.AntiCheatCore
import com.anticheat.core.player.PlayerData
import com.anticheat.core.util.CoreLog
import com.anticheat.core.util.update.PositionUpdate
import com.anticheat.core.util.update.RotationUpdate
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying

/**
 * 位置 / 朝向包解析。
 *
 * <p>PE 把 1.8 的四个独立包（Flying / Position / PositionRotation / Rotation）
 * 与 1.9+ 的合并包统一成 [WrapperPlayClientPlayerFlying]，
 * 用 `hasPositionChanged()` / `hasRotationChanged()` 区分本次更新带哪些分量——
 * 所以这里**不要**按 `event.packetType` 逐个分支，那会把版本差异重新引进来。</p>
 *
 * <p>注意 1.8 的 Flying 包只有 onGround 标志，此时 `getLocation()` 的内容无意义，
 * 必须先用 flag 判断再读。</p>
 */
object PacketPlayerUpdate {

    fun handle(event: PacketReceiveEvent) {
        try {
            if (!WrapperPlayClientPlayerFlying.isFlying(event.packetType)) return

            val data = AntiCheatCore.playerDataManager.getByUser(event.user) ?: return
            if (!data.alive) return

            val wrapper = WrapperPlayClientPlayerFlying(event)
            val onGround = wrapper.isOnGround
            val positionChanged = wrapper.hasPositionChanged()
            val rotationChanged = wrapper.hasRotationChanged()

            if (!positionChanged && !rotationChanged) {
                data.acceptFlying(onGround)
                return
            }

            val location = wrapper.location
            if (location == null) {
                data.acceptFlying(onGround)
                return
            }

            if (positionChanged) {
                applyPosition(data, location.position, onGround)
            }
            if (rotationChanged) {
                applyRotation(data, location.yaw, location.pitch, onGround)
            }

            // 物理预测骨架：此处应调用 MovementCheckRunner 计算 offset 并派发
            // PostPredictionListener。骨架阶段不猜测物理量，避免引入假阳性。
        } catch (t: Throwable) {
            // Netty 线程：异常绝不能外抛，否则会污染连接甚至导致掉线
            CoreLog.debug("位置包处理异常: " + t.message)
        }
    }

    private fun applyPosition(data: PlayerData, newPosition: Vector3d, onGround: Boolean) {
        val update = PositionUpdate(data.position, newPosition, onGround)
        data.acceptPosition(newPosition, onGround)
        data.checkManager.onPositionUpdate(update)
    }

    private fun applyRotation(data: PlayerData, yaw: Float, pitch: Float, onGround: Boolean) {
        val update = RotationUpdate(data.yaw, yaw, data.pitch, pitch)
        data.acceptRotation(yaw, pitch, onGround)
        data.checkManager.onRotationUpdate(update)
    }
}
