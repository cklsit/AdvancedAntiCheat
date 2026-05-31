package com.anticheat.managers;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.detection.combat.CombatDetectionModule;
import com.anticheat.detection.fusion.AdaptiveLearningSystem;
import com.anticheat.detection.fusion.DecisionActionCenter;
import com.anticheat.detection.fusion.ProbabilityFusionEngine;
import com.anticheat.detection.fusion.RCPComputer;
import com.anticheat.detection.movement.MovementDetectionModule;
import com.anticheat.detection.association.AssociationDetector;
import com.anticheat.detection.association.SocialGraph;
import com.anticheat.detection.association.TeamCheatingDetector;
import com.anticheat.listeners.HoneypotListener;
import com.anticheat.profiles.BehaviorTracker;
import com.anticheat.profiles.BehaviorAnalysisEngine;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.*;

public class AdvancedDetectionManager {

    private final AdvancedAntiCheat plugin;
    private final ExecutorService asyncExecutor;
    private final ScheduledExecutorService scheduledExecutor;

    private MovementDetectionModule movementModule;
    private CombatDetectionModule combatModule;
    private HoneypotListener honeypotSystem;

    private ProfileManager profileManager;
    private BehaviorTracker behaviorTracker;
    private BehaviorAnalysisEngine behaviorEngine;

    private AssociationDetector associationDetector;
    private SocialGraph socialGraph;
    private TeamCheatingDetector teamCheatingDetector;

    private ProbabilityFusionEngine fusionEngine;
    private DecisionActionCenter decisionCenter;
    private RCPComputer rcpComputer;
    private AdaptiveLearningSystem learningSystem;

    private PerformanceMonitor performanceMonitor;
    private DetectionCoordinator detectionCoordinator;

    private final Map<UUID, Long> lastCheckTime;
    private final Map<UUID, Double> playerRCP;
    private final Map<UUID, List<String>> playerDetections;

    private static final long CHECK_INTERVAL_MS = 100;
    private static final int ASYNC_POOL_SIZE = 4;
    private static final long RCP_COMPUTE_INTERVAL_MS = 1000;
    private static final long TEAM_ANALYSIS_INTERVAL_MS = 60000;

    private BukkitTask periodicCheckTask;
    private BukkitTask rcpComputeTask;
    private BukkitTask teamAnalysisTask;

    private volatile boolean degradedMode = false;
    private volatile boolean enabled = true;

    public AdvancedDetectionManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.asyncExecutor = Executors.newFixedThreadPool(ASYNC_POOL_SIZE, r -> {
            Thread t = new Thread(r, "AntiCheat-Async-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        });
        this.scheduledExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "AntiCheat-Scheduled");
            t.setDaemon(true);
            return t;
        });
        this.lastCheckTime = new ConcurrentHashMap<>();
        this.playerRCP = new ConcurrentHashMap<>();
        this.playerDetections = new ConcurrentHashMap<>();
    }

    public void initialize(AdvancedAntiCheat plugin) {
        plugin.getLogger().info("[AdvancedDetectionManager] 开始初始化...");

        initializeLayer1Engines(plugin);
        initializeLayer2Engines(plugin);
        initializeLayer3Engines(plugin);
        initializeFusionCenter();
        initializePerformanceMonitor(plugin);
        initializeCoordinator(plugin);
        startPeriodicTasks();

        plugin.getLogger().info("[AdvancedDetectionManager] 初始化完成！");
        plugin.getLogger().info("[AdvancedDetectionManager] 第一层引擎: 运动检测, 战斗检测, 蜂蜜罐系统");
        plugin.getLogger().info("[AdvancedDetectionManager] 第二层引擎: 行为分析, 玩家画像");
        plugin.getLogger().info("[AdvancedDetectionManager] 第三层引擎: 关联检测, 团队作弊检测");
        plugin.getLogger().info("[AdvancedDetectionManager] 融合中心: 概率融合, 决策中心, RCP计算");
    }

    private void initializeLayer1Engines(AdvancedAntiCheat plugin) {
        plugin.getLogger().info("[AdvancedDetectionManager] 初始化第一层引擎...");

        this.movementModule = new MovementDetectionModule(true);
        this.combatModule = new CombatDetectionModule(plugin);
        this.honeypotSystem = new HoneypotListener(plugin);

        plugin.getServer().getPluginManager().registerEvents(movementModule, plugin);
        plugin.getServer().getPluginManager().registerEvents(honeypotSystem, plugin);

        plugin.getLogger().info("[AdvancedDetectionManager] 第一层引擎初始化完成");
    }

    private void initializeLayer2Engines(AdvancedAntiCheat plugin) {
        plugin.getLogger().info("[AdvancedDetectionManager] 初始化第二层引擎...");

        this.profileManager = plugin.getProfileManager();
        this.behaviorTracker = plugin.getBehaviorTracker();
        this.behaviorEngine = new BehaviorAnalysisEngine(plugin, profileManager);

        plugin.getLogger().info("[AdvancedDetectionManager] 第二层引擎初始化完成");
    }

    private void initializeLayer3Engines(AdvancedAntiCheat plugin) {
        plugin.getLogger().info("[AdvancedDetectionManager] 初始化第三层引擎...");

        this.socialGraph = new SocialGraph();
        this.teamCheatingDetector = new TeamCheatingDetector(profileManager, socialGraph);
        this.associationDetector = new AssociationDetector(plugin, profileManager, socialGraph);

        plugin.getLogger().info("[AdvancedDetectionManager] 第三层引擎初始化完成");
    }

    private void initializeFusionCenter() {
        plugin.getLogger().info("[AdvancedDetectionManager] 初始化融合中心...");

        this.fusionEngine = new ProbabilityFusionEngine();
        this.learningSystem = new AdaptiveLearningSystem();
        this.rcpComputer = new RCPComputer(fusionEngine, learningSystem);
        this.decisionCenter = new DecisionActionCenter();

        plugin.getLogger().info("[AdvancedDetectionManager] 融合中心初始化完成");
    }

    private void initializePerformanceMonitor(AdvancedAntiCheat plugin) {
        this.performanceMonitor = new PerformanceMonitor(plugin, this);
    }

    private void initializeCoordinator(AdvancedAntiCheat plugin) {
        this.detectionCoordinator = new DetectionCoordinator(plugin, this);
    }

    private void startPeriodicTasks() {
        periodicCheckTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!enabled) return;
                performPeriodicChecks();
            }
        }.runTaskTimerAsynchronously(plugin, 20L, CHECK_INTERVAL_MS / 50);

        rcpComputeTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!enabled) return;
                computeAllPlayerRCP();
            }
        }.runTaskTimerAsynchronously(plugin, 20L * 5, RCP_COMPUTE_INTERVAL_MS / 50);

        teamAnalysisTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!enabled || degradedMode) return;
                performTeamAnalysis();
            }
        }.runTaskTimer(plugin, 20L * 30, TEAM_ANALYSIS_INTERVAL_MS / 50);
    }

    public void runChecks(Player player) {
        if (!enabled || player == null || !player.isOnline()) {
            return;
        }

        UUID uuid = player.getUniqueId();

        Long lastCheck = lastCheckTime.get(uuid);
        if (lastCheck != null && System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) {
            return;
        }
        lastCheckTime.put(uuid, System.currentTimeMillis());

        if (degradedMode) {
            runDegradedChecks(player);
        } else {
            runFullChecks(player);
        }
    }

    private void runFullChecks(Player player) {
        asyncExecutor.submit(() -> {
            try {
                runLayer1Checks(player);
                runLayer2Checks(player);
                runLayer3Checks(player);
                updatePlayerRCP(player);
                makeDecision(player);
            } catch (Exception e) {
                plugin.getLogger().warning("[AdvancedDetectionManager] 检查执行异常: " + e.getMessage());
            }
        });
    }

    private void runDegradedChecks(Player player) {
        asyncExecutor.submit(() -> {
            try {
                runLayer1Checks(player);
                updatePlayerRCP(player);
                makeDecision(player);
            } catch (Exception e) {
                plugin.getLogger().warning("[AdvancedDetectionManager] 降级检查执行异常: " + e.getMessage());
            }
        });
    }

    private void runLayer1Checks(Player player) {
        movementModule.check(player);
        combatModule.check(player);
    }

    private void runLayer2Checks(Player player) {
        behaviorEngine.analyzePlayer(player);
        behaviorTracker.detectAnomaly(player);
    }

    private void runLayer3Checks(Player player) {
        if (!degradedMode) {
            associationDetector.analyzePlayer(player);
        }
    }

    private void updatePlayerRCP(Player player) {
        UUID uuid = player.getUniqueId();

        double movementProb = movementModule.getProbability();
        double combatProb = combatModule.getProbability();
        double behaviorProb = behaviorEngine.getAnomalyScore(player);

        fusionEngine.addPlayerProbability(uuid, "movement", movementProb);
        fusionEngine.addPlayerProbability(uuid, "combat", combatProb);
        fusionEngine.addPlayerProbability(uuid, "behavior", behaviorProb);

        double rcp = rcpComputer.computeRCP(uuid);
        playerRCP.put(uuid, rcp);
    }

    public void makeDecision(Player player) {
        UUID uuid = player.getUniqueId();
        Double rcp = playerRCP.get(uuid);

        if (rcp == null) {
            return;
        }

        DecisionActionCenter.ActionLevel action = decisionCenter.decide(uuid, rcp);

        if (decisionCenter.shouldTakeAction(uuid, rcp)) {
            decisionCenter.executeAction(uuid, action);
            recordDetection(uuid, action.getDescription());
        }
    }

    private void recordDetection(UUID uuid, String detectionType) {
        playerDetections.computeIfAbsent(uuid, k -> new CopyOnWriteArrayList<>()).add(detectionType);
    }

    public double getRCP(UUID playerUUID) {
        Double rcp = playerRCP.get(playerUUID);
        return rcp != null ? rcp : 0.0;
    }

    private void performPeriodicChecks() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOnline()) {
                runChecks(player);
            }
        }
    }

    private void computeAllPlayerRCP() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            double rcp = rcpComputer.computeRCP(uuid);
            playerRCP.put(uuid, rcp);
        }
    }

    private void performTeamAnalysis() {
        if (teamCheatingDetector == null) {
            return;
        }

        List<com.anticheat.detection.association.CheatingTeam> teams =
            teamCheatingDetector.detectTeams();

        for (com.anticheat.detection.association.CheatingTeam team : teams) {
            for (UUID member : team.getMembers()) {
                recordDetection(member, "TEAM_CHEAT");
                fusionEngine.addPlayerProbability(member, "teamCheat", team.getCheatScore());
            }
        }
    }

    public void enableDegradedMode() {
        if (degradedMode) return;

        degradedMode = true;
        plugin.getLogger().warning("[AdvancedDetectionManager] 已启用降级模式 - 仅运行核心检测");

        asyncExecutor.submit(() -> {
            fusionEngine.clearAllData();
            playerRCP.clear();
        });
    }

    public void disableDegradedMode() {
        if (!degradedMode) return;

        degradedMode = false;
        plugin.getLogger().info("[AdvancedDetectionManager] 已禁用降级模式 - 恢复完整检测");

        if (teamAnalysisTask != null) {
            teamAnalysisTask.cancel();
        }
        startPeriodicTasks();
    }

    public boolean isDegradedMode() {
        return degradedMode;
    }

    public boolean isTPSHealthy() {
        return performanceMonitor.isTPSHealthy();
    }

    public void shutdown() {
        enabled = false;

        if (periodicCheckTask != null) {
            periodicCheckTask.cancel();
        }
        if (rcpComputeTask != null) {
            rcpComputeTask.cancel();
        }
        if (teamAnalysisTask != null) {
            teamAnalysisTask.cancel();
        }

        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        scheduledExecutor.shutdown();
        try {
            if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduledExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduledExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        playerRCP.clear();
        playerDetections.clear();
        lastCheckTime.clear();
    }

    public MovementDetectionModule getMovementModule() {
        return movementModule;
    }

    public CombatDetectionModule getCombatModule() {
        return combatModule;
    }

    public HoneypotListener getHoneypotSystem() {
        return honeypotSystem;
    }

    public ProfileManager getProfileManager() {
        return profileManager;
    }

    public BehaviorTracker getBehaviorTracker() {
        return behaviorTracker;
    }

    public BehaviorAnalysisEngine getBehaviorEngine() {
        return behaviorEngine;
    }

    public AssociationDetector getAssociationDetector() {
        return associationDetector;
    }

    public TeamCheatingDetector getTeamCheatingDetector() {
        return teamCheatingDetector;
    }

    public SocialGraph getSocialGraph() {
        return socialGraph;
    }

    public ProbabilityFusionEngine getFusionEngine() {
        return fusionEngine;
    }

    public DecisionActionCenter getDecisionCenter() {
        return decisionCenter;
    }

    public RCPComputer getRcpComputer() {
        return rcpComputer;
    }

    public AdaptiveLearningSystem getLearningSystem() {
        return learningSystem;
    }

    public PerformanceMonitor getPerformanceMonitor() {
        return performanceMonitor;
    }

    public DetectionCoordinator getDetectionCoordinator() {
        return detectionCoordinator;
    }

    public Map<UUID, Double> getPlayerRCP() {
        return new HashMap<>(playerRCP);
    }

    public List<String> getPlayerDetections(UUID uuid) {
        List<String> detections = playerDetections.get(uuid);
        return detections != null ? new ArrayList<>(detections) : new ArrayList<>();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void addFeedback(UUID playerUUID, boolean confirmed) {
        learningSystem.collectFeedback(playerUUID, confirmed, "unknown");
    }
}
