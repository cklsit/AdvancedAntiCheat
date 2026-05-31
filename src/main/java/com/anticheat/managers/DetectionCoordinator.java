package com.anticheat.managers;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.detection.core.DetectionModule;
import com.anticheat.detection.movement.MovementViolation;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DetectionCoordinator implements Listener {

    private final AdvancedAntiCheat plugin;
    private final AdvancedDetectionManager detectionManager;

    private final Map<UUID, Long> lastMoveTime;
    private final Map<UUID, Long> lastAttackTime;
    private final Map<UUID, Long> lastBreakTime;
    private final Map<UUID, Long> lastPlaceTime;
    private final Map<UUID, Integer> attackCount;

    private static final long MOVE_THROTTLE_MS = 50;
    private static final long ATTACK_THROTTLE_MS = 20;
    private static final long BREAK_THROTTLE_MS = 50;
    private static final long PLACE_THROTTLE_MS = 50;
    private static final int MAX_ATTACKS_PER_SECOND = 25;

    public DetectionCoordinator(AdvancedAntiCheat plugin, AdvancedDetectionManager detectionManager) {
        this.plugin = plugin;
        this.detectionManager = detectionManager;
        this.lastMoveTime = new ConcurrentHashMap<>();
        this.lastAttackTime = new ConcurrentHashMap<>();
        this.lastBreakTime = new ConcurrentHashMap<>();
        this.lastPlaceTime = new ConcurrentHashMap<>();
        this.attackCount = new ConcurrentHashMap<>();

        registerEvents();
    }

    private void registerEvents() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!detectionManager.isEnabled()) {
            return;
        }

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (event.getFrom().getX() == event.getTo().getX() &&
            event.getFrom().getY() == event.getTo().getY() &&
            event.getFrom().getZ() == event.getTo().getZ()) {
            return;
        }

        Long lastMove = lastMoveTime.get(uuid);
        long now = System.currentTimeMillis();

        if (lastMove != null && now - lastMove < MOVE_THROTTLE_MS) {
            return;
        }
        lastMoveTime.put(uuid, now);

        if (detectionManager.isDegradedMode()) {
            return;
        }

        try {
            detectionManager.getMovementModule().onPlayerMove(event);
        } catch (Exception e) {
            plugin.getLogger().warning("[DetectionCoordinator] 移动事件处理异常: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerAttack(EntityDamageByEntityEvent event) {
        if (!detectionManager.isEnabled()) {
            return;
        }

        if (!(event.getDamager() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getDamager();
        UUID uuid = player.getUniqueId();

        long now = System.currentTimeMillis();
        Long lastAttack = lastAttackTime.get(uuid);

        if (lastAttack != null && now - lastAttack < ATTACK_THROTTLE_MS) {
            return;
        }
        lastAttackTime.put(uuid, now);

        updateAttackCount(uuid);

        if (attackCount.getOrDefault(uuid, 0) > MAX_ATTACKS_PER_SECOND) {
            handleSuspiciousAttackRate(player);
            return;
        }

        try {
            detectionManager.getCombatModule().onPlayerAttack(event);
        } catch (Exception e) {
            plugin.getLogger().warning("[DetectionCoordinator] 攻击事件处理异常: " + e.getMessage());
        }
    }

    private void updateAttackCount(UUID uuid) {
        long now = System.currentTimeMillis();
        Long lastUpdate = lastAttackTime.get(uuid);

        if (lastUpdate == null || now - lastUpdate > 1000) {
            attackCount.put(uuid, 1);
        } else {
            attackCount.merge(uuid, 1, Integer::sum);
        }

        if (attackCount.get(uuid) > MAX_ATTACKS_PER_SECOND * 2) {
            attackCount.put(uuid, 0);
        }
    }

    private void handleSuspiciousAttackRate(Player player) {
        plugin.getLogger().warning("[DetectionCoordinator] 检测到异常攻击速率: " + player.getName());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!detectionManager.isEnabled()) {
            return;
        }

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        long now = System.currentTimeMillis();
        Long lastBreak = lastBreakTime.get(uuid);

        if (lastBreak != null && now - lastBreak < BREAK_THROTTLE_MS) {
            return;
        }
        lastBreakTime.put(uuid, now);

        try {
            detectionManager.getHoneypotSystem().onBlockBreak(event);
        } catch (Exception e) {
            plugin.getLogger().warning("[DetectionCoordinator] 方块破坏事件处理异常: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!detectionManager.isEnabled()) {
            return;
        }

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        long now = System.currentTimeMillis();
        Long lastPlace = lastPlaceTime.get(uuid);

        if (lastPlace != null && now - lastPlace < PLACE_THROTTLE_MS) {
            return;
        }
        lastPlaceTime.put(uuid, now);

        if (detectionManager.isDegradedMode()) {
            return;
        }

        try {
            detectionManager.getCombatModule().onBlockPlace(event);
        } catch (Exception e) {
            plugin.getLogger().warning("[DetectionCoordinator] 方块放置事件处理异常: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        lastMoveTime.remove(uuid);
        lastAttackTime.remove(uuid);
        lastBreakTime.remove(uuid);
        lastPlaceTime.remove(uuid);
        attackCount.remove(uuid);

        if (detectionManager.getBehaviorTracker() != null) {
            detectionManager.getBehaviorTracker().onPlayerJoin(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        lastMoveTime.remove(uuid);
        lastAttackTime.remove(uuid);
        lastBreakTime.remove(uuid);
        lastPlaceTime.remove(uuid);
        attackCount.remove(uuid);

        if (detectionManager.getBehaviorTracker() != null) {
            detectionManager.getBehaviorTracker().onPlayerQuit(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!detectionManager.isEnabled()) {
            return;
        }

        if (detectionManager.isDegradedMode()) {
            return;
        }

        try {
            detectionManager.getHoneypotSystem().onPlayerInteract(event);
        } catch (Exception e) {
            plugin.getLogger().warning("[DetectionCoordinator] 交互事件处理异常: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerAnimation(PlayerAnimationEvent event) {
        if (!detectionManager.isEnabled()) {
            return;
        }

        if (detectionManager.isDegradedMode()) {
            return;
        }

        try {
            if (detectionManager.getBehaviorTracker() != null) {
                detectionManager.getBehaviorTracker().onPlayerAnimation(event);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[DetectionCoordinator] 动画事件处理异常: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!detectionManager.isEnabled()) {
            return;
        }

        if (detectionManager.isDegradedMode()) {
            return;
        }

        Player killer = event.getEntity().getKiller();
        if (killer != null && killer.isOnline()) {
            detectionManager.getCombatModule().onEntityKill(event, killer);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!detectionManager.isEnabled()) {
            return;
        }

        Player player = event.getPlayer();
        String command = event.getMessage().toLowerCase();

        if (command.contains("lag") || command.contains("tps") || command.contains("reload")) {
            plugin.getLogger().info("[DetectionCoordinator] 玩家 " + player.getName() + " 执行了命令: " + event.getMessage());
        }
    }

    public void clearPlayerData(UUID uuid) {
        lastMoveTime.remove(uuid);
        lastAttackTime.remove(uuid);
        lastBreakTime.remove(uuid);
        lastPlaceTime.remove(uuid);
        attackCount.remove(uuid);
    }

    public Map<UUID, Integer> getAttackCounts() {
        return new ConcurrentHashMap<>(attackCount);
    }

    public long getTimeSinceLastMove(UUID uuid) {
        Long lastMove = lastMoveTime.get(uuid);
        return lastMove != null ? System.currentTimeMillis() - lastMove : -1;
    }

    public long getTimeSinceLastAttack(UUID uuid) {
        Long lastAttack = lastAttackTime.get(uuid);
        return lastAttack != null ? System.currentTimeMillis() - lastAttack : -1;
    }
}
