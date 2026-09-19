package com.anticheat.detection.behavior;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.detection.ViolationRecord;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GlobalAnomalyDetector —— 全局异常检测（无监督离群点分析）。
 *
 * <p>对应《全项检测规范》第八大类「全局异常检测（未知作弊）」：定期采集全体在线玩家的
 * 行为特征向量，使用 <b>孤立森林（Isolation Forest）</b> 找出少数异常群体。
 * 与传统基于规则的检测互补——规则检测覆盖「已知作弊」，本模块负责捕捉「未见过的作弊」。</p>
 *
 * <p>特征维度（每窗口，默认 60s）：</p>
 * <ol>
 *   <li>平均水平移动速度（格/tick）</li>
 *   <li>移动速度方差</li>
 *   <li>跳跃次数</li>
 *   <li>攻击次数</li>
 *   <li>挖掘方块次数</li>
 *   <li>交互（点击）次数</li>
 * </ol>
 */
public class GlobalAnomalyDetector implements Listener {

    private final AdvancedAntiCheat plugin;

    // 每个玩家的窗口累加器
    private final Map<UUID, FeatureAccumulator> accumulators = new ConcurrentHashMap<>();

    private static final int FEATURE_DIM = 6;
    private static final int TREE_COUNT = 100;          // 孤立森林树数量
    private static final int MAX_SAMPLE = 256;          // 单棵树最大采样数
    private static final int MAX_DEPTH = 8;
    private static final double ANOMALY_THRESHOLD = 0.62; // 异常分数阈值（越接近 1 越异常）
    private static final int MIN_PLAYERS_FOR_ANALYSIS = 5;

    public GlobalAnomalyDetector(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        // behavior.global-anomaly.enabled 此前只声明不生效：模块无条件注册事件并启动分析任务。
        if (!plugin.getConfig().getBoolean("behavior.global-anomaly.enabled", true)) {
            plugin.getLogger().info("[Behavior] behavior.global-anomaly.enabled=false，跳过全局异常检测模块");
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startAnalysisTask();
    }

    private void startAnalysisTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                analyze();
            }
        }.runTaskTimerAsynchronously(plugin, 20L * 60, 20L * 60);
    }

    // ---------------- 特征采集 ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getFrom().getWorld() == null) return;
        double dx = event.getTo().getX() - event.getFrom().getX();
        double dz = event.getTo().getZ() - event.getFrom().getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        FeatureAccumulator acc = accumulators.computeIfAbsent(event.getPlayer().getUniqueId(), k -> new FeatureAccumulator());
        acc.moveSpeedSum += horizontal;
        acc.moveSpeedSqSum += horizontal * horizontal;
        acc.moveSamples++;
        // 跳跃：从落地到离地的向上位移
        boolean wasOnGround = event.getFrom().getBlock().getType().isSolid();
        boolean nowOnGround = event.getTo().getBlock().getType().isSolid();
        if (wasOnGround && !nowOnGround && event.getTo().getY() > event.getFrom().getY()) {
            acc.jumpCount++;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            accumulators.computeIfAbsent(event.getDamager().getUniqueId(), k -> new FeatureAccumulator()).attackCount++;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        accumulators.computeIfAbsent(event.getPlayer().getUniqueId(), k -> new FeatureAccumulator()).breakCount++;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        accumulators.computeIfAbsent(event.getPlayer().getUniqueId(), k -> new FeatureAccumulator()).interactCount++;
    }

    // ---------------- 孤立森林分析 ----------------

    private void analyze() {
        List<Player> players = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOnline() && !p.hasPermission("anticheat.bypass")) {
                players.add(p);
            }
        }
        if (players.size() < MIN_PLAYERS_FOR_ANALYSIS) return;

        // 构建特征矩阵并重置累加器
        List<UUID> uuids = new ArrayList<>();
        List<double[]> matrix = new ArrayList<>();
        for (Player p : players) {
            FeatureAccumulator acc = accumulators.remove(p.getUniqueId());
            if (acc == null) {
                uuids.add(p.getUniqueId());
                matrix.add(new double[FEATURE_DIM]);
            } else {
                uuids.add(p.getUniqueId());
                matrix.add(acc.toVector());
            }
        }

        double[][] data = matrix.toArray(new double[0][]);
        double[] scores = isolationForestScores(data);

        for (int i = 0; i < uuids.size(); i++) {
            double score = scores[i];
            if (score >= ANOMALY_THRESHOLD) {
                Player p = Bukkit.getPlayer(uuids.get(i));
                if (p == null || !p.isOnline()) continue;
                plugin.getDetectionManager().getViolationManager().recordViolation(
                    p,
                    ViolationRecord.ViolationType.GLOBAL_ANOMALY,
                    String.format("全局行为离群（孤立森林异常分=%.3f，偏离全服人群常态）", score),
                    Math.min(0.9, 0.5 + score * 0.4)
                );
            }
        }
    }

    /**
     * 孤立森林异常分数。返回 [0,1]，越接近 1 越异常。
     */
    private double[] isolationForestScores(double[][] data) {
        int n = data.length;
        double[] scores = new double[n];
        if (n < 2) return scores;

        // 构建 TREE_COUNT 棵随机树
        List<ITree> forest = new ArrayList<>(TREE_COUNT);
        Random rnd = new Random(System.nanoTime());
        int sampleSize = Math.min(MAX_SAMPLE, n);
        for (int t = 0; t < TREE_COUNT; t++) {
            int[] indices = sampleIndices(n, sampleSize, rnd);
            forest.add(buildTree(data, indices, 0, MAX_DEPTH, rnd));
        }

        double c = avgPathLength(n); // 归一化常数
        for (int i = 0; i < n; i++) {
            double avgDepth = 0;
            for (ITree tree : forest) {
                avgDepth += pathLength(data[i], tree, 0);
            }
            avgDepth /= TREE_COUNT;
            scores[i] = Math.pow(2.0, -avgDepth / c);
        }
        return scores;
    }

    private int[] sampleIndices(int n, int sampleSize, Random rnd) {
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        for (int i = n - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int tmp = idx[i]; idx[i] = idx[j]; idx[j] = tmp;
        }
        return Arrays.copyOf(idx, Math.min(sampleSize, n));
    }

    private ITree buildTree(double[][] data, int[] indices, int depth, int maxDepth, Random rnd) {
        ITree node = new ITree();
        node.size = indices.length;
        if (depth >= maxDepth || indices.length <= 1) {
            node.leaf = true;
            return node;
        }

        // 随机选特征
        int feature = rnd.nextInt(FEATURE_DIM);
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int idx : indices) {
            double v = data[idx][feature];
            if (v < min) min = v;
            if (v > max) max = v;
        }
        if (max - min < 1e-9) {
            node.leaf = true;
            return node;
        }
        double split = min + rnd.nextDouble() * (max - min);

        List<Integer> left = new ArrayList<>();
        List<Integer> right = new ArrayList<>();
        for (int idx : indices) {
            if (data[idx][feature] < split) left.add(idx);
            else right.add(idx);
        }
        if (left.isEmpty() || right.isEmpty()) {
            node.leaf = true;
            return node;
        }

        node.splitFeature = feature;
        node.splitValue = split;
        node.left = buildTree(data, toArray(left), depth + 1, maxDepth, rnd);
        node.right = buildTree(data, toArray(right), depth + 1, maxDepth, rnd);
        return node;
    }

    private double pathLength(double[] sample, ITree node, int depth) {
        if (node.leaf) {
            if (node.size <= 1) return depth;
            return depth + avgPathLength(node.size) - 1.0;
        }
        if (sample[node.splitFeature] < node.splitValue) {
            return pathLength(sample, node.left, depth + 1);
        } else {
            return pathLength(sample, node.right, depth + 1);
        }
    }

    /** 平均路径长度 c(n)：孤立森林的归一化因子。 */
    private double avgPathLength(int n) {
        if (n <= 1) return 0;
        return 2.0 * (Math.log(n - 1) + 0.5772156649) - 2.0 * (n - 1) / n;
    }

    private int[] toArray(List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    public void clearPlayerData(UUID uuid) {
        accumulators.remove(uuid);
    }

    private static class FeatureAccumulator {
        double moveSpeedSum = 0;
        double moveSpeedSqSum = 0;
        int moveSamples = 0;
        int jumpCount = 0;
        int attackCount = 0;
        int breakCount = 0;
        int interactCount = 0;

        double[] toVector() {
            double avg = moveSamples > 0 ? moveSpeedSum / moveSamples : 0;
            double variance = moveSamples > 1
                ? (moveSpeedSqSum - (moveSpeedSum * moveSpeedSum) / moveSamples) / (moveSamples - 1)
                : 0;
            return new double[]{ avg, Math.sqrt(Math.max(0, variance)), jumpCount, attackCount, breakCount, interactCount };
        }
    }

    private static class ITree {
        int splitFeature;
        double splitValue;
        ITree left, right;
        int size;
        boolean leaf;
    }
}
