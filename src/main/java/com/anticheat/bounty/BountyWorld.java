package com.anticheat.bounty;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 赏金沙箱世界（`bounty_world`，空岛超平坦，与主世界物理隔离）。
 *
 * <h3>重启时"重置"是可选项，不是默认行为</h3>
 * 原实现**每次插件启动都删掉并重建世界**。文档要求的是"服务器重启时此世界可选择性重置"，
 * 所以改成由 `bounty.world.reset-on-start` 控制（默认 false = 复用）。
 *
 * <p>复用时必须清掉上一轮留下的实体：竞技场里堆着上次没打完的傀儡与幽灵实体，
 * 会让新会话的目标计数从一开始就是错的。因此无论是否重置，启动时都会**清空该世界
 * 的全部非玩家实体**——这是沙箱世界，里面不可能有需要保留的东西。</p>
 *
 * <h3>为什么世界创建失败要降级而不是崩</h3>
 * 沙箱是可选的玩法。建不出世界时应当只让赏金不可用（[isUsable] 返回 false），
 * 而不是把整个反作弊插件拖下水——这与核心层"失败即降级"的纪律一致。
 */
public class BountyWorld {

    private static final String WORLD_NAME = "bounty_world";

    /** 出生平台边长。 */
    public static final int PLATFORM_SIZE = 20;

    /** 平台高度（任务点位也用它）。 */
    public static final int PLATFORM_HEIGHT = 64;

    private final AdvancedAntiCheat plugin;
    private World world;

    public BountyWorld(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        setupWorld();
    }

    private void setupWorld() {
        boolean reset = plugin.getConfig().getBoolean("bounty.world.reset-on-start", false);
        World existing = Bukkit.getWorld(WORLD_NAME);

        if (existing != null && !reset) {
            world = existing;
            applyWorldRules(world);
            purgeEntities(world);
            createSpawnPlatform();
            plugin.getLogger().info("[Bounty] 复用已存在的沙箱世界 " + WORLD_NAME);
            return;
        }
        if (existing != null) {
            deleteWorld();
        }

        try {
            WorldCreator creator = new WorldCreator(WORLD_NAME);
            // generatorSettings 是较新的 API：老服务端没有这个方法，而它只是给
            // 内置生成器用的提示（我们用的是自定义空世界生成器），所以失败可以忽略。
            try {
                creator.generatorSettings("{\"layers\":[{\"block\":\"air\",\"height\":256}],"
                        + "\"biome\":\"plains\",\"structures\":{\"structures\":{}}}");
            } catch (Throwable ignored) {
                // 老版本没有该 API，自定义 ChunkGenerator 已足够
            }
            creator.generateStructures(false);
            creator.generator(new BountyVoidGenerator());
            world = Bukkit.createWorld(creator);
        } catch (Throwable t) {
            plugin.getLogger().warning("[Bounty] 创建沙箱世界失败，赏金功能将不可用: " + t.getMessage());
            world = null;
            return;
        }

        if (world == null) {
            plugin.getLogger().warning("[Bounty] 创建沙箱世界返回 null，赏金功能将不可用");
            return;
        }
        applyWorldRules(world);
        createSpawnPlatform();
    }

    private void applyWorldRules(World target) {
        if (target == null) return;
        // 逐条 try/catch：不同版本对 gamerule 名称与 keepInventory 的支持不完全一致，
        // 其中任何一条失败都不该让整个沙箱不可用。
        safeGameRule(target, "doMobSpawning", "false");
        safeGameRule(target, "doDaylightCycle", "false");
        safeGameRule(target, "doWeatherCycle", "false");
        safeGameRule(target, "doFireTick", "false");
        safeGameRule(target, "keepInventory", "true");
        safeGameRule(target, "mobGriefing", "false");
        try {
            target.setTime(6000);
            target.setStorm(false);
        } catch (Throwable ignored) {
        }
    }

    private void safeGameRule(World target, String rule, String value) {
        try {
            target.setGameRuleValue(rule, value);
        } catch (Throwable ignored) {
            // 该服务端不支持这条规则，跳过
        }
    }

    /** 清掉沙箱世界里所有非玩家实体（上一轮的傀儡、掉落物、幽灵实体）。 */
    private void purgeEntities(World target) {
        if (target == null) return;
        int removed = 0;
        try {
            for (Entity entity : target.getEntities()) {
                if (entity instanceof Player) continue;
                try {
                    entity.remove();
                    removed++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[Bounty] 清理沙箱实体失败: " + t.getMessage());
        }
        if (removed > 0) {
            plugin.getLogger().info("[Bounty] 已清理沙箱世界残留实体 " + removed + " 个");
        }
    }

    private void createSpawnPlatform() {
        if (world == null) return;
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

    /** 世界是否可用（建不出来时为 false，赏金入口据此拒绝进入）。 */
    public boolean isUsable() {
        return world != null && world.getName().equals(WORLD_NAME);
    }

    public Location getSpawnLocation() {
        if (world == null) {
            return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0).getSpawnLocation();
        }
        return new Location(world, 0.5, PLATFORM_HEIGHT, 0.5, 0, 0);
    }

    /**
     * 把玩家重置成"可以干净开始"的状态。
     *
     * <p>**不负责背包**：背包的暂存与还原由 [BountySession] 用快照完成，
     * 因为"清空"与"还原"必须成对出现，分在两个类里就会重演"清了没人还"的事故。</p>
     */
    public void preparePlayer(Player player) {
        try {
            player.getInventory().clear();
            player.getInventory().setArmorContents(null);
        } catch (Throwable ignored) {
        }
        try {
            player.setHealth(Math.min(player.getMaxHealth(), 20.0));
            player.setFoodLevel(20);
            player.setSaturation(5f);
            player.setFallDistance(0f);
            player.setFireTicks(0);
            player.setGameMode(org.bukkit.GameMode.SURVIVAL);
            player.setAllowFlight(false);
            player.setFlying(false);
            player.setWalkSpeed(0.2f);
            player.setFlySpeed(0.1f);
            player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        } catch (Throwable ignored) {
        }
    }

    /** 兼容旧调用点（当前只有 BountySession 在用它自己的快照流程）。 */
    public void resetPlayerState(Player player) {
        preparePlayer(player);
    }

    public void deleteWorld() {
        if (world == null) return;
        World target = world;
        world = null;
        try {
            Location fallback = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0).getSpawnLocation();
            for (Player player : target.getPlayers()) {
                if (fallback != null) player.teleport(fallback);
            }
            Bukkit.unloadWorld(target, false);
            deleteFolder(target.getWorldFolder());
        } catch (Throwable t) {
            plugin.getLogger().warning("[Bounty] 删除沙箱世界失败: " + t.getMessage());
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

    /** 空世界生成器（没有任何地形与结构）。 */
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
