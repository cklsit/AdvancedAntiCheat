package com.anticheat.detection.association;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DeviceFingerprint implements Serializable {
    private static final long serialVersionUID = 1L;

    private final UUID playerUUID;
    private String ipAddress;
    private String clientBrand;
    private String clientVersion;
    private String operatingSystem;
    private String javaVersion;
    private String worldAddress;
    private String serverId;
    private long firstSeen;
    private long lastSeen;
    private int loginCount;

    private final Map<String, String> customProperties;
    private final List<String> previousIPAddresses;
    private final Set<String> uniqueSessionIds;

    public DeviceFingerprint(UUID playerUUID) {
        this.playerUUID = playerUUID;
        this.firstSeen = System.currentTimeMillis();
        this.lastSeen = System.currentTimeMillis();
        this.loginCount = 0;
        this.customProperties = new ConcurrentHashMap<>();
        this.previousIPAddresses = Collections.synchronizedList(new ArrayList<>());
        this.uniqueSessionIds = Collections.synchronizedSet(new HashSet<>());
    }

    public void updateIPAddress(String newIP) {
        if (newIP != null && !newIP.equals(this.ipAddress)) {
            if (this.ipAddress != null && !this.previousIPAddresses.contains(this.ipAddress)) {
                this.previousIPAddresses.add(this.ipAddress);
            }
            this.ipAddress = newIP;
            this.lastSeen = System.currentTimeMillis();
        }
    }

    public void incrementLoginCount() {
        this.loginCount++;
        this.lastSeen = System.currentTimeMillis();
    }

    public List<UUID> findRelatedAccounts() {
        List<UUID> relatedAccounts = new ArrayList<>();

        if (ipAddress == null) {
            return relatedAccounts;
        }

        return relatedAccounts;
    }

    public double calculateSimilarity(DeviceFingerprint other) {
        if (other == null) {
            return 0.0;
        }

        double similarity = 0.0;
        int comparisonCount = 0;

        if (this.ipAddress != null && other.ipAddress != null) {
            if (this.ipAddress.equals(other.ipAddress)) {
                similarity += 1.0;
            } else if (this.ipAddress.substring(0, Math.min(this.ipAddress.length(), 6))
                       .equals(other.ipAddress.substring(0, Math.min(other.ipAddress.length(), 6)))) {
                similarity += 0.5;
            }
            comparisonCount++;
        }

        if (this.clientBrand != null && other.clientBrand != null) {
            if (this.clientBrand.equals(other.clientBrand)) {
                similarity += 1.0;
            }
            comparisonCount++;
        }

        if (this.clientVersion != null && other.clientVersion != null) {
            if (this.clientVersion.equals(other.clientVersion)) {
                similarity += 1.0;
            }
            comparisonCount++;
        }

        if (this.operatingSystem != null && other.operatingSystem != null) {
            if (this.operatingSystem.equals(other.operatingSystem)) {
                similarity += 1.0;
            }
            comparisonCount++;
        }

        if (this.javaVersion != null && other.javaVersion != null) {
            if (this.javaVersion.equals(other.javaVersion)) {
                similarity += 0.8;
            }
            comparisonCount++;
        }

        int sharedIPs = countSharedIPAddresses(other);
        if (sharedIPs > 0) {
            similarity += Math.min(1.0, sharedIPs * 0.3);
            comparisonCount++;
        }

        return comparisonCount > 0 ? similarity / comparisonCount : 0.0;
    }

    private int countSharedIPAddresses(DeviceFingerprint other) {
        int sharedCount = 0;

        if (this.ipAddress != null && other.previousIPAddresses.contains(this.ipAddress)) {
            sharedCount++;
        }
        if (other.ipAddress != null && this.previousIPAddresses.contains(other.ipAddress)) {
            sharedCount++;
        }

        for (String ip : this.previousIPAddresses) {
            if (other.previousIPAddresses.contains(ip)) {
                sharedCount++;
            }
        }

        return sharedCount;
    }

    public String generateFingerprintHash() {
        StringBuilder sb = new StringBuilder();
        sb.append(ipAddress != null ? ipAddress : "");
        sb.append(clientBrand != null ? clientBrand : "");
        sb.append(clientVersion != null ? clientVersion : "");
        sb.append(operatingSystem != null ? operatingSystem : "");
        sb.append(javaVersion != null ? javaVersion : "");

        return String.valueOf(sb.toString().hashCode());
    }

    public void addCustomProperty(String key, String value) {
        customProperties.put(key, value);
    }

    public String getCustomProperty(String key) {
        return customProperties.get(key);
    }

    public boolean hasSharedIP(DeviceFingerprint other) {
        if (this.ipAddress == null || other.ipAddress == null) {
            return false;
        }

        if (this.ipAddress.equals(other.ipAddress)) {
            return true;
        }

        return this.previousIPAddresses.contains(other.ipAddress) ||
               other.previousIPAddresses.contains(this.ipAddress);
    }

    public boolean isSuspicious() {
        if (loginCount > 100) {
            return true;
        }

        if (previousIPAddresses.size() > 10) {
            return true;
        }

        return false;
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getClientBrand() {
        return clientBrand;
    }

    public void setClientBrand(String clientBrand) {
        this.clientBrand = clientBrand;
    }

    public String getClientVersion() {
        return clientVersion;
    }

    public void setClientVersion(String clientVersion) {
        this.clientVersion = clientVersion;
    }

    public String getOperatingSystem() {
        return operatingSystem;
    }

    public void setOperatingSystem(String operatingSystem) {
        this.operatingSystem = operatingSystem;
    }

    public String getJavaVersion() {
        return javaVersion;
    }

    public void setJavaVersion(String javaVersion) {
        this.javaVersion = javaVersion;
    }

    public String getWorldAddress() {
        return worldAddress;
    }

    public void setWorldAddress(String worldAddress) {
        this.worldAddress = worldAddress;
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public long getFirstSeen() {
        return firstSeen;
    }

    public void setFirstSeen(long firstSeen) {
        this.firstSeen = firstSeen;
    }

    public long getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(long lastSeen) {
        this.lastSeen = lastSeen;
    }

    public int getLoginCount() {
        return loginCount;
    }

    public void setLoginCount(int loginCount) {
        this.loginCount = loginCount;
    }

    public List<String> getPreviousIPAddresses() {
        return new ArrayList<>(previousIPAddresses);
    }

    public Set<String> getUniqueSessionIds() {
        return new HashSet<>(uniqueSessionIds);
    }

    public Map<String, String> getCustomProperties() {
        return new HashMap<>(customProperties);
    }
}
