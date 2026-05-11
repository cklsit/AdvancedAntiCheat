package com.anticheat.managers;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.managers.BanManager.BanRecord;
import com.anticheat.profiles.PlayerProfile;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bukkit.scheduler.BukkitRunnable;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.io.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
public class DatabaseManager {

    private final AdvancedAntiCheat plugin;
    private String databaseType;
    private Connection sqlConnection;
    private JedisPool jedisPool;
    private MongoClient mongoClient;
    private MongoDatabase mongoDatabase;

    public DatabaseManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.databaseType = plugin.getConfig().getString("database.type", "sqlite").toLowerCase();
        initialize();
    }

    private void initialize() {
        switch (databaseType) {
            case "mysql":
                initializeMySQL();
                break;
            case "h2":
                initializeH2();
                break;
            case "redis":
                initializeRedis();
                break;
            case "mongodb":
            case "mongo":
                initializeMongoDB();
                break;
            case "sqlite":
            default:
                initializeSQLite();
                break;
        }
        initializeTables();
    }

    private void initializeSQLite() {
        try {
            Class.forName("org.sqlite.JDBC");
            String path = new java.io.File(plugin.getDataFolder(), "anticheat.db").getAbsolutePath();
            sqlConnection = DriverManager.getConnection("jdbc:sqlite:" + path);
            plugin.getLogger().info("SQLite数据库连接成功！");
        } catch (Exception e) {
            plugin.getLogger().severe("SQLite数据库连接失败: " + e.getMessage());
        }
    }

    private void initializeH2() {
        try {
            Class.forName("org.h2.Driver");
            String path = new java.io.File(plugin.getDataFolder(), "anticheat").getAbsolutePath();
            sqlConnection = DriverManager.getConnection("jdbc:h2:file:" + path + ";MODE=MySQL", "sa", "");
            plugin.getLogger().info("H2数据库连接成功！");
        } catch (Exception e) {
            plugin.getLogger().severe("H2数据库连接失败: " + e.getMessage());
        }
    }

    private void initializeMySQL() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            String host = plugin.getConfig().getString("database.mysql.host", "localhost");
            int port = plugin.getConfig().getInt("database.mysql.port", 3306);
            String database = plugin.getConfig().getString("database.mysql.database", "anticheat");
            String username = plugin.getConfig().getString("database.mysql.username", "root");
            String password = plugin.getConfig().getString("database.mysql.password", "");
            String url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
            sqlConnection = DriverManager.getConnection(url, username, password);
            plugin.getLogger().info("MySQL数据库连接成功！");
        } catch (Exception e) {
            plugin.getLogger().severe("MySQL数据库连接失败: " + e.getMessage());
        }
    }

    private void initializeRedis() {
        try {
            String host = plugin.getConfig().getString("database.redis.host", "localhost");
            int port = plugin.getConfig().getInt("database.redis.port", 6379);
            String password = plugin.getConfig().getString("database.redis.password", null);
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(10);
            poolConfig.setMaxIdle(5);
            jedisPool = new JedisPool(poolConfig, host, port, 0, password);
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.ping();
            }
            plugin.getLogger().info("Redis数据库连接成功！");
        } catch (Exception e) {
            plugin.getLogger().severe("Redis数据库连接失败: " + e.getMessage());
        }
    }

    private void initializeMongoDB() {
        try {
            String host = plugin.getConfig().getString("database.mongodb.host", "localhost");
            int port = plugin.getConfig().getInt("database.mongodb.port", 27017);
            String database = plugin.getConfig().getString("database.mongodb.database", "anticheat");
            String uri = "mongodb://" + host + ":" + port;
            mongoClient = MongoClients.create(uri);
            mongoDatabase = mongoClient.getDatabase(database);
            plugin.getLogger().info("MongoDB数据库连接成功！");
        } catch (Exception e) {
            plugin.getLogger().severe("MongoDB数据库连接失败: " + e.getMessage());
        }
    }

    private void initializeTables() {
        if (sqlConnection != null) {
            try (Statement stmt = sqlConnection.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS bans (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "player_uuid VARCHAR(36) NOT NULL, " +
                        "player_name VARCHAR(16) NOT NULL, " +
                        "reason VARCHAR(255) NOT NULL, " +
                        "banned_by VARCHAR(16) NOT NULL, " +
                        "ban_time BIGINT NOT NULL, " +
                        "expiry_time BIGINT, " +
                        "server_name VARCHAR(32), " +
                        "is_active INTEGER DEFAULT 1" +
                        ")");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_bans_player ON bans(player_uuid)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_bans_active ON bans(is_active)");

                stmt.execute("CREATE TABLE IF NOT EXISTS player_profiles (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "player_uuid VARCHAR(36) NOT NULL UNIQUE, " +
                        "profile_data TEXT NOT NULL, " +
                        "last_updated BIGINT NOT NULL" +
                        ")");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_profiles_player ON player_profiles(player_uuid)");

                plugin.getLogger().info("SQL数据库表初始化完成！");
            } catch (SQLException e) {
                plugin.getLogger().severe("创建数据库表失败: " + e.getMessage());
            }
        }
    }

    public void banPlayer(UUID playerUUID, String playerName, String reason, String bannedBy, long banTime, long expiryTime, String serverName) {
        new BukkitRunnable() {
            @Override
            public void run() {
                switch (databaseType) {
                    case "mysql":
                    case "h2":
                    case "sqlite":
                        banPlayerSQL(playerUUID, playerName, reason, bannedBy, banTime, expiryTime, serverName);
                        break;
                    case "redis":
                        banPlayerRedis(playerUUID.toString(), playerName, reason, bannedBy, banTime, expiryTime, serverName);
                        break;
                    case "mongodb":
                    case "mongo":
                        banPlayerMongo(playerUUID.toString(), playerName, reason, bannedBy, banTime, expiryTime, serverName);
                        break;
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    private void banPlayerSQL(UUID playerUUID, String playerName, String reason, String bannedBy, long banTime, long expiryTime, String serverName) {
        try (PreparedStatement pstmt = sqlConnection.prepareStatement(
                "INSERT INTO bans (player_uuid, player_name, reason, banned_by, ban_time, expiry_time, server_name, is_active) VALUES (?, ?, ?, ?, ?, ?, ?, 1)")) {
            pstmt.setString(1, playerUUID.toString());
            pstmt.setString(2, playerName);
            pstmt.setString(3, reason);
            pstmt.setString(4, bannedBy);
            pstmt.setLong(5, banTime);
            pstmt.setLong(6, expiryTime);
            pstmt.setString(7, serverName);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("封禁玩家SQL失败: " + e.getMessage());
        }
    }

    private void banPlayerRedis(String playerUUID, String playerName, String reason, String bannedBy, long banTime, long expiryTime, String serverName) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "anticheat:ban:" + playerUUID;
            String data = playerUUID + "|" + playerName + "|" + reason + "|" + bannedBy + "|" + banTime + "|" + expiryTime + "|" + serverName;
            if (expiryTime > 0) {
                long ttl = (expiryTime - System.currentTimeMillis()) / 1000;
                if (ttl > 0) {
                    jedis.setex(key, ttl, data);
                }
            } else {
                jedis.set(key, data);
            }
            String playerListKey = "anticheat:banned_players";
            jedis.sadd(playerListKey, playerUUID);
        } catch (Exception e) {
            plugin.getLogger().severe("封禁玩家Redis失败: " + e.getMessage());
        }
    }

    private void banPlayerMongo(String playerUUID, String playerName, String reason, String bannedBy, long banTime, long expiryTime, String serverName) {
        try {
            MongoCollection<Document> collection = mongoDatabase.getCollection("bans");
            Document document = new Document()
                    .append("player_uuid", playerUUID)
                    .append("player_name", playerName)
                    .append("reason", reason)
                    .append("banned_by", bannedBy)
                    .append("ban_time", banTime)
                    .append("expiry_time", expiryTime)
                    .append("server_name", serverName)
                    .append("is_active", true);
            collection.insertOne(document);
        } catch (Exception e) {
            plugin.getLogger().severe("封禁玩家MongoDB失败: " + e.getMessage());
        }
    }

    public boolean isPlayerBanned(UUID playerUUID) {
        switch (databaseType) {
            case "mysql":
            case "h2":
            case "sqlite":
                return isPlayerBannedSQL(playerUUID.toString());
            case "redis":
                return isPlayerBannedRedis(playerUUID.toString());
            case "mongodb":
            case "mongo":
                return isPlayerBannedMongo(playerUUID.toString());
        }
        return false;
    }

    private boolean isPlayerBannedSQL(String playerUUID) {
        try (PreparedStatement pstmt = sqlConnection.prepareStatement(
                "SELECT expiry_time FROM bans WHERE player_uuid = ? AND is_active = 1 ORDER BY ban_time DESC LIMIT 1")) {
            pstmt.setString(1, playerUUID);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    long expiryTime = rs.getLong("expiry_time");
                    if (expiryTime > 0 && expiryTime < System.currentTimeMillis()) {
                        unbanPlayerSQL(playerUUID);
                        return false;
                    }
                    return true;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("检查封禁SQL失败: " + e.getMessage());
        }
        return false;
    }

    private boolean isPlayerBannedRedis(String playerUUID) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "anticheat:ban:" + playerUUID;
            String data = jedis.get(key);
            if (data != null) {
                String[] parts = data.split("\\|");
                if (parts.length >= 6) {
                    long expiryTime = Long.parseLong(parts[5]);
                    if (expiryTime > 0 && expiryTime < System.currentTimeMillis()) {
                        unbanPlayerRedis(playerUUID);
                        return false;
                    }
                    return true;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("检查封禁Redis失败: " + e.getMessage());
        }
        return false;
    }

    private boolean isPlayerBannedMongo(String playerUUID) {
        try {
            MongoCollection<Document> collection = mongoDatabase.getCollection("bans");
            Document filter = new Document("player_uuid", playerUUID).append("is_active", true);
            Document result = collection.find(filter).first();
            if (result != null) {
                long expiryTime = result.getLong("expiry_time");
                if (expiryTime > 0 && expiryTime < System.currentTimeMillis()) {
                    unbanPlayerMongo(playerUUID);
                    return false;
                }
                return true;
            }
        } catch (Exception e) {
            plugin.getLogger().severe("检查封禁MongoDB失败: " + e.getMessage());
        }
        return false;
    }

    public BanRecord getBanRecord(UUID playerUUID) {
        switch (databaseType) {
            case "mysql":
            case "h2":
            case "sqlite":
                return getBanRecordSQL(playerUUID.toString());
            case "redis":
                return getBanRecordRedis(playerUUID.toString());
            case "mongodb":
            case "mongo":
                return getBanRecordMongo(playerUUID.toString());
        }
        return null;
    }

    private BanRecord getBanRecordSQL(String playerUUID) {
        try (PreparedStatement pstmt = sqlConnection.prepareStatement(
                "SELECT * FROM bans WHERE player_uuid = ? AND is_active = 1 ORDER BY ban_time DESC LIMIT 1")) {
            pstmt.setString(1, playerUUID);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return new BanRecord(
                            UUID.fromString(rs.getString("player_uuid")),
                            rs.getString("player_name"),
                            rs.getString("reason"),
                            rs.getString("banned_by"),
                            rs.getLong("ban_time"),
                            rs.getLong("expiry_time"),
                            rs.getString("server_name")
                    );
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("获取封禁记录SQL失败: " + e.getMessage());
        }
        return null;
    }

    private BanRecord getBanRecordRedis(String playerUUID) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "anticheat:ban:" + playerUUID;
            String data = jedis.get(key);
            if (data != null) {
                String[] parts = data.split("\\|");
                if (parts.length >= 7) {
                    return new BanRecord(
                            UUID.fromString(parts[0]),
                            parts[1],
                            parts[2],
                            parts[3],
                            Long.parseLong(parts[4]),
                            Long.parseLong(parts[5]),
                            parts[6]
                    );
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("获取封禁记录Redis失败: " + e.getMessage());
        }
        return null;
    }

    private BanRecord getBanRecordMongo(String playerUUID) {
        try {
            MongoCollection<Document> collection = mongoDatabase.getCollection("bans");
            Document filter = new Document("player_uuid", playerUUID).append("is_active", true);
            Document result = collection.find(filter).first();
            if (result != null) {
                return new BanRecord(
                        UUID.fromString(result.getString("player_uuid")),
                        result.getString("player_name"),
                        result.getString("reason"),
                        result.getString("banned_by"),
                        result.getLong("ban_time"),
                        result.getLong("expiry_time"),
                        result.getString("server_name")
                );
            }
        } catch (Exception e) {
            plugin.getLogger().severe("获取封禁记录MongoDB失败: " + e.getMessage());
        }
        return null;
    }

    public void unbanPlayer(UUID playerUUID) {
        new BukkitRunnable() {
            @Override
            public void run() {
                switch (databaseType) {
                    case "mysql":
                    case "h2":
                    case "sqlite":
                        unbanPlayerSQL(playerUUID.toString());
                        break;
                    case "redis":
                        unbanPlayerRedis(playerUUID.toString());
                        break;
                    case "mongodb":
                    case "mongo":
                        unbanPlayerMongo(playerUUID.toString());
                        break;
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    private void unbanPlayerSQL(String playerUUID) {
        try (PreparedStatement pstmt = sqlConnection.prepareStatement(
                "UPDATE bans SET is_active = 0 WHERE player_uuid = ?")) {
            pstmt.setString(1, playerUUID);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("解封玩家SQL失败: " + e.getMessage());
        }
    }

    private void unbanPlayerRedis(String playerUUID) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "anticheat:ban:" + playerUUID;
            jedis.del(key);
            String playerListKey = "anticheat:banned_players";
            jedis.srem(playerListKey, playerUUID);
        } catch (Exception e) {
            plugin.getLogger().severe("解封玩家Redis失败: " + e.getMessage());
        }
    }

    private void unbanPlayerMongo(String playerUUID) {
        try {
            MongoCollection<Document> collection = mongoDatabase.getCollection("bans");
            Document filter = new Document("player_uuid", playerUUID);
            Document update = new Document("$set", new Document("is_active", false));
            collection.updateMany(filter, update);
        } catch (Exception e) {
            plugin.getLogger().severe("解封玩家MongoDB失败: " + e.getMessage());
        }
    }

    public List<BanRecord> getAllBans() {
        List<BanRecord> bans = new ArrayList<BanRecord>();
        switch (databaseType) {
            case "mysql":
            case "h2":
            case "sqlite":
                return getAllBansSQL();
            case "redis":
                return getAllBansRedis();
            case "mongodb":
            case "mongo":
                return getAllBansMongo();
        }
        return bans;
    }

    private List<BanRecord> getAllBansSQL() {
        List<BanRecord> bans = new ArrayList<BanRecord>();
        try (Statement stmt = sqlConnection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM bans WHERE is_active = 1 ORDER BY ban_time DESC")) {
            while (rs.next()) {
                bans.add(new BanRecord(
                        UUID.fromString(rs.getString("player_uuid")),
                        rs.getString("player_name"),
                        rs.getString("reason"),
                        rs.getString("banned_by"),
                        rs.getLong("ban_time"),
                        rs.getLong("expiry_time"),
                        rs.getString("server_name")
                ));
            }
        } catch (Exception e) {
            plugin.getLogger().severe("获取所有封禁SQL失败: " + e.getMessage());
        }
        return bans;
    }

    private List<BanRecord> getAllBansRedis() {
        List<BanRecord> bans = new ArrayList<BanRecord>();
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> bannedPlayers = jedis.smembers("anticheat:banned_players");
            for (String playerUUID : bannedPlayers) {
                BanRecord record = getBanRecordRedis(playerUUID);
                if (record != null) {
                    bans.add(record);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("获取所有封禁Redis失败: " + e.getMessage());
        }
        return bans;
    }

    private List<BanRecord> getAllBansMongo() {
        List<BanRecord> bans = new ArrayList<BanRecord>();
        try {
            MongoCollection<Document> collection = mongoDatabase.getCollection("bans");
            Document filter = new Document("is_active", true);
            MongoCursor<Document> cursor = collection.find(filter).iterator();
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                bans.add(new BanRecord(
                        UUID.fromString(doc.getString("player_uuid")),
                        doc.getString("player_name"),
                        doc.getString("reason"),
                        doc.getString("banned_by"),
                        doc.getLong("ban_time"),
                        doc.getLong("expiry_time"),
                        doc.getString("server_name")
                ));
            }
        } catch (Exception e) {
            plugin.getLogger().severe("获取所有封禁MongoDB失败: " + e.getMessage());
        }
        return bans;
    }

    public String getDatabaseType() {
        return databaseType;
    }

    public void savePlayerProfile(PlayerProfile profile) {
        if (profile == null) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ObjectOutputStream oos = new ObjectOutputStream(baos);
                    oos.writeObject(profile);
                    oos.close();
                    String serialized = Base64.getEncoder().encodeToString(baos.toByteArray());

                    switch (databaseType) {
                        case "mysql":
                        case "h2":
                        case "sqlite":
                            saveProfileSQL(profile.getPlayerUUID().toString(), serialized);
                            break;
                        case "redis":
                            saveProfileRedis(profile.getPlayerUUID().toString(), serialized);
                            break;
                        case "mongodb":
                        case "mongo":
                            saveProfileMongo(profile.getPlayerUUID().toString(), serialized);
                            break;
                    }
                } catch (Exception e) {
                    plugin.getLogger().severe("保存玩家档案失败: " + e.getMessage());
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    private void saveProfileSQL(String playerUUID, String data) {
        try (PreparedStatement pstmt = sqlConnection.prepareStatement(
                "INSERT OR REPLACE INTO player_profiles (player_uuid, profile_data, last_updated) VALUES (?, ?, ?)")) {
            pstmt.setString(1, playerUUID);
            pstmt.setString(2, data);
            pstmt.setLong(3, System.currentTimeMillis());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("保存玩家档案SQL失败: " + e.getMessage());
        }
    }

    private void saveProfileRedis(String playerUUID, String data) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "anticheat:profile:" + playerUUID;
            jedis.set(key, data);
        } catch (Exception e) {
            plugin.getLogger().severe("保存玩家档案Redis失败: " + e.getMessage());
        }
    }

    private void saveProfileMongo(String playerUUID, String data) {
        try {
            MongoCollection<Document> collection = mongoDatabase.getCollection("player_profiles");
            Document filter = new Document("player_uuid", playerUUID);
            Document update = new Document("$set", new Document()
                    .append("player_uuid", playerUUID)
                    .append("profile_data", data)
                    .append("last_updated", System.currentTimeMillis()));
            collection.updateOne(filter, update, new com.mongodb.client.model.UpdateOptions().upsert(true));
        } catch (Exception e) {
            plugin.getLogger().severe("保存玩家档案MongoDB失败: " + e.getMessage());
        }
    }

    public PlayerProfile loadPlayerProfile(UUID playerUUID) {
        if (playerUUID == null) return null;

        try {
            switch (databaseType) {
                case "mysql":
                case "h2":
                case "sqlite":
                    return loadProfileSQL(playerUUID.toString());
                case "redis":
                    return loadProfileRedis(playerUUID.toString());
                case "mongodb":
                case "mongo":
                    return loadProfileMongo(playerUUID.toString());
            }
        } catch (Exception e) {
            plugin.getLogger().severe("加载玩家档案失败: " + e.getMessage());
        }
        return null;
    }

    private PlayerProfile loadProfileSQL(String playerUUID) {
        try (PreparedStatement pstmt = sqlConnection.prepareStatement(
                "SELECT profile_data FROM player_profiles WHERE player_uuid = ?")) {
            pstmt.setString(1, playerUUID);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String data = rs.getString("profile_data");
                    return deserializeProfile(data);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("加载玩家档案SQL失败: " + e.getMessage());
        }
        return null;
    }

    private PlayerProfile loadProfileRedis(String playerUUID) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "anticheat:profile:" + playerUUID;
            String data = jedis.get(key);
            if (data != null) {
                return deserializeProfile(data);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("加载玩家档案Redis失败: " + e.getMessage());
        }
        return null;
    }

    private PlayerProfile loadProfileMongo(String playerUUID) {
        try {
            MongoCollection<Document> collection = mongoDatabase.getCollection("player_profiles");
            Document filter = new Document("player_uuid", playerUUID);
            Document result = collection.find(filter).first();
            if (result != null) {
                String data = result.getString("profile_data");
                return deserializeProfile(data);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("加载玩家档案MongoDB失败: " + e.getMessage());
        }
        return null;
    }

    private PlayerProfile deserializeProfile(String data) {
        if (data == null || data.isEmpty()) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(data);
            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            ObjectInputStream ois = new ObjectInputStream(bais);
            PlayerProfile profile = (PlayerProfile) ois.readObject();
            ois.close();
            return profile;
        } catch (Exception e) {
            plugin.getLogger().severe("反序列化玩家档案失败: " + e.getMessage());
            return null;
        }
    }

    public void close() {
        try {
            if (sqlConnection != null && !sqlConnection.isClosed()) {
                sqlConnection.close();
            }
            if (jedisPool != null && !jedisPool.isClosed()) {
                jedisPool.close();
            }
            if (mongoClient != null) {
                mongoClient.close();
            }
        } catch (Exception e) {
            plugin.getLogger().severe("关闭数据库连接失败: " + e.getMessage());
        }
    }
}
