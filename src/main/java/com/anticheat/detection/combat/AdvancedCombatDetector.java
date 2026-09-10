package com.anticheat.detection.combat;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.detection.ViolationRecord;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * AdvancedCombatDetector —— 补充战斗检测器（监听器）。
 *
 * <p>覆盖战斗类检测的 5 个补充项：</p>
 * <ul>
 *   <li><b>攻击角度</b>：命中瞬间准星与目标连线的夹角，背对/大角度偏差判定 Aim/KillAura。</li>
 *   <li><b>自瞄频谱</b>：攻击前视角变化序列的频域/平滑度特征，检测锁定式自瞄。</li>
 *   <li><b>击退熵</b>：受击后位移轨迹信息熵，完美回位/直线返回表示无击退。</li>
 *   <li><b>反击退相位锁定</b>：受击 tick 与反向移动输入的同步相关性。</li>
 *   <li><b>自动喝药</b>：受伤到使用药水的反应时间低于人类极限。</li>
 * </ul>
 */
public class AdvancedCombatDetector implements Listener {

    private final AdvancedAntiCheat plugin;

    private final Map<UUID, List<RotationSample>> rotationSamples = new HashMap<>();
    private final Map<UUID, KnockbackSample> knockbackSamples = new HashMap<>();
    private final Map<UUID, Long> lastDamageTime = new HashMap<>();

    private static final double MAX_ATTACK_ANGLE_DEG = 65.0;     // 命中最大合法夹角
    private static final int ROTATION_WINDOW = 40;               // 自瞄频谱采样窗口
    private static final int KNOCKBACK_TRACK_TICKS = 20;         // 击退位移跟踪 tick
    private static final long AUTO_POTION_MIN_MS = 120;          // 自动喝药最小人类反应时间

    public AdvancedCombatDetector(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // ---------------- 攻击角度 / 自瞄频谱 ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;

        Player attacker = (Player) event.getDamager();
        LivingEntity victim = (LivingEntity) event.getEntity();
        UUID uuid = attacker.getUniqueId();

        if (isExempt(attacker)) return;

        checkAttackAngle(attacker, victim);
        recordRotation(attacker);
        checkAimbotSpectrum(attacker);
    }

    private void checkAttackAngle(Player attacker, LivingEntity victim) {
        Location eye = attacker.getEyeLocation();
        Vector look = eye.getDirection();
        Vector toTarget = victim.getEyeLocation().toVector().subtract(eye.toVector());
        double angle = Math.toDegrees(look.angle(toTarget));

        if (angle > MAX_ATTACK_ANGLE_DEG) {
            record(attacker, ViolationRecord.ViolationType.AIM_ANGLE,
                String.format("命中目标时准星夹角=%.1f°（>%.0f°）", angle, MAX_ATTACK_ANGLE_DEG),
                0.7);
        }
    }

    private void recordRotation(Player attacker) {
        UUID uuid = attacker.getUniqueId();
        List<RotationSample> samples = rotationSamples.computeIfAbsent(uuid, k -> new ArrayList<>());
        samples.add(new RotationSample(attacker.getLocation().getYaw(), attacker.getLocation().getPitch(), System.nanoTime()));
        if (samples.size() > ROTATION_WINDOW) {
            samples.remove(0);
        }
    }

    private void checkAimbotSpectrum(Player attacker) {
        UUID uuid = attacker.getUniqueId();
        List<RotationSample> samples = rotationSamples.get(uuid);
        if (samples == null || samples.size() < 10) return;

        List<Double> yawDeltas = new ArrayList<>();
        for (int i = 1; i < samples.size(); i++) {
            double delta = samples.get(i).yaw - samples.get(i - 1).yaw;
            // 归一化到 [-180,180)
            while (delta > 180) delta -= 360;
            while (delta < -180) delta += 360;
            yawDeltas.add(delta);
        }

        double mean = mean(yawDeltas);
        double variance = variance(yawDeltas, mean);
        double stdDev = Math.sqrt(variance);
        double cv = mean != 0 ? stdDev / Math.abs(mean) : Double.MAX_VALUE;

        // 过度平滑：视角变化方差极小但持续命中，表现为机械锁定
        if (stdDev < 0.05 && Math.abs(mean) > 0.001) {
            record(attacker, ViolationRecord.ViolationType.AIMBOT_SPECTRUM,
                String.format("视角变化过度平滑（标准差=%.4f，均值=%.3f）", stdDev, mean), 0.8);
            return;
        }

        // 周期性锁定：低频高相关性，检测到固定节奏
        double autocorr = autocorrelation(yawDeltas, 1);
        if (autocorr > 0.85 && cv < 1.5) {
            record(attacker, ViolationRecord.ViolationType.AIMBOT_SPECTRUM,
                String.format("视角变化周期锁定（自相关=%.3f）", autocorr), 0.75);
        }
    }

    // ---------------- 击退熵 / 反击退 ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageTaken(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player victim = (Player) event.getEntity();
        if (isExempt(victim)) return;

        UUID uuid = victim.getUniqueId();
        lastDamageTime.put(uuid, System.currentTimeMillis());

        KnockbackSample sample = knockbackSamples.computeIfAbsent(uuid, k -> new KnockbackSample());
        sample.origin = victim.getLocation().clone();
        sample.displacements.clear();
        sample.tracking = true;
        sample.trackedTicks = 0;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        KnockbackSample sample = knockbackSamples.get(uuid);
        if (sample == null || !sample.tracking) return;

        if (sample.trackedTicks < KNOCKBACK_TRACK_TICKS) {
            Vector d = event.getTo().toVector().subtract(sample.origin.toVector());
            sample.displacements.add(d.clone());
            sample.trackedTicks++;
        } else {
            sample.tracking = false;
            analyzeKnockback(player, sample);
        }
    }

    private void analyzeKnockback(Player player, KnockbackSample sample) {
        if (sample.displacements.isEmpty()) return;

        // 水平位移总长
        double totalHorizontal = 0;
        Set<String> directions = new HashSet<>();
        List<Double> dirAngles = new ArrayList<>();
        for (Vector d : sample.displacements) {
            double h = Math.sqrt(d.getX() * d.getX() + d.getZ() * d.getZ());
            totalHorizontal += h;
            if (h > 1e-6) {
                double ang = Math.toDegrees(Math.atan2(d.getX(), d.getZ()));
                dirAngles.add(ang);
                directions.add(bucketAngle(ang));
            }
        }

        // 信息熵：方向越分散熵越高；完美直线回位 → 低熵
        double entropy = shannonEntropy(directions);

        if (totalHorizontal < 0.05) {
            record(player, ViolationRecord.ViolationType.KNOCKBACK_ENTROPY,
                String.format("受击后几乎无位移（水平=%.4f格）", totalHorizontal), 0.8);
            return;
        }

        if (entropy < 0.6) {
            record(player, ViolationRecord.ViolationType.KNOCKBACK_ENTROPY,
                String.format("受击位移方向熵过低（熵=%.3f）", entropy), 0.7);
        }

        // 反击退：位移方向与击退预期高度一致且集中，检测到同步反向移动
        double dirVariance = variance(dirAngles, mean(dirAngles));
        if (!dirAngles.isEmpty() && dirVariance < 5.0 && totalHorizontal > 0.1) {
            record(player, ViolationRecord.ViolationType.ANTI_KNOCKBACK,
                String.format("受击反向位移相位锁定（方向方差=%.3f）", dirVariance), 0.65);
        }
    }

    // ---------------- 自动喝药 ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (!isPotion(event.getItem())) return;
        checkAutoPotion(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteractPotion(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        ItemStack item = event.getItem();
        if (item != null && isPotion(item)) {
            checkAutoPotion(player);
        }
    }

    private void checkAutoPotion(Player player) {
        Long last = lastDamageTime.get(player.getUniqueId());
        if (last == null) return;
        long elapsed = System.currentTimeMillis() - last;
        if (elapsed < AUTO_POTION_MIN_MS) {
            record(player, ViolationRecord.ViolationType.AUTO_POTION,
                String.format("受伤后 %dms 内自动使用药水（< %dms）", elapsed, AUTO_POTION_MIN_MS), 0.8);
        }
    }

    private boolean isPotion(ItemStack item) {
        Material m = item.getType();
        String name = m.name();
        return name.endsWith("POTION") || name.contains("POTION") || name.equals("MILK_BUCKET");
    }

    // ---------------- 工具 ----------------

    private void record(Player player, ViolationRecord.ViolationType type, String details, double level) {
        plugin.getDetectionManager().getViolationManager().recordViolation(player, type, details, level);
    }

    private boolean isExempt(Player player) {
        if (player == null || !player.isOnline()) return true;
        if (player.hasPermission("anticheat.bypass")) return true;
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE ||
            player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return true;
        return false;
    }

    private double mean(List<Double> values) {
        if (values.isEmpty()) return 0;
        double sum = 0;
        for (double v : values) sum += v;
        return sum / values.size();
    }

    private double variance(List<Double> values, double mean) {
        if (values.size() < 2) return 0;
        double sum = 0;
        for (double v : values) sum += (v - mean) * (v - mean);
        return sum / (values.size() - 1);
    }

    private double autocorrelation(List<Double> values, int lag) {
        if (values.size() <= lag) return 0;
        double mean = mean(values);
        double denom = 0, num = 0;
        for (double v : values) denom += (v - mean) * (v - mean);
        if (denom == 0) return 0;
        for (int i = 0; i < values.size() - lag; i++) {
            num += (values.get(i) - mean) * (values.get(i + lag) - mean);
        }
        return num / denom;
    }

    private String bucketAngle(double angleDeg) {
        int bucket = (int) Math.floor((angleDeg + 180.0) / 45.0);
        return String.valueOf(bucket);
    }

    private double shannonEntropy(Set<String> buckets) {
        // 方向桶数量越多越分散；此处以桶数归一化为 0~1 的"分散度"近似
        int distinct = buckets.size();
        if (distinct <= 1) return 0.0;
        // 8 个方向桶完全分散 → 1.0
        return Math.min(1.0, distinct / 8.0);
    }

    public void clearPlayerData(UUID uuid) {
        rotationSamples.remove(uuid);
        knockbackSamples.remove(uuid);
        lastDamageTime.remove(uuid);
    }

    private static class RotationSample {
        final float yaw;
        final float pitch;
        final long timeNanos;

        RotationSample(float yaw, float pitch, long timeNanos) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.timeNanos = timeNanos;
        }
    }

    private static class KnockbackSample {
        Location origin;
        final List<Vector> displacements = new ArrayList<>();
        boolean tracking = false;
        int trackedTicks = 0;
    }
}
