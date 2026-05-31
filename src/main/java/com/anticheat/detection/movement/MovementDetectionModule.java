package com.anticheat.detection.movement;

import com.anticheat.detection.core.DetectionModule;
import com.anticheat.detection.core.Evidence;
import com.anticheat.detection.physics.EntitySnapshot;
import com.anticheat.detection.physics.MovementInput;
import com.anticheat.detection.physics.PhysicsConstants;
import com.anticheat.detection.physics.Vector3D;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * MovementDetectionModule移动检测模块
 * 继承BaseDetectionModule，监听玩家移动事件并检测各种移动作弊
 */
public class MovementDetectionModule implements DetectionModule, Listener {

    private final ConcurrentMap<UUID, PlayerMovementData> playerDataMap = new ConcurrentHashMap<>();
    private final ImpossibleActionDetector impossibleActionDetector;
    private boolean enabled = true;
    private String name = "MovementDetection";
    private double probability = 0.8;

    private static final int SNAPSHOT_HISTORY_SIZE = 10;
    private static final double MAX_WALK_SPEED = 0.287;
    private static final double MAX_SPRINT_SPEED = 0.38;
    private static final double MAX_AIR_STRAFE_SPEED = 0.035;
    private static final double MAX_JUMP_DISTANCE = 1.5;
    private static final double MIN_TELEPORT_DISTANCE = 10.0;

    /**
     * 构造函数
     */
    public MovementDetectionModule() {
        this.impossibleActionDetector = new ImpossibleActionDetector();
    }

    /**
     * 构造函数
     * @param enabled 是否启用
     */
    public MovementDetectionModule(boolean enabled) {
        this();
        this.enabled = enabled;
    }

    @Override
    public void check(Player player) {
        if (!enabled) {
            return;
        }

        if (player == null || !player.isOnline()) {
            return;
        }

        if (isExemptPlayer(player)) {
            return;
        }

        PlayerMovementData data = playerDataMap.computeIfAbsent(
            player.getUniqueId(), 
            k -> new PlayerMovementData()
        );

        EntitySnapshot currentSnapshot = createSnapshot(player);
        
        if (data.lastSnapshot != null) {
            MovementViolation violation = checkImpossibleActions(
                player, 
                data.lastSnapshot, 
                currentSnapshot
            );
            
            if (violation != null) {
                handleViolation(player, violation);
            }
            
            if (isSpeedAnomaly(data.lastSnapshot, currentSnapshot)) {
                MovementViolation speedViolation = createSpeedViolation(
                    player, 
                    data.lastSnapshot, 
                    currentSnapshot
                );
                if (speedViolation != null) {
                    handleViolation(player, speedViolation);
                }
            }
            
            if (isJumpAnomaly(player, data.lastSnapshot, currentSnapshot)) {
                MovementViolation jumpViolation = createJumpViolation(
                    player, 
                    data.lastSnapshot, 
                    currentSnapshot
                );
                if (jumpViolation != null) {
                    handleViolation(player, jumpViolation);
                }
            }
        }
        
        recordSnapshot(player, currentSnapshot, data);
    }

    /**
     * 检测不可能动作
     */
    private MovementViolation checkImpossibleActions(Player player, 
                                                     EntitySnapshot from, 
                                                     EntitySnapshot to) {
        MovementViolation violation;
        
        violation = impossibleActionDetector.checkAirJump(player, from, to);
        if (violation != null) {
            return violation;
        }
        
        violation = impossibleActionDetector.checkNoFallDamage(player, from, to);
        if (violation != null) {
            return violation;
        }
        
        violation = impossibleActionDetector.checkWaterWalk(player, from, to);
        if (violation != null) {
            return violation;
        }
        
        violation = impossibleActionDetector.checkPhase(player, from, to);
        if (violation != null) {
            return violation;
        }
        
        return null;
    }

    /**
     * 检测速度异常
     */
    private boolean isSpeedAnomaly(EntitySnapshot from, EntitySnapshot to) {
        if (from.isOnGround() || to.isOnGround()) {
            return false;
        }
        
        double dx = to.getPosition().getX() - from.getPosition().getX();
        double dz = to.getPosition().getZ() - from.getPosition().getZ();
        double horizontalSpeed = Math.sqrt(dx * dx + dz * dz);
        
        if (horizontalSpeed > MAX_AIR_STRAFE_SPEED * 2) {
            return true;
        }
        
        return false;
    }

    /**
     * 检测跳跃异常
     */
    private boolean isJumpAnomaly(Player player, EntitySnapshot from, EntitySnapshot to) {
        if (!to.isOnGround() && from.isOnGround()) {
            double dy = to.getPosition().getY() - from.getPosition().getY();
            
            if (dy > MAX_JUMP_DISTANCE) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * 创建速度违规
     */
    private MovementViolation createSpeedViolation(Player player, 
                                                   EntitySnapshot from, 
                                                   EntitySnapshot to) {
        double dx = to.getPosition().getX() - from.getPosition().getX();
        double dz = to.getPosition().getZ() - from.getPosition().getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        
        double maxSpeed = getMaxAllowedSpeed(player);
        double exceedRatio = distance / maxSpeed;
        
        if (exceedRatio > 1.5) {
            double probability = Math.min(0.9, 0.5 + exceedRatio * 0.1);
            
            return new MovementViolation(
                player.getUniqueId(),
                player.getName(),
                MovementViolationType.SPEED,
                from,
                to,
                probability,
                String.format("速度异常：移动距离=%.3f，最大允许=%.3f，超出比例=%.2f",
                    distance, maxSpeed, exceedRatio),
                (int) exceedRatio
            );
        }
        
        return null;
    }

    /**
     * 创建跳跃违规
     */
    private MovementViolation createJumpViolation(Player player, 
                                                  EntitySnapshot from, 
                                                  EntitySnapshot to) {
        double dy = to.getPosition().getY() - from.getPosition().getY();
        
        if (dy > PhysicsConstants.JUMP_FORCE * 2) {
            double exceedRatio = dy / PhysicsConstants.JUMP_FORCE;
            double probability = Math.min(0.9, 0.6 + exceedRatio * 0.1);
            
            return new MovementViolation(
                player.getUniqueId(),
                player.getName(),
                MovementViolationType.HIGH_JUMP,
                from,
                to,
                probability,
                String.format("跳跃异常：垂直位移=%.3f，最大允许=%.3f，超出比例=%.2f",
                    dy, PhysicsConstants.JUMP_FORCE, exceedRatio),
                (int) exceedRatio
            );
        }
        
        return null;
    }

    /**
     * 获取最大允许速度
     */
    private double getMaxAllowedSpeed(Player player) {
        if (player.isFlying()) {
            return 0.5;
        }
        
        if (player.isSprinting()) {
            return MAX_SPRINT_SPEED * 1.5;
        }
        
        if (!player.isOnGround()) {
            return MAX_AIR_STRAFE_SPEED;
        }
        
        return MAX_WALK_SPEED * 1.2;
    }

    /**
     * 记录移动快照
     */
    public void recordSnapshot(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerMovementData data = playerDataMap.computeIfAbsent(uuid, k -> new PlayerMovementData());
        EntitySnapshot snapshot = createSnapshot(player);
        recordSnapshot(player, snapshot, data);
    }

    /**
     * 记录移动快照
     */
    private void recordSnapshot(Player player, EntitySnapshot snapshot, PlayerMovementData data) {
        data.snapshotHistory.add(snapshot);
        
        while (data.snapshotHistory.size() > SNAPSHOT_HISTORY_SIZE) {
            data.snapshotHistory.remove(0);
        }
        
        data.lastSnapshot = snapshot;
    }

    /**
     * 创建实体快照
     */
    private EntitySnapshot createSnapshot(Player player) {
        Location loc = player.getLocation();
        Vector3D position = new Vector3D(loc.getX(), loc.getY(), loc.getZ());
        Vector3D velocity = new Vector3D(player.getVelocity());
        
        return new EntitySnapshot(
            player.getUniqueId(),
            position,
            velocity,
            loc.getYaw(),
            loc.getPitch(),
            player.isOnGround(),
            player.getWorld().getFullTime()
        );
    }

    /**
     * 处理违规
     */
    private void handleViolation(Player player, MovementViolation violation) {
        Evidence evidence = new Evidence();
        evidence.addData("violationType", violation.getType().name());
        evidence.addData("probability", violation.getProbability());
        evidence.addData("details", violation.getDetails());
        evidence.addData("fromSnapshot", violation.getFromSnapshot().toString());
        evidence.addData("toSnapshot", violation.getToSnapshot().toString());
        evidence.addData("consecutiveCount", violation.getConsecutiveCount());
        
        UUID uuid = player.getUniqueId();
        PlayerMovementData data = playerDataMap.get(uuid);
        if (data != null) {
            data.violationCounts.merge(violation.getType(), 1, Integer::sum);
            data.totalViolations++;
        }
        
        if (violation.getProbability() >= 0.7) {
            playerDataMap.computeIfAbsent(uuid, k -> new PlayerMovementData())
                        .recentHighProbabilityViolations
                        .add(violation);
        }
    }

    /**
     * 检查玩家是否豁免
     */
    private boolean isExemptPlayer(Player player) {
        if (player.hasPermission("anticheat.bypass.movement")) {
            return true;
        }
        
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE ||
            player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return true;
        }
        
        if (player.isInsideVehicle()) {
            return true;
        }
        
        if (player.isSleeping()) {
            return true;
        }
        
        if (player.isDead()) {
            return true;
        }
        
        return false;
    }

    /**
     * 获取不可能动作检测器实例
     */
    public ImpossibleActionDetector getImpossibleActionDetector() {
        return impossibleActionDetector;
    }

    /**
     * 获取玩家的违规统计
     */
    public Map<MovementViolationType, Integer> getPlayerViolationStats(UUID playerId) {
        PlayerMovementData data = playerDataMap.get(playerId);
        if (data != null) {
            return new java.util.HashMap<>(data.violationCounts);
        }
        return new java.util.HashMap<>();
    }

    /**
     * 获取玩家的总违规次数
     */
    public int getPlayerTotalViolations(UUID playerId) {
        PlayerMovementData data = playerDataMap.get(playerId);
        return data != null ? data.totalViolations : 0;
    }

    /**
     * 清除玩家数据
     */
    public void clearPlayerData(UUID playerId) {
        playerDataMap.remove(playerId);
        impossibleActionDetector.clearAllData(playerId);
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public void setProbability(double probability) {
        this.probability = Math.max(0.0, Math.min(1.0, probability));
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public double getProbability() {
        return probability;
    }

    /**
     * 玩家移动数据内部类
     */
    private static class PlayerMovementData {
        EntitySnapshot lastSnapshot;
        java.util.List<EntitySnapshot> snapshotHistory = new java.util.ArrayList<>();
        Map<MovementViolationType, Integer> violationCounts = new ConcurrentHashMap<>();
        int totalViolations = 0;
        java.util.List<MovementViolation> recentHighProbabilityViolations = new java.util.ArrayList<>();
    }

    /**
     * PlayerMoveEvent事件监听器
     * 在主类中注册或在单独的监听器类中调用
     */
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.isCancelled()) {
            return;
        }
        
        if (event.getFrom().distance(event.getTo()) < 0.01) {
            return;
        }
        
        check(event.getPlayer());
    }

    /**
     * PlayerQuitEvent事件监听器
     */
    public void onPlayerQuit(PlayerQuitEvent event) {
        clearPlayerData(event.getPlayer().getUniqueId());
    }

    /**
     * PlayerTeleportEvent事件监听器
     */
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) {
            return;
        }
        
        Location from = event.getFrom();
        Location to = event.getTo();
        
        if (to == null) {
            return;
        }
        
        double distance = from.distance(to);
        
        if (distance > MIN_TELEPORT_DISTANCE) {
            PlayerMovementData data = playerDataMap.get(event.getPlayer().getUniqueId());
            if (data != null) {
                EntitySnapshot fromSnapshot = createSnapshot(event.getPlayer());
                EntitySnapshot toSnapshot = createSnapshot(event.getPlayer());
                
                MovementViolation teleportViolation = new MovementViolation(
                    event.getPlayer().getUniqueId(),
                    event.getPlayer().getName(),
                    MovementViolationType.TELEPORT,
                    fromSnapshot,
                    toSnapshot,
                    0.8,
                    String.format("检测到远距离传送：距离=%.2f格", distance),
                    1
                );
                
                handleViolation(event.getPlayer(), teleportViolation);
            }
        }
    }
}
