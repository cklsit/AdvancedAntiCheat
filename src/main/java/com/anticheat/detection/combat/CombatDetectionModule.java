package com.anticheat.detection.combat;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.detection.core.DetectionModule;
import com.anticheat.detection.core.Evidence;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CombatDetectionModule implements DetectionModule, Listener {

    private final AdvancedAntiCheat plugin;
    private final Map<UUID, CombatData> playerCombatData;
    private final AimbotHardLockDetector aimbotDetector;
    private final ReachValidator reachValidator;
    private final CPSLimiter cpsLimiter;

    private boolean enabled = true;
    private String name = "CombatDetection";
    private double probability = 0.0;

    private static final int MAX_CPS = 20;
    private static final double MAX_REACH = 4.5;
    private static final long CPS_WINDOW_MS = 1000;
    private static final int MAX_CLICK_HISTORY = 50;

    public CombatDetectionModule(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.playerCombatData = new ConcurrentHashMap<>();
        this.aimbotDetector = new AimbotHardLockDetector();
        this.reachValidator = new ReachValidator();
        this.cpsLimiter = new CPSLimiter();

        initializeModule();
    }

    private void initializeModule() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startCleanupTask();
    }

    private void startCleanupTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                cleanupOldData();
            }
        }.runTaskTimerAsynchronously(plugin, 20L * 60 * 5, 20L * 60 * 5);
    }

    private void cleanupOldData() {
        long cutoff = System.currentTimeMillis() - 60000;
        playerCombatData.entrySet().removeIf(entry -> {
            CombatData data = entry.getValue();
            return data.lastUpdate < cutoff;
        });
    }

    @Override
    public void check(Player player) {
        if (!enabled || player == null || !player.isOnline()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        CombatData data = playerCombatData.computeIfAbsent(uuid, k -> new CombatData());

        analyzeCombatPatterns(player, data);
    }

    public void onPlayerAttack(EntityDamageByEntityEvent event) {
        if (event.isCancelled() || !(event.getDamager() instanceof Player)) {
            return;
        }

        Player attacker = (Player) event.getDamager();
        Entity victim = event.getEntity();

        UUID uuid = attacker.getUniqueId();
        CombatData data = playerCombatData.computeIfAbsent(uuid, k -> new CombatData());

        long now = System.currentTimeMillis();
        data.lastUpdate = now;

        data.clickTimestamps.add(now);
        while (data.clickTimestamps.size() > MAX_CLICK_HISTORY) {
            data.clickTimestamps.poll();
        }

        cpsLimiter.recordClick(attacker);
        double cps = cpsLimiter.getCurrentCPS(uuid);
        data.currentCPS = cps;

        CPSLimiter.CPSViolationLevel cpsLevel = cpsLimiter.getCPSViolationLevel(uuid);
        if (cpsLevel == CPSLimiter.CPSViolationLevel.CHEATING) {
            handleCPSViolation(attacker, cps);
            return;
        }

        if (victim instanceof LivingEntity) {
            LivingEntity livingVictim = (LivingEntity) victim;

            double reach = reachValidator.calculateReach(attacker, livingVictim);
            data.currentReach = reach;

            ReachValidator.ReachViolationLevel reachLevel = reachValidator.checkReach(attacker, livingVictim);
            if (reachLevel == ReachValidator.ReachViolationLevel.CRITICAL) {
                handleReachViolation(attacker, reach, livingVictim);
            }

            if (victim instanceof Player) {
                Player victimPlayer = (Player) victim;

                aimbotDetector.recordLookDirection(attacker, attacker.getLocation(), attacker.getLocation().getDirection());
                aimbotDetector.recordTargetPosition(attacker, victimPlayer, victimPlayer.getLocation());

                if (aimbotDetector.isAimbotHardLock(attacker.getUniqueId(), victimPlayer)) {
                    handleAimbotViolation(attacker);
                }
            }

            recordHitData(attacker, victim, reach);
        }
    }

    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.isCancelled()) {
            return;
        }

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        CombatData data = playerCombatData.get(uuid);

        if (data != null) {
            data.recentBlocksPlaced.add(event.getBlock().getLocation().toVector());
            if (data.recentBlocksPlaced.size() > 10) {
                data.recentBlocksPlaced.remove(0);
            }
        }
    }

    public void onEntityKill(EntityDeathEvent event, Player killer) {
        if (killer == null) {
            return;
        }

        UUID uuid = killer.getUniqueId();
        CombatData data = playerCombatData.get(uuid);

        if (data != null) {
            data.killCount++;
            data.lastKillTime = System.currentTimeMillis();

            if (data.killCount > 5 && isHighKillRate(data)) {
                handleSuspiciousKillRate(killer, data);
            }
        }
    }

    private void analyzeCombatPatterns(Player player, CombatData data) {
        if (data.hitHistory.size() < 5) {
            return;
        }

        double avgReach = data.hitHistory.stream()
            .mapToDouble(HitData::getReach)
            .average()
            .orElse(0.0);

        if (avgReach > MAX_REACH * 0.9) {
            probability = Math.max(probability, 0.6);
        }

        double cps = calculateCPS(data);
        if (cps > MAX_CPS * 1.5) {
            probability = Math.max(probability, 0.7);
        }

        UUID uuid = player.getUniqueId();
        if (cpsLimiter.hasAbnormalCPSPattern(uuid)) {
            probability = Math.max(probability, 0.8);
        }
    }

    private double calculateCPS(CombatData data) {
        long now = System.currentTimeMillis();
        long cutoff = now - CPS_WINDOW_MS;

        while (!data.clickTimestamps.isEmpty() && data.clickTimestamps.peek() < cutoff) {
            data.clickTimestamps.poll();
        }

        return data.clickTimestamps.size() * (1000.0 / CPS_WINDOW_MS);
    }

    private void recordHitData(Player attacker, Entity victim, double reach) {
        UUID uuid = attacker.getUniqueId();
        CombatData data = playerCombatData.get(uuid);

        if (data != null) {
            HitData hitData = new HitData(
                System.currentTimeMillis(),
                reach,
                attacker.getLocation().getYaw(),
                attacker.getLocation().getPitch()
            );
            data.hitHistory.add(hitData);

            while (data.hitHistory.size() > 20) {
                data.hitHistory.remove(0);
            }
        }
    }

    private boolean isHighKillRate(CombatData data) {
        long timeSinceFirstKill = System.currentTimeMillis() - data.firstKillTime;
        if (timeSinceFirstKill > 60000) {
            return false;
        }
        return data.killCount >= 5;
    }

    private void handleCPSViolation(Player player, double cps) {
        Evidence evidence = new Evidence();
        evidence.addData("violationType", "HIGH_CPS");
        evidence.addData("cps", cps);
        evidence.addData("maxAllowed", MAX_CPS);
        evidence.addData("player", player.getName());

        plugin.getDetectionManager().getViolationManager().recordViolation(
            player,
            com.anticheat.detection.ViolationRecord.ViolationType.REACH,
            String.format("CPS异常: %.2f (最大: %d)", cps, MAX_CPS),
            cps / MAX_CPS
        );

        plugin.getLogger().warning("[CombatDetection] 检测到高CPS: " + player.getName() +
            " CPS: " + String.format("%.2f", cps));
    }

    private void handleReachViolation(Player player, double reach, LivingEntity victim) {
        Evidence evidence = new Evidence();
        evidence.addData("violationType", "REACH");
        evidence.addData("reach", reach);
        evidence.addData("maxAllowed", MAX_REACH);
        evidence.addData("victim", victim.getType().name());

        plugin.getDetectionManager().getViolationManager().recordViolation(
            player,
            com.anticheat.detection.ViolationRecord.ViolationType.REACH,
            String.format("攻击距离异常: %.2f格 (最大: %.2f)", reach, MAX_REACH),
            reach / MAX_REACH
        );

        plugin.getLogger().warning("[CombatDetection] 检测到超距离攻击: " + player.getName() +
            " 距离: " + String.format("%.2f", reach) + "格");
    }

    private void handleAimbotViolation(Player player) {
        Evidence evidence = new Evidence();
        evidence.addData("violationType", "AIMBOT");
        evidence.addData("player", player.getName());

        plugin.getDetectionManager().getViolationManager().recordViolation(
            player,
            com.anticheat.detection.ViolationRecord.ViolationType.KILLAURA,
            "自瞄检测: 瞄准锁定",
            0.9
        );

        plugin.getLogger().warning("[CombatDetection] 检测到自瞄: " + player.getName());
    }

    private void handleSuspiciousKillRate(Player player, CombatData data) {
        Evidence evidence = new Evidence();
        evidence.addData("violationType", "HIGH_KILL_RATE");
        evidence.addData("killCount", data.killCount);
        evidence.addData("player", player.getName());

        plugin.getDetectionManager().getViolationManager().recordViolation(
            player,
            com.anticheat.detection.ViolationRecord.ViolationType.KILLAURA,
            String.format("可疑击杀率: %d次/分钟", data.killCount),
            0.5
        );
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public double getProbability() {
        return probability;
    }

    public void setProbability(double probability) {
        this.probability = Math.max(0.0, Math.min(1.0, probability));
    }

    public CombatData getPlayerCombatData(UUID uuid) {
        return playerCombatData.get(uuid);
    }

    public double getPlayerCPS(UUID uuid) {
        CombatData data = playerCombatData.get(uuid);
        return data != null ? data.currentCPS : 0.0;
    }

    public double getPlayerReach(UUID uuid) {
        CombatData data = playerCombatData.get(uuid);
        return data != null ? data.currentReach : 0.0;
    }

    public void clearPlayerData(UUID uuid) {
        playerCombatData.remove(uuid);
        cpsLimiter.cleanup(uuid);
        reachValidator.cleanup(uuid);
        aimbotDetector.cleanup(uuid);
    }

    private static class CombatData {
        final Queue<Long> clickTimestamps = new LinkedList<>();
        final List<HitData> hitHistory = new ArrayList<>();
        final List<org.bukkit.util.Vector> recentBlocksPlaced = new ArrayList<>();

        double currentCPS = 0.0;
        double currentReach = 0.0;

        int killCount = 0;
        long firstKillTime = 0;
        long lastKillTime = 0;

        long lastUpdate = System.currentTimeMillis();
    }

    private static class HitData {
        private final long timestamp;
        private final double reach;
        private final float yaw;
        private final float pitch;

        public HitData(long timestamp, double reach, float yaw, float pitch) {
            this.timestamp = timestamp;
            this.reach = reach;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        public double getReach() {
            return reach;
        }
    }
}
