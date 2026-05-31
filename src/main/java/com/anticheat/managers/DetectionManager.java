package com.anticheat.managers;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.detection.*;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 检测管理器
 *
 * @author AntiCheat Team
 * @version 1.0.0
 */
public class DetectionManager {

    private final AdvancedAntiCheat plugin;
    private final Map<String, Detection> detections = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> violations = new HashMap<>();
    private final ViolationManager violationManager;

    private static final Logger logger = Logger.getLogger(DetectionManager.class.getName());

    /**
     * 构造函数
     *
     * @param plugin 插件实例
     */
    public DetectionManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.violationManager = new ViolationManager(plugin);
        initializeDetections();
    }

    /**
     * 获取违规管理器
     *
     * @return 违规管理器
     */
    public ViolationManager getViolationManager() {
        return violationManager;
    }

    /**
     * 初始化检测模块
     */
    private void initializeDetections() {
        detections.put("fly", new FlyDetection(this));
        detections.put("speed", new SpeedDetection(this));
        detections.put("esp", new EspDetection(this));
        detections.put("killaura", new KillAuraDetection(this));
        detections.put("reach", new ReachDetection(this));
        detections.put("scaffold", new ScaffoldDetection(this));
        detections.put("fastbreak", new FastBreakDetection(this));
        detections.put("noslow", new NoSlowDetection(this));
    }

    /**
     * 获取检测模块
     *
     * @param type 检测类型
     * @return 检测模块
     * @deprecated 使用 {@link AdvancedDetectionManager#getModuleByName(String)} 代替
     */
    @Deprecated
    public Detection getDetection(String type) {
        return detections.get(type.toLowerCase());
    }

    /**
     * 添加违规
     *
     * @param player 玩家
     * @param type   违规类型
     */
    public void addViolation(Player player, String type) {
        UUID uuid = player.getUniqueId();
        violations.computeIfAbsent(uuid, k -> new HashMap<String, Integer>());

        Map<String, Integer> playerViolations = violations.get(uuid);
        int current = playerViolations.getOrDefault(type, 0) + 1;
        playerViolations.put(type, current);

        int maxViolations = plugin.getConfigManager().getMaxViolations(type);
        if (current >= maxViolations) {
            String banTime = plugin.getConfigManager().getBanTime(type);
            String reason = "检测到作弊: " + getDetectionName(type);
            plugin.getBanManager().banPlayer(uuid, player.getName(), banTime, reason);
            violations.remove(uuid);
        }
    }

    /**
     * 获取玩家违规次数
     *
     * @param player 玩家
     * @param type   违规类型
     * @return 违规次数
     */
    public int getViolations(Player player, String type) {
        UUID uuid = player.getUniqueId();
        return violations.getOrDefault(uuid, new HashMap<String, Integer>()).getOrDefault(type, 0);
    }

    /**
     * 清除玩家违规记录
     *
     * @param uuid 玩家UUID
     */
    public void clearViolations(UUID uuid) {
        violations.remove(uuid);
    }

    /**
     * 获取杀戮光环检测模块
     *
     * @return 杀戮光环检测模块
     */
    public KillAuraDetection getKillAuraDetection() {
        return (KillAuraDetection) detections.get("killaura");
    }

    /**
     * 获取攻击距离检测模块
     *
     * @return 攻击距离检测模块
     */
    public ReachDetection getReachDetection() {
        return (ReachDetection) detections.get("reach");
    }

    /**
     * 获取脚手架检测模块
     *
     * @return 脚手架检测模块
     */
    public ScaffoldDetection getScaffoldDetection() {
        return (ScaffoldDetection) detections.get("scaffold");
    }

    /**
     * 获取快速破坏检测模块
     *
     * @return 快速破坏检测模块
     */
    public FastBreakDetection getFastBreakDetection() {
        return (FastBreakDetection) detections.get("fastbreak");
    }

    /**
     * 获取无减速检测模块
     *
     * @return 无减速检测模块
     */
    public NoSlowDetection getNoSlowDetection() {
        return (NoSlowDetection) detections.get("noslow");
    }

    /**
     * 获取检测名称
     *
     * @param type 检测类型
     * @return 检测名称
     */
    private String getDetectionName(String type) {
        String lowerType = type.toLowerCase();
        switch (lowerType) {
            case "fly":
                return "飞行作弊";
            case "speed":
                return "速度作弊";
            case "esp":
                return "透视作弊";
            case "killaura":
                return "杀戮光环";
            case "reach":
                return "攻击距离作弊";
            case "scaffold":
                return "脚手架作弊";
            case "fastbreak":
                return "快速破坏";
            case "noslow":
                return "无减速挖掘";
            default:
                return type;
        }
    }

    /**
     * 获取插件实例
     *
     * @return 插件实例
     */
    public AdvancedAntiCheat getPlugin() {
        return plugin;
    }

    /**
     * 获取所有已注册的检测类型
     *
     * @return 检测类型映射
     */
    public Map<String, Detection> getAllDetections() {
        return new HashMap<>(detections);
    }

    /**
     * 注册新的检测模块
     *
     * @param name      模块名称
     * @param detection 检测模块
     */
    public void registerDetection(String name, Detection detection) {
        if (detection != null) {
            detections.put(name.toLowerCase(), detection);
            logger.info("Registered detection module: " + name);
        }
    }

    /**
     * 移除检测模块
     *
     * @param name 模块名称
     * @return 是否成功移除
     */
    public boolean unregisterDetection(String name) {
        return detections.remove(name.toLowerCase()) != null;
    }

    /**
     * 检查模块是否存在
     *
     * @param name 模块名称
     * @return 是否存在
     */
    public boolean hasDetection(String name) {
        return detections.containsKey(name.toLowerCase());
    }

    /**
     * 获取所有违规记录
     *
     * @return 违规记录映射
     */
    public Map<UUID, Map<String, Integer>> getAllViolations() {
        return new HashMap<>(violations);
    }

    /**
     * 清除所有违规记录
     */
    public void clearAllViolations() {
        violations.clear();
    }
}
