package com.anticheat.ai.adaptive;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自适应阈值调节器 —— PID 控制器思路的简化实现。
 * <p>
 * 目标：把每个检测模块的"管理员赦免率"（误报反馈占比）压到目标值以下。
 * <ul>
 *   <li>观测：滑动窗口内 (赦免数 / (赦免数 + 确认数))；</li>
 *   <li>误差：observedFPR − targetFPR；</li>
 *   <li>调节：sensitivity *= (1 − Kp·err − Ki·∫err)，即误报多 → 降灵敏度；
 *       积分项记忆历史偏差，实现持续修正（PID 中的 I 项）；</li>
 *   <li>限幅：sensitivity ∈ [0.5, 2.0]，避免震荡。</li>
 * </ul>
 * 调节周期默认 10 分钟（由 AILabManager 调度）；输出同时供
 * 融合评分权重缩放与管理面板历史曲线展示。
 */
public class AdaptiveThresholdController {

    /** 受控模块定义。 */
    public static final String[] MODULES = {
            "movement", "combat", "behavior", "mining", "inventory",
            "aiPersonal", "aiGlobal", "aiSupervised"
    };

    private static final double TARGET_FPR = 0.005;
    private static final double KP = 0.30;
    private static final double KI = 0.02;
    private static final double MIN_SENS = 0.5;
    private static final double MAX_SENS = 2.0;

    /** 每模块灵敏度乘子。 */
    private final Map<String, Double> sensitivity = new ConcurrentHashMap<>();
    /** 每模块反馈窗口。 */
    private final Map<String, Deque<Boolean>> feedbackWindow = new ConcurrentHashMap<>();
    /** 每模块积分项。 */
    private final Map<String, Double> integral = new ConcurrentHashMap<>();
    /** 历史曲线（时间戳, 模块, sensitivity），上限 1000。 */
    private final Deque<double[]> history = new ArrayDeque<>();
    /** 反馈窗口大小。 */
    private final int windowSize;

    public AdaptiveThresholdController(int windowSize) {
        this.windowSize = Math.max(10, windowSize);
        for (String m : MODULES) {
            sensitivity.put(m, 1.0);
            feedbackWindow.put(m, new ArrayDeque<>());
            integral.put(m, 0.0);
        }
    }

    /**
     * 记录一条人工判决反馈。
     *
     * @param module        模块名（未知模块忽略）
     * @param falsePositive true = 管理员标记误报/赦免
     */
    public void recordFeedback(String module, boolean falsePositive) {
        Deque<Boolean> q = feedbackWindow.get(module);
        if (q == null) return;
        synchronized (q) {
            q.addLast(falsePositive);
            while (q.size() > windowSize) q.pollFirst();
        }
    }

    /** 执行一轮调节（异步调度线程调用）。 */
    public void adjustAll() {
        long ts = System.currentTimeMillis();
        for (String m : MODULES) {
            Deque<Boolean> q = feedbackWindow.get(m);
            int total = 0, fp = 0;
            synchronized (q) {
                total = q.size();
                for (Boolean b : q) {
                    if (b) fp++;
                }
            }
            if (total < 5) continue; // 样本不足不调节
            double observed = fp / (double) total;
            double err = observed - TARGET_FPR;
            double integ = integral.merge(m, err, (a, b) -> a + b);
            integ = Math.max(-2.0, Math.min(2.0, integ));
            integral.put(m, integ);
            double delta = 1.0 - KP * err - KI * integ;
            double next = Math.max(MIN_SENS, Math.min(MAX_SENS, sensitivity.get(m) * delta));
            sensitivity.put(m, next);
            pushHistory(ts, m, next);
        }
    }

    /** 获取模块灵敏度（未知模块返回 1.0）。 */
    public double getSensitivity(String module) {
        return sensitivity.getOrDefault(module, 1.0);
    }

    /** 全模块灵敏度快照。 */
    public Map<String, Double> snapshot() {
        return new LinkedHashMap<>(sensitivity);
    }

    /** 历史曲线快照（Web 用）：[ts, sens] 按模块分组。 */
    public Map<String, List<double[]>> historySnapshot() {
        Map<String, List<double[]>> out = new LinkedHashMap<>();
        synchronized (history) {
            for (double[] e : history) {
                String m = MODULES[(int) e[1]];
                out.computeIfAbsent(m, k -> new ArrayList<>()).add(new double[]{e[0], e[2]});
            }
        }
        return out;
    }

    /** 管理员赦免某次处罚时由外部调用：关联模块（自动推断）。 */
    public void recordPardon(String relatedModule) {
        recordFeedback(relatedModule, true);
    }

    /** 管理员确认作弊时调用。 */
    public void recordConfirm(String relatedModule) {
        recordFeedback(relatedModule, false);
    }

    private void pushHistory(long ts, String module, double sens) {
        int idx = -1;
        for (int i = 0; i < MODULES.length; i++) {
            if (MODULES[i].equals(module)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return;
        synchronized (history) {
            history.addLast(new double[]{ts, idx, sens});
            while (history.size() > 1000) history.pollFirst();
        }
    }
}
