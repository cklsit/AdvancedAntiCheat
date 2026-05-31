package com.anticheat.detection.network;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ProtocolValidator {

    private final AdvancedAntiCheat plugin;
    private final Map<UUID, ClientInfo> clientInfoMap;
    private final Map<UUID, List<Long>> packetTimings;
    private final Map<UUID, List<Integer>> packetSequenceMap;
    private final Map<UUID, Map<String, Long>> playerPacketStats;
    private final Set<String> knownClientBrands;
    
    private static final int PROTOCOL_VERSION_1_8 = 47;
    private static final int PROTOCOL_VERSION_1_12 = 340;
    private static final int PROTOCOL_VERSION_1_16 = 736;
    private static final int PROTOCOL_VERSION_1_19 = 759;
    private static final int PROTOCOL_VERSION_1_21 = 767;
    
    private static final long MIN_PACKET_INTERVAL_MS = 10;
    private static final long MAX_PACKET_INTERVAL_MS = 1000;
    private static final double PACKET_RATE_TOLERANCE = 0.15;

    public ProtocolValidator(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.clientInfoMap = new ConcurrentHashMap<>();
        this.packetTimings = new ConcurrentHashMap<>();
        this.packetSequenceMap = new ConcurrentHashMap<>();
        this.playerPacketStats = new ConcurrentHashMap<>();
        this.knownClientBrands = new HashSet<>();
        
        initializeKnownBrands();
        startValidationTask();
    }

    private void initializeKnownBrands() {
        knownClientBrands.add("vanilla");
        knownClientBrands.add("Lunar-Client");
        knownClientBrands.add("Badlion-Client");
        knownClientBrands.add("Labymod");
        knownClientBrands.add("OptiFine");
        knownClientBrands.add("feather");
        knownClientBrands.add("Salwyrr");
        knownClientBrands.add("Cosmic-Client");
        knownClientBrands.add("DashLoader");
        knownClientBrands.add("Impact");
        knownClientBrands.add("Aristois");
        knownClientBrands.add("Waldo-Client");
        knownClientBrands.add("Rift");
        knownClientBrands.add("Pulse");
        knownClientBrands.add("Future-Client");
    }

    private void startValidationTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    validatePlayerConnection(player);
                }
            }
        }.runTaskTimer(plugin, 20L * 30, 20L * 60);
    }

    public void registerClientInfo(Player player, String brand, int protocolVersion) {
        UUID uuid = player.getUniqueId();
        
        ClientInfo info = new ClientInfo(
            brand,
            protocolVersion,
            System.currentTimeMillis(),
            getServerProtocolVersion()
        );
        
        clientInfoMap.put(uuid, info);
        
        player.setMetadata("anticheat_client_brand", new FixedMetadataValue(plugin, brand));
        player.setMetadata("anticheat_protocol_version", new FixedMetadataValue(plugin, protocolVersion));
    }

    public boolean validateClient(Player player) {
        UUID uuid = player.getUniqueId();
        ClientInfo info = clientInfoMap.get(uuid);
        
        if (info == null) {
            return false;
        }
        
        if (!validateClientBrand(info.brand)) {
            plugin.getDetectionManager().getViolationManager().recordViolation(
                player,
                com.anticheat.detection.ViolationRecord.ViolationType.AUTO_MINER,
                "客户端品牌异常: " + (info.brand == null ? "空品牌" : info.brand),
                0.5
            );
            return false;
        }
        
        if (!validateProtocolVersion(info.protocolVersion)) {
            plugin.getDetectionManager().getViolationManager().recordViolation(
                player,
                com.anticheat.detection.ViolationRecord.ViolationType.AUTO_MINER,
                "协议版本异常: " + info.protocolVersion,
                0.5
            );
            return false;
        }
        
        return true;
    }

    public boolean validateClientBrand(String brand) {
        if (brand == null || brand.isEmpty()) {
            return false;
        }
        
        String lowerBrand = brand.toLowerCase();
        
        if (lowerBrand.contains("hack") || lowerBrand.contains("cheat") || 
            lowerBrand.contains("exploit") || lowerBrand.contains("inject")) {
            return false;
        }
        
        return true;
    }

    public boolean validateProtocolVersion(int clientVersion) {
        int serverVersion = getServerProtocolVersion();
        
        int versionDiff = Math.abs(clientVersion - serverVersion);
        
        return versionDiff <= 5;
    }

    public void recordPacketTiming(Player player, long timestamp) {
        UUID uuid = player.getUniqueId();
        List<Long> timings = packetTimings.computeIfAbsent(uuid, k -> new ArrayList<>());
        timings.add(timestamp);
        
        if (timings.size() > 200) {
            timings.remove(0);
        }
        
        Map<String, Long> stats = playerPacketStats.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
        stats.merge("total_packets", 1L, Long::sum);
        stats.put("last_packet_time", timestamp);
        
        if (stats.get("first_packet_time") == null) {
            stats.put("first_packet_time", timestamp);
        }
    }

    public boolean detectFakedDelay(UUID playerUUID) {
        List<Long> timings = packetTimings.get(playerUUID);
        if (timings == null || timings.size() < 30) {
            return false;
        }
        
        List<Long> recentTimings = timings.subList(Math.max(0, timings.size() - 30), timings.size());
        
        List<Long> intervals = new ArrayList<>();
        long previous = recentTimings.get(0);
        for (int i = 1; i < recentTimings.size(); i++) {
            long interval = recentTimings.get(i) - previous;
            if (interval > 0 && interval < MAX_PACKET_INTERVAL_MS) {
                intervals.add(interval);
            }
            previous = recentTimings.get(i);
        }
        
        if (intervals.size() < 10) {
            return false;
        }
        
        double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(0);
        double variance = intervals.stream()
            .mapToDouble(i -> Math.pow(i - mean, 2))
            .average()
            .orElse(Double.MAX_VALUE);
        double stdDev = Math.sqrt(variance);
        
        if (mean < MIN_PACKET_INTERVAL_MS * 2) {
            return false;
        }
        
        double coefficientOfVariation = stdDev / mean;
        
        return coefficientOfVariation < PACKET_RATE_TOLERANCE;
    }

    public boolean validatePacketStructure(Player player, Object packet) {
        if (packet == null) {
            return false;
        }
        
        String packetClassName = packet.getClass().getSimpleName();
        
        if (packetClassName.contains("非法") || packetClassName.contains("Invalid")) {
            plugin.getDetectionManager().getViolationManager().recordViolation(
                player,
                com.anticheat.detection.ViolationRecord.ViolationType.AUTO_MINER,
                "非法数据包结构: " + packetClassName,
                0.8
            );
            return false;
        }
        
        return true;
    }

    public void recordPacketSequence(Player player, int sequenceId) {
        UUID uuid = player.getUniqueId();
        List<Integer> sequence = packetSequenceMap.computeIfAbsent(uuid, k -> new ArrayList<>());
        sequence.add(sequenceId);
        
        if (sequence.size() > 100) {
            sequence.remove(0);
        }
    }

    public boolean detectPacketSequenceAnomaly(UUID playerUUID) {
        List<Integer> sequence = packetSequenceMap.get(playerUUID);
        if (sequence == null || sequence.size() < 20) {
            return false;
        }
        
        List<Integer> recentSequence = sequence.subList(Math.max(0, sequence.size() - 20), sequence.size());
        
        for (int i = 1; i < recentSequence.size(); i++) {
            int diff = recentSequence.get(i) - recentSequence.get(i - 1);
            
            if (diff <= 0 || diff > 100) {
                return false;
            }
        }
        
        return true;
    }

    public void checkPacketRate(Player player) {
        UUID uuid = player.getUniqueId();
        Map<String, Long> stats = playerPacketStats.get(uuid);
        
        if (stats == null) {
            return;
        }
        
        Long firstTime = stats.get("first_packet_time");
        Long totalPackets = stats.get("total_packets");
        
        if (firstTime == null || totalPackets == null) {
            return;
        }
        
        long duration = System.currentTimeMillis() - firstTime;
        if (duration <= 0) {
            return;
        }
        
        double packetsPerSecond = (totalPackets * 1000.0) / duration;
        
        if (packetsPerSecond > 100) {
            plugin.getDetectionManager().getViolationManager().recordViolation(
                player,
                com.anticheat.detection.ViolationRecord.ViolationType.AUTO_MINER,
                "数据包频率异常: " + String.format("%.1f", packetsPerSecond) + " 包/秒",
                0.6
            );
        }
    }

    private void validatePlayerConnection(Player player) {
        UUID uuid = player.getUniqueId();
        
        if (!validateClient(player)) {
            plugin.getLogger().warning("[ProtocolValidator] 玩家 " + player.getName() + 
                " 客户端验证失败");
        }
        
        if (detectFakedDelay(uuid)) {
            plugin.getDetectionManager().getViolationManager().recordViolation(
                player,
                com.anticheat.detection.ViolationRecord.ViolationType.AUTO_MINER,
                "检测到伪造延迟",
                0.7
            );
            
            plugin.getLogger().warning("[ProtocolValidator] 玩家 " + player.getName() + 
                " 检测到伪造延迟");
        }
        
        checkPacketRate(player);
    }

    public ClientInfo getClientInfo(UUID uuid) {
        return clientInfoMap.get(uuid);
    }

    public void removeClientInfo(UUID uuid) {
        clientInfoMap.remove(uuid);
        packetTimings.remove(uuid);
        packetSequenceMap.remove(uuid);
        playerPacketStats.remove(uuid);
    }

    public Set<String> getKnownClientBrands() {
        return Collections.unmodifiableSet(knownClientBrands);
    }

    public void addKnownClientBrand(String brand) {
        if (brand != null && !brand.isEmpty()) {
            knownClientBrands.add(brand.toLowerCase());
        }
    }

    private int getServerProtocolVersion() {
        String version = plugin.getServer().getBukkitVersion();
        
        if (version.contains("1.8")) {
            return PROTOCOL_VERSION_1_8;
        } else if (version.contains("1.9") || version.contains("1.10") || 
                   version.contains("1.11") || version.contains("1.12")) {
            return PROTOCOL_VERSION_1_12;
        } else if (version.contains("1.13") || version.contains("1.14") || 
                   version.contains("1.15")) {
            return PROTOCOL_VERSION_1_16;
        } else if (version.contains("1.16")) {
            return PROTOCOL_VERSION_1_16;
        } else if (version.contains("1.17") || version.contains("1.18") || 
                   version.contains("1.19")) {
            return PROTOCOL_VERSION_1_19;
        } else if (version.contains("1.20") || version.contains("1.21")) {
            return PROTOCOL_VERSION_1_21;
        }
        
        return PROTOCOL_VERSION_1_8;
    }

    public static class ClientInfo {
        private final String brand;
        private final int protocolVersion;
        private final long connectionTime;
        private final int serverProtocolVersion;

        public ClientInfo(String brand, int protocolVersion, long connectionTime, int serverProtocolVersion) {
            this.brand = brand;
            this.protocolVersion = protocolVersion;
            this.connectionTime = connectionTime;
            this.serverProtocolVersion = serverProtocolVersion;
        }

        public String getBrand() {
            return brand;
        }

        public int getProtocolVersion() {
            return protocolVersion;
        }

        public long getConnectionTime() {
            return connectionTime;
        }

        public int getServerProtocolVersion() {
            return serverProtocolVersion;
        }

        public boolean isVersionMatch() {
            return Math.abs(protocolVersion - serverProtocolVersion) <= 5;
        }

        @Override
        public String toString() {
            return "ClientInfo{" +
                "brand='" + brand + '\'' +
                ", protocolVersion=" + protocolVersion +
                ", serverProtocolVersion=" + serverProtocolVersion +
                ", connectionTime=" + connectionTime +
                '}';
        }
    }
}
