package com.anticheat.utils;

import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;

public class VersionUtil {

    private static String version;
    private static boolean is1_8;
    private static boolean is1_12;
    private static boolean is1_16;
    private static boolean is1_19;

    static {
        String packageName = Bukkit.getServer().getClass().getPackage().getName();
        String[] parts = packageName.split("\\.");
        
        if (parts.length > 3) {
            version = parts[3];
        } else {
            version = "v1_21_R1";
        }
        
        is1_8 = version.startsWith("v1_8");
        is1_12 = version.startsWith("v1_12");
        is1_16 = version.startsWith("v1_16");
        is1_19 = version.startsWith("v1_19") || version.startsWith("v1_20") || version.startsWith("v1_21");
    }

    public static String getVersion() {
        return version;
    }

    public static boolean is1_8() {
        return is1_8;
    }

    public static boolean is1_12() {
        return is1_12;
    }

    public static boolean is1_16() {
        return is1_16;
    }

    public static boolean is1_19Plus() {
        return is1_19;
    }

    public static boolean isHighVersion() {
        return is1_19Plus();
    }

    public static boolean isLowVersion() {
        return !is1_19Plus();
    }

    public static int getMajorVersion() {
        if (is1_8) return 8;
        if (version.startsWith("v1_9")) return 9;
        if (version.startsWith("v1_10")) return 10;
        if (version.startsWith("v1_11")) return 11;
        if (is1_12) return 12;
        if (version.startsWith("v1_13")) return 13;
        if (version.startsWith("v1_14")) return 14;
        if (version.startsWith("v1_15")) return 15;
        if (is1_16) return 16;
        if (version.startsWith("v1_17")) return 17;
        if (version.startsWith("v1_18")) return 18;
        if (version.startsWith("v1_19")) return 19;
        if (version.startsWith("v1_20")) return 20;
        if (version.startsWith("v1_21")) return 21;
        return 21;
    }

    /**
     * 判断玩家是否在水中
     *
     * @param player 玩家
     * @return 是否在水中
     */
    public static boolean isInWater(Player player) {
        if (isHighVersion()) {
            try { return player.isInWater(); } catch (Throwable t) { /* fall through */ }
        }
        Location loc = player.getLocation();
        Material material = loc.getBlock().getType();
        return isWaterMaterial(material);
    }

    /** 判断材质是否为水（包含 1.8 的 STATIONARY_WATER）。非玩家版本。 */
    public static boolean isInWaterStatic(Material material) {
        return isWaterMaterial(material);
    }

    /** 判断材质是否为水（WATER 或 1.8 的 STATIONARY_WATER）。公开版。 */
    public static boolean isWaterMaterial(Material material) {
        if (material == Material.WATER) return true;
        return isStationaryWater(material);
    }

    /**
     * 判断材质是否为静止水（兼容低版本）
     *
     * @param material 材质
     * @return 是否为静止水
     */
    private static boolean isStationaryWater(Material material) {
        try {
            Material stationaryWater = (Material) Material.class.getDeclaredField("STATIONARY_WATER").get(null);
            return material == stationaryWater;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断玩家是否在岩浆中
     *
     * @param player 玩家
     * @return 是否在岩浆中
     */
    public static boolean isInLava(Player player) {
        if (isHighVersion()) {
            return player.isInLava();
        } else {
            Location loc = player.getLocation();
            Material material = loc.getBlock().getType();
            return material == Material.LAVA || isStationaryLava(material);
        }
    }

    /**
     * 判断材质是否为静止岩浆（兼容低版本）
     *
     * @param material 材质
     * @return 是否为静止岩浆
     */
    private static boolean isStationaryLava(Material material) {
        try {
            Material stationaryLava = (Material) Material.class.getDeclaredField("STATIONARY_LAVA").get(null);
            return material == stationaryLava;
        } catch (Exception e) {
            return false;
        }
    }

    // ==========================
    // === 1.8 兼容性辅助方法 ===
    // ==========================

    /**
     * 用反射按名称取 Material 枚举值。先尝试 modernName，失败再尝试 legacyName，都失败返回 fallback。
     * 注意：所有参数均为字符串，不能直接写 Material.COBWEB 这类编译期常量（1.8 无此字段会导致 NoSuchFieldError 类装载失败）。
     */
    public static Material compatMaterial(String modernName, String legacyName, Material fallback) {
        if (modernName != null && !modernName.isEmpty()) {
            try {
                return (Material) Material.class.getDeclaredField(modernName).get(null);
            } catch (Throwable ignored) {
                // fall through
            }
        }
        if (legacyName != null && !legacyName.isEmpty()) {
            try {
                return (Material) Material.class.getDeclaredField(legacyName).get(null);
            } catch (Throwable ignored) {
                // fall through
            }
        }
        if (fallback == null) {
            Bukkit.getLogger().log(Level.WARNING, "[VersionUtil] compatMaterial 找不到现代名 '" + modernName + "' / 旧名 '" + legacyName + "' 且 fallback 为空，返回 AIR 以避免崩溃");
            return Material.AIR;
        }
        return fallback;
    }

    /**
     * 兼容羊毛方块（作为方块类型）：高版本直接取 RED_WOOL/GREEN_WOOL，
     * 1.8 取 WOOL（无颜色，需调用方再 setData 给方块上色）。
     * 全部走反射，避免 Paper 1.21 编译期 Material.WOOL 符号不存在。
     */
    public static Material compatColoredWoolMaterial(String modernColoredName, DyeColor dyeColor) {
        Material m = compatMaterial(modernColoredName, null, null);
        if (m != Material.AIR) return m;
        // 1.8 才有 Material.WOOL；fallback=null 时 compatMaterial 找不到会回退 AIR
        return compatMaterial("WOOL", "WOOL", null);
    }

    /**
     * 1.8 世界方块 setType(WOOL) 之后需要 setData(dyeColor.getData())；
     * 高版本已用 RED_WOOL 等具体颜色枚举，不需要此调用。
     * DyeColor.getData() / Block.setData(byte) 在 1.13+ 已删除，
     * 故全部走反射：失败即视为高版本跳过。
     */
    public static void applyDyeColorIfLegacyWool(Block block, Material usedMaterial, DyeColor color) {
        if (block == null || usedMaterial == null || color == null) return;
        // 仅当 usedMaterial 名为 WOOL（1.8 无颜色枚举）时才需要 setData
        if (!"WOOL".equals(usedMaterial.name())) return;
        try {
            // 1.8 DyeColor.getData() 返回 byte
            Method getData = DyeColor.class.getMethod("getData");
            byte data = (Byte) getData.invoke(color);
            // 1.8 Block.setData(byte)
            Method setData = Block.class.getMethod("setData", byte.class);
            setData.invoke(block, data);
        } catch (Throwable ignored) {
            // 高版本无此 API，高版本本身就用彩色枚举，无需 setData
        }
    }

    /** 屏障方块兼容：1.8 无 Material.BARRIER，回退为 GLASS（视觉清晰，作为围栏可接受）。 */
    public static Material compatBarrier() {
        return compatMaterial("BARRIER", null, Material.GLASS);
    }

    /** 不死图腾兼容：1.8 无 TOTEM_OF_UNDYING，回退为金苹果（GOLDEN_APPLE）。 */
    public static Material compatTotemOrFallback() {
        return compatMaterial("TOTEM_OF_UNDYING", null, Material.GOLDEN_APPLE);
    }

    /** 减少摔落伤害方块：用于 ImpossibleActionDetector 的 NoFall 豁免判断。 */
    public static boolean isFallDamageReductionBlockCompat(Material material) {
        if (material == null) return false;
        // 所有枚举常量都走反射 compatMaterial，避免直接 Material.WATER/SUGAR_CANE 符号在跨版本编译失败
        Material water = compatMaterial("WATER", "WATER", null);
        Material cobweb = compatMaterial("COBWEB", "WEB", null);
        Material hay = compatMaterial("HAY_BLOCK", "HAY_BLOCK", null);
        // 糖蔗：高版本 SUGAR_CANE 既是物品也是方块；1.8 方块枚举为 SUGAR_CANE_BLOCK
        Material sugarCane = compatMaterial("SUGAR_CANE", "SUGAR_CANE_BLOCK", null);
        Material sugarCaneLegacy = compatMaterial("SUGAR_CANE_BLOCK", "SUGAR_CANE_BLOCK", null);
        return material == water
                || material == cobweb
                || material == hay
                || material == sugarCane
                || material == sugarCaneLegacy;
    }

    /** 方块 isPassable 兼容：1.8 Block 接口没有 isPassable 方法；回退为 !isSolid（近似等价）。 */
    public static boolean safeIsPassable(Block block) {
        if (block == null) return true;
        try {
            Method m = Block.class.getMethod("isPassable");
            Object r = m.invoke(block);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            // 1.8 回退：非固体方块视为可通过
            try {
                return !block.getType().isSolid();
            } catch (Throwable t2) {
                return false;
            }
        }
    }

    /** player.isSwimming() 兼容：1.13+ 游泳动画。1.8 无此概念，直接返回 false。 */
    public static boolean safeIsSwimming(Player player) {
        if (player == null) return false;
        try {
            Method m = Player.class.getMethod("isSwimming");
            Object r = m.invoke(player);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return false;
        }
    }

    /** player.isGliding() 兼容：鞘翅 1.9+。1.8 直接返回 false。 */
    public static boolean safeIsGliding(Player player) {
        if (player == null) return false;
        try {
            Method m = Player.class.getMethod("isGliding");
            Object r = m.invoke(player);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 按名字判断玩家是否有指定药水效果（SLOW_FALLING 1.13+，1.8 不存在则返回 false）。 */
    public static boolean hasPotionEffectByName(Player player, String effectName) {
        if (player == null || effectName == null) return false;
        try {
            PotionEffectType type = PotionEffectType.getByName(effectName);
            if (type == null) return false;
            return player.hasPotionEffect(type);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 给实体设置无敌：优先 1.9+ 的 Entity.setInvulnerable(boolean)。
     * 失败则反射 NMS Entity.setInvulnerable(true)（v1_8_R3 等 1.8 版本存在）。
     * 都失败时返回 false，调用方可改为注册 EntityDamageEvent 临时监听器来 cancel 伤害。
     */
    public static boolean callSetInvulnerable(Entity entity, boolean invulnerable) {
        if (entity == null) return false;
        // 路径 1：Bukkit API（1.9+）
        try {
            Method m = Entity.class.getMethod("setInvulnerable", boolean.class);
            m.invoke(entity, invulnerable);
            return true;
        } catch (Throwable ignored) {
        }
        // 路径 2：反射 CraftEntity#getHandle → NMS Entity#setInvulnerable（1.8.x）
        try {
            Method getHandle = entity.getClass().getMethod("getHandle");
            Object nmsEntity = getHandle.invoke(entity);
            if (nmsEntity != null) {
                Method setInv = nmsEntity.getClass().getMethod("setInvulnerable", boolean.class);
                setInv.invoke(nmsEntity, invulnerable);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 设置实体是否受重力影响（验证码里盔甲架做"跳跃"动画时用：关掉重力才能手动抬升）。
     *
     * <p>1.8 上 {@code setGravity(boolean)} 声明在 {@link org.bukkit.entity.ArmorStand}，
     * 1.9+ 挪到了 {@code Entity}。按 1.21 编译时生成的方法引用在老版本上可能不存在，
     * 因此统一走 {@code getClass().getMethod(...)} 反射（Craft 实现类两个版本都有该方法）。
     *
     * @return 是否成功设置
     */
    public static boolean callSetGravity(Entity entity, boolean gravity) {
        if (entity == null) return false;
        try {
            Method m = entity.getClass().getMethod("setGravity", boolean.class);
            m.invoke(entity, gravity);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 设置实体默认可见性（蜜罐幽灵实体用）。
     * 优先 1.19.4+ 的 LivingEntity.setVisibleByDefault(boolean)；
     * 1.8 无此方法，降级为 LivingEntity.setInvisible(boolean)（语义近似：让诱饵实体对正常玩家不可见）；
     * 都失败时静默跳过（no-op），保证插件在老版本上能正常启用而不抛 NoSuchMethodError。
     */
    public static void safeSetVisibleByDefault(LivingEntity entity, boolean visibleByDefault) {
        if (entity == null) return;
        // 路径 1：1.19.4+ Paper 的 setVisibleByDefault(boolean)
        try {
            Method m = LivingEntity.class.getMethod("setVisibleByDefault", boolean.class);
            m.invoke(entity, visibleByDefault);
            return;
        } catch (Throwable ignored) {
            // fall through
        }
        // 路径 2：降级 setInvisible(boolean)，取反语义（visibleByDefault=false → 隐身）
        try {
            Method m = LivingEntity.class.getMethod("setInvisible", boolean.class);
            m.invoke(entity, !visibleByDefault);
        } catch (Throwable ignored) {
            // 彻底降级：no-op
        }
    }

    /**
     * 玩家瞄准实体：优先 Player.getTargetEntity(int maxDistance)（1.13+）。
     * 1.8 无此方法，回退到简单的视线 5 格内最近实体遍历（保证不 NoSuchMethodError）。
     */
    public static Entity safeGetTargetEntity(Player player, int maxDistance) {
        if (player == null) return null;
        try {
            Method m = Player.class.getMethod("getTargetEntity", int.class);
            Object r = m.invoke(player, maxDistance);
            if (r instanceof Entity) return (Entity) r;
        } catch (Throwable ignored) {
        }
        // 1.8 回退：遍历 maxDistance 范围内的实体，取玩家方向上最近的
        try {
            Location eye = player.getEyeLocation();
            org.bukkit.util.Vector dir = eye.getDirection().normalize();
            Entity closest = null;
            double closestT = Double.MAX_VALUE;
            for (Entity e : player.getNearbyEntities(maxDistance, maxDistance, maxDistance)) {
                if (e.equals(player)) continue;
                Location el = e.getLocation();
                org.bukkit.util.Vector diff = el.toVector().subtract(eye.toVector());
                double proj = diff.dot(dir);
                if (proj < 0 || proj > maxDistance) continue;
                // 与射线的垂直距离
                org.bukkit.util.Vector perp = diff.subtract(dir.clone().multiply(proj));
                double perpLen = perp.length();
                if (perpLen < 1.2 && proj < closestT) {
                    closestT = proj;
                    closest = e;
                }
            }
            return closest;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 对 World.setGameRule 的兼容封装（1.8 World.setGameRuleValue 是 deprecated 且部分版本签名不同）。 */
    public static void setGameRuleSafe(org.bukkit.World world, String rule, String value) {
        if (world == null || rule == null || value == null) return;
        try {
            // 高版本 World.setGameRule(GameRule<T>, T)
            Class<?> gameRuleCls;
            try {
                gameRuleCls = Class.forName("org.bukkit.GameRule");
            } catch (ClassNotFoundException e) {
                gameRuleCls = null;
            }
            if (gameRuleCls != null) {
                try {
                    Method getRule = gameRuleCls.getMethod("getByName", String.class);
                    Object ruleObj = getRule.invoke(null, rule);
                    if (ruleObj != null) {
                        Method m = org.bukkit.World.class.getMethod("setGameRule", gameRuleCls, Object.class);
                        // 字符串的值根据 GameRule<T> 需要转型：若 Boolean 则 valueOf
                        try {
                            String ruleTypeName = ruleObj.getClass().getSimpleName();
                            if (ruleTypeName.contains("Boolean") || "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                                m.invoke(world, ruleObj, Boolean.valueOf(value));
                            } else {
                                m.invoke(world, ruleObj, Integer.valueOf(value));
                            }
                            return;
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            // 回退：deprecated 的 setGameRuleValue(String, String)（1.8 存在）
            Method m = org.bukkit.World.class.getMethod("setGameRuleValue", String.class, String.class);
            m.invoke(world, rule, value);
        } catch (Throwable t) {
            Bukkit.getLogger().log(Level.WARNING, "[VersionUtil] 设置游戏规则 " + rule + " 失败: " + t.getMessage());
        }
    }

    /**
     * 跨版本安全获取在线玩家列表。
     * <p>
     * 1.8.x / FlamePaper 1.8.8 中 {@code Bukkit.getOnlinePlayers()} 返回原始
     * {@code Player[]}；Paper/Spigot 1.13+ 返回 {@code Collection<? extends Player>}。
     * 现代版 JAR 编译后，老服务器上按数组调用会发生不兼容（ArrayList(Collection) 构造失败、
     * 或产生空列表）。本方法通过反射检测返回类型并统一转换为 List。
     * </p>
     *
     * @return 当前在线玩家的可变 List；服务器异常时返回空 List（永不为 null）
     */
    public static List<Player> safeGetOnlinePlayers() {
        try {
            Bukkit.getLogger().info("[VersionUtil] safeGetOnlinePlayers called");
            Method method = Bukkit.class.getMethod("getOnlinePlayers");
            Object ret = method.invoke(null);
            List<Player> result = new ArrayList<>();
            if (ret == null) {
                Bukkit.getLogger().info("[VersionUtil] safeGetOnlinePlayers returning 0 players (null return)");
                return result;
            }
            if (ret instanceof Collection) {
                @SuppressWarnings("unchecked")
                Collection<? extends Player> col = (Collection<? extends Player>) ret;
                result = new ArrayList<>(col);
            } else if (ret instanceof Player[]) {
                result = new ArrayList<>(Arrays.asList((Player[]) ret));
            } else {
                // 兜底：无法识别时尝试 Bukkit 直接 API
                try {
                    result = new ArrayList<>(Bukkit.getOnlinePlayers());
                } catch (Throwable ignoredFallback) {
                    // result 保持空
                }
            }
            Bukkit.getLogger().info("[VersionUtil] safeGetOnlinePlayers called, returning " + result.size() + " players (type=" + result.getClass().getSimpleName() + ")");
            return result;
        } catch (Throwable t) {
            Bukkit.getLogger().log(Level.WARNING, "[VersionUtil] safeGetOnlinePlayers 反射失败: " + t.getMessage());
            // 兜底（在编译目标为 Paper 的 JAR 上可用；若 1.8 上又出错，返回空）
            try {
                List<Player> result = new ArrayList<>(Bukkit.getOnlinePlayers());
                Bukkit.getLogger().info("[VersionUtil] safeGetOnlinePlayers (fallback) returning " + result.size() + " players");
                return result;
            } catch (Throwable ignored2) {
                Bukkit.getLogger().info("[VersionUtil] safeGetOnlinePlayers returning 0 players (all fallbacks failed)");
                return new ArrayList<>();
            }
        }
    }
}
