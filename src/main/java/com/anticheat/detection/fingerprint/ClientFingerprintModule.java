package com.anticheat.detection.fingerprint;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.detection.ViolationRecord;
import com.anticheat.integration.ProtocolIntegration;
import com.anticheat.utils.VersionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ClientFingerprintModule —— 客户端与环境指纹检测模块（监听器）。
 *
 * <p>覆盖《全项检测规范》第六大类全部 3 项：</p>
 * <ul>
 *   <li><b>渲染距离验证（矿透）</b>：在玩家渲染距离之外安放诱饵矿石，精准挖掘即证明透视。</li>
 *   <li><b>GUI 响应时间指纹</b>：周期性弹出自定义 GUI，记录点击/关闭响应延迟，与纯净基线比对。</li>
 *   <li><b>隐写特征标记</b>：向玩家发放携带唯一 ID 的特殊物品，追踪其跨账号流转以关联小号。</li>
 * </ul>
 */
public class ClientFingerprintModule implements Listener {

    private final AdvancedAntiCheat plugin;
    private final ProtocolIntegration protocolIntegration;

    // 诱饵矿石：位置 -> 原始材质（真实方块替换路径，无 ProtocolLib 时回退）
    private final Map<Location, Material> trapBlocks = new ConcurrentHashMap<>();
    // 协议级假方块：位置 -> 目标玩家 UUID（ProtocolLib 路径，仅客户端单侧渲染）
    private final Map<Location, UUID> fakeOres = new ConcurrentHashMap<>();
    // 隐写标记：标记ID -> 玩家UUID
    private final Map<String, UUID> markerOwners = new ConcurrentHashMap<>();
    // GUI 探针状态
    private final Map<UUID, Long> guiOpenTime = new ConcurrentHashMap<>();
    private final Map<UUID, List<Long>> guiLatencies = new ConcurrentHashMap<>();

    private static final int RENDER_DISTANCE_MIN = 48;        // 诱饵矿石最小距离
    private static final double GUI_MIN_LATENCY_MS = 80.0;    // GUI 响应下限（低于即疑似注入客户端）
    private static final double GUI_CV_THRESHOLD = 0.15;      // 响应延迟变异系数阈值

    public ClientFingerprintModule(AdvancedAntiCheat plugin, ProtocolIntegration protocolIntegration) {
        this.plugin = plugin;
        this.protocolIntegration = protocolIntegration;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startTrapTask();
        startAutoProbeTask();
    }

    // ---------------- 渲染距离验证（矿透） ----------------

    private void startTrapTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!plugin.getConfig().getBoolean("fingerprint.render-distance.enabled", true)) return;
                for (Player player : VersionUtil.safeGetOnlinePlayers()) {
                    if (player.hasPermission("anticheat.bypass")) continue;
                    placeTrapOre(player);
                }
            }
        }.runTaskTimer(plugin, 20L * 60, 20L * 60 * 15);
    }

    private void placeTrapOre(Player player) {
        Location origin = player.getLocation();
        // 在玩家渲染距离之外的随机水平方向埋设诱饵钻石矿
        double angle = Math.random() * 2 * Math.PI;
        int distance = RENDER_DISTANCE_MIN + (int) (Math.random() * 32);
        int dx = (int) (Math.cos(angle) * distance);
        int dz = (int) (Math.sin(angle) * distance);
        Location loc = origin.clone().add(dx, 0, dz);
        loc.setY(origin.getWorld().getHighestBlockYAt(loc) - 3); // 埋在地下三层
        if (loc.getY() < 1) return;

        Material fakeOre = VersionUtil.compatMaterial("DIAMOND_ORE", "DIAMOND_ORE", Material.STONE);
        Block block = loc.getBlock();
        Material original = block.getType();

        // 优先协议级假方块（仅该玩家客户端渲染，不改真实地形）；无 ProtocolLib 回退真实替换
        if (protocolIntegration != null && protocolIntegration.isAvailable()
                && original.isSolid() && !original.name().contains("ORE")) {
            protocolIntegration.sendFakeBlock(player, loc, fakeOre);
            fakeOres.put(loc, player.getUniqueId());
            return;
        }

        // 回退：只把"地下非矿"方块替换为诱饵，避免污染地表
        if (original.isSolid() && !original.name().contains("ORE")) {
            block.setType(fakeOre);
            trapBlocks.put(loc, original);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreakTrap(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        Location loc = event.getBlock().getLocation();

        // 协议级假方块：该位置若为某玩家的假矿，破坏即证明透视
        UUID fakeOwner = fakeOres.remove(loc);
        if (fakeOwner != null) {
            event.setCancelled(true);
            record(player, ViolationRecord.ViolationType.RENDER_DISTANCE,
                "挖掘了渲染距离之外的协议级诱饵矿石（矿透/透视）", 0.95);
            return;
        }

        Material original = trapBlocks.remove(loc);
        if (original != null) {
            event.setCancelled(true);
            event.getBlock().setType(original);
            record(player, ViolationRecord.ViolationType.RENDER_DISTANCE,
                "挖掘了渲染距离之外的诱饵矿石（矿透/透视）", 0.95);
        }
    }

    // ---------------- GUI 响应时间指纹 ----------------

    public void probeGui(Player player) {
        if (!plugin.getConfig().getBoolean("fingerprint.gui.enabled", true)) return;
        Inventory gui = Bukkit.createInventory(null, 9, "§7验证中…");
        player.openInventory(gui);
        guiOpenTime.put(player.getUniqueId(), System.currentTimeMillis());
    }

    /**
     * 低频自动探针：仅对「已有违规记录」的玩家周期性弹 GUI 测响应，
     * 避免打扰正常玩家。默认关闭，通过 fingerprint.gui.auto-probe 开启。
     */
    private void startAutoProbeTask() {
        final long[] lastProbe = {0L};
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!plugin.getConfig().getBoolean("fingerprint.gui.enabled", true)) return;
                if (!plugin.getConfig().getBoolean("fingerprint.gui.auto-probe", false)) return;
                int intervalMin = Math.max(1, plugin.getConfig().getInt("fingerprint.gui.auto-probe-interval-minutes", 5));
                long now = System.currentTimeMillis();
                if (now - lastProbe[0] < intervalMin * 60_000L) return;

                Player candidate = pickSuspiciousPlayer();
                if (candidate != null) {
                    lastProbe[0] = now;
                    probeGui(candidate);
                }
            }
        }.runTaskTimer(plugin, 20L * 60, 20L * 60);
    }

    private Player pickSuspiciousPlayer() {
        List<Player> suspicious = new ArrayList<>();
        for (Player p : VersionUtil.safeGetOnlinePlayers()) {
            if (p.hasPermission("anticheat.bypass")) continue;
            if (guiOpenTime.containsKey(p.getUniqueId())) continue; // 已有探针窗口未关
            try {
                int total = plugin.getDetectionManager().getViolationManager()
                    .getViolationHistory(p.getUniqueId()).size();
                if (total > 0) suspicious.add(p);
            } catch (Throwable ignored) {
            }
        }
        if (suspicious.isEmpty()) return null;
        return suspicious.get(new Random().nextInt(suspicious.size()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGuiClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        Long opened = guiOpenTime.remove(player.getUniqueId());
        if (opened == null) return;

        long latency = System.currentTimeMillis() - opened;
        List<Long> latencies = guiLatencies.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
        latencies.add(latency);
        if (latencies.size() > 20) latencies.remove(0);

        analyzeGuiLatency(player, latencies);
    }

    private void analyzeGuiLatency(Player player, List<Long> latencies) {
        if (latencies.size() < 5) return;
        double mean = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        double stdDev = stdDev(latencies, mean);
        double cv = mean > 0 ? stdDev / mean : Double.MAX_VALUE;

        // 响应过快且恒定 → 注入客户端/自动化
        if (mean < GUI_MIN_LATENCY_MS) {
            record(player, ViolationRecord.ViolationType.GUI_FINGERPRINT,
                String.format("GUI 响应过快（均值=%.0fms < %.0fms）", mean, GUI_MIN_LATENCY_MS), 0.6);
        } else if (cv < GUI_CV_THRESHOLD) {
            record(player, ViolationRecord.ViolationType.GUI_FINGERPRINT,
                String.format("GUI 响应过于恒定（变异系数=%.3f）", cv), 0.55);
        }
    }

    // ---------------- 隐写特征标记 ----------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!plugin.getConfig().getBoolean("fingerprint.stegano.enabled", true)) return;
        if (player.hasPermission("anticheat.bypass")) return;

        String markerId = UUID.randomUUID().toString().substring(0, 8);
        markerOwners.put(markerId, player.getUniqueId());

        ItemStack marker = new ItemStack(VersionUtil.compatTotemOrFallback());
        ItemMeta meta = marker.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§f§k§7" + markerId); // 隐藏的隐写标记名（低版本回退）
            // 1.14+ 写入 NBT 持久化标记（低版本自动回退显示名）
            writeMarkerNbt(meta, markerId);
            marker.setItemMeta(meta);
        }
        player.getInventory().addItem(marker);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (isExempt(player)) return;

        // 检查交换/拾取到物品是否携带他人隐写标记 → 关联小号
        ItemStack current = event.getCurrentItem();
        if (current != null) checkMarkerTransfer(player, current);
        ItemStack cursor = event.getCursor();
        if (cursor != null) checkMarkerTransfer(player, cursor);
    }

    private void checkMarkerTransfer(Player holder, ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        // 优先读 NBT 持久化标记（1.14+），拿不到再退回显示名混淆
        String nbtMarker = readMarkerNbt(meta);
        if (nbtMarker != null && !nbtMarker.isEmpty()) {
            UUID owner = markerOwners.get(nbtMarker);
            if (owner != null && !owner.equals(holder.getUniqueId())) {
                plugin.getLogger().info("[Fingerprint] 玩家 " + holder.getName() +
                    " 持有玩家 " + owner + " 的隐写标记物品（NBT 关联，疑似小号）");
                record(holder, ViolationRecord.ViolationType.BEHAVIOR_ANOMALY,
                    "持有他人隐写标记物品（NBT）", 0.4);
            }
            return;
        }

        if (!meta.hasDisplayName()) return;
        String name = meta.getDisplayName();
        for (Map.Entry<String, UUID> entry : markerOwners.entrySet()) {
            if (name.contains(entry.getKey()) && !entry.getValue().equals(holder.getUniqueId())) {
                // 持有他人的隐写标记物品 → 账号关联线索
                plugin.getLogger().info("[Fingerprint] 玩家 " + holder.getName() +
                    " 持有玩家 " + entry.getValue() + " 的隐写标记物品（疑似小号关联）");
                record(holder, ViolationRecord.ViolationType.BEHAVIOR_ANOMALY,
                    "持有他人隐写标记物品", 0.4);
                return;
            }
        }
    }

    /**
     * 将隐写标记 ID 写入物品 NBT（PersistentDataContainer，1.14+）。
     * 全程反射，1.8 无 NBT API 时静默回退（调用方回退到显示名混淆）。
     */
    private void writeMarkerNbt(ItemMeta meta, String markerId) {
        try {
            Object pdc = meta.getClass().getMethod("getPersistentDataContainer").invoke(meta);
            Class<?> nsKeyClass = Class.forName("org.bukkit.NamespacedKey");
            Object key = nsKeyClass
                .getConstructor(org.bukkit.plugin.Plugin.class, String.class)
                .newInstance(plugin, "anticheat_marker");
            Class<?> pdtClass = Class.forName("org.bukkit.persistence.PersistentDataType");
            Object stringType = pdtClass.getField("STRING").get(null);
            Class<?> pdcClass = Class.forName("org.bukkit.persistence.PersistentDataContainer");
            pdcClass.getMethod("set", nsKeyClass, pdtClass, Object.class)
                .invoke(pdc, key, stringType, markerId);
        } catch (Throwable ignored) {
            // 低版本无 NBT API，静默回退
        }
    }

    /**
     * 从物品 NBT 读取隐写标记 ID（PersistentDataContainer，1.14+）。
     * 全程反射，拿不到（1.8）时返回 null，调用方回退到显示名扫描。
     */
    private String readMarkerNbt(ItemMeta meta) {
        try {
            Object pdc = meta.getClass().getMethod("getPersistentDataContainer").invoke(meta);
            Class<?> nsKeyClass = Class.forName("org.bukkit.NamespacedKey");
            Object key = nsKeyClass
                .getConstructor(org.bukkit.plugin.Plugin.class, String.class)
                .newInstance(plugin, "anticheat_marker");
            Class<?> pdtClass = Class.forName("org.bukkit.persistence.PersistentDataType");
            Object stringType = pdtClass.getField("STRING").get(null);
            Class<?> pdcClass = Class.forName("org.bukkit.persistence.PersistentDataContainer");
            Object value = pdcClass.getMethod("get", nsKeyClass, pdtClass)
                .invoke(pdc, key, stringType);
            return value == null ? null : value.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------------- 工具 ----------------

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

    private double stdDev(List<Long> values, double mean) {
        double sum = 0;
        for (long v : values) sum += (v - mean) * (v - mean);
        return Math.sqrt(sum / Math.max(1, values.size() - 1));
    }

    public void clearPlayerData(UUID uuid) {
        guiOpenTime.remove(uuid);
        guiLatencies.remove(uuid);
    }

    public Map<Location, Material> getTrapBlocks() {
        return Collections.unmodifiableMap(trapBlocks);
    }
}
