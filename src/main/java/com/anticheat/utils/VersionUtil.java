package com.anticheat.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

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
            return player.isInWater();
        } else {
            Location loc = player.getLocation();
            Material material = loc.getBlock().getType();
            return material == Material.WATER || isStationaryWater(material);
        }
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
}
