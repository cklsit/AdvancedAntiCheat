package com.anticheat.listeners;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 玩家下线时通知 ReplayRecorder 停止 session 并触发异步 ZIP 存档。
 */
public class PlayerQuitListener implements Listener {

    private final AdvancedAntiCheat plugin;

    public PlayerQuitListener(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        try {
            plugin.getReplayRecorder().stopSession(player.getUniqueId());
        } catch (Throwable t) {
            plugin.getLogger().warning("[Replay] PlayerQuitListener.stopSession 异常: " + t.getMessage());
        }

        // 监视调度（需求 1）：玩家退出立即释放槽位，并调度队列头部
        try {
            if (plugin.getSurveillanceScheduler() != null) {
                plugin.getSurveillanceScheduler().onPlayerQuit(player.getUniqueId());
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[Replay] PlayerQuitListener.onPlayerQuit 异常: " + t.getMessage());
        }
    }
}
