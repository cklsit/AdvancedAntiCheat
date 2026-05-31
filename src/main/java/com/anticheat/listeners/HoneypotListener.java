package com.anticheat.listeners;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.detection.ViolationRecord;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HoneypotListener implements Listener {

    private final AdvancedAntiCheat plugin;
    private final Set<Location> hologramOres;
    private final Set<UUID> ghostEntities;
    private final Map<UUID, Long> playerBreakTimes;
    private final Map<UUID, Map<String, Long>> playerActionHistory;
    private final Map<UUID, List<Long>> packetTimings;
    
    private static final String HOLOGRAM_PREFIX = "HONEYPOT_ORE_";
    private static final String GHOST_ENTITY_PREFIX = "HONEYPOT_GHOST_";
    private static final String METADATA_KEY = "anticheat_honeypot";
    private static final long MIN_BREAK_TIME_MS = 200;

    public HoneypotListener(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.hologramOres = ConcurrentHashMap.newKeySet();
        this.ghostEntities = ConcurrentHashMap.newKeySet();
        this.playerBreakTimes = new ConcurrentHashMap<>();
        this.playerActionHistory = new ConcurrentHashMap<>();
        this.packetTimings = new ConcurrentHashMap<>();
        
        initializeHoneypots();
    }

    private void initializeHoneypots() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!plugin.getConfig().getBoolean("honeypot.enabled", true)) {
                    return;
                }
                
                spawnHologramOres();
                spawnGhostEntities();
            }
        }.runTaskTimer(plugin, 20L * 60, 20L * 60 * 10);
        
        if (plugin.getConfig().getBoolean("honeypot.enabled", true)) {
            spawnHologramOres();
            spawnGhostEntities();
        }
    }

    private void spawnHologramOres() {
        if (!plugin.getConfig().getBoolean("honeypot.hologram-ore.enabled", true)) {
            return;
        }

        hologramOres.clear();
        
        int oreCount = plugin.getConfig().getInt("honeypot.hologram-ore.count", 20);
        List<String> worldNames = plugin.getConfig().getStringList("honeypot.hologram-ore.worlds");
        
        for (String worldName : worldNames) {
            org.bukkit.World world = Bukkit.getWorld(worldName);
            if (world == null) continue;
            
            for (int i = 0; i < oreCount; i++) {
                int x = (int) (Math.random() * 1000) - 500;
                int z = (int) (Math.random() * 1000) - 500;
                int y = world.getHighestBlockYAt(x, z) + 1;
                
                Location loc = new Location(world, x, y, z);
                hologramOres.add(loc);
            }
        }
    }

    private void spawnGhostEntities() {
        if (!plugin.getConfig().getBoolean("honeypot.ghost-entity.enabled", true)) {
            return;
        }

        ghostEntities.clear();
        
        int entityCount = plugin.getConfig().getInt("honeypot.ghost-entity.count", 10);
        List<String> worldNames = plugin.getConfig().getStringList("honeypot.ghost-entity.worlds");
        
        for (String worldName : worldNames) {
            org.bukkit.World world = Bukkit.getWorld(worldName);
            if (world == null) continue;
            
            for (int i = 0; i < entityCount; i++) {
                double x = world.getSpawnLocation().getX() + (Math.random() * 200) - 100;
                double z = world.getSpawnLocation().getZ() + (Math.random() * 200) - 100;
                double y = world.getHighestBlockYAt((int) x, (int) z) + 1;
                
                Location loc = new Location(world, x, y, z);
                LivingEntity entity = (LivingEntity) world.spawnEntity(loc, EntityType.PIG);
                entity.setVisibleByDefault(false);
                entity.setMetadata(METADATA_KEY, new FixedMetadataValue(plugin, GHOST_ENTITY_PREFIX + UUID.randomUUID().toString()));
                ghostEntities.add(entity.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.getConfig().getBoolean("honeypot.enabled", true)) {
            return;
        }
        
        if (event.getPlayer().hasPermission("anticheat.bypass")) {
            return;
        }
        
        Location blockLoc = event.getBlock().getLocation();
        
        if (isHologramOre(blockLoc)) {
            event.setCancelled(true);
            
            plugin.getDetectionManager().getViolationManager().recordViolation(
                event.getPlayer(),
                com.anticheat.detection.ViolationRecord.ViolationType.X_RAY,
                "幻象矿石检测: 挖掘了不存在的钻石矿",
                1.0
            );
            
            plugin.getLogger().warning("[Honeypot] 检测到透视作弊: " + event.getPlayer().getName() + 
                " 在位置 " + blockLoc.getWorld().getName() + ":" + 
                blockLoc.getBlockX() + "," + blockLoc.getBlockY() + "," + blockLoc.getBlockZ());
            
            return;
        }
        
        recordBreakTime(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!plugin.getConfig().getBoolean("honeypot.enabled", true)) {
            return;
        }
        
        if (event.getPlayer().hasPermission("anticheat.bypass")) {
            return;
        }
        
        if (event.getAction() == Action.LEFT_CLICK_BLOCK || event.getAction() == Action.LEFT_CLICK_AIR) {
            recordInteraction(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityTarget(EntityTargetEvent event) {
        if (!plugin.getConfig().getBoolean("honeypot.enabled", true)) {
            return;
        }
        
        if (!(event.getTarget() instanceof Player)) {
            return;
        }
        
        Player targetPlayer = (Player) event.getTarget();
        
        if (targetPlayer.hasPermission("anticheat.bypass")) {
            return;
        }
        
        if (isGhostEntity(event.getEntity())) {
            plugin.getDetectionManager().getViolationManager().recordViolation(
                targetPlayer,
                com.anticheat.detection.ViolationRecord.ViolationType.CHEST_ESP,
                "幽灵实体检测: 攻击了隐形实体",
                1.0
            );
            
            plugin.getLogger().warning("[Honeypot] 检测到ESP/自瞄作弊: " + targetPlayer.getName());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!plugin.getConfig().getBoolean("honeypot.enabled", true)) {
            return;
        }
        
        if (!(event.getDamager() instanceof Player)) {
            return;
        }
        
        Player player = (Player) event.getDamager();
        
        if (player.hasPermission("anticheat.bypass")) {
            return;
        }
        
        Entity entity = event.getEntity();
        
        if (isGhostEntity(entity)) {
            event.setCancelled(true);
            
            plugin.getDetectionManager().getViolationManager().recordViolation(
                player,
                com.anticheat.detection.ViolationRecord.ViolationType.CHEST_ESP,
                "幽灵实体检测: 攻击了隐形生物",
                1.0
            );
            
            plugin.getLogger().warning("[Honeypot] 检测到ESP/自瞄作弊: " + player.getName() + 
                " 攻击了幽灵实体");
            
            return;
        }
        
        if (entity instanceof LivingEntity && !entity.equals(player)) {
            LivingEntity livingEntity = (LivingEntity) entity;
            Location entityLoc = livingEntity.getLocation();
            Location playerLoc = player.getLocation();
            
            double distance = playerLoc.distance(entityLoc);
            
            if (distance > plugin.getConfig().getDouble("honeypot.impossible-break.max-distance", 4.5)) {
                if (!hasLineOfSight(player, entityLoc)) {
                    plugin.getDetectionManager().getViolationManager().recordViolation(
                        player,
                        com.anticheat.detection.ViolationRecord.ViolationType.KILLAURA,
                        "不可能攻击距离检测: " + String.format("%.2f", distance) + " 格",
                        1.0
                    );
                }
            }
            
            checkImpossibleBreakPattern(player, entityLoc);
        }
    }

    private void recordBreakTime(Player player) {
        UUID uuid = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        
        playerBreakTimes.put(uuid, currentTime);
        
        Map<String, Long> history = playerActionHistory.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
        history.put("last_break", currentTime);
    }

    private void recordInteraction(Player player) {
        UUID uuid = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        
        Map<String, Long> history = playerActionHistory.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
        Long lastBreak = history.get("last_break");
        
        if (lastBreak != null && currentTime - lastBreak < MIN_BREAK_TIME_MS) {
            Location playerLoc = player.getLocation();
            
            plugin.getDetectionManager().getViolationManager().recordViolation(
                player,
                com.anticheat.detection.ViolationRecord.ViolationType.AUTO_MINER,
                "自动脚本检测: 挖掘间隔异常",
                1.0
            );
            
            plugin.getLogger().warning("[Honeypot] 检测到自动脚本: " + player.getName() + 
                " 挖掘间隔 " + (currentTime - lastBreak) + "ms");
        }
        
        history.put("last_interact", currentTime);
    }

    private void checkImpossibleBreakPattern(Player player, Location entityLoc) {
        UUID uuid = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        
        List<Long> timings = packetTimings.computeIfAbsent(uuid, k -> new ArrayList<>());
        timings.add(currentTime);
        
        if (timings.size() > 20) {
            timings.remove(0);
        }
        
        if (timings.size() >= 10) {
            boolean isRegular = checkTimingRegularity(timings);
            
            if (isRegular) {
                plugin.getDetectionManager().getViolationManager().recordViolation(
                    player,
                    com.anticheat.detection.ViolationRecord.ViolationType.AUTO_MINER,
                    "自动攻击检测: 攻击间隔过于规律",
                    1.0
                );
                
                plugin.getLogger().warning("[Honeypot] 检测到自动攻击: " + player.getName() + 
                    " 攻击间隔标准差过低");
            }
        }
    }

    private boolean checkTimingRegularity(List<Long> timings) {
        if (timings.size() < 5) {
            return false;
        }
        
        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < timings.size(); i++) {
            intervals.add(timings.get(i) - timings.get(i - 1));
        }
        
        double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(0);
        double variance = intervals.stream()
            .mapToDouble(i -> Math.pow(i - mean, 2))
            .average()
            .orElse(Double.MAX_VALUE);
        double stdDev = Math.sqrt(variance);
        
        double coefficientOfVariation = mean > 0 ? stdDev / mean : Double.MAX_VALUE;
        
        return coefficientOfVariation < 0.05;
    }

    private boolean isHologramOre(Location loc) {
        if (loc == null) {
            return false;
        }
        
        for (Location hologramLoc : hologramOres) {
            if (hologramLoc.getWorld().equals(loc.getWorld()) &&
                hologramLoc.getBlockX() == loc.getBlockX() &&
                hologramLoc.getBlockY() == loc.getBlockY() &&
                hologramLoc.getBlockZ() == loc.getBlockZ()) {
                return true;
            }
        }
        
        return false;
    }

    private boolean isGhostEntity(Entity entity) {
        if (entity == null || !entity.hasMetadata(METADATA_KEY)) {
            return false;
        }
        
        List<MetadataValue> metadata = entity.getMetadata(METADATA_KEY);
        for (MetadataValue value : metadata) {
            if (value.asString().startsWith(GHOST_ENTITY_PREFIX)) {
                return true;
            }
        }
        
        return false;
    }

    private boolean hasLineOfSight(Player player, Location target) {
        Location eyeLocation = player.getEyeLocation();
        org.bukkit.util.Vector direction = eyeLocation.getDirection().normalize();
        
        double distance = eyeLocation.distance(target);
        
        for (double d = 0; d < distance; d += 0.1) {
            Location checkLoc = eyeLocation.clone().add(direction.clone().multiply(d));
            if (checkLoc.getBlock().getType().isSolid()) {
                return false;
            }
        }
        
        return true;
    }

    public void recordPacketTiming(UUID playerUUID, long timestamp) {
        List<Long> timings = packetTimings.computeIfAbsent(playerUUID, k -> new ArrayList<>());
        timings.add(timestamp);
        
        if (timings.size() > 100) {
            timings.remove(0);
        }
    }

    public boolean detectFakedDelay(UUID playerUUID) {
        List<Long> timings = packetTimings.get(playerUUID);
        if (timings == null || timings.size() < 20) {
            return false;
        }
        
        return checkTimingRegularity(timings);
    }

    public Set<Location> getHologramOres() {
        return Collections.unmodifiableSet(hologramOres);
    }

    public Set<UUID> getGhostEntities() {
        return Collections.unmodifiableSet(ghostEntities);
    }
}
