package com.anticheat.captcha.tasks;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public abstract class CaptchaTask {

    protected final AdvancedAntiCheat plugin;

    public CaptchaTask(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }

    public abstract void start(Player player, Location location);

    public abstract void cleanup(Player player);

    public abstract String getTaskDescription();

    public abstract boolean isCompleted(Player player);

    protected void sendInstruction(Player player, String instruction) {
        player.sendMessage("§e§l[验证码] §f" + instruction);
    }

    protected void sendProgress(Player player, int current, int total) {
        player.sendMessage("§6[进度] §f任务 " + current + "/" + total);
    }
}
