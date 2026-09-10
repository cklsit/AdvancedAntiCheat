package com.anticheat;

import com.anticheat.commands.*;
import com.anticheat.compat.CompatManager;
import com.anticheat.captcha.CaptchaManager;
import com.anticheat.bounty.BountyManager;
import com.anticheat.listeners.*;
import com.anticheat.managers.*;
import com.anticheat.managers.ffmpeg.FfmpegManager;
import com.anticheat.profiles.BehaviorTracker;
import com.anticheat.profiles.PlayerProfile;
import com.anticheat.replay.CameraBinder;
import com.anticheat.replay.ReplaySettings;
import com.anticheat.replay.SurveillanceScheduler;
import com.anticheat.utils.VersionUtil;
import com.anticheat.web.WebServer;
import com.anticheat.web.auth.AuthManager;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class AdvancedAntiCheat extends JavaPlugin {

    private BanManager banManager;
    private ReportManager reportManager;
    private DetectionManager detectionManager;
    private ConfigManager configManager;
    private CheckClientManager checkClientManager;
    private CheckClientConfigManager checkClientConfigManager;
    private BehaviorTracker behaviorTracker;
    private CaptchaManager captchaManager;
    private BountyManager bountyManager;
    private ProfileManager profileManager;
    private com.anticheat.listeners.ProfileGUIListener profileGUIListener;
    private AdvancedDetectionManager advancedDetectionManager;

    // 违规回放
    private ReplayRecorder replayRecorder;
    // 观察者客户端跟随管理器（ReplayObserver_* 每 tick teleport）
    private ObserverFollowManager observerFollowManager;
    // 容器 observerctl HTTP 客户端：HLS 录制启动/停止、mp4 合成
    private FfmpegManager ffmpegManager;
    // 观察者池调度：observer 分配 / ffmpeg 启停 / 跟随 / 订阅计数 / 错误重试
    private ObserverPoolManager observerPoolManager;
    // Replay WS 广播器（由 WebServer 创建后注入，供全局推送 HUD/事件）
    private com.anticheat.web.ws.replay.ReplayBroadcaster replayBroadcaster;

    // 违规回放重构：统一配置 / 摄像机绑定 / 监视调度
    private ReplaySettings replaySettings;
    private CameraBinder cameraBinder;
    private SurveillanceScheduler surveillanceScheduler;

    // Web 面板相关
    private AuditManager auditManager;
    private AuthManager authManager;
    private WebServer webServer;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        String version = VersionUtil.getVersion();
        boolean isHighVersion = VersionUtil.isHighVersion();

        getLogger().info("§6[AdvancedAntiCheat] 检测到服务器版本: " + version);
        getLogger().info("§6[AdvancedAntiCheat] 使用" + (isHighVersion ? "高版本" : "低版本") + "兼容模式");

        initializeManagers();
        registerListeners();
        registerCommands();

        startRiskDecayTask();

        // 启动 Web 面板（依赖 AuditManager、BanManager 已就绪）
        startWebPanel();

        getLogger().info("§2[AdvancedAntiCheat] 插件已成功启用！");
        getLogger().info("§6[AdvancedAntiCheat] 保护您的服务器免受作弊侵害！");
    }

    @Override
    public void onDisable() {
        // 先停 Web 面板，避免后续保存过程中触发脏推送
        if (webServer != null) {
            webServer.stop();
        }
        if (auditManager != null) {
            // AuditManager 当前无 close 钩子，预留扩展位
        }
        banManager.saveBans();
        reportManager.saveReports();
        checkClientManager.saveCheckData();
        if (behaviorTracker != null) {
            behaviorTracker.saveAllProfiles();
        }
        if (bountyManager != null) {
            bountyManager.onDisable();
        }
        if (profileManager != null) {
            profileManager.shutdown();
        }
        if (advancedDetectionManager != null) {
            advancedDetectionManager.shutdown();
        }
        if (replayRecorder != null) {
            replayRecorder.shutdown();
        }
        // 先停 observer pool（内部会调 ffmpeg.stopStream + 停止跟随），再停 follow/ffmpeg
        if (observerPoolManager != null) {
            observerPoolManager.shutdown();
        }
        if (observerFollowManager != null) {
            observerFollowManager.shutdown();
        }
        if (ffmpegManager != null) {
            ffmpegManager.shutdown();
        }
        if (surveillanceScheduler != null) {
            surveillanceScheduler.shutdown();
        }
        if (cameraBinder != null) {
            cameraBinder.shutdown();
        }
        getLogger().info("§4[AdvancedAntiCheat] 插件已禁用！");
    }

    private void initializeManagers() {
        File dataFolder = getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        configManager = new ConfigManager(this);
        checkClientConfigManager = new CheckClientConfigManager(this);
        banManager = new BanManager(this);
        reportManager = new ReportManager(this);
        detectionManager = new DetectionManager(this);
        checkClientManager = new CheckClientManager(this);
        behaviorTracker = new BehaviorTracker(this);
        captchaManager = new CaptchaManager(this);
        bountyManager = new BountyManager(this);
        profileManager = new ProfileManager(this);

        advancedDetectionManager = new AdvancedDetectionManager(this);
        advancedDetectionManager.initialize(this);

        try {
            replayRecorder = new ReplayRecorder(this);
        } catch (Throwable t) {
            getLogger().warning("[Replay] ReplayRecorder 初始化失败: " + t.getMessage());
        }

        try {
            observerFollowManager = new ObserverFollowManager(this);
        } catch (Throwable t) {
            getLogger().warning("[Replay] ObserverFollowManager 初始化失败: " + t.getMessage());
        }

        // === 新增 FfmpegManager：从 config.yml 读 observer 池 URL + hls 目录
        try {
            Map<Integer, String> urls = new HashMap<>();
            // 默认尝试从 config 读：replay.observer.{1,2,3}.url
            for (int i = 1; i <= 3; i++) {
                String def = "http://127.0.0.1:1808" + i; // 与 docker-compose 建议端口一致
                String url = getConfig().getString("replay.observer." + i + ".url", def);
                if (url != null && !url.trim().isEmpty()) urls.put(i, url.trim());
            }
            File hlsRoot = new File(getDataFolder(), getConfig().getString("replay.hls.dir", "hls"));
            hlsRoot.mkdirs();
            ffmpegManager = new FfmpegManager(this, urls, hlsRoot);
        } catch (Throwable t) {
            getLogger().warning("[Replay] FfmpegManager 初始化失败: " + t.getMessage());
            t.printStackTrace();
        }

        // ObserverPoolManager：依赖 replayRecorder / ffmpegManager / observerFollowManager 已就绪
        try {
            if (replayRecorder != null && ffmpegManager != null && observerFollowManager != null) {
                observerPoolManager = new ObserverPoolManager(this);
            } else {
                getLogger().warning("[Replay] ObserverPoolManager 跳过初始化：前置依赖未就绪");
            }
        } catch (Throwable t) {
            getLogger().warning("[Replay] ObserverPoolManager 初始化失败: " + t.getMessage());
            t.printStackTrace();
        }

        // === 重构新增：ReplaySettings / CameraBinder / SurveillanceScheduler ===
        try {
            replaySettings = new ReplaySettings(this);
            cameraBinder = new CameraBinder(this, replaySettings);
            if (observerPoolManager != null) {
                surveillanceScheduler = new SurveillanceScheduler(this, replaySettings, observerPoolManager);
                getLogger().info("[Replay] 监视调度器已启用：maxConcurrent=" + replaySettings.getMaxConcurrent()
                        + "，池大小=" + observerPoolManager.getPoolSize());
            } else {
                getLogger().warning("[Replay] SurveillanceScheduler 跳过初始化：ObserverPoolManager 未就绪");
            }
        } catch (Throwable t) {
            getLogger().warning("[Replay] 监视调度器初始化失败: " + t.getMessage());
            t.printStackTrace();
        }
    }

    /**
     * 启动内嵌 Web 面板：先初始化 AuditManager → AuthManager → WebServer。
     * WebServer.start() 内部在独立 daemon 线程启动 Javalin，不阻塞主线程。
     */
    private void startWebPanel() {
        try {
            auditManager = new AuditManager(this);
            authManager = new AuthManager(this);
            authManager.startCleaner();
            webServer = new WebServer(this, authManager);
            webServer.start();
        } catch (Throwable t) {
            getLogger().severe("[Web] Web 面板启动异常: " + t.getMessage());
            t.printStackTrace();
        }
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        // AuthManager 配置热更
        if (authManager != null) {
            try {
                authManager.reload();
            } catch (Throwable t) {
                getLogger().warning("[Web] 重新加载 AuthManager 配置失败: " + t.getMessage());
            }
        }
        // 回放/监视配置热更
        if (replaySettings != null) {
            try {
                replaySettings.reload();
            } catch (Throwable t) {
                getLogger().warning("[Replay] 重新加载回放配置失败: " + t.getMessage());
            }
        }
        if (surveillanceScheduler != null) {
            try {
                surveillanceScheduler.onConfigReload();
            } catch (Throwable t) {
                getLogger().warning("[Replay] 调度器热更失败: " + t.getMessage());
            }
        }
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new PlayerMoveListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerQuitListener(this), this);
        // 观察者客户端 ReplayObserver_* 登录/登出
        getServer().getPluginManager().registerEvents(
                new ReplayObserverLoginListener(this, observerFollowManager), this);
        getServer().getPluginManager().registerEvents(new PlayerCommandListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerLoginListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerCheckListener(this), this);
        getServer().getPluginManager().registerEvents(new BehaviorListener(this), this);
        getServer().getPluginManager().registerEvents(new CaptchaListener(this), this);
        getServer().getPluginManager().registerEvents(new BountyListener(this), this);
        profileGUIListener = new com.anticheat.listeners.ProfileGUIListener(this);
        getServer().getPluginManager().registerEvents(profileGUIListener, this);

        if (VersionUtil.isHighVersion()) {
            getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
            getServer().getMessenger().registerIncomingPluginChannel(this, "BungeeCord", new BungeeCordMessageListener(this));
        }
    }

    private void registerCommands() {
        getCommand("report").setExecutor(new ReportCommand(this));
        getCommand("goto").setExecutor(new GotoCommand(this));
        getCommand("ban").setExecutor(new BanCommand(this));
        getCommand("unban").setExecutor(new UnbanCommand(this));
        getCommand("anticheat").setExecutor(new AntiCheatCommand(this));
        getCommand("ac").setExecutor(new AntiCheatCommand(this));
        getCommand("checkclient").setExecutor(new CheckClientCommand(this));
        getCommand("checkdone").setExecutor(new CheckDoneCommand(this));
        getCommand("captcha").setExecutor(new CaptchaCommand(this));
        getCommand("bounty").setExecutor(new BountyCommand(this));

        // 观察者客户端跟随控制命令
        ReplayObserverCommand replayCmd = new ReplayObserverCommand(this);
        try {
            getCommand("aac_replay_follow").setExecutor(replayCmd);
            getCommand("aac_replay_unfollow").setExecutor(replayCmd);
        } catch (NullPointerException e) {
            getLogger().warning("[Replay] aac_replay_follow/unfollow 命令未在 plugin.yml 注册");
        }
    }

    public BanManager getBanManager() {
        return banManager;
    }

    public DatabaseManager getDatabaseManager() {
        return banManager.getDatabaseManager();
    }

    public ReportManager getReportManager() {
        return reportManager;
    }

    public DetectionManager getDetectionManager() {
        return detectionManager;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public CheckClientManager getCheckClientManager() {
        return checkClientManager;
    }

    public CheckClientConfigManager getCheckClientConfigManager() {
        return checkClientConfigManager;
    }

    public BehaviorTracker getBehaviorTracker() {
        return behaviorTracker;
    }

    public CaptchaManager getCaptchaManager() {
        return captchaManager;
    }

    public BountyManager getBountyManager() {
        return bountyManager;
    }

    public ProfileManager getProfileManager() {
        return profileManager;
    }

    public com.anticheat.listeners.ProfileGUIListener getProfileGUIListener() {
        return profileGUIListener;
    }

    public AdvancedDetectionManager getAdvancedDetectionManager() {
        return advancedDetectionManager;
    }

    public AuditManager getAuditManager() {
        return auditManager;
    }

    public AuthManager getAuthManager() {
        return authManager;
    }

    public WebServer getWebServer() {
        return webServer;
    }

    public ReplayRecorder getReplayRecorder() {
        return replayRecorder;
    }

    public ObserverFollowManager getObserverFollowManager() {
        return observerFollowManager;
    }

    public FfmpegManager getFfmpegManager() {
        return ffmpegManager;
    }

    public ObserverPoolManager getObserverPoolManager() {
        return observerPoolManager;
    }

    public ReplaySettings getReplaySettings() {
        return replaySettings;
    }

    public CameraBinder getCameraBinder() {
        return cameraBinder;
    }

    public SurveillanceScheduler getSurveillanceScheduler() {
        return surveillanceScheduler;
    }

    public com.anticheat.web.ws.replay.ReplayBroadcaster getReplayBroadcaster() {
        return replayBroadcaster;
    }

    public void setReplayBroadcaster(com.anticheat.web.ws.replay.ReplayBroadcaster b) {
        this.replayBroadcaster = b;
    }

    private void startRiskDecayTask() {
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            for (PlayerProfile profile : profileManager.getCachedProfiles().values()) {
                profile.decayRiskScore();
            }
        }, 20L * 60 * 60, 20L * 60 * 60);
    }
}
