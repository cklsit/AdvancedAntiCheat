package com.anticheat.detection.movement;

import com.anticheat.detection.physics.EntitySnapshot;
import java.util.UUID;

/**
 * MovementViolation移动违规类
 * 封装一次移动违规的详细信息，包括违规类型、证据快照和概率值
 */
public class MovementViolation {

    private final UUID playerId;
    private final String playerName;
    private final MovementViolationType type;
    private final EntitySnapshot fromSnapshot;
    private final EntitySnapshot toSnapshot;
    private final double probability;
    private final long timestamp;
    private final String details;
    private final int consecutiveCount;

    public MovementViolation(UUID playerId, String playerName, MovementViolationType type,
                            EntitySnapshot fromSnapshot, EntitySnapshot toSnapshot,
                            double probability, String details, int consecutiveCount) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.type = type;
        this.fromSnapshot = fromSnapshot;
        this.toSnapshot = toSnapshot;
        this.probability = probability;
        this.timestamp = System.currentTimeMillis();
        this.details = details;
        this.consecutiveCount = consecutiveCount;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public MovementViolationType getType() {
        return type;
    }

    public EntitySnapshot getFromSnapshot() {
        return fromSnapshot;
    }

    public EntitySnapshot getToSnapshot() {
        return toSnapshot;
    }

    public double getProbability() {
        return probability;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getDetails() {
        return details;
    }

    public int getConsecutiveCount() {
        return consecutiveCount;
    }

    public MovementViolationType.ViolationSeverity getSeverity() {
        return type.getDefaultSeverity();
    }

    @Override
    public String toString() {
        return String.format("MovementViolation{player='%s', type=%s, probability=%.2f, details='%s'}",
                playerName, type.name(), probability, details);
    }
}
