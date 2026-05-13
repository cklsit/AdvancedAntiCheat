package com.anticheat.repositories.impl;

import com.anticheat.managers.BanManager.BanRecord;
import com.anticheat.repositories.DatabaseRepository;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SQLRepository implements DatabaseRepository {
    
    private final Connection connection;
    
    public SQLRepository(Connection connection) {
        this.connection = connection;
    }
    
    public Connection getConnection() {
        return connection;
    }
    
    @Override
    public void banPlayer(UUID playerUUID, String playerName, String reason, 
                         String bannedBy, long banTime, long expiryTime, String serverName) {
        try (PreparedStatement pstmt = connection.prepareStatement(
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
            throw new RuntimeException("封禁玩家SQL失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public boolean isPlayerBanned(UUID playerUUID) {
        try (PreparedStatement pstmt = connection.prepareStatement(
                "SELECT expiry_time FROM bans WHERE player_uuid = ? AND is_active = 1 ORDER BY ban_time DESC LIMIT 1")) {
            pstmt.setString(1, playerUUID.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    long expiryTime = rs.getLong("expiry_time");
                    if (expiryTime > 0 && expiryTime < System.currentTimeMillis()) {
                        unbanPlayer(playerUUID);
                        return false;
                    }
                    return true;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("检查封禁SQL失败: " + e.getMessage(), e);
        }
        return false;
    }
    
    @Override
    public BanRecord getBanRecord(UUID playerUUID) {
        try (PreparedStatement pstmt = connection.prepareStatement(
                "SELECT * FROM bans WHERE player_uuid = ? AND is_active = 1 ORDER BY ban_time DESC LIMIT 1")) {
            pstmt.setString(1, playerUUID.toString());
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
            throw new RuntimeException("获取封禁记录SQL失败: " + e.getMessage(), e);
        }
        return null;
    }
    
    @Override
    public void unbanPlayer(UUID playerUUID) {
        try (PreparedStatement pstmt = connection.prepareStatement(
                "UPDATE bans SET is_active = 0 WHERE player_uuid = ?")) {
            pstmt.setString(1, playerUUID.toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("解封玩家SQL失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public List<BanRecord> getAllBans() {
        List<BanRecord> bans = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
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
            throw new RuntimeException("获取所有封禁SQL失败: " + e.getMessage(), e);
        }
        return bans;
    }
    
    @Override
    public void savePlayerProfile(UUID playerUUID, String serializedData) {
        try (PreparedStatement pstmt = connection.prepareStatement(
                "INSERT OR REPLACE INTO player_profiles (player_uuid, profile_data, last_updated) VALUES (?, ?, ?)")) {
            pstmt.setString(1, playerUUID.toString());
            pstmt.setString(2, serializedData);
            pstmt.setLong(3, System.currentTimeMillis());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("保存玩家档案SQL失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public String loadPlayerProfile(UUID playerUUID) {
        try (PreparedStatement pstmt = connection.prepareStatement(
                "SELECT profile_data FROM player_profiles WHERE player_uuid = ?")) {
            pstmt.setString(1, playerUUID.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("profile_data");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("加载玩家档案SQL失败: " + e.getMessage(), e);
        }
        return null;
    }
    
    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            throw new RuntimeException("关闭SQL数据库连接失败: " + e.getMessage(), e);
        }
    }
}