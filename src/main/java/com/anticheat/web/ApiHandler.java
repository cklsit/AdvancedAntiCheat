package com.anticheat.web;

import com.anticheat.AdvancedAntiCheat;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class ApiHandler implements HttpHandler {
    private final AdvancedAntiCheat plugin;
    private final WebSocketManager webSocketManager;
    
    public ApiHandler(AdvancedAntiCheat plugin, WebSocketManager webSocketManager) {
        this.plugin = plugin;
        this.webSocketManager = webSocketManager;
    }
    
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        
        if ("GET".equals(method)) {
            handleGet(exchange, path);
        } else if ("POST".equals(method)) {
            handlePost(exchange, path);
        } else {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
        }
    }
    
    private void handleGet(HttpExchange exchange, String path) throws IOException {
        if ("/api/dashboard".equals(path)) {
            sendResponse(exchange, 200, getDashboardData());
        } else if ("/api/players".equals(path)) {
            sendResponse(exchange, 200, getPlayersData());
        } else if ("/api/events".equals(path)) {
            sendResponse(exchange, 200, getRecentEvents());
        } else if ("/api/config".equals(path)) {
            sendResponse(exchange, 200, getConfigData());
        } else {
            sendResponse(exchange, 404, "{\"error\":\"Not found\"}");
        }
    }
    
    private void handlePost(HttpExchange exchange, String path) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        
        if ("/api/ban".equals(path)) {
            sendResponse(exchange, 200, handleBan(body));
        } else if ("/api/kick".equals(path)) {
            sendResponse(exchange, 200, handleKick(body));
        } else if ("/api/broadcast".equals(path)) {
            sendResponse(exchange, 200, handleBroadcast(body));
        } else {
            sendResponse(exchange, 404, "{\"error\":\"Not found\"}");
        }
    }
    
    private String getDashboardData() {
        int onlinePlayers = Bukkit.getOnlinePlayers().size();
        int suspectPlayers = 0;
        int todayIntercepts = 0;
        double captchaSuccessRate = 0.85;
        int activeCases = 3;
        
        return String.format("{" +
            "\"onlinePlayers\": %d," +
            "\"suspectPlayers\": %d," +
            "\"todayIntercepts\": %d," +
            "\"captchaSuccessRate\": %.2f," +
            "\"activeCases\": %d," +
            "\"riskLevel\": %d," +
            "\"riskTrend\": [65, 58, 72, 81, 69, 75, 70]," +
            "\"serverName\": \"%s\"" +
            "}", onlinePlayers, suspectPlayers, todayIntercepts, 
                captchaSuccessRate, activeCases, calculateRiskLevel(),
                plugin.getServer().getName());
    }
    
    private int calculateRiskLevel() {
        return 65 + (int) (Math.random() * 20);
    }
    
    private String getPlayersData() {
        StringBuilder players = new StringBuilder("[");
        boolean first = true;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!first) players.append(",");
            first = false;
            int riskScore = 20 + (int) (Math.random() * 60);
            players.append(String.format("{" +
                "\"name\": \"%s\"," +
                "\"uuid\": \"%s\"," +
                "\"ping\": %d," +
                "\"client\": \"Vanilla\"," +
                "\"riskScore\": %d," +
                "\"onlineTime\": \"%dh %dm\"," +
                "\"riskLevel\": \"%s\"" +
                "}", player.getName(), player.getUniqueId().toString(),
                player.getPing(), riskScore,
                1, 30,
                riskScore < 30 ? "low" : riskScore < 60 ? "medium" : "high"));
        }
        players.append("]");
        return players.toString();
    }
    
    private String getRecentEvents() {
        return "[" +
            "{" +
                "\"time\": \"14:35:22\"," +
                "\"type\": \"warning\"," +
                "\"player\": \"Steve\"," +
                "\"module\": \"Speed\"," +
                "\"score\": 45," +
                "\"location\": \"123, 64, 456\"" +
            "}," +
            "{" +
                "\"time\": \"14:34:18\"," +
                "\"type\": \"high\"," +
                "\"player\": \"Alex\"," +
                "\"module\": \"KillAura\"," +
                "\"score\": 89," +
                "\"location\": \"789, 64, 123\"" +
            "}," +
            "{" +
                "\"time\": \"14:33:55\"," +
                "\"type\": \"success\"," +
                "\"player\": \"Notch\"," +
                "\"module\": \"Captcha\"," +
                "\"score\": 100," +
                "\"location\": \"456, 64, 789\"" +
            "}" +
        "]";
    }
    
    private String getConfigData() {
        return "{" +
            "\"modules\": {" +
                "\"fly\": {\"enabled\": true, \"sensitivity\": 7}," +
                "\"speed\": {\"enabled\": true, \"sensitivity\": 6}," +
                "\"killAura\": {\"enabled\": true, \"sensitivity\": 8}," +
                "\"reach\": {\"enabled\": true, \"sensitivity\": 5}," +
                "\"esp\": {\"enabled\": true, \"sensitivity\": 7}," +
                "\"fastBreak\": {\"enabled\": true, \"sensitivity\": 6}," +
                "\"scaffold\": {\"enabled\": true, \"sensitivity\": 7}," +
                "\"noSlow\": {\"enabled\": true, \"sensitivity\": 5}" +
            "}" +
        "}";
    }
    
    private String handleBan(String body) {
        return "{\"success\":true,\"message\":\"Ban command received\"}";
    }
    
    private String handleKick(String body) {
        return "{\"success\":true,\"message\":\"Kick command received\"}";
    }
    
    private String handleBroadcast(String body) {
        return "{\"success\":true,\"message\":\"Broadcast command received\"}";
    }
    
    private void sendResponse(HttpExchange exchange, int code, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(code, response.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response.getBytes(StandardCharsets.UTF_8));
        }
    }
}
