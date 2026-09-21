package com.anticheat;

import com.anticheat.commands.*;
import com.anticheat.captcha.CaptchaManager;
import com.anticheat.bounty.BountyManager;
import com.anticheat.core.AntiCheatCore;
import com.anticheat.core.platform.bukkit.BukkitPlatformLoader;
import com.anticheat.listeners.*;
import com.anticheat.managers.*;
import com.anticheat.profiles.BehaviorTracker;
import com.anticheat.profiles.PlayerProfile;
import com.anticheat.utils.VersionUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

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
    /** 可信白名单：名单内玩家挂载 anticheat.bypass 附件，全局豁免反作弊封禁 */
    private WhitelistManager whitelistManager;
    private com.anticheat.listeners.ProfileGUIListener profileGUIListener;
    /** /ac config 多级管理界面 */
    private com.anticheat.listeners.ConfigGUIListener configGUIListener;
    private AdvancedDetectionManager advancedDetectionManager;
    /** AI 实验室：特征工程 + 个人基线 + 孤立森林 + 集群发现 + 自适应阈值 + 监督闭环 */
    private com.anticheat.ai.AILabManager aiLabManager;

    // 审计日志（融合决策的自动处罚动作留痕）
    private AuditManager auditManager;

    /** Grim 式核心层是否成功启动（失败即降级为纯旧规则模式，插件整体保持可用） */
    private boolean coreStarted;

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

        // 初始化审计日志（供融合决策的处罚动作留痕）
        initializeAuditManager();

        // Grim 式核心层（包层 + 生命周期 + 检测注册）——失败不影响旧检测体系
        initializeCore();

        getLogger().info("§2[AdvancedAntiCheat] 插件已成功启用！");
        getLogger().info("§6[AdvancedAntiCheat] 保护您的服务器免受作弊侵害！");
    }

    @Override
    public void onDisable() {
        stopCore();

        if (auditManager != null) {
            // AuditManager 当前无 close 钩子，预留扩展位
        }
        banManager.saveBans();
        if (whitelistManager != null) {
            whitelistManager.save();
        }
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
        if (aiLabManager != null) {
            aiLabManager.shutdown();
        }
        if (advancedDetectionManager != null) {
            advancedDetectionManager.shutdown();
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
        whitelistManager = new WhitelistManager(this);

        advancedDetectionManager = new AdvancedDetectionManager(this);
        advancedDetectionManager.initialize(this);

        // AI 实验室（依赖 ProfileManager / AdvancedDetectionManager 就绪）
        try {
            aiLabManager = new com.anticheat.ai.AILabManager(this);
            aiLabManager.initialize();
            getServer().getPluginManager().registerEvents(aiLabManager.lifecycleListener(), this);
        } catch (Throwable t) {
            getLogger().warning("[AILab] AI 实验室初始化失败，本次运行退化为纯规则模式: " + t.getMessage());
            aiLabManager = null;
        }
    }

    /**
     * 初始化审计日志管理器。
     *
     * <p>此前这里还负责启动内嵌 Web 面板（AuthManager + Javalin WebServer）。
     * Web 面板与观察者回放架构已整体移除，仅保留审计落库能力，
     * 供 {@code DecisionActionCenter} 记录自动处罚动作、运维事后追溯。</p>
     */
    private void initializeAuditManager() {
        try {
            auditManager = new AuditManager(this);
        } catch (Throwable t) {
            getLogger().severe("[Audit] 审计管理器初始化异常: " + t.getMessage());
        }
    }

    /**
     * 启动 Grim 式核心层。
     *
     * <p>失败即**降级**而不是禁用插件：核心层（PacketEvents 通道注入 + 每玩家检测实例）
     * 与旧检测体系是两套独立链路，包层在个别服务端上注入失败（非标准 Netty 管道、
     * 其它注入型插件冲突）时，旧体系仍应继续提供保护。</p>
     */
    private void initializeCore() {
        try {
            if (!getConfig().getBoolean("core.enabled", true)) {
                getLogger().info("[Core] core.enabled=false，核心层未启动（仅旧检测体系生效）");
                return;
            }

            AntiCheatCore.load(new BukkitPlatformLoader(this));
            AntiCheatCore.start();
            coreStarted = AntiCheatCore.isInitialized();
            getLogger().info("§b[Core] Grim 式核心层已启用（" + AntiCheatCore.VERSION + "）");
        } catch (Throwable t) {
            coreStarted = false;
            getLogger().severe("[Core] 核心层启动失败，已降级为纯旧规则模式: " + t);
        }
    }

    /** 停止核心层：必须早于其它管理器的收尾，避免已拆卸的通道上还有回调。 */
    private void stopCore() {
        if (!coreStarted) {
            return;
        }
        try {
            AntiCheatCore.stop();
        } catch (Throwable t) {
            getLogger().warning("[Core] 核心层停止异常: " + t.getMessage());
        } finally {
            coreStarted = false;
        }
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new PlayerMoveListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerCommandListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerLoginListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerCheckListener(this), this);
        getServer().getPluginManager().registerEvents(new BehaviorListener(this), this);
        getServer().getPluginManager().registerEvents(new CaptchaListener(this), this);
        getServer().getPluginManager().registerEvents(new BountyListener(this), this);
        profileGUIListener = new com.anticheat.listeners.ProfileGUIListener(this);
        getServer().getPluginManager().registerEvents(profileGUIListener, this);

        // 白名单：自身即监听器（登录挂载 bypass 权限 / 退服清理权限附件）
        if (whitelistManager != null) {
            getServer().getPluginManager().registerEvents(whitelistManager, this);
        }
        // /ac config 六层管理界面点击分发
        configGUIListener = new com.anticheat.listeners.ConfigGUIListener(this);
        getServer().getPluginManager().registerEvents(configGUIListener, this);

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
        AntiCheatCommand antiCheatCommand = new AntiCheatCommand(this);
        getCommand("anticheat").setExecutor(antiCheatCommand);
        getCommand("ac").setExecutor(antiCheatCommand);
        getCommand("anticheat").setTabCompleter(antiCheatCommand);
        getCommand("ac").setTabCompleter(antiCheatCommand);
        getCommand("checkclient").setExecutor(new CheckClientCommand(this));
        getCommand("checkdone").setExecutor(new CheckDoneCommand(this));
        getCommand("captcha").setExecutor(new CaptchaCommand(this));
        getCommand("bounty").setExecutor(new BountyCommand(this));
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

    public WhitelistManager getWhitelistManager() {
        return whitelistManager;
    }

    public com.anticheat.listeners.ConfigGUIListener getConfigGUIListener() {
        return configGUIListener;
    }

    public AdvancedDetectionManager getAdvancedDetectionManager() {
        return advancedDetectionManager;
    }

    public com.anticheat.ai.AILabManager getAILabManager() {
        return aiLabManager;
    }

    public AuditManager getAuditManager() {
        return auditManager;
    }

    /** 核心层是否处于运行态（`/ac` 运维查询用）。 */
    public boolean isCoreStarted() {
        return coreStarted;
    }

    private void startRiskDecayTask() {
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            for (PlayerProfile profile : profileManager.getCachedProfiles().values()) {
                profile.decayRiskScore();
            }
        }, 20L * 60 * 60, 20L * 60 * 60);
    }
}
