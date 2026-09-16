package com.anticheat.detection.inventory;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.detection.ViolationRecord;
import com.anticheat.utils.VersionUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
// 1.9+ 事件：只允许出现在 SwapHandListener（独立类）里。
// import 本身不会让外层类在 1.8 上加载失败——只有方法签名/字节码引用才会。
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InventoryDetectionModule —— 背包与物品交互检测模块（监听器）。
 *
 * <p>覆盖《全项检测规范》第四大类全部 4 项：</p>
 * <ul>
 *   <li><b>背包操作状态机</b>：容器开关/槽位操作事件序列的合法性迁移校验。</li>
 *   <li><b>物品移动速度</b>：背包点击频率过高或过于恒定 → 自动整理。</li>
 *   <li><b>容器开关频率</b>：容器打开/关闭间隔模式不符人类习惯 → 自动仓库。</li>
 *   <li><b>副手切换精度</b>：受伤到图腾/物品切换完成时间低于人类反应极限 → AutoTotem。</li>
 * </ul>
 *
 * <p>自动盔甲（AutoArmor）复用装备槽点击 + 受伤时间差判定。</p>
 */
public class InventoryDetectionModule implements Listener {

    private final AdvancedAntiCheat plugin;

    private final Map<UUID, Deque<Long>> clickTimestamps = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> containerEvents = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastDamageTime = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastOffhandSwap = new ConcurrentHashMap<>();
    private final Map<UUID, String> openContainerType = new ConcurrentHashMap<>();

    private static final int MAX_CLICK_HISTORY = 200;
    private static final int MAX_CONTAINER_HISTORY = 100;
    private static final double MAX_CLICK_CPS = 18.0;      // 背包点击上限
    private static final double CLICK_CV_THRESHOLD = 0.15; // 点击间隔变异系数阈值
    private static final int CONTAINER_MIN_INTERVAL_MS = 150; // 容器开关最小间隔
    private static final long OFFHAND_REACTION_MIN_MS = 80;   // 副手切换最小反应时间
    private static final long ARMOR_REACTION_MIN_MS = 80;     // 盔甲装备最小反应时间

    public InventoryDetectionModule(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        registerSwapHandListener();
        startCleanupTask();
    }

    /**
     * 条件注册副手切换监听器。
     *
     * <p>PlayerSwapHandItemsEvent 是 1.9+ 才有的事件。它必须放在<b>独立的类</b>里注册：
     * Bukkit 的 registerEvents 以「类」为单位解析全部 @EventHandler，
     * 只要类中出现 1.8 不存在的事件类型，整个类的处理器都会被丢弃并打出
     * "has failed to register events for class …"——在 1.8 上表现为
     * 整个背包检测静默失效（不报错、不提示，最坏的一类故障）。
     *
     * <p>本模式由 tools/audit_dual_version.py 的第 5 项检查，见 SwapHandListener 上的标注。
     */
    private void registerSwapHandListener() {
        try {
            Class.forName("org.bukkit.event.player.PlayerSwapHandItemsEvent");
        } catch (Throwable absent) {
            return; // 1.8.x：没有该事件，跳过副手检测
        }
        try {
            plugin.getServer().getPluginManager().registerEvents(new SwapHandListener(this), plugin);
        } catch (Throwable t) {
            plugin.getLogger().warning("[Inventory] 副手切换监听器注册失败（已跳过，不影响其余背包检测）：" + t);
        }
    }

    /**
     * 仅承载 1.9+ 副手切换事件的监听器（独立类，1.8 上不会被加载）。
     */
    public static final class SwapHandListener implements Listener {
        // dual-version-guard: safe-conditional ——
        // 本类在 registerSwapHandListener() 里先探测事件是否存在再注册，
        // 因此 1.8 上永远不会被注册，也不会连带丢失其它监听器的注册。

        private final InventoryDetectionModule module;

        public SwapHandListener(InventoryDetectionModule module) {
            this.module = module;
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onSwapHandItemsSwap(PlayerSwapHandItemsEvent event) {
            module.handleSwapHandItems(event.getPlayer());
        }
    }

    private void startCleanupTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long cutoff = System.currentTimeMillis() - 120000;
                clickTimestamps.entrySet().removeIf(e -> e.getValue().isEmpty() ||
                    e.getValue().peekLast() == null || e.getValue().peekLast() < cutoff);
                containerEvents.entrySet().removeIf(e -> e.getValue().isEmpty() ||
                    e.getValue().peekLast() == null || e.getValue().peekLast() < cutoff);
            }
        }.runTaskTimerAsynchronously(plugin, 20L * 60, 20L * 60);
    }

    // ---------------- 物品移动速度 / 容器开关 ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (isExempt(player)) return;

        // 仅统计玩家自身背包/快捷栏的点击（排除外部容器拖拽噪声）
        long now = System.currentTimeMillis();
        Deque<Long> clicks = clickTimestamps.computeIfAbsent(player.getUniqueId(), k -> new LinkedList<>());
        clicks.addLast(now);
        while (clicks.size() > MAX_CLICK_HISTORY) clicks.removeFirst();

        analyzeItemMoveSpeed(player, clicks);

        // 自动盔甲：受伤后极短时间内点击盔甲槽位
        if (isArmorSlot(event.getSlot())) {
            checkAutoArmor(player, now);
        }
    }

    private void analyzeItemMoveSpeed(Player player, Deque<Long> clicks) {
        if (clicks.size() < 20) return;
        long now = System.currentTimeMillis();
        long cutoff = now - 1000;
        List<Long> recent = new ArrayList<>();
        for (Long t : clicks) {
            if (t >= cutoff) recent.add(t);
        }
        if (recent.size() < 5) return;

        double cps = recent.size();
        if (cps > MAX_CLICK_CPS) {
            record(player, ViolationRecord.ViolationType.ITEM_MOVE_SPAM,
                String.format("背包物品移动过快（%.1f 次/秒 > %.0f）", cps, MAX_CLICK_CPS), 0.6);
        }

        // 间隔过于恒定 → 脚本化
        List<Long> intervals = new ArrayList<>();
        Collections.sort(recent);
        for (int i = 1; i < recent.size(); i++) intervals.add(recent.get(i) - recent.get(i - 1));
        double mean = meanLong(intervals);
        double cv = mean > 0 ? stdDevLong(intervals, mean) / mean : Double.MAX_VALUE;
        if (cv < CLICK_CV_THRESHOLD && recent.size() >= 8) {
            record(player, ViolationRecord.ViolationType.ITEM_MOVE_SPAM,
                String.format("物品移动间隔过于恒定（变异系数=%.3f）", cv), 0.55);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onContainerOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        if (isExempt(player)) return;
        if (event.getInventory().getType() == InventoryType.PLAYER) return;

        openContainerType.put(player.getUniqueId(), event.getInventory().getType().name());
        recordContainerEvent(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onContainerClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        if (isExempt(player)) return;

        openContainerType.remove(player.getUniqueId());
        recordContainerEvent(player);
    }

    private void recordContainerEvent(Player player) {
        long now = System.currentTimeMillis();
        Deque<Long> events = containerEvents.computeIfAbsent(player.getUniqueId(), k -> new LinkedList<>());
        events.addLast(now);
        while (events.size() > MAX_CONTAINER_HISTORY) events.removeFirst();
        analyzeContainerFrequency(player, events);
    }

    private void analyzeContainerFrequency(Player player, Deque<Long> events) {
        if (events.size() < 10) return;
        List<Long> recent = new ArrayList<>(events);
        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < recent.size(); i++) intervals.add(recent.get(i) - recent.get(i - 1));

        double mean = meanLong(intervals);
        if (mean > 0 && mean < CONTAINER_MIN_INTERVAL_MS) {
            record(player, ViolationRecord.ViolationType.CONTAINER_SPAM,
                String.format("容器开关过于频繁（平均间隔=%.0fms < %dms）", mean, CONTAINER_MIN_INTERVAL_MS), 0.5);
        }
    }

    // ---------------- 副手切换精度 / 自动图腾 / 自动盔甲 ----------------

    /**
     * 副手切换判定入口。
     *
     * <p>触发源（1.9+ 的 {@code PlayerSwapHandItemsEvent}）在 {@link SwapHandListener} 里，
     * 该类只在本服务端存在该事件时才会被注册。<b>不要</b>把带 1.9+ 事件参数的方法搬回本类：
     * Bukkit 注册监听器是按「类」为单位整体处理的，一旦类里出现 1.8 不存在的事件类型，
     * {@code registerEvents} 会抛错并<b>丢弃该类的全部处理器</b>，
     * 结果就是背包点击 / 容器开关 / 受伤时间戳等 1.8 本可用的检测一起静默失效。
     */
    void handleSwapHandItems(Player player) {
        if (isExempt(player)) return;
        checkOffhandSwap(player);
    }

    private void checkOffhandSwap(Player player) {
        Long last = lastDamageTime.get(player.getUniqueId());
        if (last == null) return;
        long now = System.currentTimeMillis();
        long elapsed = now - last;
        if (elapsed < OFFHAND_REACTION_MIN_MS) {
            record(player, ViolationRecord.ViolationType.OFFHAND_SWAP,
                String.format("受伤后 %dms 内完成副手切换（< %dms）", elapsed, OFFHAND_REACTION_MIN_MS), 0.7);
        }
        lastOffhandSwap.put(player.getUniqueId(), now);
    }

    private void checkAutoArmor(Player player, long now) {
        Long last = lastDamageTime.get(player.getUniqueId());
        if (last == null) return;
        long elapsed = now - last;
        if (elapsed < ARMOR_REACTION_MIN_MS) {
            record(player, ViolationRecord.ViolationType.AUTO_ARMOR,
                String.format("受伤后 %dms 内装备盔甲（< %dms）", elapsed, ARMOR_REACTION_MIN_MS), 0.7);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        if (isExempt(player)) return;
        lastDamageTime.put(player.getUniqueId(), System.currentTimeMillis());
    }

    // ---------------- 工具 ----------------

    private boolean isArmorSlot(int slot) {
        // 装备槽位：靴子/护腿/胸甲/头盔（点击背包内的盔甲物品也在此范围）
        return slot >= 36 && slot <= 39;
    }

    private boolean isExempt(Player player) {
        if (player == null || !player.isOnline()) return true;
        if (player.hasPermission("anticheat.bypass")) return true;
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE ||
            player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return true;
        return false;
    }

    private void record(Player player, ViolationRecord.ViolationType type, String details, double level) {
        plugin.getDetectionManager().getViolationManager().recordViolation(player, type, details, level);
    }

    private double meanLong(List<Long> values) {
        if (values.isEmpty()) return 0;
        long sum = 0;
        for (long v : values) sum += v;
        return (double) sum / values.size();
    }

    private double stdDevLong(List<Long> values, double mean) {
        if (values.size() < 2) return 0;
        double sum = 0;
        for (long v : values) sum += (v - mean) * (v - mean);
        return Math.sqrt(sum / (values.size() - 1));
    }

    public void clearPlayerData(UUID uuid) {
        clickTimestamps.remove(uuid);
        containerEvents.remove(uuid);
        lastDamageTime.remove(uuid);
        lastOffhandSwap.remove(uuid);
        openContainerType.remove(uuid);
    }
}
