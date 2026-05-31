package com.anticheat.profiles;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class InventoryStateMachine implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final Map<UUID, List<InventoryTransition>> playerTransitions;
    private final Map<UUID, Map<String, Integer>> transitionCounts;
    private final Map<UUID, Integer> totemSwapCount;
    private final Map<UUID, Long> lastTotemSwap;
    
    private static final int MIN_TRANSITIONS_FOR_ANALYSIS = 20;
    private static final int AUTO_TOTEM_SWAP_THRESHOLD = 10;
    private static final long MAX_TOTEM_SWAP_TIME = 500;
    
    public InventoryStateMachine() {
        this.playerTransitions = new ConcurrentHashMap<>();
        this.transitionCounts = new ConcurrentHashMap<>();
        this.totemSwapCount = new ConcurrentHashMap<>();
        this.lastTotemSwap = new ConcurrentHashMap<>();
    }
    
    public void recordTransition(UUID playerUUID, InventoryTransition transition) {
        List<InventoryTransition> transitions = playerTransitions.computeIfAbsent(
            playerUUID, k -> new ArrayList<>());
        
        synchronized (transitions) {
            transitions.add(transition);
            
            if (transitions.size() > 200) {
                transitions.remove(0);
            }
        }
        
        Map<String, Integer> counts = transitionCounts.computeIfAbsent(
            playerUUID, k -> new HashMap<>());
        
        String key = transition.getType().name();
        counts.merge(key, 1, Integer::sum);
        
        if (transition.getType() == TransitionType.TOTEM_SWAP) {
            totemSwapCount.merge(playerUUID, 1, Integer::sum);
            lastTotemSwap.put(playerUUID, transition.getTimestamp());
        }
    }
    
    public boolean isValidTransition(UUID playerUUID, InventoryTransition transition) {
        List<InventoryTransition> transitions = playerTransitions.get(playerUUID);
        if (transitions == null || transitions.isEmpty()) {
            return true;
        }
        
        InventoryTransition lastTransition;
        synchronized (transitions) {
            lastTransition = transitions.get(transitions.size() - 1);
        }
        
        if (lastTransition == null) {
            return true;
        }
        
        if (!lastTransition.isValidNextState(transition)) {
            return false;
        }
        
        long timeDiff = transition.getTimestamp() - lastTransition.getTimestamp();
        if (timeDiff < 0) {
            return false;
        }
        
        return true;
    }
    
    public boolean isAutoTotem(UUID playerUUID) {
        int count = totemSwapCount.getOrDefault(playerUUID, 0);
        return count >= AUTO_TOTEM_SWAP_THRESHOLD;
    }
    
    public boolean isRapidTotemSwap(UUID playerUUID) {
        List<InventoryTransition> transitions = playerTransitions.get(playerUUID);
        if (transitions == null) {
            return false;
        }
        
        List<InventoryTransition> recentTransitions;
        synchronized (transitions) {
            if (transitions.size() < 2) {
                return false;
            }
            int windowSize = Math.min(20, transitions.size());
            recentTransitions = transitions.subList(
                transitions.size() - windowSize, transitions.size());
        }
        
        List<Long> totemSwapTimes = new ArrayList<>();
        for (InventoryTransition t : recentTransitions) {
            if (t.getType() == TransitionType.TOTEM_SWAP) {
                totemSwapTimes.add(t.getTimestamp());
            }
        }
        
        if (totemSwapTimes.size() < 2) {
            return false;
        }
        
        for (int i = 1; i < totemSwapTimes.size(); i++) {
            long interval = totemSwapTimes.get(i) - totemSwapTimes.get(i - 1);
            if (interval < MAX_TOTEM_SWAP_TIME) {
                return true;
            }
        }
        
        return false;
    }
    
    public double getTotemSwapRate(UUID playerUUID) {
        List<InventoryTransition> transitions = playerTransitions.get(playerUUID);
        if (transitions == null || transitions.isEmpty()) {
            return 0.0;
        }
        
        long firstTime, lastTime;
        synchronized (transitions) {
            firstTime = transitions.get(0).getTimestamp();
            lastTime = transitions.get(transitions.size() - 1).getTimestamp();
        }
        
        long duration = lastTime - firstTime;
        if (duration <= 0) {
            return 0.0;
        }
        
        int totemCount = totemSwapCount.getOrDefault(playerUUID, 0);
        double ratePerMinute = (totemCount * 60000.0) / duration;
        
        return ratePerMinute;
    }
    
    public Map<TransitionType, Integer> getTransitionDistribution(UUID playerUUID) {
        Map<String, Integer> counts = transitionCounts.get(playerUUID);
        if (counts == null) {
            return Collections.emptyMap();
        }
        
        Map<TransitionType, Integer> distribution = new HashMap<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            try {
                TransitionType type = TransitionType.valueOf(entry.getKey());
                distribution.put(type, entry.getValue());
            } catch (IllegalArgumentException e) {
                // ignore unknown types
            }
        }
        
        return distribution;
    }
    
    public int getTransitionCount(UUID playerUUID) {
        List<InventoryTransition> transitions = playerTransitions.get(playerUUID);
        return transitions != null ? transitions.size() : 0;
    }
    
    public int getTotemSwapCount(UUID playerUUID) {
        return totemSwapCount.getOrDefault(playerUUID, 0);
    }
    
    public void clearPlayerData(UUID playerUUID) {
        playerTransitions.remove(playerUUID);
        transitionCounts.remove(playerUUID);
        totemSwapCount.remove(playerUUID);
        lastTotemSwap.remove(playerUUID);
    }
    
    public boolean hasEnoughData(UUID playerUUID) {
        List<InventoryTransition> transitions = playerTransitions.get(playerUUID);
        return transitions != null && transitions.size() >= MIN_TRANSITIONS_FOR_ANALYSIS;
    }
    
    public static class InventoryTransition implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private final TransitionType type;
        private final int fromSlot;
        private final int toSlot;
        private final long timestamp;
        private final String itemType;
        
        public InventoryTransition(TransitionType type, int fromSlot, int toSlot, 
                                   long timestamp, String itemType) {
            this.type = type;
            this.fromSlot = fromSlot;
            this.toSlot = toSlot;
            this.timestamp = timestamp;
            this.itemType = itemType;
        }
        
        public TransitionType getType() {
            return type;
        }
        
        public int getFromSlot() {
            return fromSlot;
        }
        
        public int getToSlot() {
            return toSlot;
        }
        
        public long getTimestamp() {
            return timestamp;
        }
        
        public String getItemType() {
            return itemType;
        }
        
        public boolean isValidNextState(InventoryTransition next) {
            if (next.type == TransitionType.TOTEM_SWAP) {
                return true;
            }
            
            if (this.type == TransitionType.OPEN_CONTAINER && 
                next.type == TransitionType.CLOSE_CONTAINER) {
                return true;
            }
            
            if (next.type == TransitionType.CLOSE_CONTAINER && 
                this.type == TransitionType.OPEN_CONTAINER) {
                return false;
            }
            
            return true;
        }
    }
    
    public enum TransitionType {
        CLICK_SLOT,
        DROP_ITEM,
        SWAP_HANDS,
        TOTEM_SWAP,
        OPEN_CONTAINER,
        CLOSE_CONTAINER,
        SHIFT_CLICK,
        MIDDLE_CLICK,
        DRAG,
        HOTBAR_SWAP
    }
}
