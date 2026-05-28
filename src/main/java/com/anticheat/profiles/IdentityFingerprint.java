package com.anticheat.profiles;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class IdentityFingerprint implements Serializable {
    private static final long serialVersionUID = 1L;

    private String currentName;
    private List<String> historicalNames;
    private long firstJoinTime;
    private long totalPlayTime;

    private String currentIP;
    private List<IPRecord> ipHistory;

    private String clientBrand;
    private String protocolVersion;
    private int renderDistance;
    private String language;

    private String inferredOS;
    private String javaVersion;
    private String hardwareFingerprint;

    public IdentityFingerprint() {
        this.historicalNames = new ArrayList<>();
        this.ipHistory = new ArrayList<>();
    }

    public void addHistoricalName(String name) {
        if (!historicalNames.contains(name)) {
            historicalNames.add(name);
        }
    }

    public void addIPRecord(String ip, String location, String isp) {
        ipHistory.add(new IPRecord(ip, location, isp, System.currentTimeMillis()));
    }

    public static class IPRecord implements Serializable {
        private static final long serialVersionUID = 1L;
        public String ip;
        public String location;
        public String isp;
        public long timestamp;

        public IPRecord(String ip, String location, String isp, long timestamp) {
            this.ip = ip;
            this.location = location;
            this.isp = isp;
            this.timestamp = timestamp;
        }
    }

    public String getCurrentName() { return currentName; }
    public void setCurrentName(String currentName) { this.currentName = currentName; }
    public List<String> getHistoricalNames() { return historicalNames; }
    public long getFirstJoinTime() { return firstJoinTime; }
    public void setFirstJoinTime(long firstJoinTime) { this.firstJoinTime = firstJoinTime; }
    public long getTotalPlayTime() { return totalPlayTime; }
    public void setTotalPlayTime(long totalPlayTime) { this.totalPlayTime = totalPlayTime; }
    public String getCurrentIP() { return currentIP; }
    public void setCurrentIP(String currentIP) { this.currentIP = currentIP; }
    public List<IPRecord> getIpHistory() { return ipHistory; }
    public String getClientBrand() { return clientBrand; }
    public void setClientBrand(String clientBrand) { this.clientBrand = clientBrand; }
    public String getProtocolVersion() { return protocolVersion; }
    public void setProtocolVersion(String protocolVersion) { this.protocolVersion = protocolVersion; }
    public int getRenderDistance() { return renderDistance; }
    public void setRenderDistance(int renderDistance) { this.renderDistance = renderDistance; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getInferredOS() { return inferredOS; }
    public void setInferredOS(String inferredOS) { this.inferredOS = inferredOS; }
    public String getJavaVersion() { return javaVersion; }
    public void setJavaVersion(String javaVersion) { this.javaVersion = javaVersion; }
    public String getHardwareFingerprint() { return hardwareFingerprint; }
    public void setHardwareFingerprint(String hardwareFingerprint) { this.hardwareFingerprint = hardwareFingerprint; }
}

