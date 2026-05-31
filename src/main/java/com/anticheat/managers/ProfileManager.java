package com.anticheat.managers;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.profiles.PlayerProfile;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ProfileManager {
    private final AdvancedAntiCheat plugin;
    private final Map<UUID, PlayerProfile> cachedProfiles;
    private final Set<UUID> dirtyProfiles;
    private final AtomicLong lastSaveTime;
    
    private static final int MAX_CACHE_SIZE = 500;
    private static final long SAVE_INTERVAL_MS = 60000;
    private static final long MAX_PROFILE_AGE_MS = 3600000;
    
    private BukkitTask autoSaveTask;

    public ProfileManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.cachedProfiles = new LinkedHashMap<UUID, PlayerProfile>(MAX_CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, PlayerProfile> eldest) {
                return size() > MAX_CACHE_SIZE;
            }
        };
        this.dirtyProfiles = Collections.synchronizedSet(Collections.newSetFromMap(new HashMap<>()));
        this.lastSaveTime = new AtomicLong(System.currentTimeMillis());
        
        startAutoSaveTask();
    }
    
    private void startAutoSaveTask() {
        autoSaveTask = new BukkitRunnable() {
            @Override
            public void run() {
                autoSaveDirtyProfiles();
            }
        }.runTaskTimerAsynchronously(plugin, SAVE_INTERVAL_MS / 50, SAVE_INTERVAL_MS / 50);
    }
    
    private void autoSaveDirtyProfiles() {
        Set<UUID> toSave;
        synchronized (dirtyProfiles) {
            if (dirtyProfiles.isEmpty()) {
                return;
            }
            toSave = new HashSet<>(dirtyProfiles);
            dirtyProfiles.clear();
        }
        
        for (UUID uuid : toSave) {
            PlayerProfile profile = cachedProfiles.get(uuid);
            if (profile != null) {
                plugin.getDatabaseManager().savePlayerProfile(profile);
            }
        }
    }
    
    public Map<UUID, PlayerProfile> getCachedProfiles() {
        return cachedProfiles;
    }
    
    public AdvancedAntiCheat getPlugin() {
        return plugin;
    }

    public PlayerProfile getProfile(UUID uuid) {
        PlayerProfile profile = cachedProfiles.get(uuid);
        if (profile != null) {
            cleanOldProfiles();
        }
        return profile;
    }

    public PlayerProfile getProfile(Player player) {
        return getProfile(player.getUniqueId());
    }

    public PlayerProfile getOrCreateProfile(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerProfile profile = cachedProfiles.get(uuid);
        
        if (profile == null) {
            profile = loadProfileAsync(uuid, player.getName());
            if (profile == null) {
                profile = new PlayerProfile(uuid, player.getName());
            }
            
            synchronized (cachedProfiles) {
                if (cachedProfiles.size() >= MAX_CACHE_SIZE) {
                    evictOldestProfile();
                }
                cachedProfiles.put(uuid, profile);
            }
        }
        
        profile.updateName(player.getName());
        profile.updateLastSeen();
        return profile;
    }
    
    private PlayerProfile loadProfileAsync(UUID uuid, String playerName) {
        try {
            PlayerProfile profile = plugin.getDatabaseManager().loadPlayerProfile(uuid);
            if (profile != null) {
                profile.updateName(playerName);
            }
            return profile;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load profile for " + playerName + ": " + e.getMessage());
            return null;
        }
    }
    
    private void evictOldestProfile() {
        UUID oldestUUID = null;
        long oldestTime = Long.MAX_VALUE;
        
        for (Map.Entry<UUID, PlayerProfile> entry : cachedProfiles.entrySet()) {
            if (!entry.getValue().getPlayerName().isEmpty()) {
                long lastSeen = entry.getValue().getLastSeen();
                if (lastSeen < oldestTime) {
                    oldestTime = lastSeen;
                    oldestUUID = entry.getKey();
                }
            }
        }
        
        if (oldestUUID != null) {
            saveAndRemoveProfile(oldestUUID);
        }
    }
    
    private void cleanOldProfiles() {
        long now = System.currentTimeMillis();
        List<UUID> toRemove = new ArrayList<>();
        
        for (Map.Entry<UUID, PlayerProfile> entry : cachedProfiles.entrySet()) {
            if (now - entry.getValue().getLastSeen() > MAX_PROFILE_AGE_MS) {
                toRemove.add(entry.getKey());
            }
        }
        
        for (UUID uuid : toRemove) {
            saveAndRemoveProfile(uuid);
        }
    }

    public void cacheProfile(PlayerProfile profile) {
        synchronized (cachedProfiles) {
            if (cachedProfiles.size() >= MAX_CACHE_SIZE) {
                evictOldestProfile();
            }
            cachedProfiles.put(profile.getPlayerUUID(), profile);
        }
    }

    public void uncacheProfile(UUID uuid) {
        saveAndRemoveProfile(uuid);
    }
    
    private void saveAndRemoveProfile(UUID uuid) {
        PlayerProfile profile = cachedProfiles.remove(uuid);
        if (profile != null) {
            profile.updateLastSeen();
            plugin.getDatabaseManager().savePlayerProfile(profile);
        }
        dirtyProfiles.remove(uuid);
    }

    public void saveProfile(UUID uuid) {
        PlayerProfile profile = cachedProfiles.get(uuid);
        if (profile != null) {
            profile.updateLastSeen();
            plugin.getDatabaseManager().savePlayerProfile(profile);
            dirtyProfiles.remove(uuid);
        }
    }
    
    public void markDirty(UUID uuid) {
        dirtyProfiles.add(uuid);
    }

    public void saveAllProfiles() {
        List<PlayerProfile> profilesToSave;
        synchronized (cachedProfiles) {
            profilesToSave = new ArrayList<>(cachedProfiles.values());
        }
        
        for (PlayerProfile profile : profilesToSave) {
            profile.updateLastSeen();
            plugin.getDatabaseManager().savePlayerProfile(profile);
        }
        
        dirtyProfiles.clear();
        lastSaveTime.set(System.currentTimeMillis());
    }
    
    public void saveAllProfilesAsync() {
        new BukkitRunnable() {
            @Override
            public void run() {
                saveAllProfiles();
            }
        }.runTaskAsynchronously(plugin);
    }

    public void updatePlayerIP(Player player, String ip) {
        PlayerProfile profile = getOrCreateProfile(player);
        String currentIP = profile.getIdentity().getCurrentIP();
        
        if (currentIP == null || !currentIP.equals(ip)) {
            profile.getIdentity().setCurrentIP(ip);
            String location = "Unknown"; 
            String isp = "Unknown";
            profile.getIdentity().addIPRecord(ip, location, isp);
            markDirty(player.getUniqueId());
        }
    }

    public void updateClientBrand(Player player, String brand) {
        PlayerProfile profile = getOrCreateProfile(player);
        String currentBrand = profile.getIdentity().getClientBrand();
        
        if (currentBrand == null || !currentBrand.equals(brand)) {
            profile.getIdentity().setClientBrand(brand);
            markDirty(player.getUniqueId());
        }
    }

    public void recordViolation(Player player, String rule, int severity, String penalty, String executor) {
        PlayerProfile profile = getOrCreateProfile(player);
        profile.addViolation(rule, severity, penalty, false, executor);
        markDirty(player.getUniqueId());
    }

    public void recordViolation(Player player, String rule, int severity, String executor) {
        recordViolation(player, rule, severity, "警告", executor);
    }

    public void recordCaptchaTrial(Player player, String reason, boolean passed) {
        PlayerProfile profile = getOrCreateProfile(player);
        profile.addCaptchaTrial(reason, passed);
        markDirty(player.getUniqueId());
    }
    
    public void shutdown() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
        }
        saveAllProfiles();
    }
}
