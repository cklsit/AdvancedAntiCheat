package com.anticheat.managers;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.detection.ViolationRecord;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class ViolationManager {
    private final AdvancedAntiCheat plugin;
    private final List<ViolationRecord> violationRecords;

    public ViolationManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.violationRecords = new ArrayList<>();
    }

    public void recordViolation(Player player, ViolationRecord.ViolationType type, String details, double level) {
        violationRecords.add(new ViolationRecord(
                player.getUniqueId(),
                player.getName(),
                type,
                details,
                level
        ));

        plugin.getDetectionManager().addViolation(player, type.name().toLowerCase());
    }

    public List<ViolationRecord> getViolationRecords() {
        return new ArrayList<>(violationRecords);
    }

    public void clearRecords() {
        violationRecords.clear();
    }
}
