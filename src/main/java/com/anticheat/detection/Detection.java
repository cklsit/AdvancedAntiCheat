package com.anticheat.detection;

import com.anticheat.managers.DetectionManager;
import org.bukkit.entity.Player;

public abstract class Detection {

    protected final DetectionManager manager;

    public Detection(DetectionManager manager) {
        this.manager = manager;
    }

    public abstract void check(Player player);

    protected DetectionManager getManager() {
        return manager;
    }
}