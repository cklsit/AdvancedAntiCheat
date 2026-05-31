package com.anticheat.managers;

import com.anticheat.AdvancedAntiCheat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class PerformanceMonitor {

    private final AdvancedAntiCheat plugin;
    private final AdvancedDetectionManager detectionManager;

    private final Deque<Double> tpsHistory;
    private final Deque<Double> cpuHistory;
    private final Deque<Long> memoryHistory;

    private final AtomicReference<Double> currentTPS;
    private final AtomicReference<Double> currentCPU;
    private final AtomicLong currentMemoryUsage;

    private volatile boolean degradedModeEnabled = false;
    private volatile boolean monitoring = true;

    private BukkitTask monitoringTask;
    private BukkitTask adjustmentTask;

    private static final int HISTORY_SIZE = 60;
    private static final double TPS_THRESHOLD_NORMAL = 19.0;
    private static final double TPS_THRESHOLD_WARNING = 17.0;
    private static final double TPS_THRESHOLD_CRITICAL = 15.0;
    private static final double CPU_THRESHOLD_WARNING = 80.0;
    private static final double CPU_THRESHOLD_CRITICAL = 95.0;
    private static final long MEMORY_THRESHOLD_WARNING = 80;
    private static final long MEMORY_THRESHOLD_CRITICAL = 95;
    private static final long MONITORING_INTERVAL_MS = 1000;
    private static final long ADJUSTMENT_INTERVAL_MS = 5000;

    private static final double[] TPS_LEVELS = {20.0, 19.5, 19.0, 18.0, 17.0, 15.0};
    private static final int[] PROCESSING_FREQUENCIES = {100, 100, 150, 200, 300, 500};

    private int currentFrequencyLevel = 2;
    private int previousFrequencyLevel = 2;

    private final OperatingSystemMXBean osBean;

    public PerformanceMonitor(AdvancedAntiCheat plugin, AdvancedDetectionManager detectionManager) {
        this.plugin = plugin;
        this.detectionManager = detectionManager;
        this.tpsHistory = new LinkedList<>();
        this.cpuHistory = new LinkedList<>();
        this.memoryHistory = new LinkedList<>();
        this.currentTPS = new AtomicReference<>(20.0);
        this.currentCPU = new AtomicReference<>(0.0);
        this.currentMemoryUsage = new AtomicLong(0);
        this.osBean = ManagementFactory.getOperatingSystemMXBean();

        startMonitoring();
    }

    private void startMonitoring() {
        monitoringTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!monitoring) return;
                performMonitoring();
            }
        }.runTaskTimerAsynchronously(plugin, 20L, MONITORING_INTERVAL_MS / 50);

        adjustmentTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!monitoring) return;
                performAdjustment();
            }
        }.runTaskTimer(plugin, 20L, ADJUSTMENT_INTERVAL_MS / 50);
    }

    private void performMonitoring() {
        try {
            double tps = getServerTPS();
            currentTPS.set(tps);

            synchronized (tpsHistory) {
                tpsHistory.addLast(tps);
                if (tpsHistory.size() > HISTORY_SIZE) {
                    tpsHistory.removeFirst();
                }
            }

            double cpu = getSystemCPUUsage();
            currentCPU.set(cpu);

            synchronized (cpuHistory) {
                cpuHistory.addLast(cpu);
                if (cpuHistory.size() > HISTORY_SIZE) {
                    cpuHistory.removeFirst();
                }
            }

            long memory = getMemoryUsage();
            currentMemoryUsage.set(memory);

            synchronized (memoryHistory) {
                memoryHistory.addLast(memory);
                if (memoryHistory.size() > HISTORY_SIZE) {
                    memoryHistory.removeFirst();
                }
            }

            checkThresholds();

        } catch (Exception e) {
            plugin.getLogger().warning("[PerformanceMonitor] 监控异常: " + e.getMessage());
        }
    }

    private void checkThresholds() {
        double tps = currentTPS.get();
        double cpu = currentCPU.get();
        long memory = currentMemoryUsage.get();

        if (tps < TPS_THRESHOLD_CRITICAL || cpu > CPU_THRESHOLD_CRITICAL || memory > MEMORY_THRESHOLD_CRITICAL) {
            if (!degradedModeEnabled) {
                enableDegradedMode();
            }
        } else if (tps > TPS_THRESHOLD_WARNING && cpu < CPU_THRESHOLD_WARNING && memory < MEMORY_THRESHOLD_WARNING) {
            if (degradedModeEnabled) {
                disableDegradedMode();
            }
        }
    }

    private void performAdjustment() {
        double tps = currentTPS.get();

        int newLevel = calculateFrequencyLevel(tps);

        if (newLevel != currentFrequencyLevel) {
            previousFrequencyLevel = currentFrequencyLevel;
            currentFrequencyLevel = newLevel;

            adjustProcessingFrequency();

            plugin.getLogger().info("[PerformanceMonitor] TPS等级调整: " +
                previousFrequencyLevel + " -> " + currentFrequencyLevel +
                " (TPS: " + String.format("%.2f", tps) + ")");
        }
    }

    private int calculateFrequencyLevel(double tps) {
        for (int i = 0; i < TPS_LEVELS.length; i++) {
            if (tps >= TPS_LEVELS[i]) {
                return i;
            }
        }
        return TPS_LEVELS.length - 1;
    }

    private void adjustProcessingFrequency() {
        int frequency = PROCESSING_FREQUENCIES[currentFrequencyLevel];

        plugin.getLogger().info("[PerformanceMonitor] 处理频率调整: " + frequency + "ms");
    }

    public boolean isTPSHealthy() {
        return currentTPS.get() >= TPS_THRESHOLD_NORMAL;
    }

    public boolean isTPSWarning() {
        return currentTPS.get() < TPS_THRESHOLD_WARNING && currentTPS.get() >= TPS_THRESHOLD_CRITICAL;
    }

    public boolean isTPSCritical() {
        return currentTPS.get() < TPS_THRESHOLD_CRITICAL;
    }

    public boolean isCPUWarning() {
        return currentCPU.get() >= CPU_THRESHOLD_WARNING && currentCPU.get() < CPU_THRESHOLD_CRITICAL;
    }

    public boolean isCPUCritical() {
        return currentCPU.get() >= CPU_THRESHOLD_CRITICAL;
    }

    public boolean isMemoryWarning() {
        return currentMemoryUsage.get() >= MEMORY_THRESHOLD_WARNING && currentMemoryUsage.get() < MEMORY_THRESHOLD_CRITICAL;
    }

    public boolean isMemoryCritical() {
        return currentMemoryUsage.get() >= MEMORY_THRESHOLD_CRITICAL;
    }

    public void enableDegradedMode() {
        if (degradedModeEnabled) {
            return;
        }

        degradedModeEnabled = true;
        detectionManager.enableDegradedMode();

        plugin.getLogger().warning("[PerformanceMonitor] 启用降级模式 - 服务器性能下降");

        notifyAdministrators("服务器性能下降，自动启用降级模式以减少负载");
    }

    public void disableDegradedMode() {
        if (!degradedModeEnabled) {
            return;
        }

        degradedModeEnabled = false;
        detectionManager.disableDegradedMode();

        plugin.getLogger().info("[PerformanceMonitor] 禁用降级模式 - 服务器性能恢复正常");

        notifyAdministrators("服务器性能恢复，禁用降级模式");
    }

    private void notifyAdministrators(String message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("anticheat.admin")) {
                    player.sendMessage("§c[AntiCheat-Performance] §f" + message);
                }
            }
        });
    }

    private double getServerTPS() {
        try {
            Object server = Bukkit.getServer();
            java.lang.reflect.Method getTPSMethod = server.getClass().getMethod("getTPS");
            Object result = getTPSMethod.invoke(server);

            if (result instanceof double[]) {
                return ((double[]) result)[0];
            } else if (result instanceof Double[]) {
                return ((Double[]) result)[0];
            }

            return 20.0;
        } catch (Exception e) {
            return 20.0;
        }
    }

    private double getSystemCPUUsage() {
        try {
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOsBean =
                    (com.sun.management.OperatingSystemMXBean) osBean;
                return sunOsBean.getSystemCpuLoad() * 100;
            }
            return 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private long getMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        return (usedMemory * 100) / totalMemory;
    }

    public double getCurrentTPS() {
        return currentTPS.get();
    }

    public double getCurrentCPU() {
        return currentCPU.get();
    }

    public long getCurrentMemoryUsage() {
        return currentMemoryUsage.get();
    }

    public double getAverageTPS() {
        synchronized (tpsHistory) {
            if (tpsHistory.isEmpty()) {
                return 20.0;
            }
            double sum = 0;
            for (Double tps : tpsHistory) {
                sum += tps;
            }
            return sum / tpsHistory.size();
        }
    }

    public double getAverageCPU() {
        synchronized (cpuHistory) {
            if (cpuHistory.isEmpty()) {
                return 0.0;
            }
            double sum = 0;
            for (Double cpu : cpuHistory) {
                sum += cpu;
            }
            return sum / cpuHistory.size();
        }
    }

    public long getAverageMemoryUsage() {
        synchronized (memoryHistory) {
            if (memoryHistory.isEmpty()) {
                return 0;
            }
            long sum = 0;
            for (Long memory : memoryHistory) {
                sum += memory;
            }
            return sum / memoryHistory.size();
        }
    }

    public double getMinTPS() {
        synchronized (tpsHistory) {
            return tpsHistory.stream().mapToDouble(Double::doubleValue).min().orElse(20.0);
        }
    }

    public double getMaxCPU() {
        synchronized (cpuHistory) {
            return cpuHistory.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        }
    }

    public PerformanceStatus getPerformanceStatus() {
        double tps = currentTPS.get();
        double cpu = currentCPU.get();
        long memory = currentMemoryUsage.get();

        if (tps < TPS_THRESHOLD_CRITICAL || cpu > CPU_THRESHOLD_CRITICAL || memory > MEMORY_THRESHOLD_CRITICAL) {
            return PerformanceStatus.CRITICAL;
        } else if (tps < TPS_THRESHOLD_WARNING || cpu > CPU_THRESHOLD_WARNING || memory > MEMORY_THRESHOLD_WARNING) {
            return PerformanceStatus.WARNING;
        } else {
            return PerformanceStatus.NORMAL;
        }
    }

    public boolean isDegradedModeEnabled() {
        return degradedModeEnabled;
    }

    public int getCurrentFrequencyLevel() {
        return currentFrequencyLevel;
    }

    public long getProcessingInterval() {
        return PROCESSING_FREQUENCIES[currentFrequencyLevel];
    }

    public void shutdown() {
        monitoring = false;

        if (monitoringTask != null) {
            monitoringTask.cancel();
        }
        if (adjustmentTask != null) {
            adjustmentTask.cancel();
        }
    }

    public enum PerformanceStatus {
        NORMAL,
        WARNING,
        CRITICAL
    }
}
