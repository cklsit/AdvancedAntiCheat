package com.anticheat.repositories.impl;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.replay.ReplaySegment;
import com.anticheat.managers.replay.TracePoint;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 回放数据仓库。
 * <p>
 * 独立管理 JDBC Connection，SQLite/H2 使用 file 连接串，MySQL 复用
 * DatabaseManager 的 URL 构造方式。所有公开方法假定调用方已在异步线程。
 */
public class ReplayRepository {

    private final AdvancedAntiCheat plugin;
    private final String databaseType;
    private Connection connection;

    public ReplayRepository(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.databaseType = plugin.getConfig().getString("database.type", "sqlite").toLowerCase();
        this.connection = createConnection();
    }

    // ========== Connection 管理 ==========

    private Connection createConnection() {
        try {
            switch (databaseType) {
                case "mysql":
                    return createMySQLConnection();
                case "h2":
                    return createH2Connection();
                case "sqlite":
                default:
                    return createSQLiteConnection();
            }
        } catch (Throwable t) {
            plugin.getLogger().severe("[Replay] 创建数据库连接失败: " + t.getMessage());
            return null;
        }
    }

    private Connection createSQLiteConnection() throws Exception {
        Class.forName("org.sqlite.JDBC");
        String path = new File(plugin.getDataFolder(), "anticheat.db").getAbsolutePath();
        // SQLite 启用 WAL 模式以获得更好的并发读写性能
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
        }
        return conn;
    }

    private Connection createH2Connection() throws Exception {
        Class.forName("org.h2.Driver");
        String path = new File(plugin.getDataFolder(), "anticheat").getAbsolutePath();
        return DriverManager.getConnection(
                "jdbc:h2:file:" + path + ";MODE=MySQL", "sa", "");
    }

    private Connection createMySQLConnection() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        String host = plugin.getConfig().getString("database.mysql.host", "localhost");
        int port = plugin.getConfig().getInt("database.mysql.port", 3306);
        String database = plugin.getConfig().getString("database.mysql.database", "anticheat");
        String username = plugin.getConfig().getString("database.mysql.username", "root");
        String password = plugin.getConfig().getString("database.mysql.password", "");
        String url = "jdbc:mysql://" + host + ":" + port + "/" + database +
                "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        return DriverManager.getConnection(url, username, password);
    }

    /**
     * 获取可用的 Connection。如果已关闭则重建。
     */
    private synchronized Connection ensureConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = createConnection();
        }
        return connection;
    }

    // ========== 表结构（DatabaseManager 也会同步创建，这里作为兜底） ==========

    public void ensureTables() {
        try {
            Connection conn = ensureConnection();
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS replay_segment (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "player_uuid VARCHAR(36) NOT NULL, " +
                        "player_name VARCHAR(16), " +
                        "violation_type VARCHAR(32), " +
                        "violation_level VARCHAR(16), " +
                        "start_ms BIGINT NOT NULL, " +
                        "end_ms BIGINT NOT NULL, " +
                        "violation_offset_ms BIGINT NOT NULL, " +
                        "created_at BIGINT NOT NULL" +
                        ")");
                stmt.execute("CREATE TABLE IF NOT EXISTS replay_point (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "segment_id INTEGER NOT NULL, " +
                        "time_offset_ms BIGINT NOT NULL, " +
                        "x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, " +
                        "yaw REAL NOT NULL, pitch REAL NOT NULL, " +
                        "on_ground INTEGER NOT NULL, " +
                        "game_mode VARCHAR(16), " +
                        "FOREIGN KEY (segment_id) REFERENCES replay_segment(id) ON DELETE CASCADE" +
                        ")");
                try {
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_replay_seg_player ON replay_segment(player_uuid)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_replay_seg_created ON replay_segment(created_at)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_replay_point_seg ON replay_point(segment_id)");
                } catch (SQLException ignored) {
                    // 部分方言不支持 CREATE INDEX IF NOT EXISTS，忽略
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[Replay] 建表失败: " + e.getMessage());
        }
    }

    // ========== 写入 ==========

    /**
     * 事务内保存一个 ReplaySegment：先 insert segment 拿到自增 id，再批量 insert points。
     */
    public synchronized long saveSegment(ReplaySegment seg) {
        if (seg == null) return -1L;
        try {
            Connection conn = ensureConnection();
            conn.setAutoCommit(false);
            try {
                long segId = insertSegment(conn, seg);
                seg.setId(segId);
                batchInsertPoints(conn, segId, seg.getPoints());
                conn.commit();
                return segId;
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("[Replay] 保存片段失败: " + e.getMessage());
            return -1L;
        }
    }

    private long insertSegment(Connection conn, ReplaySegment seg) throws SQLException {
        String sql = "INSERT INTO replay_segment " +
                "(player_uuid, player_name, violation_type, violation_level, " +
                "start_ms, end_ms, violation_offset_ms, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, seg.getPlayerUuid().toString());
            ps.setString(2, seg.getPlayerName());
            ps.setString(3, seg.getViolationType());
            ps.setString(4, seg.getViolationLevel());
            ps.setLong(5, seg.getStartMs());
            ps.setLong(6, seg.getEndMs());
            ps.setLong(7, seg.getViolationOffsetMs());
            ps.setLong(8, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("未能获取 replay_segment 自增主键");
    }

    private void batchInsertPoints(Connection conn, long segmentId, List<TracePoint> points) throws SQLException {
        if (points == null || points.isEmpty()) return;
        String sql = "INSERT INTO replay_point " +
                "(segment_id, time_offset_ms, x, y, z, yaw, pitch, on_ground, game_mode) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int batchSize = 0;
            for (TracePoint p : points) {
                ps.setLong(1, segmentId);
                ps.setLong(2, p.getTimeOffsetMs());
                ps.setDouble(3, p.getX());
                ps.setDouble(4, p.getY());
                ps.setDouble(5, p.getZ());
                ps.setFloat(6, p.getYaw());
                ps.setFloat(7, p.getPitch());
                ps.setInt(8, p.isOnGround() ? 1 : 0);
                ps.setString(9, p.getGameMode());
                ps.addBatch();
                if (++batchSize >= 200) {
                    ps.executeBatch();
                    batchSize = 0;
                }
            }
            if (batchSize > 0) {
                ps.executeBatch();
            }
        }
    }

    // ========== 查询 ==========

    /**
     * 分页查询回放片段列表（不含 points，减少传输）。
     */
    public List<ReplaySegment> listSegments(String playerUuid, String violationType, int page, int pageSize) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, player_uuid, player_name, violation_type, violation_level, " +
                "start_ms, end_ms, violation_offset_ms FROM replay_segment WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (playerUuid != null && !playerUuid.isEmpty()) {
            sql.append(" AND player_uuid = ?");
            params.add(playerUuid);
        }
        if (violationType != null && !violationType.isEmpty()) {
            sql.append(" AND violation_type = ?");
            params.add(violationType);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        int offset = Math.max(0, (page - 1)) * pageSize;
        params.add(pageSize);
        params.add(offset);

        List<ReplaySegment> list = new ArrayList<>();
        try {
            Connection conn = ensureConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapSegment(rs, false));
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[Replay] 查询片段列表失败: " + e.getMessage());
        }
        return list;
    }

    /**
     * 统计符合条件的回放片段总数。
     */
    public synchronized long countSegments(String playerUuid, String violationType) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM replay_segment WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (playerUuid != null && !playerUuid.isEmpty()) {
            sql.append(" AND player_uuid = ?");
            params.add(playerUuid);
        }
        if (violationType != null && !violationType.isEmpty()) {
            sql.append(" AND violation_type = ?");
            params.add(violationType);
        }
        try {
            Connection conn = ensureConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getLong(1);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[Replay] 统计片段数量失败: " + e.getMessage());
        }
        return 0L;
    }

    /**
     * 按 id 查询完整回放片段（含全部轨迹点）。
     */
    public ReplaySegment getSegmentById(long id) {
        ReplaySegment seg = null;
        try {
            Connection conn = ensureConnection();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, player_uuid, player_name, violation_type, violation_level, " +
                    "start_ms, end_ms, violation_offset_ms FROM replay_segment WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        seg = mapSegment(rs, false);
                    }
                }
            }
            if (seg != null) {
                seg.setPoints(listPoints(conn, id));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[Replay] 查询片段详情失败: " + e.getMessage());
        }
        return seg;
    }

    private List<TracePoint> listPoints(Connection conn, long segmentId) throws SQLException {
        List<TracePoint> points = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT time_offset_ms, x, y, z, yaw, pitch, on_ground, game_mode " +
                "FROM replay_point WHERE segment_id = ? ORDER BY time_offset_ms ASC")) {
            ps.setLong(1, segmentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TracePoint p = new TracePoint();
                    p.setTimeOffsetMs(rs.getLong("time_offset_ms"));
                    p.setX(rs.getDouble("x"));
                    p.setY(rs.getDouble("y"));
                    p.setZ(rs.getDouble("z"));
                    p.setYaw(rs.getFloat("yaw"));
                    p.setPitch(rs.getFloat("pitch"));
                    p.setOnGround(rs.getInt("on_ground") == 1);
                    p.setGameMode(rs.getString("game_mode"));
                    points.add(p);
                }
            }
        }
        return points;
    }

    private ReplaySegment mapSegment(ResultSet rs, boolean loadPoints) throws SQLException {
        ReplaySegment s = new ReplaySegment();
        s.setId(rs.getLong("id"));
        // UUID 解析容错：脏数据不致整个列表 500
        try {
            s.setPlayerUuid(UUID.fromString(rs.getString("player_uuid")));
        } catch (IllegalArgumentException e) {
            s.setPlayerUuid(null);
        }
        s.setPlayerName(rs.getString("player_name"));
        s.setViolationType(rs.getString("violation_type"));
        s.setViolationLevel(rs.getString("violation_level"));
        s.setStartMs(rs.getLong("start_ms"));
        s.setEndMs(rs.getLong("end_ms"));
        s.setViolationOffsetMs(rs.getLong("violation_offset_ms"));
        if (loadPoints) {
            // 调用方应显式调用 listPoints
            s.setPoints(new ArrayList<>());
        }
        return s;
    }

    // ========== 删除 ==========

    /**
     * 删除指定 id 的片段（级联删除 replay_point）。
     */
    public synchronized boolean deleteSegment(long id) {
        try {
            Connection conn = ensureConnection();
            conn.setAutoCommit(false);
            try {
                // SQLite/CASCADE 会自动删 replay_point，但为了兼容 MySQL 显式删除
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM replay_point WHERE segment_id = ?")) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
                int rows;
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM replay_segment WHERE id = ?")) {
                    ps.setLong(1, id);
                    rows = ps.executeUpdate();
                }
                conn.commit();
                return rows > 0;
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[Replay] 删除片段失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 删除超过 retentionDays 天的片段（级联删除）。
     */
    public synchronized int deleteExpired(int retentionDays) {
        if (retentionDays <= 0) return 0;
        long cutoff = System.currentTimeMillis() - (long) retentionDays * 86400000L;
        try {
            Connection conn = ensureConnection();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM replay_point WHERE segment_id IN " +
                        "(SELECT id FROM replay_segment WHERE created_at < ?)")) {
                    ps.setLong(1, cutoff);
                    ps.executeUpdate();
                }
                int rows;
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM replay_segment WHERE created_at < ?")) {
                    ps.setLong(1, cutoff);
                    rows = ps.executeUpdate();
                }
                conn.commit();
                return rows;
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[Replay] 清理过期回放失败: " + e.getMessage());
            return 0;
        }
    }

    // ========== 资源释放 ==========

    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[Replay] 关闭连接失败: " + e.getMessage());
        }
    }
}
