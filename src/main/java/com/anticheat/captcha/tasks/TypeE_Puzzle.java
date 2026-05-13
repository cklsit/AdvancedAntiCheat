package com.anticheat.captcha.tasks;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TypeE_Puzzle extends CaptchaTask {

    private final Map<UUID, PuzzleInfo> activePuzzles;
    private final Random random;

    public TypeE_Puzzle(AdvancedAntiCheat plugin) {
        super(plugin);
        this.activePuzzles = new ConcurrentHashMap<>();
        this.random = new Random();
    }

    @Override
    public void start(Player player, Location location) {
        PuzzleType puzzleType = PuzzleType.values()[random.nextInt(PuzzleType.values().length)];

        PuzzleInfo info = new PuzzleInfo(puzzleType, 0, false);
        activePuzzles.put(player.getUniqueId(), info);

        switch (puzzleType) {
            case PATTERN_MATCH:
                startPatternMatch(player, location, info);
                break;
            case COLOR_SELECT:
                startColorSelect(player, location, info);
                break;
            case NUMBER_GUESS:
                startNumberGuess(player, location, info);
                break;
        }
    }

    private void startPatternMatch(Player player, Location location, PuzzleInfo info) {
        int targetNumber = random.nextInt(5) + 1;

        info.targetAnswer = targetNumber;

        List<Material> materials = Arrays.asList(
                Material.STONE, Material.DIRT, Material.GRASS_BLOCK,
                Material.COAL_ORE, Material.IRON_ORE, Material.GOLD_ORE
        );

        Material targetMaterial = materials.get(random.nextInt(materials.size()));

        for (int i = 0; i < 6; i++) {
            Location displayLoc = location.clone().add(-3 + i, 2, -3);
            org.bukkit.block.Block block = displayLoc.getBlock();

            if (i < targetNumber) {
                block.setType(targetMaterial);
            } else {
                block.setType(Material.AIR);
            }
        }

        info.blocks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Location buttonLoc = location.clone().add(-3 + i, 0, 2);
            org.bukkit.block.Block button = buttonLoc.getBlock();
            button.setType(Material.STONE_BUTTON);
            info.blocks.add(buttonLoc);
        }

        sendInstruction(player, "选择与上方图案数量匹配的按钮 (1-6)");
    }

    private void startColorSelect(Player player, Location location, PuzzleInfo info) {
        Material[] colors = {
                Material.RED_WOOL, Material.BLUE_WOOL,
                Material.GREEN_WOOL, Material.YELLOW_WOOL
        };

        Material targetColor = colors[random.nextInt(colors.length)];
        info.targetAnswer = Arrays.asList(colors).indexOf(targetColor);

        Location displayLoc = location.clone().add(0, 2, -3);
        displayLoc.getBlock().setType(targetColor);

        for (int i = 0; i < 4; i++) {
            Location buttonLoc = location.clone().add(-3 + i, 0, 2);
            buttonLoc.getBlock().setType(colors[i]);

            if (info.blocks == null) {
                info.blocks = new ArrayList<>();
            }
            info.blocks.add(buttonLoc);
        }

        sendInstruction(player, "选择与上方颜色相同的方块");
    }

    private void startNumberGuess(Player player, Location location, PuzzleInfo info) {
        int targetNumber = random.nextInt(9) + 1;
        info.targetAnswer = targetNumber - 1;

        player.sendMessage("§7目标数字: §e" + targetNumber);

        Material[] numberBlocks = {
                Material.WHITE_WOOL, Material.ORANGE_WOOL, Material.MAGENTA_WOOL,
                Material.LIGHT_BLUE_WOOL, Material.YELLOW_WOOL, Material.LIME_WOOL,
                Material.PINK_WOOL, Material.GRAY_WOOL, Material.LIGHT_GRAY_WOOL
        };

        for (int i = 0; i < 9; i++) {
            Location buttonLoc = location.clone().add(-4 + i, 0, 0);
            org.bukkit.block.Block button = buttonLoc.getBlock();
            button.setType(Material.STONE_BUTTON);

            Location woolLoc = buttonLoc.clone().add(0, 1, 0);
            woolLoc.getBlock().setType(numberBlocks[i]);

            if (info.blocks == null) {
                info.blocks = new ArrayList<>();
            }
            info.blocks.add(buttonLoc);
            if (info.signs == null) {
                info.signs = new ArrayList<>();
            }
            info.signs.add(woolLoc);
        }

        player.sendMessage("§e颜色对应: 1-白 2-橙 3-品红 4-浅蓝 5-黄 6-浅绿 7-粉 8-灰 9-浅灰");
        sendInstruction(player, "点击数字 " + targetNumber + " 对应的颜色方块下方的按钮");
    }

    @Override
    public void cleanup(Player player) {
        PuzzleInfo info = activePuzzles.remove(player.getUniqueId());
        if (info != null) {
            if (info.blocks != null) {
                for (Location loc : info.blocks) {
                    loc.getBlock().setType(Material.AIR);
                }
            }
            if (info.signs != null) {
                for (Location loc : info.signs) {
                    loc.getBlock().setType(Material.AIR);
                }
            }
        }
    }

    @Override
    public String getTaskDescription() {
        return "人机验证谜题";
    }

    @Override
    public boolean isCompleted(Player player) {
        return false;
    }

    public void onButtonPress(Player player, Location buttonLocation) {
        PuzzleInfo info = activePuzzles.get(player.getUniqueId());
        if (info == null || info.completed) {
            return;
        }

        if (info.blocks != null) {
            int buttonIndex = info.blocks.indexOf(buttonLocation);
            if (buttonIndex == info.targetAnswer) {
                info.completed = true;
                cleanup(player);
                plugin.getCaptchaManager().completeTask(player);
            } else {
                player.sendMessage("§c答案错误，请重试");
            }
        }
    }

    private enum PuzzleType {
        PATTERN_MATCH,
        COLOR_SELECT,
        NUMBER_GUESS
    }

    private static class PuzzleInfo {
        final PuzzleType type;
        int targetAnswer;
        boolean completed;
        List<Location> blocks;
        List<Location> signs;

        PuzzleInfo(PuzzleType type, int targetAnswer, boolean completed) {
            this.type = type;
            this.targetAnswer = targetAnswer;
            this.completed = completed;
        }
    }
}
