package com.anticheat.captcha;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.utils.VersionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.concurrent.atomic.AtomicInteger;

public class CaptchaWorld {

    private final AdvancedAntiCheat plugin;
    private World captchaWorld;
    private final AtomicInteger locationIndex;

    private static final int PLATFORM_SIZE = 10;
    private static final int PLATFORM_HEIGHT = 100;
    private static final int DISTANCE_BETWEEN_PLATFORMS = 50;

    public CaptchaWorld(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.locationIndex = new AtomicInteger(0);
        setupWorld();
    }

    private void setupWorld() {
        String worldName = "captcha_world";
        captchaWorld = Bukkit.getWorld(worldName);

        if (captchaWorld == null) {
            org.bukkit.WorldCreator creator = new org.bukkit.WorldCreator(worldName);
            
            if (VersionUtil.isHighVersion()) {
                creator.generatorSettings("{\"layers\":[{\"block\":\"air\",\"height\":1}],\"biome\":\"plains\"}");
            } else {
                creator.generatorSettings("2;0;1;");
            }
            creator.generateStructures(false);
            
            captchaWorld = Bukkit.createWorld(creator);
            
            if (captchaWorld != null) {
                captchaWorld.setGameRuleValue("doMobSpawning", "false");
                captchaWorld.setGameRuleValue("doDaylightCycle", "false");
                captchaWorld.setGameRuleValue("doWeatherCycle", "false");
                captchaWorld.setGameRuleValue("doNaturalRegeneration", "false");
                captchaWorld.setGameRuleValue("keepInventory", "true");
                captchaWorld.setTime(1000);
                captchaWorld.setWeatherDuration(0);
                captchaWorld.setStorm(false);
            }
        }

        if (captchaWorld == null) {
            captchaWorld = Bukkit.getWorlds().get(0);
        }
    }

    public Location getNextLocation() {
        int index = locationIndex.getAndIncrement();
        if (index > 100) {
            locationIndex.set(0);
            index = 0;
        }

        int x = index * DISTANCE_BETWEEN_PLATFORMS;
        int z = 0;

        Location location = new Location(captchaWorld, x + PLATFORM_SIZE / 2, PLATFORM_HEIGHT, z + PLATFORM_SIZE / 2);

        ensurePlatform(location);

        return location;
    }

    private void ensurePlatform(Location center) {
        int x = center.getBlockX() - PLATFORM_SIZE / 2;
        int y = PLATFORM_HEIGHT - 1;
        int z = center.getBlockZ() - PLATFORM_SIZE / 2;

        for (int dx = 0; dx < PLATFORM_SIZE; dx++) {
            for (int dz = 0; dz < PLATFORM_SIZE; dz++) {
                Block block = captchaWorld.getBlockAt(x + dx, y, z + dz);
                if (block.getType() != Material.BEDROCK) {
                    block.setType(Material.BEDROCK);
                }
            }
        }

        for (int dx = 0; dx <= PLATFORM_SIZE + 1; dx++) {
            for (int dz = 0; dz <= PLATFORM_SIZE + 1; dz++) {
                if (dx == 0 || dx == PLATFORM_SIZE + 1 || dz == 0 || dz == PLATFORM_SIZE + 1) {
                    for (int dy = 1; dy <= 5; dy++) {
                        Block block = captchaWorld.getBlockAt(x + dx - 1, y + dy, z + dz - 1);
                        if (block.getType() != Material.BARRIER) {
                            block.setType(Material.BARRIER);
                        }
                    }
                }
            }
        }
    }

    public void cleanup(Location location) {
        int x = location.getBlockX() - PLATFORM_SIZE / 2;
        int y = PLATFORM_HEIGHT - 1;
        int z = location.getBlockZ() - PLATFORM_SIZE / 2;

        for (int dx = 0; dx <= PLATFORM_SIZE + 1; dx++) {
            for (int dz = 0; dz <= PLATFORM_SIZE + 1; dz++) {
                for (int dy = 0; dy <= 10; dy++) {
                    Block block = captchaWorld.getBlockAt(x + dx - 1, y + dy, z + dz - 1);
                    if (block.getType() == Material.BARRIER) {
                        block.setType(Material.AIR);
                    }
                }
            }
        }
    }

    public World getWorld() {
        return captchaWorld;
    }

    public static int getPlatformSize() {
        return PLATFORM_SIZE;
    }

    public static int getPlatformHeight() {
        return PLATFORM_HEIGHT;
    }
}
