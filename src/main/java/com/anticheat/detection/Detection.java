package com.anticheat.detection;

import com.anticheat.managers.DetectionManager;
import org.bukkit.entity.Player;
import org.bukkit.GameMode;

public abstract class Detection {

    protected final DetectionManager manager;
    protected final String detectionType;

    public Detection(DetectionManager manager) {
        this.manager = manager;
        this.detectionType = getClass().getSimpleName().toLowerCase().replace("detection", "");
    }

    public abstract void check(Player player);

    protected DetectionManager getManager() {
        return manager;
    }

    protected boolean shouldSkipDetection(Player player) {
        return isConfigDisabled() || hasBypassPermission(player) || isExemptGameMode(player);
    }

    protected boolean isConfigDisabled() {
        return !getManager().getPlugin().getConfigManager().isDetectionEnabled(detectionType);
    }

    protected boolean hasBypassPermission(Player player) {
        return player.hasPermission("anticheat.bypass") || 
               player.hasPermission("anticheat.bypass." + detectionType);
    }

    protected boolean isExemptGameMode(Player player) {
        GameMode mode = player.getGameMode();
        return mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR;
    }

    protected boolean isDead(Player player) {
        return player.isDead();
    }

    protected boolean isBeingChecked(Player player) {
        return getManager().getPlugin().getCheckClientManager().isBeingChecked(player.getUniqueId());
    }

    public String getDetectionType() {
        return detectionType;
    }
}