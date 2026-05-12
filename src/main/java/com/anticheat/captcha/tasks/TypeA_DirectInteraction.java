package com.anticheat.captcha.tasks;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.event.player.PlayerToggleSneakEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TypeA_DirectInteraction extends CaptchaTask {

    private final Map<UUID, TargetInfo> activeTargets;
    private final Random random;

    private static final DyeColor[] COLORS = {
            DyeColor.PINK, DyeColor.BLUE, DyeColor.GREEN, DyeColor.YELLOW,
            DyeColor.RED, DyeColor.PURPLE, DyeColor.CYAN, DyeColor.ORANGE
    };

    public TypeA_DirectInteraction(AdvancedAntiCheat plugin) {
        super(plugin);
        this.activeTargets = new ConcurrentHashMap<>();
        this.random = new Random();
    }

    @Override
    public void start(Player player, Location location) {
        DyeColor targetColor = COLORS[random.nextInt(COLORS.length)];

        List<Sheep> sheepList = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Location spawnLoc = location.clone().add(
                    (random.nextDouble() - 0.5) * 8,
                    0,
                    (random.nextDouble() - 0.5) * 8
            );
            Sheep sheep = player.getWorld().spawn(spawnLoc, Sheep.class);
            if (i == 0) {
                sheep.setColor(targetColor);
            } else {
                DyeColor otherColor;
                do {
                    otherColor = COLORS[random.nextInt(COLORS.length)];
                } while (otherColor == targetColor);
                sheep.setColor(otherColor);
            }
            sheep.setInvulnerable(true);
            sheep.setSilent(true);
            sheepList.add(sheep);
        }

        TargetInfo info = new TargetInfo(targetColor, sheepList);
        activeTargets.put(player.getUniqueId(), info);

        sendInstruction(player, "注视" + getColorName(targetColor) + "的羊，然后按下潜行键");
    }

    @Override
    public void cleanup(Player player) {
        TargetInfo info = activeTargets.remove(player.getUniqueId());
        if (info != null) {
            for (Sheep sheep : info.sheepList) {
                sheep.remove();
            }
        }
    }

    @Override
    public String getTaskDescription() {
        return "定向交互任务";
    }

    @Override
    public boolean isCompleted(Player player) {
        return false;
    }

    public boolean onPlayerSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        TargetInfo info = activeTargets.get(player.getUniqueId());

        if (info == null || !event.isSneaking()) {
            return false;
        }

        org.bukkit.entity.Entity target = player.getTargetEntity(5);

        if (target instanceof Sheep) {
            Sheep sheep = (Sheep) target;
            if (sheep.getColor() == info.targetColor) {
                cleanup(player);
                plugin.getCaptchaManager().completeTask(player);
                return true;
            }
        }

        return false;
    }

    private String getColorName(DyeColor color) {
        switch (color) {
            case PINK: return "§d粉红色";
            case BLUE: return "§9蓝色";
            case GREEN: return "§a绿色";
            case YELLOW: return "§e黄色";
            case RED: return "§c红色";
            case PURPLE: return "§5紫色";
            case CYAN: return "§b青色";
            case ORANGE: return "§6橙色";
            default: return color.name();
        }
    }

    private static class TargetInfo {
        final DyeColor targetColor;
        final List<Sheep> sheepList;

        TargetInfo(DyeColor targetColor, List<Sheep> sheepList) {
            this.targetColor = targetColor;
            this.sheepList = sheepList;
        }
    }
}
