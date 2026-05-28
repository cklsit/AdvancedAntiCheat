package com.anticheat.managers;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.profiles.PlayerProfile;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ProfileManager {
    private final AdvancedAntiCheat plugin;
    private final Map<UUID, PlayerProfile> cachedProfiles;

    public ProfileManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.cachedProfiles = new HashMap<>();
    }
    
    public Map<UUID, PlayerProfile> getCachedProfiles() {
        return cachedProfiles;
    }

    public PlayerProfile getProfile(UUID uuid) {
        return cachedProfiles.get(uuid);
    }

    public PlayerProfile getProfile(Player player) {
        return getProfile(player.getUniqueId());
    }

    public PlayerProfile getOrCreateProfile(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerProfile profile = cachedProfiles.get(uuid);
        
        if (profile == null) {
            profile = plugin.getDatabaseManager().loadPlayerProfile(uuid);
            if (profile == null) {
                profile = new PlayerProfile(uuid, player.getName());
            }
            cachedProfiles.put(uuid, profile);
        }
        
        profile.updateName(player.getName());
        return profile;
    }

    public void cacheProfile(PlayerProfile profile) {
        cachedProfiles.put(profile.getPlayerUUID(), profile);
    }

    public void uncacheProfile(UUID uuid) {
        cachedProfiles.remove(uuid);
    }

    public void saveProfile(UUID uuid) {
        PlayerProfile profile = cachedProfiles.get(uuid);
        if (profile != null) {
            plugin.getDatabaseManager().savePlayerProfile(profile);
        }
    }

    public void saveAllProfiles() {
        for (PlayerProfile profile : cachedProfiles.values()) {
            plugin.getDatabaseManager().savePlayerProfile(profile);
        }
    }

    public void updatePlayerIP(Player player, String ip) {
        PlayerProfile profile = getOrCreateProfile(player);
        profile.getIdentity().setCurrentIP(ip);
        String location = "Unknown"; 
        String isp = "Unknown";
        profile.getIdentity().addIPRecord(ip, location, isp);
    }

    public void updateClientBrand(Player player, String brand) {
        PlayerProfile profile = getOrCreateProfile(player);
        profile.getIdentity().setClientBrand(brand);
    }

    public void recordViolation(Player player, String rule, int severity, String penalty, String executor) {
        PlayerProfile profile = getOrCreateProfile(player);
        profile.addViolation(rule, severity, penalty, false, executor);
        saveProfile(player.getUniqueId());
    }

    public void recordViolation(Player player, String rule, int severity, String executor) {
        recordViolation(player, rule, severity, "警告", executor);
    }

    public void recordCaptchaTrial(Player player, String reason, boolean passed) {
        PlayerProfile profile = getOrCreateProfile(player);
        profile.addCaptchaTrial(reason, passed);
        saveProfile(player.getUniqueId());
    }
}

