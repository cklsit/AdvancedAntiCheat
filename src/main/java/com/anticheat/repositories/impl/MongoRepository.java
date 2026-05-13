package com.anticheat.repositories.impl;

import com.anticheat.managers.BanManager.BanRecord;
import com.anticheat.repositories.DatabaseRepository;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MongoRepository implements DatabaseRepository {
    
    private final com.mongodb.client.MongoDatabase mongoDatabase;
    
    public MongoRepository(com.mongodb.client.MongoDatabase mongoDatabase) {
        this.mongoDatabase = mongoDatabase;
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
    
    @Override
    public void close() {
    }
}