package com.anticheat.managers;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.detection.ViolationRecord;
import com.anticheat.managers.replay.RingBuffer;
import com.anticheat.managers.replay.TracePoint;
import com.anticheat.managers.replay.TracePoint.ArmorHalfPoint;
import com.anticheat.managers.replay.ViolationMarker;
import com.anticheat.managers.replay.ZipArchiver;
import com.anticheat.replay.CrosshairProbe;
import com.anticheat.web.util.JsonMapper;
import com.anticheat.web.ws.replay.ReplayBroadcaster;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家持续录制 + 实时回放 录制器（Task 1 重构）。
 * <p>
 * 与旧版的核心差异：
 * <ul>
 *     <li>旧：违规触发时才采样 + 固化 segment（BUFFER_CAPACITY=800，~30s）</li>
 *     <li>新：玩家进服即启动采样（SESSION_BUFFER_CAPACITY=144000，2h × 20Hz），
 *         下线时自动 ZIP 存档</li>
 * </ul>
 * <p>
 * 内存安全要点：
 * <ul>
 *     <li>RingBuffer 使用固定容量数组 + 读写锁，不扩容</li>
 *     <li>blockHeights 为 null 时 TracePoint 约 48 字节；稀疏采样（每 ~10 点采一次）
 *         平均 ~200 字节。144K × 200B ≈ 28MB/玩家（理论），实际可能更小。</li>
 *     <li>所有 IO（ZIP 写入）在异步线程，不阻塞主线程。</li>
 * </ul>
 */
public class ReplayRecorder {

    private final AdvancedAntiCheat plugin;

    /** 每个玩家 session 环形缓冲容量：2h × 20Hz ≈ 144,000 点 */
    private static final int SESSION_BUFFER_CAPACITY = 144_000;

    /** 采样间隔毫秒数（20Hz = 每 50ms 一次） */
    private static final long SAMPLE_INTERVAL_MS = 50L;

    /** 方块高度采样的稀疏间隔（每多少个采样点采一次） */
    private static final int BLOCK_HEIGHT_SAMPLE_EVERY_N = 10;

    // ===== 核心数据结构 =====

    /** 每个在线玩家一个 RingBuffer */
    private final Map<UUID, RingBuffer<TracePoint>> sessions = new ConcurrentHashMap<>();

    /** 每个 session 的起点 epoch ms（计算 timeOffsetMs） */
    private final Map<UUID, Long> sessionEpoch = new ConcurrentHashMap<>();

    /** 每个玩家的违规标记列表 */
    private final Map<UUID, List<ViolationMarker>> violationMarkers = new ConcurrentHashMap<>();

    /** 记录每个玩家的名字（UUID → playerName），供 Web 展示 */
    private final Map<UUID, String> playerNames = new ConcurrentHashMap<>();

    /** 采样定时器中记录玩家上次采样的时间（用于节流） */
    private final Map<UUID, Long> lastSampleMs = new ConcurrentHashMap<>();

    /** 方块高度采样的计数（每 N 点采一次） */
    private final Map<UUID, Integer> blockHeightSampleCounter = new ConcurrentHashMap<>();

    /** 热栏 inventory 采样的计数（每 20 点采一次，~1 秒） */
    private final Map<UUID, Integer> invSampleCounter = new ConcurrentHashMap<>();

    /** 采样定时任务引用（便于 shutdown 取消） */
    private BukkitRunnable sampleTask;

    /** 视频存档目录 */
    private final File videoDir;

    /** 默认保留天数 */
    private static final int DEFAULT_RETENTION_DAYS = 7;

    // === NEW: WS 推送回调 ===
    /** 回放 WS 广播器；由 WebServer.start 注册完 ReplayWSHandler 后注入。 */
    private volatile ReplayBroadcaster replayBroadcaster = null;

    public void setReplayBroadcaster(ReplayBroadcaster b) {
        this.replayBroadcaster = b;
    }

    public ReplayRecorder(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.videoDir = new File(plugin.getDataFolder(), "video");

        // 启动 BukkitRunnable —— 主线程每 tick 跑一次，遍历所有活跃玩家
        startSampleTask();

        // 初始清理
        ZipArchiver.cleanupExpired(videoDir, plugin.getLogger());

        // 每天清理过期存档
        scheduleArchiveCleanup();

        plugin.getLogger().info("[Replay] ReplayRecorder（持续录制模式）已初始化，bufferCapacity=" + SESSION_BUFFER_CAPACITY);
    }

    // ========== BukkitRunnable 采样 ==========

    private void startSampleTask() {
        sampleTask = new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    long now = System.currentTimeMillis();
                    for (Map.Entry<UUID, RingBuffer<TracePoint>> entry : sessions.entrySet()) {
                        UUID uuid = entry.getKey();
                        Long epoch = sessionEpoch.get(uuid);
                        if (epoch == null) continue;

                        // 节流：距上次采样 >= 50ms 才采
                        Long last = lastSampleMs.get(uuid);
                        if (last != null && now - last < SAMPLE_INTERVAL_MS) continue;
                        lastSampleMs.put(uuid, now);

                        // 找玩家（可能已下线但还没 stopSession 清理，跳过）
                        Player player = findOnlinePlayer(uuid);
                        if (player == null) continue;

                        sample(player, now - epoch);
                    }
                } catch (Throwable t) {
                    plugin.getLogger().warning("[Replay] 采样定时器异常: " + t.getMessage());
                }
            }
        };
        sampleTask.runTaskTimer(plugin, 20L, 1L); // 20 tick 后启动，每 1 tick 跑一次
    }

    private Player findOnlinePlayer(UUID uuid) {
        try {
            // 优先 getPlayer(UUID) —— 跨版本兼容
            return plugin.getServer().getPlayer(uuid);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 单次采样。必须在主线程执行（Location / getInventory 等 API 在主线程最安全）。
     * blockHeights 作为稀疏采样，每 N 点采一次。
     */
    private void sample(Player player, long timeOffsetMs) {
        UUID uuid = player.getUniqueId();
        RingBuffer<TracePoint> buf = sessions.get(uuid);
        if (buf == null) return;

        TracePoint tp = new TracePoint();
        tp.setTimeOffsetMs(timeOffsetMs);
        tp.setX(player.getLocation().getX());
        tp.setY(player.getLocation().getY());
        tp.setZ(player.getLocation().getZ());
        tp.setYaw(player.getLocation().getYaw());
        tp.setPitch(player.getLocation().getPitch());
        tp.setOnGround(player.isOnGround());

        // gameMode
        try {
            GameMode gm = player.getGameMode();
            tp.setGameMode(gm != null ? gm.name() : "SURVIVAL");
        } catch (Throwable ignored) {
            tp.setGameMode("SURVIVAL");
        }

        // 主手物品（跨版本兼容）
        tp.setMainHandItemId(safeGetMainHandItemId(player));

        // --- NEW: HUD 字段（跨版本兼容，全部 try-catch） ---
        tp.setWallClockMs(System.currentTimeMillis());
        try { tp.setHealthHalf(toInt(player.getHealth()*2)); } catch (Throwable e) { tp.setHealthHalf(40); }
        try { tp.setHunger(clampByte(player.getFoodLevel(), 0, 20)); } catch (Throwable e) { tp.setHunger(20); }
        try { tp.setArmorHalf(ArmorHalfPoint.calcHalfArmorPoints(player)); } catch (Throwable e) { tp.setArmorHalf(0); }
        try { tp.setExpLevel((short) Math.max(0, player.getLevel())); } catch (Throwable e) { tp.setExpLevel(0); }
        try {
            float exp = player.getExp(); // 0.0-1.0（本等级百分比）
            tp.setExpPercent(clampByte((int)Math.round(exp*100), 0, 100));
        } catch (Throwable e) { tp.setExpPercent(0); }
        try { tp.setHotbarSlot(clampByte(player.getInventory().getHeldItemSlot(), 0, 8)); } catch (Throwable e) { tp.setHotbarSlot(0); }

        // inventory：每 20 采样点（~1 秒）采一次完整 36 格 + 热栏 9 格
        int invCounter = invSampleCounter.getOrDefault(uuid, 0) + 1;
        if (invCounter >= 20) {
            invCounter = 0;
            ItemStack[] inv36 = readMainInventory36(player);
            if (inv36 != null) {
                String[] full = new String[36];
                for (int i = 0; i < 36; i++) {
                    ItemStack s = inv36[i];
                    if (s != null && s.getType() != null && s.getType() != Material.AIR) {
                        full[i] = s.getType().name();
                    }
                }
                tp.setInventoryFull(full);
                String[] bar = new String[9];
                System.arraycopy(full, 0, bar, 0, 9);
                tp.setInventoryHotbar(bar);
            }
        }
        invSampleCounter.put(uuid, invCounter);

        // 准星目标（需求 4）：每 tick 探测，实体优先、方块兜底
        try {
            if (plugin.getReplaySettings() != null && plugin.getReplaySettings().isCrosshairEnabled()) {
                CrosshairProbe.Result cr = CrosshairProbe.probe(player,
                        plugin.getReplaySettings().getCrosshairMaxDistance());
                tp.setTargetKind(cr.kind);
                if (cr.kind == 1) {
                    tp.setTargetEntityType(cr.entityType);
                    tp.setTargetEntityName(cr.entityName);
                    tp.setTargetDistance(cr.distance);
                } else if (cr.kind == 2) {
                    tp.setTargetBlockType(cr.blockType);
                    tp.setTargetDistance(cr.distance);
                }
            }
        } catch (Throwable ignored) {
        }

        // 方块高度 —— 稀疏采样，异步取（避免阻塞主线程）
        int counter = blockHeightSampleCounter.getOrDefault(uuid, 0);
        counter++;
        if (counter >= BLOCK_HEIGHT_SAMPLE_EVERY_N) {
            blockHeightSampleCounter.put(uuid, 0);
            // 异步采样方块高度
            final TracePoint snapshot = tp;
            runAsyncSafe(() -> {
                try {
                    int[] heights = sampleBlockHeights(player);
                    snapshot.setBlockHeights(heights);
                } catch (Throwable ignored) {}
            });
        } else {
            blockHeightSampleCounter.put(uuid, counter);
        }

        buf.add(tp);

        // === WS 推送 20Hz HUD（v3 信封；仅当有订阅者时才序列化，节约 CPU）
        ReplayBroadcaster b = replayBroadcaster;
        if (b != null && b.subscriberCount(uuid) > 0) {
            try {
                b.broadcastEnvelope(uuid, "hud",
                        com.anticheat.web.ws.replay.HudFrameBuilder.build(tp, timeOffsetMs));
            } catch (Throwable ignored) {
            }
        }
    }

    // ===== NEW: 采样辅助方法 =====

    private static byte clampByte(int v, int lo, int hi) {
        return (byte) Math.max(lo, Math.min(hi, v));
    }

    private static int toInt(double v) {
        return (int) Math.max(0, Math.min(Byte.MAX_VALUE, Math.round(v)));
    }

    /** 跨版本获取玩家主手物品名称。 */
    private String safeGetMainHandItemId(Player player) {
        try {
            // 1) Paper/Spigot 1.9+: getInventory().getItemInMainHand()
            try {
                Method m = player.getInventory().getClass().getMethod("getItemInMainHand");
                ItemStack stack = (ItemStack) m.invoke(player.getInventory());
                if (stack != null && stack.getType() != null) {
                    return stack.getType().name();
                }
                return null;
            } catch (NoSuchMethodException ignored) {
                // fall through
            }
            // 2) 1.8.8: getInventory().getItemInHand()
            try {
                Method m = player.getInventory().getClass().getMethod("getItemInHand");
                ItemStack stack = (ItemStack) m.invoke(player.getInventory());
                if (stack != null && stack.getType() != null) {
                    return stack.getType().name();
                }
                return null;
            } catch (NoSuchMethodException ignored) {
                // fall through
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 读取主背包 36 格（热栏 0-8 + 主格 9-35）。
     * 1.9+ 用 getStorageContents()（返回 36），1.8 用 getContents()（前 36 即主背包），
     * 都失败返回 null（该帧不采样背包）。
     */
    private ItemStack[] readMainInventory36(Player player) {
        try {
            ItemStack[] inv = player.getInventory().getStorageContents();
            if (inv != null && inv.length >= 36) return inv;
        } catch (Throwable ignored) {
        }
        try {
            ItemStack[] inv = player.getInventory().getContents();
            if (inv != null && inv.length >= 36) return inv;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 7×7 方块高度采样。49 个方块的 y 坐标。
     * 必须在异步线程执行（主线程调用 getHighestBlockYAt 可能阻塞）。
     */
    private int[] sampleBlockHeights(Player player) {
        try {
            World world = player.getWorld();
            if (world == null) return null;
            int px = player.getLocation().getBlockX();
            int pz = player.getLocation().getBlockZ();
            int[] heights = new int[49];
            int idx = 0;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    try {
                        heights[idx++] = world.getHighestBlockYAt(px + dx, pz + dz);
                    } catch (Throwable t) {
                        heights[idx++] = 0;
                    }
                }
            }
            return heights;
        } catch (Throwable t) {
            return null;
        }
    }

    private void runAsyncSafe(Runnable r) {
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, r);
        } catch (Throwable ignored) {
        }
    }

    // ========== Session 生命周期 ==========

    /**
     * 玩家进服时调用。创建 RingBuffer + 注册 session 元数据。
     */
    public void startSession(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();

        // 防止重复启动
        if (sessions.containsKey(uuid)) {
            return;
        }

        RingBuffer<TracePoint> buf = new RingBuffer<>(SESSION_BUFFER_CAPACITY);
        sessions.put(uuid, buf);
        long now = System.currentTimeMillis();
        sessionEpoch.put(uuid, now);
        violationMarkers.put(uuid, Collections.synchronizedList(new ArrayList<>()));
        playerNames.put(uuid, player.getName());
        lastSampleMs.remove(uuid);
        blockHeightSampleCounter.remove(uuid);
        invSampleCounter.remove(uuid);

        plugin.getLogger().info("[Replay] 开始录制 player=" + player.getName()
                + " bufferCapacity=" + SESSION_BUFFER_CAPACITY);
    }

    /**
     * 玩家下线时调用。snapshot → 异步 ZIP 存档 → 清理内存。
     */
    public void stopSession(UUID uuid) {
        if (uuid == null) return;

        RingBuffer<TracePoint> buf = sessions.remove(uuid);
        Long epoch = sessionEpoch.remove(uuid);
        List<ViolationMarker> markers = violationMarkers.remove(uuid);
        String playerName = playerNames.get(uuid);

        // === WS 通知 offline（v3 信封；在 entries 清理之后尽早推，避免订阅者还在等 HUD）
        ReplayBroadcaster b = replayBroadcaster;
        if (b != null && b.subscriberCount(uuid) > 0) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("event", "offline");
            ev.put("message", "玩家已下线");
            try {
                b.broadcastEnvelope(uuid, "event", ev);
            } catch (Throwable ignored) {
            }
        }

        if (buf == null) {
            // 没有 session，清理残留
            lastSampleMs.remove(uuid);
            blockHeightSampleCounter.remove(uuid);
            invSampleCounter.remove(uuid);
            playerNames.remove(uuid);
            return;
        }

        // snapshot（在当前线程做 RingBuffer 的读锁拷贝，O(n)）
        final List<TracePoint> points = buf.snapshot();
        final List<ViolationMarker> markersCopy = markers != null ? new ArrayList<>(markers) : Collections.emptyList();
        final long startTime = epoch != null ? epoch : System.currentTimeMillis();
        final long endTime = System.currentTimeMillis();
        final String name = playerName != null ? playerName : uuid.toString();

        // 清理残留
        lastSampleMs.remove(uuid);
        blockHeightSampleCounter.remove(uuid);
        invSampleCounter.remove(uuid);
        playerNames.remove(uuid);

        // 异步写 ZIP
        runAsyncSafe(() -> {
            try {
                long bytesEstimate = 0;
                for (TracePoint tp : points) {
                    if (tp.getBlockHeights() != null) bytesEstimate += 200;
                    else bytesEstimate += 48;
                }
                long durationSec = (endTime - startTime) / 1000L;

                File zip = ZipArchiver.saveToVideoDir(
                        name, uuid, startTime, endTime,
                        points, markersCopy,
                        videoDir,
                        plugin.getLogger(),
                        null
                );

                long sizeKB = 0;
                if (zip != null) {
                    sizeKB = zip.length() / 1024;
                }

                plugin.getLogger().info("[Replay] 已存档 player=" + name
                        + " duration=" + durationSec + "s"
                        + " violations=" + markersCopy.size()
                        + " size=" + sizeKB + "KB"
                        + " points=" + points.size());
            } catch (Throwable t) {
                plugin.getLogger().warning("[Replay] 下线存档失败 player=" + name + " err=" + t.getMessage());
            }
        });
    }

    // ========== 违规标记 ==========

    /**
     * 由 ViolationManager 在触发违规时调用。
     * 在 RingBuffer 中找最接近的点索引，追加 ViolationMarker。
     * <p>
     * 与旧版 {@code onViolationTriggered} 的区别：
     * <ul>
     *     <li>旧版：拷贝缓冲 + 等待 10s + 固化 segment 入库（异步等待，会阻塞内存）</li>
     *     <li>新版：仅追加一个 ViolationMarker，永不固化 segment（下线时一次性 ZIP）</li>
     * </ul>
     */
    public void recordViolation(UUID uuid, String type, String level) {
        if (uuid == null || type == null) return;
        try {
            RingBuffer<TracePoint> buf = sessions.get(uuid);
            if (buf == null) return;
            int markerIndex = Math.max(0, buf.size() - 1);
            ViolationMarker vm = new ViolationMarker(markerIndex, type, level);
            List<ViolationMarker> list = violationMarkers.computeIfAbsent(uuid, k -> Collections.synchronizedList(new ArrayList<>()));
            list.add(vm);

            String pname = playerNames.get(uuid);
            plugin.getLogger().info("[Replay] 标记违规 player=" + (pname != null ? pname : uuid)
                    + " type=" + type + " markerIndex=" + markerIndex);

            // === 重构：违规 → 机位取证保持（方案 A）＋ 可选插队（须主线程操作 Bukkit 实体）===
            final boolean severe = level != null
                    && (level.equalsIgnoreCase("HIGH") || level.equalsIgnoreCase("CRITICAL")
                        || level.equalsIgnoreCase("SEVERE"));
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    if (plugin.getCameraBinder() != null && plugin.getCameraBinder().triggerEvidenceHold(uuid)) {
                        plugin.getLogger().info("[Replay] 已切过肩机位取证: " + uuid);
                    }
                } catch (Throwable ignored) {
                }
                try {
                    if (plugin.getSurveillanceScheduler() != null) {
                        plugin.getSurveillanceScheduler().onViolation(uuid, severe);
                    }
                } catch (Throwable ignored) {
                }
            });

            // === WS 广播违规推送（v3 信封）
            ReplayBroadcaster b = replayBroadcaster;
            if (b != null && b.subscriberCount(uuid) > 0) {
                long wall = 0L;
                long t = 0L;
                int idx = Math.max(0, Math.min(vm.getPointIndex(), buf.size() - 1));
                TracePoint tp = buf.get(idx);
                if (tp != null) {
                    wall = tp.getWallClockMs();
                    t = tp.getTimeOffsetMs();
                }
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("t", t);
                v.put("wallClockMs", wall);
                v.put("type", vm.getType() != null ? vm.getType() : "");
                v.put("level", vm.getLevel() != null ? vm.getLevel() : "");
                v.put("details", "");
                v.put("confidence", 0.0);
                try {
                    b.broadcastEnvelope(uuid, "violation", v);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[Replay] recordViolation 异常: " + t.getMessage());
        }
    }

    /**
     * 兼容旧版 ViolationManager 的入口（旧代码仍调用 onViolationTriggered）。
     * 现在改为只调用 recordViolation，不再固化 segment。
     */
    @Deprecated
    public void onViolationTriggered(UUID uuid, ViolationRecord record) {
        if (uuid == null || record == null) return;
        recordViolation(uuid,
                record.getType() != null ? record.getType().name() : "UNKNOWN",
                record.getSeverity() != null ? record.getSeverity().name() : "");
    }

    // ========== 外部暴露 ==========

    /** 取指定玩家的 session RingBuffer（可能为 null）。 */
    public RingBuffer<TracePoint> getSession(UUID uuid) {
        if (uuid == null) return null;
        return sessions.get(uuid);
    }

    /** 取指定玩家的违规标记列表（新 List 拷贝；无则返回空 List）。 */
    public List<ViolationMarker> getViolationMarkers(UUID uuid) {
        if (uuid == null) return Collections.emptyList();
        List<ViolationMarker> src = violationMarkers.get(uuid);
        if (src == null) return Collections.emptyList();
        return new ArrayList<>(src);
    }

    /** 取所有在线 session 的玩家名快照（UUID → playerName）。 */
    public Map<UUID, String> getPlayerNames() {
        return new HashMap<>(playerNames);
    }

    /** 取所有活跃 session 的 UUID 集合快照。 */
    public Set<UUID> getActivePlayers() {
        return new HashSet<>(sessions.keySet());
    }

    /** 取指定玩家的 session 起点 epoch ms。 */
    public Long getSessionEpoch(UUID uuid) {
        return sessionEpoch.get(uuid);
    }

    /**
     * 兼容旧版调用：返回当前玩家 session 的 snapshot。
     */
    public List<TracePoint> getRecorder(UUID uuid) {
        RingBuffer<TracePoint> buf = sessions.get(uuid);
        if (buf == null) return new ArrayList<>();
        return buf.snapshot();
    }

    /**
     * 兼容旧版 ViolationManager.broadcastAlert 中的调用。
     * 由于现在不再保存 segId，返回 null。
     */
    @Deprecated
    public Long getLastSegmentId(UUID uuid) {
        return null;
    }

    /** 暴露视频存档目录，供 ReplayHandler 使用。 */
    public File getVideoDir() {
        return videoDir;
    }

    // ========== NEW: InitSnapshot (init 帧 / ObserverPoolManager 复用) ==========

    /**
     * Session 快照：最近 500 点 HUD frame + 违规列表 + 起点时间戳等。
     * 供 ReplayWSHandler.onConnect 构造 init 帧、以及 ObserverPoolManager 在真正
     * acquire 成功后推送带 observer_ready 事件的完整历史帧时使用。
     */
    public static class InitSnapshot {

        /** 最近最多 500 个 HUD frame（按写入时间升序）；若无则为 empty list，永不为 null。
         *  每个元素是一个 hud frame Map，结构：{w,h,f,a,lvl,xp,hs,main?,inv?}。*/
        public final List<Map<String, Object>> recentHudFrames;

        /** 违规标记列表；无则为 empty list，永不为 null。
         *  每个元素：{wallClockMs, t, type, level, details}。*/
        public final List<Map<String, Object>> violations;

        /** 会话起始绝对时间戳（System.currentTimeMillis()）；无 session 时为 0。*/
        public final long wallClockStartMs;

        /** 玩家名字；未知时为空字符串 ""，永不为 null。*/
        public final String playerName;

        public InitSnapshot(List<Map<String, Object>> recentHudFrames,
                            List<Map<String, Object>> violations,
                            long wallClockStartMs,
                            String playerName) {
            this.recentHudFrames = recentHudFrames != null ? recentHudFrames : new ArrayList<>();
            this.violations = violations != null ? violations : new ArrayList<>();
            this.wallClockStartMs = wallClockStartMs;
            this.playerName = playerName != null ? playerName : "";
        }
    }

    /**
     * 取指定 session 的初始化快照：最近 500 点 HUD frame + violations list + startMs + playerName。
     * 无 session 时返回空内容对象（不会返回 null）。
     */
    public InitSnapshot getSessionSnapshot(UUID uuid) {
        List<Map<String, Object>> hudList = new ArrayList<>();
        List<Map<String, Object>> vList = new ArrayList<>();
        long startMs = 0L;
        String pname = "";

        if (uuid == null) {
            return new InitSnapshot(hudList, vList, 0L, "");
        }

        RingBuffer<TracePoint> buf = sessions.get(uuid);
        List<TracePoint> snap = (buf != null) ? buf.snapshot() : new ArrayList<>();
        int snapSize = snap.size();

        // 最近最多 500 点
        int startIdx = Math.max(0, snapSize - 500);
        for (int i = startIdx; i < snapSize; i++) {
            TracePoint tp = snap.get(i);
            if (tp == null) continue;
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("w", tp.getWallClockMs());
            f.put("h", (int) tp.getHealthHalf());
            f.put("f", (int) tp.getHunger());
            f.put("a", (int) tp.getArmorHalf());
            f.put("lvl", (int) tp.getExpLevel());
            f.put("xp", (int) tp.getExpPercent());
            f.put("hs", (int) tp.getHotbarSlot());
            if (tp.getMainHandItemId() != null) f.put("main", tp.getMainHandItemId());
            if (tp.getInventoryHotbar() != null) f.put("inv", tp.getInventoryHotbar());
            hudList.add(f);
        }

        // violations
        List<ViolationMarker> markers = violationMarkers.getOrDefault(uuid, Collections.emptyList());
        if (!markers.isEmpty() && snapSize > 0) {
            for (ViolationMarker m : markers) {
                long wall = 0L;
                long t = 0L;
                int idx = Math.max(0, Math.min(m.getPointIndex(), snapSize - 1));
                TracePoint tp = snap.get(idx);
                if (tp != null) {
                    wall = tp.getWallClockMs();
                    t = tp.getTimeOffsetMs();
                }
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("wallClockMs", wall);
                v.put("t", t);
                v.put("type", m.getType() != null ? m.getType() : "");
                v.put("level", m.getLevel() != null ? m.getLevel() : "");
                v.put("details", "");
                vList.add(v);
            }
        }

        Long epoch = sessionEpoch.get(uuid);
        if (epoch != null) startMs = epoch;

        String nm = playerNames.get(uuid);
        if (nm != null) pname = nm;

        return new InitSnapshot(hudList, vList, startMs, pname);
    }

    // ========== 清理 ==========

    private void scheduleArchiveCleanup() {
        long delayMs = computeDelayToHour(3);
        long periodMs = 24L * 60L * 60L * 1000L;
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    int retention = plugin.getConfig().getInt("replay.retentionDays", DEFAULT_RETENTION_DAYS);
                    ZipArchiver.cleanupExpired(videoDir, retention * 86400000L, plugin.getLogger());
                } catch (Throwable t) {
                    plugin.getLogger().warning("[Replay] 定时清理失败: " + t.getMessage());
                }
            }
        }.runTaskTimerAsynchronously(plugin, delayMs / 50L, periodMs / 50L);
    }

    private static long computeDelayToHour(int hour) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long target = cal.getTimeInMillis();
        long now = System.currentTimeMillis();
        if (target <= now) {
            target += 24L * 60L * 60L * 1000L;
        }
        return target - now;
    }

    /** onDisable 时调用：取消采样任务，尝试为所有仍在线的玩家做一次 ZIP 存档。 */
    public void shutdown() {
        try {
            if (sampleTask != null) {
                sampleTask.cancel();
            }
            // 为所有仍在 session 中的玩家做存档
            Set<UUID> stillActive = new HashSet<>(sessions.keySet());
            for (UUID uuid : stillActive) {
                try {
                    stopSession(uuid);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        }
    }
}
