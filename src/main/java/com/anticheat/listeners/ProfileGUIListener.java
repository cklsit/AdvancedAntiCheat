package com.anticheat.listeners;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ProfileGUIListener implements Listener {
    private final AdvancedAntiCheat plugin;
    private final Map<UUID, UUID> activeGUIs;

    public ProfileGUIListener(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.activeGUIs = new ConcurrentHashMap<>();
    }

    public void registerGUI(Player viewer, Player target) {
        activeGUIs.put(viewer.getUniqueId(), target.getUniqueId());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player viewer = (Player) event.getWhoClicked();
        
        UUID targetUUID = activeGUIs.get(viewer.getUniqueId());
        if (targetUUID == null) return;
        
        event.setCancelled(true);
        
        Player target = Bukkit.getPlayer(targetUUID);
        if (target == null) {
            viewer.sendMessage(ChatColor.RED + "Player is not online");
            viewer.closeInventory();
            activeGUIs.remove(viewer.getUniqueId());
            return;
        }

        int slot = event.getRawSlot();
        handleAction(slot, viewer, target);
        activeGUIs.remove(viewer.getUniqueId());
    }

    private void handleAction(int slot, Player viewer, Player target) {
        switch (slot) {
            case 45: 
                handleObserve(viewer, target);
                break;
            case 47: 
                handleCaptcha(viewer, target);
                break;
            case 48: 
                handleBan(viewer, target, 1);
                break;
            case 49: 
                handleBan(viewer, target, 7);
                break;
            case 50: 
                handlePermBan(viewer, target);
                break;
            case 51: 
                handleReset(viewer, target);
                break;
        }
        
        viewer.closeInventory();
    }

    private void handleObserve(Player viewer, Player target) {
        viewer.setGameMode(org.bukkit.GameMode.SPECTATOR);
        viewer.teleport(target);
        viewer.sendMessage(ChatColor.GREEN + "Observing " + target.getName());
    }

    private void handleCaptcha(Player viewer, Player target) {
        plugin.getCaptchaManager().startCaptcha(target, com.anticheat.captcha.CaptchaManager.Initiator.ADMIN);
        viewer.sendMessage(ChatColor.GREEN + "Sent captcha to " + target.getName());
        target.sendMessage(ChatColor.YELLOW + "Admin requested you to complete captcha verification");
    }

    private void handleBan(Player viewer, Player target, int days) {
        String reason = "AntiCheat System Auto-Ban";
        long expireTime = System.currentTimeMillis() + (days * 24L * 60L * 60L * 1000L);
        
        Bukkit.getBanList(BanList.Type.NAME).addBan(target.getName(), reason, new Date(expireTime), viewer.getName());
        target.kickPlayer(ChatColor.RED + "You have been banned: " + reason);
        
        plugin.getProfileManager().recordViolation(target, "Admin Ban", 100, days + " Day Ban", viewer.getName());
        viewer.sendMessage(ChatColor.GREEN + "Banned " + target.getName() + " for " + days + " days");
    }

    private void handlePermBan(Player viewer, Player target) {
        String reason = "AntiCheat System Permanent Ban";
        
        Bukkit.getBanList(BanList.Type.NAME).addBan(target.getName(), reason, null, viewer.getName());
        target.kickPlayer(ChatColor.RED + "You have been permanently banned: " + reason);
        
        plugin.getProfileManager().recordViolation(target, "Admin Permanent Ban", 100, "Permanent Ban", viewer.getName());
        viewer.sendMessage(ChatColor.GREEN + "Permanently banned " + target.getName());
    }

    private void handleReset(Player viewer, Player target) {
        plugin.getProfileManager().uncacheProfile(target.getUniqueId());
        viewer.sendMessage(ChatColor.YELLOW + "Reset " + target.getName() + "'s behavior baseline");
    }
}

