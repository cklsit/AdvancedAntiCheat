package com.anticheat.ai.feedback;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 标签存储：内存 CopyOnWriteArrayList + JSON 文件异步持久化。
 * <p>
 * 支持按 label / source / 玩家名 / 时间段筛选与分页；
 * 样本上限（默认 20000，超限淘汰最旧）防止无限膨胀。
 */
public class FeedbackStore {

    private static final Type LIST_TYPE = new TypeToken<List<LabeledSample>>() {
    }.getType();

    private final Path file;
    private final CopyOnWriteArrayList<LabeledSample> samples = new CopyOnWriteArrayList<>();
    private final AtomicLong idGen = new AtomicLong(1);
    private final int maxSamples;
    private volatile boolean dirty;
    /** 距上次训练新增的标签数（触发自动训练）。 */
    private final java.util.concurrent.atomic.AtomicLong newSinceTrain = new AtomicLong();

    public FeedbackStore(Path file, int maxSamples) {
        this.file = file;
        this.maxSamples = Math.max(1000, maxSamples);
    }

    /** 启动时加载（同步，数据量小可接受）。 */
    public void load() {
        if (!Files.isRegularFile(file)) return;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Gson gson = new Gson();
            List<LabeledSample> loaded = gson.fromJson(r, LIST_TYPE);
            if (loaded != null) {
                samples.addAll(loaded);
                long max = loaded.stream().mapToLong(s -> s.id).max().orElse(0);
                idGen.set(max + 1);
            }
        } catch (Exception ignored) {
        }
    }

    /** 新增样本并异步落盘。 */
    public void add(LabeledSample sample) {
        sample.id = idGen.getAndIncrement();
        samples.add(sample);
        trimIfNeeded();
        dirty = true;
        newSinceTrain.incrementAndGet();
        saveAsync();
    }

    /** 修正标签（管理员批量修正）。 */
    public boolean correct(long id, int newLabel, String note) {
        for (LabeledSample s : samples) {
            if (s.id == id) {
                s.label = newLabel;
                if (note != null && !note.isEmpty()) s.note = note;
                dirty = true;
                saveAsync();
                return true;
            }
        }
        return false;
    }

    public boolean delete(long id) {
        boolean removed = samples.removeIf(s -> s.id == id);
        if (removed) {
            dirty = true;
            saveAsync();
        }
        return removed;
    }

    // ================= 查询 =================

    public List<LabeledSample> query(int labelFilter, String sourceFilter, String nameFilter,
                                     long startTime, long endTime, int page, int pageSize) {
        return samples.stream()
                .filter(s -> labelFilter < 0 || s.label == labelFilter)
                .filter(s -> sourceFilter == null || sourceFilter.isEmpty() || sourceFilter.equals(s.source))
                .filter(s -> nameFilter == null || nameFilter.isEmpty()
                        || s.playerName.toLowerCase().contains(nameFilter.toLowerCase()))
                .filter(s -> startTime <= 0 || s.timestamp >= startTime)
                .filter(s -> endTime <= 0 || s.timestamp <= endTime)
                .sorted(Comparator.comparingLong((LabeledSample s) -> s.timestamp).reversed())
                .skip((long) Math.max(0, page) * Math.max(1, pageSize))
                .limit(Math.max(1, pageSize))
                .collect(Collectors.toList());
    }

    public int countTotal() {
        return samples.size();
    }

    public int countByLabel(int label) {
        return (int) samples.stream().filter(s -> s.label == label).count();
    }

    public long getNewSinceTrain() {
        return newSinceTrain.get();
    }

    public void resetNewSinceTrain() {
        newSinceTrain.set(0);
    }

    /** 训练用：全部带特征的样本。 */
    public List<LabeledSample> allWithFeatures() {
        return samples.stream().filter(s -> s.features != null && s.features.length > 0)
                .collect(Collectors.toList());
    }

    // ================= 持久化 =================

    public void saveAsync() {
        // 简单合并写：脏标记 + 同步兜底（数据量小，直接同步写也可接受）
        save();
    }

    public synchronized void save() {
        if (!dirty) return;
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                gson.toJson(new ArrayList<>(samples), w);
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (Exception ignored) {
        }
    }

    private void trimIfNeeded() {
        while (samples.size() > maxSamples) {
            LabeledSample oldest = null;
            for (LabeledSample s : samples) {
                if (oldest == null || s.timestamp < oldest.timestamp) oldest = s;
            }
            if (oldest == null) break;
            samples.remove(oldest);
        }
    }
}
