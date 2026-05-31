package com.anticheat;

import com.anticheat.commands.*;
import com.anticheat.compat.CompatManager;
import com.anticheat.captcha.CaptchaManager;
import com.anticheat.bounty.BountyManager;
import com.anticheat.listeners.*;
import com.anticheat.managers.*;
import com.anticheat.profiles.BehaviorTracker;
import com.anticheat.profiles.PlayerProfile;
import com.anticheat.utils.VersionUtil;
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
    private com.anticheat.listeners.ProfileGUIListener profileGUIListener;
    private AdvancedDetectionManager advancedDetectionManager;

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
        
        getLogger().info("§2[AdvancedAntiCheat] 插件已成功启用！");
        getLogger().info("§6[AdvancedAntiCheat] 保护您的服务器免受作弊侵害！");
    }

    @Override
    public void onDisable() {
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

    private void startRiskDecayTask() {
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            for (PlayerProfile profile : profileManager.getCachedProfiles().values()) {
                profile.decayRiskScore();
            }
        }, 20L * 60 * 60, 20L * 60 * 60);
    }
}
