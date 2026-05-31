package com.anticheat.detection.core;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BaseDetectionModule抽象类，所有检测模块的基类。
 * 提供通用方法和统计功能，管理违规计数和冷却时间。
 */
public abstract class BaseDetectionModule {

    private final String name;
    private final Map<UUID, Integer> violationCounts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastViolationTime = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    private boolean enabled = true;
    private int violationThreshold = 3;
    private long cooldownTime = 60000L;

    protected BaseDetectionModule(String name) {
        this.name = name;
    }

    /**
     * 分析玩家行为（子类实现）
     * @param player 要分析的玩家
     * @return 证据对象，如果无异常返回null
     */
    protected abstract Evidence analyze(Player player);

    /**
     * 获取分析数据（子类实现）
     * @param player 要获取数据的玩家
     * @return 包含分析数据的Map
     */
    protected abstract Map<String, Object> getAnalysisData(Player player);

    /**
     * 计算作弊概率（子类实现）
     * @param evidence 证据对象
     * @param player 相关玩家
     * @return 概率值（0.0-1.0）
     */
    protected abstract double calculateProbability(Evidence evidence, Player player);

    /**
     * 获取模块名称
     * @return 模块名称
     */
    public String getName() {
        return name;
    }

    /**
     * 检查模块是否启用
     * @return 是否启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置模块启用状态
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 管理违规计数
     * @param playerUUID 玩家UUID
     */
    public void incrementViolation(UUID playerUUID) {
        int count = violationCounts.getOrDefault(playerUUID, 0);
        violationCounts.put(playerUUID, count + 1);
    }

    /**
     * 获取玩家违规次数
     * @param playerUUID 玩家UUID
     * @return 违规次数
     */
    public int getViolationCount(UUID playerUUID) {
        return violationCounts.getOrDefault(playerUUID, 0);
    }

    /**
     * 清除玩家违规记录
     * @param playerUUID 玩家UUID
     */
    public void clearViolations(UUID playerUUID) {
        violationCounts.remove(playerUUID);
        lastViolationTime.remove(playerUUID);
    }

    /**
     * 检查玩家是否在冷却中
     * @param playerUUID 玩家UUID
     * @return 是否在冷却中
     */
    public boolean isOnCooldown(UUID playerUUID) {
        Long lastTime = lastViolationTime.get(playerUUID);
        if (lastTime == null) {
            return false;
        }
        return System.currentTimeMillis() - lastTime < cooldownTime;
    }

    /**
     * 设置玩家冷却
     * @param playerUUID 玩家UUID
     */
    public void setCooldown(UUID playerUUID) {
        cooldowns.put(playerUUID, System.currentTimeMillis());
    }

    /**
     * 检查玩家是否在冷却中
     * @param playerUUID 玩家UUID
     * @return 是否在冷却中
     */
    public boolean isOnCooldownPlayer(UUID playerUUID) {
        Long cooldownStart = cooldowns.get(playerUUID);
        if (cooldownStart == null) {
            return false;
        }
        return System.currentTimeMillis() - cooldownStart < cooldownTime;
    }

    /**
     * 获取违规阈值
     * @return 违规阈值
     */
    public int getViolationThreshold() {
        return violationThreshold;
    }

    /**
     * 设置违规阈值
     * @param threshold 违规阈值
     */
    public void setViolationThreshold(int threshold) {
        this.violationThreshold = threshold;
    }

    /**
     * 获取冷却时间
     * @return 冷却时间（毫秒）
     */
    public long getCooldownTime() {
        return cooldownTime;
    }

    /**
     * 设置冷却时间
     * @param cooldownTime 冷却时间（毫秒）
     */
    public void setCooldownTime(long cooldownTime) {
        this.cooldownTime = cooldownTime;
    }

    /**
     * 获取模块统计信息
     * @return 统计信息Map
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("name", name);
        stats.put("enabled", enabled);
        stats.put("violationThreshold", violationThreshold);
        stats.put("cooldownTime", cooldownTime);
        stats.put("totalViolations", violationCounts.size());
        return stats;
    }

    /**
     * 检查玩家是否豁免检测
     * @param player 玩家
     * @return 是否豁免
     */
    protected boolean isExempt(Player player) {
        if (player == null || !player.isOnline()) {
            return true;
        }
        return player.hasPermission("anticheat.bypass") ||
               player.getGameMode() == org.bukkit.GameMode.CREATIVE ||
               player.getGameMode() == org.bukkit.GameMode.SPECTATOR;
    }
}
