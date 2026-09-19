package com.anticheat.repositories.impl;

import com.anticheat.managers.BanManager.BanRecord;
import com.anticheat.managers.audit.AuditQuery;
import com.anticheat.managers.audit.AuditRecord;
import com.anticheat.repositories.DatabaseRepository;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MongoRepository implements DatabaseRepository {
    
    private final com.mongodb.client.MongoDatabase mongoDatabase;
    /**
     * 创建 {@link #mongoDatabase} 的客户端；由外部直接注入 database 时为 null。
     * 持有它是为了让 {@link #close()} 能真正释放连接池。
     */
    private final com.mongodb.client.MongoClient mongoClient;
    
    public MongoRepository(com.mongodb.client.MongoDatabase mongoDatabase) {
        this(mongoDatabase, null);
    }

    public MongoRepository(com.mongodb.client.MongoDatabase mongoDatabase,
                           com.mongodb.client.MongoClient mongoClient) {
        this.mongoDatabase = mongoDatabase;
        this.mongoClient = mongoClient;
    }
    
    @Override
    public void banPlayer(UUID playerUUID, String playerName, String reason, 
                         String bannedBy, long banTime, long expiryTime, String serverName) {
        try {
            MongoCollection<Document> collection = mongoDatabase.getCollection("bans");
            Document document = new Document()
                    .append("player_uuid", playerUUID.toString())
                    .append("player_name", playerName)
                    .append("reason", reason)
                    .append("banned_by", bannedBy)
                    .append("ban_time", banTime)
                    .append("expiry_time", expiryTime)
                    .append("server_name", serverName)
                    .append("is_active", true);
            collection.insertOne(document);
        } catch (Exception e) {
            throw new RuntimeException("封禁玩家MongoDB失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public boolean isPlayerBanned(UUID playerUUID) {
        try {
            MongoCollection<Document> collection = mongoDatabase.getCollection("bans");
            Document filter = new Document("player_uuid", playerUUID.toString())
                                   .append("is_active", true);
            Document result = collection.find(filter).first();
            if (result != null) {
                long expiryTime = result.getLong("expiry_time");
                if (expiryTime > 0 && expiryTime < System.currentTimeMillis()) {
                    unbanPlayer(playerUUID);
                    return false;
                }
                return true;
            }
        } catch (Exception e) {
            throw new RuntimeException("检查封禁MongoDB失败: " + e.getMessage(), e);
        }
        return false;
    }
    
    @Override
    public BanRecord getBanRecord(UUID playerUUID) {
        try {
            MongoCollection<Document> collection = mongoDatabase.getCollection("bans");
            Document filter = new Document("player_uuid", playerUUID.toString())
                                   .append("is_active", true);
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
            throw new RuntimeException("获取封禁记录MongoDB失败: " + e.getMessage(), e);
        }
        return null;
    }
    
    @Override
    public void unbanPlayer(UUID playerUUID) {
        try {
            MongoCollection<Document> collection = mongoDatabase.getCollection("bans");
            Document filter = new Document("player_uuid", playerUUID.toString());
            Document update = new Document("$set", new Document("is_active", false));
            collection.updateMany(filter, update);
        } catch (Exception e) {
            throw new RuntimeException("解封玩家MongoDB失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public List<BanRecord> getAllBans() {
        List<BanRecord> bans = new ArrayList<>();
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
            throw new RuntimeException("获取所有封禁MongoDB失败: " + e.getMessage(), e);
        }
        return bans;
    }
    
    @Override
    public void savePlayerProfile(UUID playerUUID, String serializedData) {
        try {
            MongoCollection<Document> collection = mongoDatabase.getCollection("player_profiles");
            Document filter = new Document("player_uuid", playerUUID.toString());
            Document update = new Document("$set", new Document()
                    .append("player_uuid", playerUUID.toString())
                    .append("profile_data", serializedData)
                    .append("last_updated", System.currentTimeMillis()));
            collection.updateOne(filter, update, new UpdateOptions().upsert(true));
        } catch (Exception e) {
            throw new RuntimeException("保存玩家档案MongoDB失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public String loadPlayerProfile(UUID playerUUID) {
        try {
            MongoCollection<Document> collection = mongoDatabase.getCollection("player_profiles");
            Document filter = new Document("player_uuid", playerUUID.toString());
            Document result = collection.find(filter).first();
            if (result != null) {
                return result.getString("profile_data");
            }
        } catch (Exception e) {
            throw new RuntimeException("加载玩家档案MongoDB失败: " + e.getMessage(), e);
        }
        return null;
    }

    // ===== 审计日志 =====

    @Override
    public void ensureAuditTable() {
        // MongoDB 无 schema，集合按写入时自动创建
        try {
            mongoDatabase.getCollection("audit_logs");
        } catch (Exception ignored) {
        }
    }

    @Override
    public void saveAudit(AuditRecord audit) {
        try {
            MongoCollection<Document> collection = mongoDatabase.getCollection("audit_logs");
            Document doc = new Document()
                    .append("timestamp", audit.getTimestamp())
                    .append("operator", audit.getOperator())
                    .append("operator_role", audit.getOperatorRole())
                    .append("type", audit.getType())
                    .append("target", audit.getTarget())
                    .append("ip", audit.getIp())
                    .append("result", audit.getResult())
                    .append("detail", audit.getDetail());
            collection.insertOne(doc);
            if (audit.getId() == null && doc.get("_id") != null) {
                audit.setId(((Number) doc.get("_id")).longValue());
            }
        } catch (Exception e) {
            throw new RuntimeException("保存审计记录MongoDB失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<AuditRecord> queryAudits(AuditQuery query) {
        List<AuditRecord> list = new ArrayList<>();
        try {
            MongoCollection<Document> collection = mongoDatabase.getCollection("audit_logs");
            Bson filter = buildMongoFilter(query);
            int skip = (query.getPage() - 1) * query.getPageSize();
            try (MongoCursor<Document> cursor = collection.find(filter)
                    .sort(new Document("timestamp", -1))
                    .skip(skip)
                    .limit(query.getPageSize())
                    .iterator()) {
                while (cursor.hasNext()) {
                    list.add(mapDocument(cursor.next()));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("查询审计记录MongoDB失败: " + e.getMessage(), e);
        }
        return list;
    }

    @Override
    public long countAudits(AuditQuery query) {
        try {
            MongoCollection<Document> collection = mongoDatabase.getCollection("audit_logs");
            return collection.countDocuments(buildMongoFilter(query));
        } catch (Exception e) {
            throw new RuntimeException("统计审计记录MongoDB失败: " + e.getMessage(), e);
        }
    }

    private Bson buildMongoFilter(AuditQuery q) {
        List<Bson> filters = new ArrayList<>();
        if (q == null) {
            return new Document();
        }
        if (q.getType() != null && !q.getType().isEmpty()) {
            filters.add(Filters.eq("type", q.getType()));
        }
        if (q.getResult() != null && !q.getResult().isEmpty()) {
            filters.add(Filters.eq("result", q.getResult()));
        }
        if (q.getStartTime() != null) {
            filters.add(Filters.gte("timestamp", q.getStartTime()));
        }
        if (q.getEndTime() != null) {
            filters.add(Filters.lte("timestamp", q.getEndTime()));
        }
        if (q.getKeyword() != null && !q.getKeyword().isEmpty()) {
            Bson kw = Filters.or(
                    Filters.regex("operator", q.getKeyword(), "i"),
                    Filters.regex("target", q.getKeyword(), "i"),
                    Filters.regex("detail", q.getKeyword(), "i")
            );
            filters.add(kw);
        }
        return filters.isEmpty() ? new Document() : Filters.and(filters);
    }

    private AuditRecord mapDocument(Document d) {
        AuditRecord r = new AuditRecord();
        Object id = d.get("_id");
        if (id instanceof Number) {
            r.setId(((Number) id).longValue());
        }
        r.setTimestamp(d.getLong("timestamp") == null ? 0 : d.getLong("timestamp"));
        r.setOperator(d.getString("operator"));
        Object role = d.get("operator_role");
        if (role instanceof Number) {
            r.setOperatorRole(((Number) role).intValue());
        }
        r.setType(d.getString("type"));
        r.setTarget(d.getString("target"));
        r.setIp(d.getString("ip"));
        r.setResult(d.getString("result"));
        r.setDetail(d.getString("detail"));
        return r;
    }

    /**
     * 关闭底层 MongoClient，释放连接池。
     *
     * <p>此前是空实现，而 MongoClient 在 DatabaseManager 里被创建成局部变量后引用即丢失，
     * 导致插件卸载时连接永远不释放（重载/重启会持续泄漏连接）。</p>
     *
     * <p>不向上抛异常：调用方 DatabaseManager.close() 已有一层 catch，且卸载路径上
     * 释放失败没有可恢复动作（本类不持有 plugin，无法记日志）。</p>
     */
    @Override
    public void close() {
        if (mongoClient == null) {
            return; // database 由外部注入，客户端生命周期不归本类管理
        }
        try {
            mongoClient.close();
        } catch (Exception e) {
            // 连接已失效时 close 抛错属预期情形，忽略
        }
    }
}