package com.anticheat.bounty;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class BountyWorld {
    private static final String WORLD_NAME = "bounty_world";
    private static final int PLATFORM_SIZE = 20;
    private static final int PLATFORM_HEIGHT = 64;

    private final AdvancedAntiCheat plugin;
    private World world;

    public BountyWorld(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        setupWorld();
    }

    private void setupWorld() {
        world = Bukkit.getWorld(WORLD_NAME);
        if (world != null) {
            deleteWorld();
        }

        WorldCreator creator = new WorldCreator(WORLD_NAME);
        creator.generatorSettings("{\"layers\":[{\"block\":\"air\",\"height\":256}],\"biome\":\"plains\",\"structures\":{\"structures\":{}}}");
        creator.generateStructures(false);
        creator.generator(new BountyVoidGenerator());

        world = Bukkit.createWorld(creator);

        if (world != null) {
            world.setGameRuleValue("doMobSpawning", "false");
            world.setGameRuleValue("doDaylightCycle", "false");
            world.setGameRuleValue("doWeatherCycle", "false");
            world.setGameRuleValue("keepInventory", "true");
            world.setTime(6000);
            world.setStorm(false);
        }

        if (world == null) {
            world = Bukkit.getWorlds().get(0);
        }

        createSpawnPlatform();
    }

    private void createSpawnPlatform() {
        Location center = new Location(world, 0, PLATFORM_HEIGHT, 0);
        int x = center.getBlockX() - PLATFORM_SIZE / 2;
        int y = PLATFORM_HEIGHT - 1;
        int z = center.getBlockZ() - PLATFORM_SIZE / 2;

        for (int dx = 0; dx < PLATFORM_SIZE; dx++) {
            for (int dz = 0; dz < PLATFORM_SIZE; dz++) {
                Block block = world.getBlockAt(x + dx, y, z + dz);
                if (block.getType() != Material.BEDROCK) {
                    block.setType(Material.BEDROCK);
                }
            }
        }
    }

    public Location getSpawnLocation() {
        return new Location(world, 0.5, PLATFORM_HEIGHT, 0.5, 0, 0);
    }

    public void preparePlayer(Player player) {
        player.getInventory().clear();
        player.setHealth(20);
        player.setFoodLevel(20);
        player.setSaturation(5);
        player.setFallDistance(0);
        player.setFireTicks(0);
        player.setGameMode(org.bukkit.GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setWalkSpeed(0.2f);
        player.setFlySpeed(0.1f);

        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
    }

    public void resetPlayerState(Player player) {
        preparePlayer(player);
        player.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
    }

    public void deleteWorld() {
        if (world != null) {
            for (Player player : world.getPlayers()) {
                player.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
            }
            Bukkit.unloadWorld(world, false);
            File worldFolder = world.getWorldFolder();
            deleteFolder(worldFolder);
            world = null;
        }
    }

    private void deleteFolder(File folder) {
        if (folder == null || !folder.exists()) return;
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

    public World getWorld() {
        return world;
    }

    public static class BountyVoidGenerator extends ChunkGenerator {
        @Override
        public ChunkData generateChunkData(World world, Random random, int x, int z, BiomeGrid biome) {
            return createChunkData(world);
        }

        @Override
        public List<BlockPopulator> getDefaultPopulators(World world) {
            return Arrays.asList();
        }

        @Override
        public boolean canSpawn(World world, int x, int z) {
            return true;
        }

        @Override
        public Location getFixedSpawnLocation(World world, Random random) {
            return new Location(world, 0, PLATFORM_HEIGHT, 0);
        }
    }
}
