package com.anticheat.captcha.tasks;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TypeD_BlockMaze extends CaptchaTask {

    private final Map<UUID, MazeInfo> activeMazes;
    private final Random random;

    private static final DyeColor[] COLORS = {
            DyeColor.RED, DyeColor.BLUE, DyeColor.GREEN, DyeColor.YELLOW
    };

    public enum DyeColor {
        RED("红色", Material.RED_SHULKER_BOX),
        BLUE("蓝色", Material.BLUE_SHULKER_BOX),
        GREEN("绿色", Material.GREEN_SHULKER_BOX),
        YELLOW("黄色", Material.YELLOW_SHULKER_BOX);

        private final String name;
        private final Material material;

        DyeColor(String name, Material material) {
            this.name = name;
            this.material = material;
        }

        public String getName() {
            return name;
        }

        public Material getMaterial() {
            return material;
        }
    }

    public TypeD_BlockMaze(AdvancedAntiCheat plugin) {
        super(plugin);
        this.activeMazes = new ConcurrentHashMap<>();
        this.random = new Random();
    }

    @Override
    public void start(Player player, Location location) {
        List<DyeColor> sequence = generateSequence();

        Map<Location, DyeColor> blocks = placeBlocks(player, location, sequence);

        MazeInfo info = new MazeInfo(sequence, blocks, new ArrayList<>(), 0);
        activeMazes.put(player.getUniqueId(), info);

        StringBuilder sequenceDesc = new StringBuilder();
        for (int i = 0; i < sequence.size(); i++) {
            if (i > 0) sequenceDesc.append(" -> ");
            sequenceDesc.append(sequence.get(i).getName());
        }

        sendInstruction(player, "按照顺序依次站在方块上");
        player.sendMessage("§7顺序: " + sequenceDesc);
    }

    private List<DyeColor> generateSequence() {
        List<DyeColor> colors = new ArrayList<>(Arrays.asList(COLORS));
        Collections.shuffle(colors);
        return colors.subList(0, 4);
    }

    private Map<Location, DyeColor> placeBlocks(Player player, Location center, List<DyeColor> sequence) {
        Map<Location, DyeColor> blocks = new HashMap<>();

        int[][] positions = {
                {-2, 0}, {0, 0}, {2, 0},
                {-2, 2}, {0, 2}, {2, 2},
                {-2, -2}, {0, -2}, {2, -2}
        };

        Set<Integer> usedPositions = new HashSet<>();

        for (DyeColor color : sequence) {
            int posIndex;
            do {
                posIndex = random.nextInt(positions.length);
            } while (usedPositions.contains(posIndex));
            usedPositions.add(posIndex);

            Location blockLoc = center.clone().add(positions[posIndex][0], -1, positions[posIndex][1]);
            blockLoc.getBlock().setType(color.getMaterial());
            blocks.put(blockLoc, color);

            Location armorStandLoc = blockLoc.clone().add(0.5, 1.5, 0.5);
            ArmorStand armorStand = player.getWorld().spawn(armorStandLoc, ArmorStand.class);
            armorStand.setInvisible(true);
            armorStand.setInvulnerable(true);
            armorStand.setGravity(false);
            armorStand.setCustomName(getColorName(color));
            armorStand.setCustomNameVisible(true);
        }

        return blocks;
    }

    private String getColorName(DyeColor color) {
        switch (color) {
            case RED: return "§c红色";
            case BLUE: return "§9蓝色";
            case GREEN: return "§a绿色";
            case YELLOW: return "§e黄色";
            default: return color.name();
        }
    }

    @Override
    public void cleanup(Player player) {
        MazeInfo info = activeMazes.remove(player.getUniqueId());
        if (info != null) {
            for (Location loc : info.blocks.keySet()) {
                loc.getBlock().setType(Material.AIR);
            }
        }
    }

    @Override
    public String getTaskDescription() {
        return "方块踩踏任务";
    }

    @Override
    public boolean isCompleted(Player player) {
        return false;
    }

    public void onPlayerMove(Player player, Location to) {
        MazeInfo info = activeMazes.get(player.getUniqueId());
        if (info == null) {
            return;
        }

        Location feetLoc = to.clone().subtract(0, 0.5, 0);
        feetLoc.setY(Math.floor(feetLoc.getY()));

        for (Map.Entry<Location, DyeColor> entry : info.blocks.entrySet()) {
            Location blockLoc = entry.getKey();
            if (feetLoc.getBlockX() == blockLoc.getBlockX() &&
                feetLoc.getBlockZ() == blockLoc.getBlockZ()) {

                if (entry.getValue() == info.sequence.get(info.currentIndex)) {
                    info.playerSequence.add(entry.getValue());
                    info.currentIndex++;

                    if (info.currentIndex >= info.sequence.size()) {
                        cleanup(player);
                        plugin.getCaptchaManager().completeTask(player);
                    } else {
                        player.sendMessage("§a正确！继续下一步");
                    }
                } else {
                    info.currentIndex = 0;
                    info.playerSequence.clear();
                    player.sendMessage("§c顺序错误，请重新开始");
                }

                return;
            }
        }
    }

    private static class MazeInfo {
        final List<DyeColor> sequence;
        final Map<Location, DyeColor> blocks;
        final List<DyeColor> playerSequence;
        int currentIndex;

        MazeInfo(List<DyeColor> sequence, Map<Location, DyeColor> blocks, List<DyeColor> playerSequence, int currentIndex) {
            this.sequence = sequence;
            this.blocks = blocks;
            this.playerSequence = playerSequence;
            this.currentIndex = currentIndex;
        }
    }
}
