package com.anticheat.managers;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.profiles.PlayerProfile;
import com.anticheat.repositories.DatabaseRepository;
import com.anticheat.repositories.impl.MongoRepository;
import com.anticheat.repositories.impl.RedisRepository;
import com.anticheat.repositories.impl.SQLRepository;
import com.anticheat.utils.ProfileSerializer;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bukkit.scheduler.BukkitRunnable;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DatabaseManager {
    
    private final AdvancedAntiCheat plugin;
    private final String databaseType;
    private final DatabaseRepository repository;
    
    public DatabaseManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
        this.databaseType = plugin.getConfig().getString("database.type", "sqlite").toLowerCase();
        this.repository = createRepository();
        initializeTables();
    }
    
    private DatabaseRepository createRepository() {
        switch (databaseType) {
            case "mysql":
                return new SQLRepository(createMySQLConnection());
            case "h2":
                return new SQLRepository(createH2Connection());
            case "redis":
                return new RedisRepository(createRedisPool());
            case "mongodb":
            case "mongo":
                return new MongoRepository(createMongoDatabase());
            case "sqlite":
            default:
                return new SQLRepository(createSQLiteConnection());
        }
    }
    
    private Connection createSQLiteConnection() {
        try {
            Class.forName("org.sqlite.JDBC");
            String path = new File(plugin.getDataFolder(), "anticheat.db").getAbsolutePath();
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
            plugin.getLogger().info("SQLite数据库连接成功！");
            return connection;
        } catch (Exception e) {
            throw new RuntimeException("SQLite数据库连接失败: " + e.getMessage(), e);
        }
    }
    
    private Connection createH2Connection() {
        try {
            Class.forName("org.h2.Driver");
            String path = new File(plugin.getDataFolder(), "anticheat").getAbsolutePath();
            Connection connection = DriverManager.getConnection(
                    "jdbc:h2:file:" + path + ";MODE=MySQL", "sa", "");
            plugin.getLogger().info("H2数据库连接成功！");
            return connection;
        } catch (Exception e) {
            throw new RuntimeException("H2数据库连接失败: " + e.getMessage(), e);
        }
    }
    
    private Connection createMySQLConnection() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            String host = plugin.getConfig().getString("database.mysql.host", "localhost");
            int port = plugin.getConfig().getInt("database.mysql.port", 3306);
            String database = plugin.getConfig().getString("database.mysql.database", "anticheat");
            String username = plugin.getConfig().getString("database.mysql.username", "root");
            String password = plugin.getConfig().getString("database.mysql.password", "");
            String url = "jdbc:mysql://" + host + ":" + port + "/" + database + 
                        "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
            Connection connection = DriverManager.getConnection(url, username, password);
            plugin.getLogger().info("MySQL数据库连接成功！");
            return connection;
        } catch (Exception e) {
            throw new RuntimeException("MySQL数据库连接失败: " + e.getMessage(), e);
        }
    }
    
    private JedisPool createRedisPool() {
        try {
            String host = plugin.getConfig().getString("database.redis.host", "localhost");
            int port = plugin.getConfig().getInt("database.redis.port", 6379);
            String password = plugin.getConfig().getString("database.redis.password", null);
            
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(10);
            poolConfig.setMaxIdle(5);
            
            JedisPool jedisPool = new JedisPool(poolConfig, host, port, 0, password);
            
            try (redis.clients.jedis.Jedis jedis = jedisPool.getResource()) {
                jedis.ping();
            }
            
            plugin.getLogger().info("Redis数据库连接成功！");
            return jedisPool;
        } catch (Exception e) {
            throw new RuntimeException("Redis数据库连接失败: " + e.getMessage(), e);
        }
    }
    
    private MongoDatabase createMongoDatabase() {
        try {
            String host = plugin.getConfig().getString("database.mongodb.host", "localhost");
            int port = plugin.getConfig().getInt("database.mongodb.port", 27017);
            String database = plugin.getConfig().getString("database.mongodb.database", "anticheat");
            String uri = "mongodb://" + host + ":" + port;
            
            MongoClient mongoClient = MongoClients.create(uri);
            MongoDatabase mongoDatabase = mongoClient.getDatabase(database);
            
            plugin.getLogger().info("MongoDB数据库连接成功！");
            return mongoDatabase;
        } catch (Exception e) {
            throw new RuntimeException("MongoDB数据库连接失败: " + e.getMessage(), e);
        }
    }
    
    private void initializeTables() {
        if (repository instanceof SQLRepository) {
            try {
                SQLRepository sqlRepo = (SQLRepository) repository;
                Connection connection = sqlRepo.getConnection();
                
                try (Statement stmt = connection.createStatement()) {
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
                }
            } catch (SQLException e) {
                throw new RuntimeException("创建数据库表失败: " + e.getMessage(), e);
            }
        }
    }
    
    public void banPlayer(UUID playerUUID, String playerName, String reason, 
                         String bannedBy, long banTime, long expiryTime, String serverName) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    repository.banPlayer(playerUUID, playerName, reason, bannedBy, 
                                        banTime, expiryTime, serverName);
                } catch (Exception e) {
                    plugin.getLogger().severe("封禁玩家失败: " + e.getMessage());
                }
            }
        }.runTaskAsynchronously(plugin);
    }
    
    public boolean isPlayerBanned(UUID playerUUID) {
        try {
            return repository.isPlayerBanned(playerUUID);
        } catch (Exception e) {
            plugin.getLogger().severe("检查封禁失败: " + e.getMessage());
            return false;
        }
    }
    
    public BanManager.BanRecord getBanRecord(UUID playerUUID) {
        try {
            return repository.getBanRecord(playerUUID);
        } catch (Exception e) {
            plugin.getLogger().severe("获取封禁记录失败: " + e.getMessage());
            return null;
        }
    }
    
    public void unbanPlayer(UUID playerUUID) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    repository.unbanPlayer(playerUUID);
                } catch (Exception e) {
                    plugin.getLogger().severe("解封玩家失败: " + e.getMessage());
                }
            }
        }.runTaskAsynchronously(plugin);
    }
    
    public List<BanManager.BanRecord> getAllBans() {
        try {
            return repository.getAllBans();
        } catch (Exception e) {
            plugin.getLogger().severe("获取所有封禁失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }
    
    public void savePlayerProfile(PlayerProfile profile) {
        if (profile == null) return;
        
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    String serialized = ProfileSerializer.serialize(profile);
                    repository.savePlayerProfile(profile.getPlayerUUID(), serialized);
                } catch (Exception e) {
                    plugin.getLogger().severe("保存玩家档案失败: " + e.getMessage());
                }
            }
        }.runTaskAsynchronously(plugin);
    }
    
    public PlayerProfile loadPlayerProfile(UUID playerUUID) {
        if (playerUUID == null) return null;
        
        try {
            String serialized = repository.loadPlayerProfile(playerUUID);
            if (serialized != null) {
                return ProfileSerializer.deserialize(serialized);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("加载玩家档案失败: " + e.getMessage());
        }
        return null;
    }
    
    public String getDatabaseType() {
        return databaseType;
    }
    
    public void close() {
        try {
            repository.close();
        } catch (Exception e) {
            plugin.getLogger().severe("关闭数据库连接失败: " + e.getMessage());
        }
    }
}