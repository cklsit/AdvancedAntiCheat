package com.anticheat.detection;

import com.anticheat.managers.DetectionManager;
import com.anticheat.utils.VersionUtil;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class FlyDetection extends Detection {

    private final Map<UUID, Double> previousY = new HashMap<>();
    private final Map<UUID, Integer> flyTicks = new HashMap<>();
    private final Map<UUID, Long> lastJumpTime = new HashMap<>();
    private final Map<UUID, Double> jumpStartY = new HashMap<>();

    private static final int FLY_TICK_THRESHOLD = 40;
    private static final double NORMAL_JUMP_MAX_HEIGHT = 1.2;
    private static final double MAX_FALL_DISTANCE = 3.0;

    public FlyDetection(DetectionManager manager) {
        super(manager);
    }

    @Override
    public void check(Player player) {
        if (!getManager().getPlugin().getConfigManager().isDetectionEnabled("fly")) {
            return;
        }

        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE || 
            player.getGameMode() == org.bukkit.GameMode.SPECTATOR ||
            player.hasPermission("anticheat.bypass.fly")) {
            return;
        }

        if (player.isFlying()) {
            flyTicks.put(player.getUniqueId(), 0);
            return;
        }

        if (getManager().getPlugin().getCheckClientManager().isBeingChecked(player.getUniqueId())) {
            return;
        }

        UUID uuid = player.getUniqueId();
        Location loc = player.getLocation();
        double currentY = loc.getY();
        Vector velocity = player.getVelocity();

        Integer ticks = flyTicks.getOrDefault(uuid, 0);

        if (!isOnGround(player) && !isInWater(player) && !isInLava(player)) {
            Long lastJump = lastJumpTime.get(uuid);
            Double startY = jumpStartY.get(uuid);
            long now = System.currentTimeMillis();

            if (lastJump != null && now - lastJump < 2000) {
                double jumpHeight = currentY - startY;
                if (jumpHeight <= NORMAL_JUMP_MAX_HEIGHT) {
                    ticks = Math.max(0, ticks - 1);
                }
            }

            double fallDistance = player.getFallDistance();
            if (fallDistance > 0 && fallDistance <= MAX_FALL_DISTANCE) {
                ticks = Math.max(0, ticks - 1);
            }

            if (velocity.getY() > 0.1 && !isNormalJump(player, velocity)) {
                ticks++;
            }
        } else {
            ticks = Math.max(0, ticks - 2);
            if (player.isOnGround()) {
                lastJumpTime.remove(uuid);
                jumpStartY.remove(uuid);
            }
        }

        flyTicks.put(uuid, ticks);
        previousY.put(uuid, currentY);

        if (ticks >= FLY_TICK_THRESHOLD) {
            getManager().addViolation(player, "fly");
            flyTicks.put(uuid, 0);
        }
    }

    public void onPlayerJump(Player player) {
        UUID uuid = player.getUniqueId();
        lastJumpTime.put(uuid, System.currentTimeMillis());
        jumpStartY.put(uuid, player.getLocation().getY());
    }

    private boolean isOnGround(Player player) {
        if (player.isOnGround()) {
            return true;
        }

        Location feetLoc = player.getLocation().subtract(0, 0.1, 0);
        Block block = feetLoc.getBlock();
        return block.getType().isSolid() && !block.isPassable();
    }

    private boolean isNormalJump(Player player, Vector velocity) {
        if (velocity.getY() <= 0.4) {
            return true;
        }

        Long lastJump = lastJumpTime.get(player.getUniqueId());
        if (lastJump != null && System.currentTimeMillis() - lastJump < 500) {
            return true;
        }

        return false;
    }

    private boolean isInWater(Player player) {
        return VersionUtil.isInWater(player);
    }

    private boolean isInLava(Player player) {
        return VersionUtil.isInLava(player);
    }
}
