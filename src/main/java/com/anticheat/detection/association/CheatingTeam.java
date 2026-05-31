package com.anticheat.detection.association;

import java.io.Serializable;
import java.util.*;

public class CheatingTeam implements Serializable {
    private static final long serialVersionUID = 1L;

    private final UUID teamId;
    private final Set<UUID> members;
    private final double cheatScore;
    private final long formationTime;
    private final List<String> cheatingPatterns;
    private final Map<UUID, Double> memberScores;

    public CheatingTeam(UUID teamId, Set<UUID> members, double cheatScore) {
        this.teamId = teamId;
        this.members = new HashSet<>(members);
        this.cheatScore = cheatScore;
        this.formationTime = System.currentTimeMillis();
        this.cheatingPatterns = new ArrayList<>();
        this.memberScores = new HashMap<>();
    }

    public UUID getTeamId() {
        return teamId;
    }

    public Set<UUID> getMembers() {
        return new HashSet<>(members);
    }

    public double getCheatScore() {
        return cheatScore;
    }

    public long getFormationTime() {
        return formationTime;
    }

    public List<String> getCheatingPatterns() {
        return new ArrayList<>(cheatingPatterns);
    }

    public void addCheatingPattern(String pattern) {
        if (!cheatingPatterns.contains(pattern)) {
            cheatingPatterns.add(pattern);
        }
    }

    public Map<UUID, Double> getMemberScores() {
        return new HashMap<>(memberScores);
    }

    public void setMemberScore(UUID member, double score) {
        memberScores.put(member, score);
    }

    public int getTeamSize() {
        return members.size();
    }

    public boolean hasSynchronizedBehavior() {
        return cheatingPatterns.stream()
            .anyMatch(pattern -> pattern.contains("synchronized") ||
                                pattern.contains("sync") ||
                                pattern.contains("simultaneous"));
    }

    public boolean isHighRiskTeam() {
        return cheatScore >= 0.8 || members.size() >= 4;
    }

    @Override
    public String toString() {
        return "CheatingTeam{" +
                "teamId=" + teamId +
                ", members=" + members.size() +
                ", cheatScore=" + cheatScore +
                ", patterns=" + cheatingPatterns +
                '}';
    }
}
