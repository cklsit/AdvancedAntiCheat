package com.anticheat.detection.association;

public enum InteractionType {
    TRADE(1.0),
    CHAT(0.5),
    PARTY(0.8),
    COMBAT(0.6),
    FRIEND(0.7),
    GUILD(0.9),
    DUEL(0.4),
    TEAM(0.8);

    private final double weight;

    InteractionType(double weight) {
        this.weight = weight;
    }

    public double getWeight() {
        return weight;
    }
}
