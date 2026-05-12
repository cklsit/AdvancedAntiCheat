package com.anticheat.captcha.tasks;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

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
            case ARROW_DIRECTION:
                startArrowDirection(player, location, info);
                break;
            case COLOR_SELECT:
                startColorSelect(player, location, info);
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

    private void startArrowDirection(Player player, Location location, PuzzleInfo info) {
        String[] directions = {"北", "东", "南", "西"};
        int targetIndex = random.nextInt(directions.length);
        String targetDirection = directions[targetIndex];

        info.targetAnswer = targetIndex;

        player.sendMessage("§7目标方向: §e" + targetDirection);

        float[] rotations = {0f, 90f, 180f, 270f};

        for (int i = 0; i < 4; i++) {
            Location buttonLoc = location.clone().add(-3 + i, 0, 2);
            org.bukkit.block.Block button = buttonLoc.getBlock();
            button.setType(Material.STONE_BUTTON);

            Location itemFrameLoc = buttonLoc.clone().add(0, 1, 0);
            org.bukkit.entity.ItemFrame itemFrame = player.getWorld().spawn(itemFrameLoc, org.bukkit.entity.ItemFrame.class);

            ItemStack arrow = new ItemStack(Material.ARROW);
            itemFrame.setItem(arrow);
            itemFrame.setRotation(rotations[i]);

            if (info.itemFrames == null) {
                info.itemFrames = new ArrayList<>();
            }
            info.itemFrames.add(itemFrame);
            if (info.blocks == null) {
                info.blocks = new ArrayList<>();
            }
            info.blocks.add(buttonLoc);
        }

        sendInstruction(player, "点击指向" + targetDirection + "的箭头按钮");
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

    @Override
    public void cleanup(Player player) {
        PuzzleInfo info = activePuzzles.remove(player.getUniqueId());
        if (info != null) {
            if (info.blocks != null) {
                for (Location loc : info.blocks) {
                    loc.getBlock().setType(Material.AIR);
                }
            }
            if (info.itemFrames != null) {
                for (org.bukkit.entity.ItemFrame frame : info.itemFrames) {
                    frame.remove();
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
        ARROW_DIRECTION,
        COLOR_SELECT
    }

    private static class PuzzleInfo {
        final PuzzleType type;
        int targetAnswer;
        boolean completed;
        List<Location> blocks;
        List<org.bukkit.entity.ItemFrame> itemFrames;

        PuzzleInfo(PuzzleType type, int targetAnswer, boolean completed) {
            this.type = type;
            this.targetAnswer = targetAnswer;
            this.completed = completed;
        }
    }
}
