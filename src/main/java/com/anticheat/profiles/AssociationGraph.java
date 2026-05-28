package com.anticheat.profiles;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AssociationGraph implements Serializable {
    private static final long serialVersionUID = 1L;

    private List<AssociationRecord> associatedAccounts;
    private List<InteractionRecord> frequentInteractors;
    private String groupId;

    public AssociationGraph() {
        this.associatedAccounts = new ArrayList<>();
        this.frequentInteractors = new ArrayList<>();
    }

    public void addAssociation(UUID uuid, String name, int strength) {
        associatedAccounts.add(new AssociationRecord(uuid, name, strength));
    }

    public void addInteraction(UUID uuid, String name, int interactionCount) {
        frequentInteractors.add(new InteractionRecord(uuid, name, interactionCount));
    }

    public static class AssociationRecord implements Serializable {
        private static final long serialVersionUID = 1L;
        public UUID uuid;
        public String name;
        public int associationStrength;

        public AssociationRecord(UUID uuid, String name, int associationStrength) {
            this.uuid = uuid;
            this.name = name;
            this.associationStrength = associationStrength;
        }
    }

    public static class InteractionRecord implements Serializable {
        private static final long serialVersionUID = 1L;
        public UUID uuid;
        public String name;
        public int interactionCount;

        public InteractionRecord(UUID uuid, String name, int interactionCount) {
            this.uuid = uuid;
            this.name = name;
            this.interactionCount = interactionCount;
        }
    }

    public List<AssociationRecord> getAssociatedAccounts() { return associatedAccounts; }
    public List<InteractionRecord> getFrequentInteractors() { return frequentInteractors; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
}

