package com.anticheat.repositories.impl;

import com.anticheat.managers.BanManager.BanRecord;
import com.anticheat.repositories.DatabaseRepository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class RedisRepository implements DatabaseRepository {
    
    private final JedisPool jedisPool;
    
    public RedisRepository(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }
    
    @Override
    public void banPlayer(UUID playerUUID, String playerName, String reason, 
                         String bannedBy, long banTime, long expiryTime, String serverName) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "anticheat:ban:" + playerUUID.toString();
            String data = playerUUID.toString() + "|" + playerName + "|" + reason + "|" + 
                         bannedBy + "|" + banTime + "|" + expiryTime + "|" + serverName;
            if (expiryTime > 0) {
                long ttl = (expiryTime - System.currentTimeMillis()) / 1000;
                if (ttl > 0) {
                    jedis.setex(key, ttl, data);
                }
            } else {
                jedis.set(key, data);
            }
            String playerListKey = "anticheat:banned_players";
            jedis.sadd(playerListKey, playerUUID.toString());
        } catch (Exception e) {
            throw new RuntimeException("封禁玩家Redis失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public boolean isPlayerBanned(UUID playerUUID) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "anticheat:ban:" + playerUUID.toString();
            String data = jedis.get(key);
            if (data != null) {
                String[] parts = data.split("\\|");
                if (parts.length >= 6) {
                    long expiryTime = Long.parseLong(parts[5]);
                    if (expiryTime > 0 && expiryTime < System.currentTimeMillis()) {
                        unbanPlayer(playerUUID);
                        return false;
                    }
                    return true;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("检查封禁Redis失败: " + e.getMessage(), e);
        }
        return false;
    }
    
    @Override
    public BanRecord getBanRecord(UUID playerUUID) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "anticheat:ban:" + playerUUID.toString();
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
            throw new RuntimeException("获取封禁记录Redis失败: " + e.getMessage(), e);
        }
        return null;
    }
    
    @Override
    public void unbanPlayer(UUID playerUUID) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "anticheat:ban:" + playerUUID.toString();
            jedis.del(key);
            String playerListKey = "anticheat:banned_players";
            jedis.srem(playerListKey, playerUUID.toString());
        } catch (Exception e) {
            throw new RuntimeException("解封玩家Redis失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public List<BanRecord> getAllBans() {
        List<BanRecord> bans = new ArrayList<>();
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> bannedPlayers = jedis.smembers("anticheat:banned_players");
            for (String playerUUID : bannedPlayers) {
                BanRecord record = getBanRecord(UUID.fromString(playerUUID));
                if (record != null) {
                    bans.add(record);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("获取所有封禁Redis失败: " + e.getMessage(), e);
        }
        return bans;
    }
    
    @Override
    public void savePlayerProfile(UUID playerUUID, String serializedData) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "anticheat:profile:" + playerUUID.toString();
            jedis.set(key, serializedData);
        } catch (Exception e) {
            throw new RuntimeException("保存玩家档案Redis失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public String loadPlayerProfile(UUID playerUUID) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "anticheat:profile:" + playerUUID.toString();
            return jedis.get(key);
        } catch (Exception e) {
            throw new RuntimeException("加载玩家档案Redis失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void close() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
        }
    }
}