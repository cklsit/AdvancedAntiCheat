package com.anticheat.captcha.tasks;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.Location;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TypeB_PrecisionHit extends CaptchaTask {

    private final Map<UUID, TargetChicken> activeTargets;
    private final Random random;

    public TypeB_PrecisionHit(AdvancedAntiCheat plugin) {
        super(plugin);
        this.activeTargets = new ConcurrentHashMap<>();
        this.random = new Random();
    }

    @Override
    public void start(Player player, Location location) {
        Location chickenLoc = location.clone().add(0, 1.5, 0);
        Chicken chicken = player.getWorld().spawn(chickenLoc, Chicken.class);
        chicken.setInvulnerable(true);
        chicken.setSilent(true);

        TargetChicken info = new TargetChicken(chicken, 0, location);
        activeTargets.put(player.getUniqueId(), info);

        player.getInventory().addItem(new ItemStack(org.bukkit.Material.SNOWBALL, 10));

        sendInstruction(player, "用雪球击落那只鸡");

        startChickenMovement(player);
    }

    private void startChickenMovement(Player player) {
        org.bukkit.scheduler.BukkitRunnable movementTask = new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                TargetChicken info = activeTargets.get(player.getUniqueId());
                if (info == null || !player.isOnline()) {
                    this.cancel();
                    return;
                }

                if (info.chicken.isDead()) {
                    this.cancel();
                    return;
                }

                Location loc = info.chicken.getLocation();
                double y = loc.getY();
                if (y > info.platformLocation.getY() + 4) {
                    info.chicken.teleport(new Location(loc.getWorld(), loc.getX(), info.platformLocation.getY() + 3, loc.getZ(), loc.getYaw(), loc.getPitch()));
                }

                Vector velocity = new Vector(
                        (random.nextDouble() - 0.5) * 1.5,
                        (random.nextDouble() - 0.5) * 0.5,
                        (random.nextDouble() - 0.5) * 1.5
                );

                info.chicken.setVelocity(velocity);
            }
        };

        movementTask.runTaskTimer(plugin, 0, 8);
    }

    @Override
    public void cleanup(Player player) {
        TargetChicken info = activeTargets.remove(player.getUniqueId());
        if (info != null && info.chicken != null && !info.chicken.isDead()) {
            info.chicken.remove();
        }
    }

    @Override
    public String getTaskDescription() {
        return "精确打击任务";
    }

    @Override
    public boolean isCompleted(Player player) {
        return false;
    }

    public boolean onSnowballHit(Snowball snowball, org.bukkit.entity.Entity hitEntity) {
        if (!(hitEntity instanceof Chicken)) {
            return false;
        }

        Player player = (Player) snowball.getShooter();
        TargetChicken info = activeTargets.get(player.getUniqueId());

        if (info == null) {
            return false;
        }

        if (hitEntity.getUniqueId().equals(info.chicken.getUniqueId())) {
            info.hitCount++;
            if (info.hitCount >= 3) {
                info.chicken.remove();
                cleanup(player);
                plugin.getCaptchaManager().completeTask(player);
                return true;
            }
        }

        return false;
    }

    private static class TargetChicken {
        final Chicken chicken;
        int hitCount;
        final Location platformLocation;

        TargetChicken(Chicken chicken, int hitCount, Location platformLocation) {
            this.chicken = chicken;
            this.hitCount = hitCount;
            this.platformLocation = platformLocation;
        }
    }
}
