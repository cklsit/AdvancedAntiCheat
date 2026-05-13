package com.anticheat.listeners;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class BountyListener implements Listener {
    private final AdvancedAntiCheat plugin;

    public BountyListener(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (plugin.getBountyManager().isInBounty(player)) {
            plugin.getBountyManager().leaveBounty(player);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!plugin.getBountyManager().isInBounty(player)) {
            return;
        }

        String command = event.getMessage().toLowerCase();
        if (command.startsWith("/bounty ")) {
            return;
        }

        event.setCancelled(true);
        player.sendMessage("§c在漏洞赏金沙箱中只能使用 /bounty 相关命令");
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (plugin.getBountyManager().isInBounty(player)) {
            if (plugin.getBountyManager().getSession(player).isInTask()) {
                plugin.getBountyManager().getSession(player).log("[MOVE] Player moved: " + event.getTo().toString());
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (plugin.getBountyManager().isInBounty(player)) {
            if (plugin.getBountyManager().getSession(player).isInTask()) {
                plugin.getBountyManager().getSession(player).log("[BLOCK] Block broken: " + event.getBlock().getType().name());
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (plugin.getBountyManager().isInBounty(player)) {
            if (plugin.getBountyManager().getSession(player).isInTask()) {
                plugin.getBountyManager().getSession(player).log("[BLOCK] Block placed: " + event.getBlockPlaced().getType().name());
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getEntity();
        if (plugin.getBountyManager().isInBounty(player)) {
            if (plugin.getBountyManager().getSession(player).isInTask()) {
                plugin.getBountyManager().getSession(player).log("[DAMAGE] Player took damage: " + event.getCause().name());
            }
        }
    }
}
