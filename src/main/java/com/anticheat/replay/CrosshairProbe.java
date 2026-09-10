package com.anticheat.replay;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * 准星目标探测（需求 4：显示准星指向的实体及其类型）。
 *
 * <p>从玩家眼部沿视线做<b>实体优先、方块兜底</b>的探测：</p>
 * <ol>
 *   <li>对周围实体做「射线-包围球」相交，取沿视线最近者；</li>
 *   <li>无实体命中时，用 {@code getTargetBlock} 找方块。</li>
 * </ol>
 *
 * <p>方块探测用反射兼容两个签名：现代 Paper {@code getTargetBlock(Set&lt;Material&gt;, int)}
 * 与 1.8 {@code getTargetBlock(HashSet&lt;Byte&gt;, int)}——本插件编译目标为 Paper 1.21 但
 * 运行时落在 1.8.8，直接调用会 {@code NoSuchMethodError}，故走反射双通道。</p>
 *
 * <p>实体包围球用「位置 + 估计半高 + 估计半径」近似，不追求像素级精确——
 * 该字段仅用于叠层显示「正在看谁」，不参与封禁判定。</p>
 *
 * <p><b>线程约束</b>：世界/实体访问必须在主线程调用。</p>
 */
public final class CrosshairProbe {

    private CrosshairProbe() {}

    /** 探测结果。kind：0=无，1=实体，2=方块。 */
    public static final class Result {
        public final byte kind;
        public final String entityType;
        public final String entityName;
        public final float distance;
        public final String blockType;

        private Result(byte kind, String entityType, String entityName, float distance, String blockType) {
            this.kind = kind;
            this.entityType = entityType;
            this.entityName = entityName;
            this.distance = distance;
            this.blockType = blockType;
        }

        public static final Result NONE = new Result((byte) 0, null, null, 0f, null);

        public static Result entity(String type, String name, float distance) {
            return new Result((byte) 1, type, name, distance, null);
        }

        public static Result block(String blockType, float distance) {
            return new Result((byte) 2, null, null, distance, blockType);
        }
    }

    /**
     * 探测玩家准星指向的实体或方块。
     *
     * @param maxDistance 探测最大距离（格），超出视为无目标
     */
    public static Result probe(Player player, double maxDistance) {
        if (player == null || !player.isOnline()) return Result.NONE;

        Location eye = player.getEyeLocation();
        Vector dir;
        try {
            dir = eye.getDirection().clone().normalize();
        } catch (Throwable t) {
            return Result.NONE;
        }

        // 1. 实体：射线-包围球，取沿视线最近
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        try {
            for (Entity e : player.getNearbyEntities(maxDistance, maxDistance, maxDistance)) {
                if (e == player || !isTargetable(e)) continue;
                double d = raySphereHit(eye, dir, centerOf(e), radiusOf(e));
                if (d >= 0 && d <= maxDistance && d < bestDist) {
                    bestDist = d;
                    best = e;
                }
            }
        } catch (Throwable ignored) {
        }

        if (best != null) {
            String type = safeTypeName(best);
            String name = nameOf(best);
            return Result.entity(type, name, (float) bestDist);
        }

        // 2. 方块兜底
        Block b = targetBlock(player, (int) Math.ceil(maxDistance));
        if (b != null && b.getType() != null && b.getType() != Material.AIR) {
            double dist = eye.distance(b.getLocation().clone().add(0.5, 0.5, 0.5));
            return Result.block(b.getType().name(), (float) dist);
        }

        return Result.NONE;
    }

    // ===================== 内部 =====================

    private static boolean isTargetable(Entity e) {
        if (e instanceof Player) {
            String n = e.getName();
            if (n != null && n.startsWith("ReplayObserver_")) return false;
        }
        return (e instanceof LivingEntity) || (e instanceof Item) || (e instanceof ArmorStand);
    }

    private static Location centerOf(Entity e) {
        Location loc = e.getLocation().clone();
        if (e instanceof Player) {
            loc.add(0, 0.9, 0);
        } else if (e instanceof LivingEntity) {
            loc.add(0, 0.5, 0);
        } else {
            loc.add(0, 0.25, 0);
        }
        return loc;
    }

    private static double radiusOf(Entity e) {
        if (e instanceof Player) return 0.5;
        if (e instanceof LivingEntity) return 0.6;
        return 0.3;
    }

    private static String safeTypeName(Entity e) {
        try {
            org.bukkit.entity.EntityType t = e.getType();
            return t != null ? t.name() : e.getClass().getSimpleName();
        } catch (Throwable t) {
            return e.getClass().getSimpleName();
        }
    }

    private static String nameOf(Entity e) {
        try {
            if (e instanceof Player) return e.getName();
            if (e instanceof LivingEntity) {
                String custom = ((LivingEntity) e).getCustomName();
                if (custom != null && !custom.isEmpty()) return custom;
            }
            if (e instanceof Item) {
                Item it = (Item) e;
                if (it.getItemStack() != null && it.getItemStack().getType() != null) {
                    return it.getItemStack().getType().name();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 射线与包围球求交，返回沿射线的最短命中距离，未命中返回 -1。 */
    private static double raySphereHit(Location eye, Vector dir, Location center, double radius) {
        double ox = center.getX() - eye.getX();
        double oy = center.getY() - eye.getY();
        double oz = center.getZ() - eye.getZ();
        double tca = ox * dir.getX() + oy * dir.getY() + oz * dir.getZ();
        if (tca < 0) return -1;
        double d2 = (ox * ox + oy * oy + oz * oz) - tca * tca;
        double r2 = radius * radius;
        if (d2 > r2) return -1;
        double thc = Math.sqrt(r2 - d2);
        double t = tca - thc;
        if (t < 0) t = tca + thc;
        return t < 0 ? -1 : t;
    }

    /** 版本兼容的方块视线探测：现代 Set&lt;Material&gt; 优先，1.8 HashSet&lt;Byte&gt; 兜底。 */
    private static Block targetBlock(Player player, int maxDistance) {
        try {
            Method m = Player.class.getMethod("getTargetBlock", Set.class, int.class);
            Object r = m.invoke(player, null, maxDistance);
            if (r instanceof Block) return (Block) r;
        } catch (Throwable ignored) {
        }
        try {
            Method m = Player.class.getMethod("getTargetBlock", HashSet.class, int.class);
            Object r = m.invoke(player, null, maxDistance);
            if (r instanceof Block) return (Block) r;
        } catch (Throwable ignored) {
        }
        return null;
    }
}
