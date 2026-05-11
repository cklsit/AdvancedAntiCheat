package com.anticheat.profiles;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class BehaviorTracker {

    private final AdvancedAntiCheat plugin;
    private final Map<UUID, PlayerBehaviorData> playerData;
    private final Map<UUID, PlayerProfile> profiles;

    private static final long CPS_WINDOW_MS = 1000;
    private static final long WALK_STAY_CHECK_INTERVAL = 60000;

    public BehaviorTracker(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.playerData = new ConcurrentHashMap<>();
        this.profiles = new ConcurrentHashMap<>();
    }

    public void onPlayerJoin(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerProfile profile = plugin.getDatabaseManager().loadPlayerProfile(uuid);
        if (profile == null) {
            profile = new PlayerProfile(uuid, player.getName());
        }
        profiles.put(uuid, profile);
        playerData.put(uuid, new PlayerBehaviorData());
    }

    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        saveProfile(uuid);
        playerData.remove(uuid);
    }

    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!event.getAction().name().contains("LEFT")) return;
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        PlayerBehaviorData data = playerData.get(uuid);
        if (data == null) return;

        long now = System.currentTimeMillis();
        data.clickTimestamps.add(now);
        data.clickTimestamps.removeIf(ts -> now - ts > CPS_WINDOW_MS * 10);

        double cps = calculateCPS(data);
        if (cps > 0) {
            PlayerProfile profile = profiles.get(uuid);
            if (profile != null) {
                profile.updateCPS(cps);
            }
        }

        data.interfaceActionsThisMinute.incrementAndGet();
    }

    public void onPlayerAnimation(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        PlayerBehaviorData data = playerData.get(uuid);
        if (data == null) return;

        long now = System.currentTimeMillis();
        long lastSwing = data.lastArmSwing.get();
        if (lastSwing > 0) {
            double interval = (now - lastSwing) / 1000.0;
            if (interval > 0.1 && interval < 10) {
                PlayerProfile profile = profiles.get(uuid);
                if (profile != null) {
                    profile.updateJumpInterval(interval);
                }
            }
        }
        data.lastArmSwing.set(now);
    }

    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.isCancelled()) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (event.getFrom().getX() == event.getTo().getX() &&
            event.getFrom().getZ() == event.getTo().getZ()) {
            return;
        }

        PlayerBehaviorData data = playerData.get(uuid);
        if (data == null) return;

        data.walkTimeThisPeriod.incrementAndGet();

        float yawDiff = Math.abs(event.getFrom().getYaw() - event.getTo().getYaw());
        if (yawDiff > 180) yawDiff = 360 - yawDiff;

        float pitchDiff = Math.abs(event.getFrom().getPitch() - event.getTo().getPitch());

        if (yawDiff > 0.5 || pitchDiff > 0.5) {
            double turnSpeed = Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);
            PlayerProfile profile = profiles.get(uuid);
            if (profile != null) {
                profile.updateTurnSpeed(turnSpeed);
            }
        }
    }

    public void checkWalkStayRatio(UUID uuid) {
        PlayerBehaviorData data = playerData.get(uuid);
        if (data == null) return;

        long now = System.currentTimeMillis();
        long lastCheck = data.lastWalkStayCheck.get();
        if (now - lastCheck < WALK_STAY_CHECK_INTERVAL) return;

        if (!data.lastWalkStayCheck.compareAndSet(lastCheck, now)) {
            return;
        }

        int walkTime = data.walkTimeThisPeriod.getAndSet(0);
        int totalSeconds = (int) ((now - lastCheck) / 1000);
        int stayTime = totalSeconds - walkTime;
        if (stayTime < 0) stayTime = 0;

        int total = walkTime + stayTime;
        if (total > 0) {
            double ratio = (double) walkTime / total;
            PlayerProfile profile = profiles.get(uuid);
            if (profile != null) {
                profile.updateWalkStayRatio(ratio);
            }
        }
    }

    public void checkInterfaceActions(UUID uuid) {
        PlayerBehaviorData data = playerData.get(uuid);
        if (data == null) return;

        long now = System.currentTimeMillis();
        long lastCheck = data.lastInterfaceCheck.get();
        if (now - lastCheck < 60000) return;

        if (!data.lastInterfaceCheck.compareAndSet(lastCheck, now)) {
            return;
        }

        int actions = data.interfaceActionsThisMinute.getAndSet(0);
        double actionsPerMinute = actions;

        PlayerProfile profile = profiles.get(uuid);
        if (profile != null) {
            profile.updateInterfaceAction(actionsPerMinute);
        }
    }

    private double calculateCPS(PlayerBehaviorData data) {
        long now = System.currentTimeMillis();
        data.clickTimestamps.removeIf(ts -> now - ts > CPS_WINDOW_MS);

        int clicks = data.clickTimestamps.size();
        if (clicks == 0) return 0;

        return clicks * (1000.0 / CPS_WINDOW_MS);
    }

    public PlayerProfile getProfile(UUID uuid) {
        return profiles.get(uuid);
    }

    public void saveProfile(UUID uuid) {
        PlayerProfile profile = profiles.get(uuid);
        if (profile != null) {
            profile.updateLastSeen();
            plugin.getDatabaseManager().savePlayerProfile(profile);
        }
    }

    public void saveAllProfiles() {
        for (UUID uuid : profiles.keySet()) {
            saveProfile(uuid);
        }
    }

    public boolean hasEnoughData(UUID uuid) {
        PlayerProfile profile = profiles.get(uuid);
        return profile != null && profile.hasEnoughSamples();
    }

    public String getAnomalyReport(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerProfile profile = profiles.get(uuid);
        PlayerBehaviorData data = playerData.get(uuid);

        if (profile == null || data == null) {
            return null;
        }

        double currentCPS = calculateCPS(data);
        double currentTurnSpeed = data.recentTurnSpeed;
        double currentJumpInterval = data.recentJumpInterval;
        double currentInterfaceActions = data.interfaceActionsThisMinute.get();
        double currentWalkStayRatio = data.walkTimeThisPeriod.get() > 0 ? 0.8 : 0.2;

        return profile.getAnomalyReport(currentCPS, currentTurnSpeed,
            currentJumpInterval, currentInterfaceActions, currentWalkStayRatio);
    }

    public boolean detectAnomaly(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerProfile profile = profiles.get(uuid);

        if (profile == null || !profile.hasEnoughSamples()) {
            return false;
        }

        PlayerBehaviorData data = playerData.get(uuid);
        if (data == null) return false;

        double currentCPS = calculateCPS(data);

        return profile.isCPSAnomaly(currentCPS) ||
               profile.isTurnSpeedAnomaly(data.recentTurnSpeed) ||
               profile.isJumpIntervalAnomaly(data.recentJumpInterval) ||
               profile.isInterfaceActionAnomaly(data.interfaceActionsThisMinute.get()) ||
               profile.isWalkStayRatioAnomaly(data.walkTimeThisPeriod.get() > 0 ? 0.8 : 0.2) ||
               profile.detectBehaviorShift();
    }

    private static class PlayerBehaviorData {
        final List<Long> clickTimestamps = new ArrayList<>();
        final AtomicInteger interfaceActionsThisMinute = new AtomicInteger(0);
        final AtomicInteger walkTimeThisPeriod = new AtomicInteger(0);
        final AtomicLong lastArmSwing = new AtomicLong(0);
        final AtomicLong lastWalkStayCheck = new AtomicLong(0);
        final AtomicLong lastInterfaceCheck = new AtomicLong(0);

        volatile double recentTurnSpeed = 0;
        volatile double recentJumpInterval = 1.0;
    }
}
