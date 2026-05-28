package com.anticheat.profiles;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class BehaviorFeatures implements Serializable {
    private static final long serialVersionUID = 1L;

    private MovementFeatures movement;
    private CombatFeatures combat;
    private MiningFeatures mining;
    private InventoryFeatures inventory;
    private SocialFeatures social;

    public BehaviorFeatures() {
        this.movement = new MovementFeatures();
        this.combat = new CombatFeatures();
        this.mining = new MiningFeatures();
        this.inventory = new InventoryFeatures();
        this.social = new SocialFeatures();
    }

    public static class MovementFeatures implements Serializable {
        private static final long serialVersionUID = 1L;

        public double horizontalSpeedMean;
        public double horizontalSpeedVariance;
        public double verticalSpeedMean;
        public double verticalSpeedVariance;
        public double accelerationMean;
        public double accelerationVariance;

        public double jumpIntervalMean;
        public double jumpIntervalVariance;
        public double sneakToggleFrequency;
        public double sprintRatio;

        public double yawEntropy;
        public double pitchEntropy;
        public double yawSpectrum;
        public double pitchSpectrum;

        public double airPoseChangeRate;
        public double landingBehavior;

        public int illegalMoveAttempts;

        public MovementFeatures() {}
    }

    public static class CombatFeatures implements Serializable {
        private static final long serialVersionUID = 1L;

        public double cpsMean;
        public double cpsVariance;
        public double attackDistanceMean;
        public double attackDistanceVariance;
        public double attackAngleDeviation;
        public double knockbackRecoveryDelay;
        public double trajectoryRandomness;
        public double targetSwitchFrequency;
        public double multiTargetProbability;
        public double totemReactionTime;
        public double armorSwapSpeed;

        public CombatFeatures() {}
    }

    public static class MiningFeatures implements Serializable {
        private static final long serialVersionUID = 1L;

        public double breakTimeConsistency;
        public double breakTimeFluctuation;
        public double placementElevationMean;
        public double placementElevationVariance;
        public double buildSpeed;
        public double pathRegularity;
        public double mineMoveSyncScore;

        public MiningFeatures() {}
    }

    public static class InventoryFeatures implements Serializable {
        private static final long serialVersionUID = 1L;

        public double itemMoveIntervalMean;
        public double itemMoveIntervalVariance;
        public double slotClickSpeed;
        public double containerToggleFrequency;
        public double organizationBehavior;
        public double craftNormality;
        public double tradeNormality;

        public InventoryFeatures() {}
    }

    public static class SocialFeatures implements Serializable {
        private static final long serialVersionUID = 1L;

        public double chatFrequency;
        public List<String> commonPhrases;
        public List<String> commandHabits;
        public int reportCount;
        public List<String> reportReasons;

        public SocialFeatures() {
            this.commonPhrases = new ArrayList<>();
            this.commandHabits = new ArrayList<>();
            this.reportReasons = new ArrayList<>();
        }
    }

    public MovementFeatures getMovement() { return movement; }
    public CombatFeatures getCombat() { return combat; }
    public MiningFeatures getMining() { return mining; }
    public InventoryFeatures getInventory() { return inventory; }
    public SocialFeatures getSocial() { return social; }
}

