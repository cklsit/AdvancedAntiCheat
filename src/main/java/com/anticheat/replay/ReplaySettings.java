package com.anticheat.replay;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 回放与监视相关配置的集中访问器。
 *
 * <p>所有字段在 {@link #reload()} 时从 config.yml 重新读取，因此面板或文件改动后
 * 调用 {@code reload()} 即可热生效，无需重启服务器。</p>
 *
 * <p>配置根路径为 {@code replay.*}，缺失项一律回退到代码内默认值，
 * 保证老版本 config.yml 升级后不会因缺键而失效。</p>
 */
public final class ReplaySettings {

    /** 并发监视上限的默认值（需求 1 的 x）。 */
    public static final int DEFAULT_MAX_CONCURRENT = 3;

    private final AdvancedAntiCheat plugin;

    // ---------- surveillance ----------
    private boolean surveillanceEnabled = true;
    private boolean autoOnJoin = true;
    private int maxConcurrent = DEFAULT_MAX_CONCURRENT;
    private boolean queueEnabled = true;
    private int queueMaxSize = 50;
    private boolean priorityBoostOnViolation = false;
    private List<String> excludePermissions = Collections.singletonList("anticheat.admin");
    private List<String> excludeUuids = Collections.emptyList();

    // ---------- camera ----------
    private CameraMode cameraMode = CameraMode.ATTACH;
    private boolean autoShoulderOnViolation = true;
    private long autoShoulderHoldMs = 8_000L;
    private double shoulderDistance = 3.0;
    private double shoulderHeight = 1.2;
    private double shoulderYawOffset = 0.0;
    private boolean allowManualSwitch = true;

    // ---------- video quality ----------
    private int minHeight = 720;
    private int minBitrateKbps = 1200;
    private boolean evidenceLock = true;

    // ---------- telemetry ----------
    private boolean crosshairEnabled = true;
    private double crosshairMaxDistance = 6.0;
    private boolean fullInventory = true;

    // ---------- observer client（§4.4.3 世界完整性） ----------
    /** 观察者客户端渲染距离（区块）。过大加载慢、过小视野差。 */
    private int renderDistance = 8;
    /** 绑定前预热的目标周围区块半径（0=关闭）。 */
    private int chunkPrewarmRadius = 3;

    public ReplaySettings(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        reload();
    }

    /** 重新读取配置。必须在主线程调用（plugin.getConfig() 非线程安全）。 */
    public void reload() {
        FileConfiguration c = plugin.getConfig();

        surveillanceEnabled = c.getBoolean("replay.surveillance.enabled", true);
        autoOnJoin = c.getBoolean("replay.surveillance.autoOnJoin", true);
        maxConcurrent = Math.max(1, c.getInt("replay.surveillance.maxConcurrent", DEFAULT_MAX_CONCURRENT));
        queueEnabled = c.getBoolean("replay.surveillance.queue.enabled", true);
        queueMaxSize = Math.max(1, c.getInt("replay.surveillance.queue.maxSize", 50));
        priorityBoostOnViolation = c.getBoolean("replay.surveillance.queue.priorityBoostOnViolation", false);
        excludePermissions = lower(c.getStringList("replay.surveillance.exclude.permissions"));
        excludeUuids = lower(c.getStringList("replay.surveillance.exclude.uuids"));

        cameraMode = CameraMode.parse(c.getString("replay.capture.camera.mode"), CameraMode.ATTACH);
        autoShoulderOnViolation = c.getBoolean("replay.capture.camera.autoShoulderOnViolation", true);
        autoShoulderHoldMs = Math.max(1_000L,
                c.getLong("replay.capture.camera.autoShoulderHoldSeconds", 8L) * 1000L);
        shoulderDistance = c.getDouble("replay.capture.camera.shoulder.distance", 3.0);
        shoulderHeight = c.getDouble("replay.capture.camera.shoulder.height", 1.2);
        shoulderYawOffset = c.getDouble("replay.capture.camera.shoulder.yawOffset", 0.0);
        allowManualSwitch = c.getBoolean("replay.capture.camera.allowManualSwitch", true);

        minHeight = c.getInt("replay.capture.video.minHeight", 720);
        minBitrateKbps = c.getInt("replay.capture.video.minBitrateKbps", 1200);
        evidenceLock = c.getBoolean("replay.capture.video.evidenceLock", true);

        crosshairEnabled = c.getBoolean("replay.telemetry.crosshair.enabled", true);
        crosshairMaxDistance = c.getDouble("replay.telemetry.crosshair.maxDistance", 6.0);
        fullInventory = c.getBoolean("replay.telemetry.inventory.fullInventory", true);

        renderDistance = Math.max(2, c.getInt("replay.observer.client.renderDistance", 8));
        chunkPrewarmRadius = Math.max(0, c.getInt("replay.observer.client.chunkPrewarmRadius", 3));
    }

    private static List<String> lower(List<String> in) {
        if (in == null || in.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) {
            if (s != null) out.add(s.trim().toLowerCase());
        }
        return out;
    }

    // ---------- getters ----------

    public AdvancedAntiCheat getPlugin() { return plugin; }

    public boolean isSurveillanceEnabled() { return surveillanceEnabled; }
    public boolean isAutoOnJoin() { return autoOnJoin; }
    public int getMaxConcurrent() { return maxConcurrent; }
    public boolean isQueueEnabled() { return queueEnabled; }
    public int getQueueMaxSize() { return queueMaxSize; }
    public boolean isPriorityBoostOnViolation() { return priorityBoostOnViolation; }
    public List<String> getExcludePermissions() { return excludePermissions; }
    public List<String> getExcludeUuids() { return excludeUuids; }

    public CameraMode getCameraMode() { return cameraMode; }
    public boolean isAutoShoulderOnViolation() { return autoShoulderOnViolation; }
    public long getAutoShoulderHoldMs() { return autoShoulderHoldMs; }
    public double getShoulderDistance() { return shoulderDistance; }
    public double getShoulderHeight() { return shoulderHeight; }
    public double getShoulderYawOffset() { return shoulderYawOffset; }
    public boolean isAllowManualSwitch() { return allowManualSwitch; }

    public int getMinHeight() { return minHeight; }
    public int getMinBitrateKbps() { return minBitrateKbps; }
    public boolean isEvidenceLock() { return evidenceLock; }

    public boolean isCrosshairEnabled() { return crosshairEnabled; }
    public double getCrosshairMaxDistance() { return crosshairMaxDistance; }
    public boolean isFullInventory() { return fullInventory; }

    public int getRenderDistance() { return renderDistance; }
    public int getChunkPrewarmRadius() { return chunkPrewarmRadius; }

    /** 把当前生效值写回 config（面板"保存并应用"用），缺失键会被补齐。 */
    public void persist() {
        FileConfiguration c = plugin.getConfig();
        c.set("replay.surveillance.enabled", surveillanceEnabled);
        c.set("replay.surveillance.autoOnJoin", autoOnJoin);
        c.set("replay.surveillance.maxConcurrent", maxConcurrent);
        c.set("replay.surveillance.queue.enabled", queueEnabled);
        c.set("replay.surveillance.queue.maxSize", queueMaxSize);
        c.set("replay.surveillance.queue.priorityBoostOnViolation", priorityBoostOnViolation);
        c.set("replay.capture.camera.mode", cameraMode.name().toLowerCase());
        c.set("replay.capture.camera.autoShoulderOnViolation", autoShoulderOnViolation);
        c.set("replay.capture.camera.autoShoulderHoldSeconds", autoShoulderHoldMs / 1000L);
        c.set("replay.capture.camera.shoulder.distance", shoulderDistance);
        c.set("replay.capture.camera.shoulder.height", shoulderHeight);
        c.set("replay.capture.camera.shoulder.yawOffset", shoulderYawOffset);
        c.set("replay.capture.camera.allowManualSwitch", allowManualSwitch);
        c.set("replay.capture.video.minHeight", minHeight);
        c.set("replay.capture.video.minBitrateKbps", minBitrateKbps);
        c.set("replay.capture.video.evidenceLock", evidenceLock);
        c.set("replay.telemetry.crosshair.enabled", crosshairEnabled);
        c.set("replay.telemetry.crosshair.maxDistance", crosshairMaxDistance);
        c.set("replay.telemetry.inventory.fullInventory", fullInventory);
        c.set("replay.observer.client.renderDistance", renderDistance);
        c.set("replay.observer.client.chunkPrewarmRadius", chunkPrewarmRadius);
        plugin.saveConfig();
    }

    /** 热更新单个字段（面板编辑用），返回是否识别该键。 */
    public boolean setField(String key, Object value) {
        if (key == null || value == null) return false;
        switch (key) {
            case "maxConcurrent":
                if (value instanceof Number) { maxConcurrent = Math.max(1, ((Number) value).intValue()); return true; }
                return false;
            case "surveillanceEnabled":
                if (value instanceof Boolean) { surveillanceEnabled = (Boolean) value; return true; }
                return false;
            case "autoOnJoin":
                if (value instanceof Boolean) { autoOnJoin = (Boolean) value; return true; }
                return false;
            case "queueEnabled":
                if (value instanceof Boolean) { queueEnabled = (Boolean) value; return true; }
                return false;
            case "queueMaxSize":
                if (value instanceof Number) { queueMaxSize = Math.max(1, ((Number) value).intValue()); return true; }
                return false;
            case "cameraMode":
                cameraMode = CameraMode.parse(String.valueOf(value), cameraMode);
                return true;
            case "autoShoulderOnViolation":
                if (value instanceof Boolean) { autoShoulderOnViolation = (Boolean) value; return true; }
                return false;
            default:
                return false;
        }
    }
}
