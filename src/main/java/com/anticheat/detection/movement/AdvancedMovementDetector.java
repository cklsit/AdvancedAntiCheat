package com.anticheat.detection.movement;

import com.anticheat.detection.physics.EntitySnapshot;
import com.anticheat.utils.VersionUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AdvancedMovementDetector —— 补充移动检测器。
 *
 * <p>覆盖 {@link ImpossibleActionDetector} 未包含的两类移动作弊：</p>
 * <ul>
 *   <li><b>蜘蛛攀爬（Spider）</b>：玩家紧贴竖直墙面持续向上位移，且未处于可攀爬方块（梯子/藤蔓）上。</li>
 *   <li><b>岩浆行走（Jesus-Lava）</b>：玩家在岩浆表面持续直立行走不下沉，且无抗火/跨岩浆手段。</li>
 * </ul>
 *
 * <p>与 {@link ImpossibleActionDetector} 一样，本类输出 {@link MovementViolation}，
 * 由 {@link MovementDetectionModule} 统一消费并记入证据链。</p>
 */
public class AdvancedMovementDetector {

    private final Map<UUID, SpiderData> spiderDataMap = new ConcurrentHashMap<>();
    private final Map<UUID, LavaWalkData> lavaWalkDataMap = new ConcurrentHashMap<>();

    private static final double SPIDER_VERTICAL_SPEED = 0.08;   // 攀爬最低向上速度阈值
    private static final int SPIDER_MIN_TICKS = 6;              // 持续攀爬判定 tick 数
    private static final int LAVA_WALK_MIN_TICKS = 12;          // 岩浆行走判定 tick 数
    private static final double LAVA_Y_STABLE = 0.06;           // Y 轴变化容忍度

    /**
     * 蜘蛛攀爬检测：贴墙向上连续移动且无合法攀爬媒介。
     */
    public MovementViolation checkSpider(Player player, EntitySnapshot from, EntitySnapshot to) {
        if (isExempt(player)) {
            spiderDataMap.remove(player.getUniqueId());
            return null;
        }

        double dy = to.getPosition().getY() - from.getPosition().getY();
        boolean movingUp = dy > SPIDER_VERTICAL_SPEED;

        SpiderData data = spiderDataMap.computeIfAbsent(player.getUniqueId(), k -> new SpiderData());

        if (!movingUp || to.isOnGround()) {
            data.wallClingTicks = 0;
            return null;
        }

        if (!isAdjacentToSolidWall(player) || isOnClimbable(player)) {
            data.wallClingTicks = 0;
            return null;
        }

        data.wallClingTicks++;

        if (data.wallClingTicks >= SPIDER_MIN_TICKS) {
            double probability = Math.min(0.9, 0.5 + data.wallClingTicks * 0.05);
            return new MovementViolation(
                player.getUniqueId(),
                player.getName(),
                MovementViolationType.SPIDER,
                from,
                to,
                probability,
                String.format("贴墙向上攀爬（连续=%dtick，垂直速度=%.3f）", data.wallClingTicks, dy),
                data.wallClingTicks
            );
        }

        return null;
    }

    /**
     * 岩浆行走检测：在岩浆表面持续直立行走不下沉。
     */
    public MovementViolation checkLavaWalk(Player player, EntitySnapshot from, EntitySnapshot to) {
        if (isExempt(player)) {
            lavaWalkDataMap.remove(player.getUniqueId());
            return null;
        }

        LavaWalkData data = lavaWalkDataMap.computeIfAbsent(player.getUniqueId(), k -> new LavaWalkData());

        if (!isOnLavaSurface(player)) {
            data.lavaWalkTicks = 0;
            return null;
        }

        if (hasLavaExemption(player)) {
            data.lavaWalkTicks = 0;
            return null;
        }

        double yChange = Math.abs(to.getPosition().getY() - from.getPosition().getY());
        double horizontalSpeed = Math.sqrt(
            Math.pow(to.getPosition().getX() - from.getPosition().getX(), 2) +
            Math.pow(to.getPosition().getZ() - from.getPosition().getZ(), 2)
        );

        if (yChange < LAVA_Y_STABLE && horizontalSpeed > 0.05) {
            data.lavaWalkTicks++;
            if (data.lavaWalkTicks >= LAVA_WALK_MIN_TICKS) {
                double probability = Math.min(0.9, 0.55 + data.lavaWalkTicks * 0.01);
                return new MovementViolation(
                    player.getUniqueId(),
                    player.getName(),
                    MovementViolationType.LAVA_WALK,
                    from,
                    to,
                    probability,
                    String.format("在岩浆表面行走（持续=%dtick，水平速度=%.3f）", data.lavaWalkTicks, horizontalSpeed),
                    data.lavaWalkTicks
                );
            }
        } else {
            data.lavaWalkTicks = Math.max(0, data.lavaWalkTicks - 2);
        }

        return null;
    }

    /** 玩家脚下是否为岩浆表面（含 1.8 STATIONARY_LAVA）。 */
    private boolean isOnLavaSurface(Player player) {
        Location below = player.getLocation().clone().subtract(0, 1, 0);
        Material m = below.getBlock().getType();
        if (m == Material.LAVA) return true;
        try {
            Material stationary = (Material) Material.class.getDeclaredField("STATIONARY_LAVA").get(null);
            return m == stationary;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 岩浆行走豁免：抗火、骑乘、创意/旁观。 */
    private boolean hasLavaExemption(Player player) {
        if (VersionUtil.hasPotionEffectByName(player, "FIRE_RESISTANCE")) return true;
        if (player.isInsideVehicle()) return true;
        return false;
    }

    /** 是否紧贴固体墙面。 */
    private boolean isAdjacentToSolidWall(Player player) {
        Location loc = player.getLocation();
        // 检查水平四方向与上下各一格是否有固体方块紧贴
        Location[] checks = {
            loc.clone().add(0.3, 0, 0), loc.clone().add(-0.3, 0, 0),
            loc.clone().add(0, 0, 0.3), loc.clone().add(0, 0, -0.3),
            loc.clone().add(0.3, 1, 0), loc.clone().add(-0.3, 1, 0),
            loc.clone().add(0, 1, 0.3), loc.clone().add(0, 1, -0.3),
        };
        for (Location c : checks) {
            Block b = c.getBlock();
            if (b.getType().isSolid() && !VersionUtil.safeIsPassable(b)) {
                return true;
            }
        }
        return false;
    }

    /** 是否处于可攀爬方块（梯子/藤蔓）上。 */
    private boolean isOnClimbable(Player player) {
        Material m = player.getLocation().getBlock().getType();
        Material ladder = VersionUtil.compatMaterial("LADDER", "LADDER", null);
        Material vine = VersionUtil.compatMaterial("VINE", "VINE", null);
        return m == ladder || m == vine;
    }

    private boolean isExempt(Player player) {
        if (player == null || !player.isOnline()) return true;
        if (player.hasPermission("anticheat.bypass.movement")) return true;
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE ||
            player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return true;
        if (player.isInsideVehicle() || player.isFlying() || player.isSleeping() || player.isDead()) return true;
        return false;
    }

    public void clearPlayerData(UUID uuid) {
        spiderDataMap.remove(uuid);
        lavaWalkDataMap.remove(uuid);
    }

    private static class SpiderData {
        int wallClingTicks = 0;
    }

    private static class LavaWalkData {
        int lavaWalkTicks = 0;
    }
}
