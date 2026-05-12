package com.anticheat.captcha.tasks;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.util.EulerAngle;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TypeC_SequenceReplay extends CaptchaTask {

    private final Map<UUID, SequenceInfo> activeSequences;
    private final Random random;

    private static final Action[] ALL_ACTIONS = {
            Action.JUMP, Action.TURN_LEFT, Action.SPRINT, Action.SNEAK
    };

    public enum Action {
        JUMP("跳跃"),
        TURN_LEFT("转向左"),
        SPRINT("疾跑"),
        SNEAK("潜行");

        private final String description;

        Action(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    public TypeC_SequenceReplay(AdvancedAntiCheat plugin) {
        super(plugin);
        this.activeSequences = new ConcurrentHashMap<>();
        this.random = new Random();
    }

    @Override
    public void start(Player player, Location location) {
        List<Action> sequence = generateSequence();

        Location armorStandLoc = location.clone().add(3, 0, 0);
        ArmorStand armorStand = player.getWorld().spawn(armorStandLoc, ArmorStand.class);
        armorStand.setInvisible(false);
        armorStand.setInvulnerable(true);
        armorStand.setGravity(false);

        SequenceInfo info = new SequenceInfo(sequence, armorStand, new ArrayList<>(), false);
        activeSequences.put(player.getUniqueId(), info);

        StringBuilder sequenceDesc = new StringBuilder();
        for (int i = 0; i < sequence.size(); i++) {
            if (i > 0) sequenceDesc.append(" -> ");
            sequenceDesc.append(sequence.get(i).getDescription());
        }

        sendInstruction(player, "观察盔甲架的动作序列，然后重复它的动作");
        player.sendMessage("§7序列: " + sequenceDesc);

        playSequence(player, armorStand, sequence, 0);
    }

    private List<Action> generateSequence() {
        List<Action> sequence = new ArrayList<>();
        int length = random.nextInt(3) + 2;

        for (int i = 0; i < length; i++) {
            Action action = ALL_ACTIONS[random.nextInt(ALL_ACTIONS.length)];
            if (i > 0 && action == sequence.get(i - 1)) {
                i--;
                continue;
            }
            sequence.add(action);
        }

        return sequence;
    }

    private void playSequence(Player player, ArmorStand armorStand, List<Action> sequence, int index) {
        if (index >= sequence.size()) {
            SequenceInfo info = activeSequences.get(player.getUniqueId());
            if (info != null) {
                info.waitingForPlayer = true;
                player.sendMessage("§e现在轮到你了！重复刚才的动作序列");
            }
            return;
        }

        Action action = sequence.get(index);
        animateArmorStand(armorStand, action);

        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                animateNextPose(armorStand, sequence.get(index));
                playSequence(player, armorStand, sequence, index + 1);
            }
        }.runTaskLater(plugin, 20);
    }

    private void animateArmorStand(ArmorStand armorStand, Action action) {
        switch (action) {
            case JUMP:
                armorStand.setHeadPose(new EulerAngle(Math.toRadians(-30), 0, 0));
                break;
            case TURN_LEFT:
                armorStand.setHeadPose(new EulerAngle(0, Math.toRadians(-45), 0));
                break;
            case SPRINT:
                armorStand.setBodyPose(new EulerAngle(Math.toRadians(20), 0, 0));
                break;
            case SNEAK:
                armorStand.setBodyPose(new EulerAngle(Math.toRadians(45), 0, 0));
                break;
        }
    }

    private void animateNextPose(ArmorStand armorStand, Action action) {
        switch (action) {
            case JUMP:
                armorStand.setHeadPose(new EulerAngle(0, 0, 0));
                break;
            case TURN_LEFT:
                armorStand.setHeadPose(new EulerAngle(0, 0, 0));
                break;
            case SPRINT:
                armorStand.setBodyPose(new EulerAngle(0, 0, 0));
                break;
            case SNEAK:
                armorStand.setBodyPose(new EulerAngle(0, 0, 0));
                break;
        }
    }

    @Override
    public void cleanup(Player player) {
        SequenceInfo info = activeSequences.remove(player.getUniqueId());
        if (info != null && info.armorStand != null) {
            info.armorStand.remove();
        }
    }

    @Override
    public String getTaskDescription() {
        return "序列复刻任务";
    }

    @Override
    public boolean isCompleted(Player player) {
        return false;
    }

    public void onPlayerAction(Player player, Action action) {
        SequenceInfo info = activeSequences.get(player.getUniqueId());
        if (info == null || !info.waitingForPlayer) {
            return;
        }

        info.playerActions.add(action);

        if (info.playerActions.size() >= info.targetSequence.size()) {
            if (compareSequences(info.targetSequence, info.playerActions)) {
                cleanup(player);
                plugin.getCaptchaManager().completeTask(player);
            } else {
                info.playerActions.clear();
                player.sendMessage("§c序列不正确，请重试");
            }
        }
    }

    private boolean compareSequences(List<Action> expected, List<Action> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }

        for (int i = 0; i < expected.size(); i++) {
            if (expected.get(i) != actual.get(i)) {
                return false;
            }
        }

        return true;
    }

    private static class SequenceInfo {
        final List<Action> targetSequence;
        final ArmorStand armorStand;
        final List<Action> playerActions;
        boolean waitingForPlayer;

        SequenceInfo(List<Action> targetSequence, ArmorStand armorStand, List<Action> playerActions, boolean waitingForPlayer) {
            this.targetSequence = targetSequence;
            this.armorStand = armorStand;
            this.playerActions = playerActions;
            this.waitingForPlayer = waitingForPlayer;
        }
    }
}
