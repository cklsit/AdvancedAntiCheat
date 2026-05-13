package com.anticheat.repositories;

import com.anticheat.managers.BanManager.BanRecord;
import java.util.List;
import java.util.UUID;

public interface DatabaseRepository {
    
    void banPlayer(UUID playerUUID, String playerName, String reason, 
                   String bannedBy, long banTime, long expiryTime, String serverName);
    
    boolean isPlayerBanned(UUID playerUUID);
    
    BanRecord getBanRecord(UUID playerUUID);
    
    void unbanPlayer(UUID playerUUID);
    
    List<BanRecord> getAllBans();
    
    void savePlayerProfile(UUID playerUUID, String serializedData);
    
    String loadPlayerProfile(UUID playerUUID);
    
    void close();
}