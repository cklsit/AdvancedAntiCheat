package com.anticheat.captcha;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

public class CaptchaWorld {

    private final AdvancedAntiCheat plugin;
    private World captchaWorld;
    private final AtomicInteger locationIndex;

    private static final int PLATFORM_SIZE = 10;
    private static final int PLATFORM_HEIGHT = 100;
    private static final int DISTANCE_BETWEEN_PLATFORMS = 50;
    private static final String WORLD_NAME = "captcha_void_world";

    public CaptchaWorld(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.locationIndex = new AtomicInteger(0);
        setupWorld();
    }

    private void setupWorld() {
        captchaWorld = Bukkit.getWorld(WORLD_NAME);

        if (captchaWorld != null) {
            deleteWorld();
        }

        org.bukkit.WorldCreator creator = new org.bukkit.WorldCreator(WORLD_NAME);
        creator.generateStructures(false);
        creator.generator(new CaptchaVoidGenerator());

        captchaWorld = Bukkit.createWorld(creator);

        if (captchaWorld != null) {
            captchaWorld.setGameRuleValue("doMobSpawning", "false");
            captchaWorld.setGameRuleValue("doDaylightCycle", "false");
            captchaWorld.setGameRuleValue("doWeatherCycle", "false");
            captchaWorld.setGameRuleValue("doNaturalRegeneration", "false");
            captchaWorld.setGameRuleValue("keepInventory", "false");
            captchaWorld.setTime(1000);
            captchaWorld.setWeatherDuration(0);
            captchaWorld.setStorm(false);
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

    public void preparePlayer(Player player) {
        player.getInventory().clear();
        player.getEnderChest().clear();
        player.setHealth(20);
        player.setFoodLevel(20);
        player.setSaturation(5);
        player.setExhaustion(0);
        player.setFallDistance(0);
        player.setFireTicks(0);
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setWalkSpeed(0.2f);
        player.setFlySpeed(0.2f);

        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
    }

    public void deleteWorld() {
        if (captchaWorld != null) {
            for (Player player : captchaWorld.getPlayers()) {
                player.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
            }

            Bukkit.unloadWorld(captchaWorld, false);

            File worldFolder = captchaWorld.getWorldFolder();
            deleteFolder(worldFolder);

            captchaWorld = null;
        }
    }

    private void deleteFolder(File folder) {
        if (folder == null || !folder.exists()) {
            return;
        }

        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteFolder(file);
                } else {
                    file.delete();
                }
            }
        }

        folder.delete();
    }

    public void resetWorld() {
        deleteWorld();
        setupWorld();
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

    public static class CaptchaVoidGenerator extends ChunkGenerator {
        @Override
        public ChunkData generateChunkData(World world, Random random, int x, int z, BiomeGrid biome) {
            ChunkData chunk = createChunkData(world);
            return chunk;
        }

        @Override
        public List<BlockPopulator> getDefaultPopulators(World world) {
            return java.util.Collections.emptyList();
        }

        @Override
        public boolean canSpawn(World world, int x, int z) {
            return true;
        }

        @Override
        public Location getFixedSpawnLocation(World world, Random random) {
            return new Location(world, 0, 100, 0);
        }
    }
}
