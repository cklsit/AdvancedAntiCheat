package com.anticheat.detection.behavior;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.detection.ViolationRecord;
import com.anticheat.utils.VersionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AntiReconDetector —— 反侦察行为检测（作弊者规避）。
 *
 * <p>对应《全项检测规范》第八大类「反侦察行为检测」：向疑似作弊玩家发送「管理员正在观察」的
 * 假信号（在玩家附近短暂生成一个隐形的旁观者盔甲架 + 一条仅其可见的提示），随后监测其
 * 后续行为变化——若该玩家在观察到信号后违规产生速率骤降为 0，说明其很可能此前正在作弊，
 * 并在察觉「观察」后主动关闭了作弊功能，据此生成反侦察证据。</p>
 */
public class AntiReconDetector {

    private final AdvancedAntiCheat plugin;

    private static final String OBSERVER_META = "anticheat_fake_observer";

    // 观察状态：UUID -> 观察开始时累计违规数
    private final Map<UUID, Integer> observationStartViolations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> observationStartTime = new ConcurrentHashMap<>();
    private final Set<UUID> alreadyFlagged = ConcurrentHashMap.newKeySet();

    private static final int MIN_VIOLATIONS_TO_OBSERVE = 3;      // 触发观察的最低累计违规数
    private static final long OBSERVE_WINDOW_MS = 60000;         // 观察窗口
    private static final double RECON_THRESHOLD = 0.7;

    public AntiReconDetector(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        startObservationTask();
    }

    private void startObservationTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : VersionUtil.safeGetOnlinePlayers()) {
                    if (!player.isOnline() || player.hasPermission("anticheat.bypass")) continue;
                    UUID uuid = player.getUniqueId();

                    // 处于观察窗口：判断是否在观察后停止了违规
                    if (observationStartTime.containsKey(uuid)) {
                        evaluateObservation(player);
                    } else {
                        maybeStartObservation(player);
                    }
                }
            }
        }.runTaskTimer(plugin, 20L * 30, 20L * 30);
    }

    private void maybeStartObservation(Player player) {
        UUID uuid = player.getUniqueId();
        int total = totalViolations(player);
        if (total >= MIN_VIOLATIONS_TO_OBSERVE && !alreadyFlagged.contains(uuid)) {
            observationStartViolations.put(uuid, total);
            observationStartTime.put(uuid, System.currentTimeMillis());
            spawnFakeObserver(player);
        }
    }

    private void evaluateObservation(Player player) {
        UUID uuid = player.getUniqueId();
        Long start = observationStartTime.get(uuid);
        if (start == null) return;

        long elapsed = System.currentTimeMillis() - start;
        if (elapsed < OBSERVE_WINDOW_MS) return;

        Integer startCount = observationStartViolations.remove(uuid);
        observationStartTime.remove(uuid);
        if (startCount == null) return;

        int nowCount = totalViolations(player);

        // 观察期内零新增违规，但此前却有持续违规 → 疑似察觉观察后关闭作弊
        if (nowCount == startCount && startCount >= MIN_VIOLATIONS_TO_OBSERVE) {
            alreadyFlagged.add(uuid);
            plugin.getDetectionManager().getViolationManager().recordViolation(
                player,
                ViolationRecord.ViolationType.ANTI_RECON,
                String.format("观察到管理员信号后违规骤停（观察前=%d次，观察期新增=0）", startCount),
                RECON_THRESHOLD
            );
        }
    }

    /** 在玩家附近生成短暂隐形的「旁观者」盔甲架，模拟管理员观察。 */
    private void spawnFakeObserver(final Player player) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    Location loc = player.getLocation().clone().add(0, 1, 2);
                    ArmorStand stand = player.getWorld().spawn(loc, ArmorStand.class);
                    stand.setVisible(false);
                    stand.setGravity(false);
                    stand.setMarker(true);
                    stand.setInvulnerable(true);
                    stand.setMetadata(OBSERVER_META, new FixedMetadataValue(plugin, player.getUniqueId().toString()));
                    // 仅目标玩家可见的「管理员」提示
                    player.sendMessage("§8§o[系统] 一名管理员正在观察该区域…");

                    // 3 秒后移除假观察者
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            if (stand.isValid()) stand.remove();
                        }
                    }.runTaskLater(plugin, 20L * 3);
                } catch (Throwable ignored) {
                    // 某些版本 spawn(ArmorStand) 不可用，忽略即可
                }
            }
        }.runTask(plugin);
    }

    private int totalViolations(Player player) {
        try {
            return plugin.getDetectionManager().getViolationManager()
                .getViolationHistory(player.getUniqueId()).size();
        } catch (Throwable t) {
            return 0;
        }
    }

    public void clearPlayerData(UUID uuid) {
        observationStartViolations.remove(uuid);
        observationStartTime.remove(uuid);
    }
}
