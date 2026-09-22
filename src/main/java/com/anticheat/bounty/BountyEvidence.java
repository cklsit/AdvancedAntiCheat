package com.anticheat.bounty;

import org.bukkit.plugin.Plugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 证据包：把一次任务的"可复核材料"落成文件（文档第四节 3）。
 *
 * <p>产出三样东西，都在同一个目录里：</p>
 * <ul>
 *   <li>{@code events.log}：事件时间线（进任务 / 目标进度 / 检测命中 / 判定依据）；</li>
 *   <li>{@code samples.csv}：逐采样状态（seq,x,y,z,yaw,pitch,onGround,attacked）——
 *       管理员可以直接丢进表格工具画曲线；</li>
 *   <li>{@code summary.txt}：指标实测值与人类基线的对比、以及判定结论。</li>
 * </ul>
 *
 * <h3>为什么必须是有界的</h3>
 * 沙箱对"任何玩家"开放，而证据是按采样逐行写的。不设上限就等于给所有玩家提供了
 * 一个把服务器磁盘写满的入口——单次任务写几百 MB 是完全做得到的。
 * 因此事件与采样各设行数上限，超出后**丢弃并计数**（而不是静默丢弃：
 * 摘要里会写明丢了多少行，管理员据此判断证据是否完整）。
 */
public class BountyEvidence {

    /** 事件行上限。 */
    public static final int MAX_EVENT_LINES = 2000;

    /** 采样行上限（30 分钟 @ 20tps 全采样约 36000 行，这里刻意只留够复核的量）。 */
    public static final int MAX_SAMPLE_LINES = 12000;

    private final List<String> events = new ArrayList<>();
    private final List<String> samples = new ArrayList<>();

    private int droppedEvents;
    private int droppedSamples;

    /** 记一条事件。 */
    public void event(String line) {
        if (events.size() >= MAX_EVENT_LINES) {
            droppedEvents++;
            return;
        }
        events.add(line);
    }

    /** 记一行采样（CSV，不含表头）。 */
    public void sample(long seq, double x, double y, double z,
                       float yaw, float pitch, boolean onGround, boolean attacked) {
        if (samples.size() >= MAX_SAMPLE_LINES) {
            droppedSamples++;
            return;
        }
        StringBuilder sb = new StringBuilder(64);
        sb.append(seq).append(',')
                .append(fmt(x)).append(',').append(fmt(y)).append(',').append(fmt(z)).append(',')
                .append(fmt(yaw)).append(',').append(fmt(pitch)).append(',')
                .append(onGround ? '1' : '0').append(',')
                .append(attacked ? '1' : '0');
        samples.add(sb.toString());
    }

    public int eventCount() {
        return events.size();
    }

    public int sampleCount() {
        return samples.size();
    }

    public int droppedEvents() {
        return droppedEvents;
    }

    public int droppedSamples() {
        return droppedSamples;
    }

    /**
     * 落盘。
     *
     * @return 相对插件数据目录的路径（写进 `bounty_case.evidence_path`）；
     *   写入失败时返回 null（调用方据此在案例里记 NULL，而不是记一个不存在的路径）
     */
    public String write(Plugin plugin, String playerName, String taskId, long stampMillis) {
        String folderName = sanitize(playerName) + "-" + taskId + "-" + stampMillis;
        File dir = new File(new File(plugin.getDataFolder(), "bounty-evidence"), folderName);
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("[Bounty] 无法创建证据目录: " + dir.getAbsolutePath());
            return null;
        }

        try {
            writeLines(new File(dir, "events.log"), events);
            writeLines(new File(dir, "samples.csv"), withHeader());
        } catch (IOException e) {
            plugin.getLogger().warning("[Bounty] 写证据文件失败: " + e.getMessage());
            return null;
        }
        return "bounty-evidence/" + folderName;
    }

    /** 把摘要单独写成 summary.txt（判定完成后才拿得到，所以与上面分开）。 */
    public boolean writeSummary(Plugin plugin, String relativeDir, String summary) {
        if (relativeDir == null) return false;
        File dir = new File(plugin.getDataFolder(), relativeDir);
        try {
            writeLines(new File(dir, "summary.txt"), splitLines(summary));
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("[Bounty] 写摘要失败: " + e.getMessage());
            return false;
        }
    }

    /** 采样的 CSV 表头（单独一行，保证 admin 用表格工具时列名正确）。 */
    private List<String> withHeader() {
        List<String> out = new ArrayList<>(samples.size() + 1);
        out.add("# seq,x,y,z,yaw,pitch,onGround,attacked");
        out.addAll(samples);
        return out;
    }

    /**
     * 指标 + 基线 + 判定的摘要文本。
     *
     * @param metrics 实测指标（键 → 值，NaN 表示样本不足）
     */
    public static String buildSummary(String playerName, String taskName, String verdictText,
                                      String reason, Map<String, Double> metrics,
                                      Map<String, String> baselineText, int samples,
                                      double anomalyScore, boolean baselineReady) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("玩家: ").append(playerName).append('\n');
        sb.append("任务: ").append(taskName).append('\n');
        sb.append("结论: ").append(verdictText).append('\n');
        sb.append("依据: ").append(reason).append('\n');
        sb.append("采样数: ").append(samples).append('\n');
        sb.append("异常分: ").append(String.format(Locale.ROOT, "%.2f", anomalyScore))
                .append("（人类基线").append(baselineReady ? "已就绪" : "未就绪").append("）").append('\n');
        sb.append('\n').append("== 指标对比 ==").append('\n');
        for (Map.Entry<String, Double> entry : metrics.entrySet()) {
            String key = entry.getKey();
            double value = entry.getValue();
            sb.append(String.format(Locale.ROOT, "%-20s %s", key,
                    Double.isNaN(value) ? "样本不足" : String.format(Locale.ROOT, "%.5f", value)));
            String baseline = baselineText.get(key);
            if (baseline != null) {
                sb.append("   人类基线 ").append(baseline);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 内部

    private static void writeLines(File file, List<String> lines) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8))) {
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
        }
    }

    private static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            out.add(line);
        }
        return out;
    }

    /** 文件名里只保留字母数字与中划线，避免玩家名里的怪字符拼出奇怪路径。 */
    private static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) return "unknown";
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-') {
                sb.append(c);
            }
        }
        return sb.length() == 0 ? "unknown" : sb.toString();
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    /** 当天日期串（目录名与日志用）。 */
    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }
}
