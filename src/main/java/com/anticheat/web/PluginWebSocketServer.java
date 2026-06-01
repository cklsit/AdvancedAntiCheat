package com.anticheat.web;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.profiles.PlayerProfile;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.entity.Player;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class PluginWebSocketServer {
    private final AdvancedAntiCheat plugin;
    private final int port;
    private WebSocketServer server;
    private final Set<WebSocket> clients = ConcurrentHashMap.newKeySet();
    private final Gson gson;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private static final int HEARTBEAT_INTERVAL = 30;

    public PluginWebSocketServer(AdvancedAntiCheat plugin, int port) {
        this.plugin = plugin;
        this.port = port;
        this.gson = new GsonBuilder().create();
    }

    public void start() {
        if (isRunning.get()) {
            return;
        }

        try {
            server = new WebSocketServer(new InetSocketAddress(port)) {
                @Override
                public void onOpen(WebSocket conn, ClientHandshake handshake) {
                    handleConnection(conn);
                }

                @Override
                public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                    handleDisconnection(conn);
                }

                @Override
                public void onMessage(WebSocket conn, String message) {
                    handleMessage(conn, message);
                }

                @Override
                public void onError(WebSocket conn, Exception ex) {
                    if (conn != null) {
                        plugin.getLogger().warning("[WebSocket] Connection error: " + ex.getMessage());
                        clients.remove(conn);
                    }
                }

                @Override
                public void onStart() {
                    plugin.getLogger().info("[WebSocket] Server started on port " + port);
                    isRunning.set(true);
                }
            };

            server.setReuseAddr(true);
            server.setConnectionLostTimeout(HEARTBEAT_INTERVAL);

            new Thread(() -> {
                try {
                    server.start();
                    startHeartbeat();
                    broadcastPluginStatus(true);
                } catch (RuntimeException e) {
                    plugin.getLogger().severe("[WebSocket] Failed to start server: " + e.getMessage());
                }
            }).start();

        } catch (RuntimeException e) {
            plugin.getLogger().severe("[WebSocket] Failed to start server: " + e.getMessage());
        }
    }

    public void stop() {
        if (!isRunning.get()) {
            return;
        }

        broadcastPluginStatus(false);

        if (server != null) {
            try {
                server.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                plugin.getLogger().severe("[WebSocket] Error stopping server: " + e.getMessage());
            } catch (RuntimeException e) {
                plugin.getLogger().severe("[WebSocket] Error stopping server: " + e.getMessage());
            }
        }

        scheduler.shutdown();
        clients.clear();
        isRunning.set(false);
        plugin.getLogger().info("[WebSocket] Server stopped");
    }

    private void handleConnection(WebSocket conn) {
        clients.add(conn);
        plugin.getLogger().info("[WebSocket] Client connected: " + conn.getRemoteSocketAddress());

        sendMessage(conn, createMessage("connected", new ConnectionResponse(
            "connected",
            plugin.getDescription().getVersion(),
            System.currentTimeMillis(),
            getConnectedClientsCount()
        )));

        sendPluginStatus(conn);
        sendOnlinePlayers(conn);
    }

    private void handleDisconnection(WebSocket conn) {
        clients.remove(conn);
        plugin.getLogger().info("[WebSocket] Client disconnected: " + conn.getRemoteSocketAddress());
    }

    private void handleMessage(WebSocket conn, String message) {
        try {
            Message msg = gson.fromJson(message, Message.class);
            if (msg == null || msg.type == null) {
                return;
            }

            switch (msg.type) {
                case "heartbeat":
                    sendMessage(conn, createMessage("heartbeat_ack", new HeartbeatAck(System.currentTimeMillis())));
                    break;

                case "ping":
                    sendMessage(conn, createMessage("pong", new Pong(System.currentTimeMillis())));
                    break;

                case "get_status":
                    sendPluginStatus(conn);
                    break;

                case "get_players":
                    sendOnlinePlayers(conn);
                    break;

                case "subscribe":
                    if (msg.data != null) {
                        handleSubscription(conn, msg.data);
                    }
                    break;

                default:
                    plugin.getLogger().warning("[WebSocket] Unknown message type: " + msg.type);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[WebSocket] Error parsing message: " + e.getMessage());
        }
    }

    private void handleSubscription(WebSocket conn, Object data) {
        Map<String, Object> subscription = convertToMap(data);
        String room = (String) subscription.get("room");
        if (room != null) {
            plugin.getLogger().info("[WebSocket] Client subscribed to: " + room);
        }
    }

    private void startHeartbeat() {
        scheduler.scheduleAtFixedRate(() -> {
            if (!isRunning.get()) {
                return;
            }

            long timestamp = System.currentTimeMillis();
            Iterator<WebSocket> iterator = clients.iterator();

            while (iterator.hasNext()) {
                WebSocket client = iterator.next();
                try {
                    if (!client.isOpen()) {
                        iterator.remove();
                        continue;
                    }
                    sendMessage(client, createMessage("heartbeat", new Heartbeat(timestamp)));
                } catch (Exception e) {
                    iterator.remove();
                }
            }
        }, HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL, TimeUnit.SECONDS);
    }

    public void broadcastPluginStatus(boolean enabled) {
        PluginStatus status = new PluginStatus(
            enabled,
            plugin.getDescription().getVersion(),
            System.currentTimeMillis(),
            plugin.getServer().getOnlinePlayers().size(),
            getClientsCount()
        );

        broadcast(createMessage("plugin_status", status));
    }

    public void broadcastAlert(AlertData alert) {
        broadcast(createMessage("alert", alert));
    }

    public void broadcastPlayerData(PlayerData data) {
        broadcast(createMessage("player_data", data));
    }

    public void broadcastViolation(ViolationData violation) {
        broadcast(createMessage("violation", violation));
    }

    public void sendPlayerJoin(Player player) {
        PlayerData data = createPlayerData(player);
        broadcast(createMessage("player_join", data));
    }

    public void sendPlayerQuit(Player player) {
        PlayerData data = createPlayerData(player);
        broadcast(createMessage("player_quit", data));
    }

    public void sendViolation(Player player, String violationType, String details) {
        double riskScore = 0;
        PlayerProfile profile = plugin.getProfileManager().getProfile(player);
        if (profile != null) {
            riskScore = profile.getRiskScore();
        }

        ViolationData data = new ViolationData(
            player.getUniqueId().toString(),
            player.getName(),
            violationType,
            details,
            System.currentTimeMillis(),
            (int) riskScore
        );
        broadcastViolation(data);
    }

    private void sendPluginStatus(WebSocket conn) {
        PluginStatus status = new PluginStatus(
            true,
            plugin.getDescription().getVersion(),
            System.currentTimeMillis(),
            plugin.getServer().getOnlinePlayers().size(),
            getClientsCount()
        );

        sendMessage(conn, createMessage("plugin_status", status));
    }

    private void sendOnlinePlayers(WebSocket conn) {
        List<PlayerData> players = new ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            players.add(createPlayerData(player));
        }

        sendMessage(conn, createMessage("online_players", new OnlinePlayers(
            players,
            System.currentTimeMillis()
        )));
    }

    private PlayerData createPlayerData(Player player) {
        double riskScore = 0;
        String riskLevel = "LOW";

        PlayerProfile profile = plugin.getProfileManager().getProfile(player);
        if (profile != null) {
            riskScore = profile.getRiskScore();
            riskLevel = getRiskLevelFromScore(riskScore);
        }

        return new PlayerData(
            player.getUniqueId().toString(),
            player.getName(),
            player.getLocation().getX(),
            player.getLocation().getY(),
            player.getLocation().getZ(),
            player.getLocation().getWorld() != null ? player.getLocation().getWorld().getName() : "unknown",
            player.getLocation().getYaw(),
            player.getLocation().getPitch(),
            (int) riskScore,
            riskLevel,
            System.currentTimeMillis()
        );
    }

    private String getRiskLevelFromScore(double score) {
        if (score >= 80) {
            return "CRITICAL";
        } else if (score >= 50) {
            return "HIGH";
        } else if (score >= 20) {
            return "MEDIUM";
        } else {
            return "LOW";
        }
    }

    private <T> void broadcast(Message<T> message) {
        String json = gson.toJson(message);
        for (WebSocket client : clients) {
            try {
                if (client.isOpen()) {
                    client.send(json);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[WebSocket] Error sending message: " + e.getMessage());
            }
        }
    }

    private <T> void sendMessage(WebSocket conn, Message<T> message) {
        try {
            if (conn.isOpen()) {
                conn.send(gson.toJson(message));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[WebSocket] Error sending message: " + e.getMessage());
        }
    }

    private <T> Message<T> createMessage(String type, T data) {
        return new Message<>(type, data, System.currentTimeMillis());
    }

    private Map<String, Object> convertToMap(Object obj) {
        if (obj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) obj;
            return map;
        }
        return new HashMap<>();
    }

    private int getClientsCount() {
        return clients.size();
    }

    private int getConnectedClientsCount() {
        return (int) clients.stream().filter(WebSocket::isOpen).count();
    }

    public boolean isRunning() {
        return isRunning.get();
    }

    public int getConnectedClients() {
        return getClientsCount();
    }

    public static class Message<T> {
        public String type;
        public T data;
        public long timestamp;

        public Message(String type, T data, long timestamp) {
            this.type = type;
            this.data = data;
            this.timestamp = timestamp;
        }
    }

    public static class ConnectionResponse {
        public String status;
        public String version;
        public long timestamp;
        public int clientsConnected;

        public ConnectionResponse(String status, String version, long timestamp, int clientsConnected) {
            this.status = status;
            this.version = version;
            this.timestamp = timestamp;
            this.clientsConnected = clientsConnected;
        }
    }

    public static class PluginStatus {
        public boolean enabled;
        public String version;
        public long timestamp;
        public int onlinePlayers;
        public int dashboardConnections;

        public PluginStatus(boolean enabled, String version, long timestamp, int onlinePlayers, int dashboardConnections) {
            this.enabled = enabled;
            this.version = version;
            this.timestamp = timestamp;
            this.onlinePlayers = onlinePlayers;
            this.dashboardConnections = dashboardConnections;
        }
    }

    public static class Heartbeat {
        public long timestamp;

        public Heartbeat(long timestamp) {
            this.timestamp = timestamp;
        }
    }

    public static class HeartbeatAck {
        public long timestamp;

        public HeartbeatAck(long timestamp) {
            this.timestamp = timestamp;
        }
    }

    public static class Pong {
        public long timestamp;

        public Pong(long timestamp) {
            this.timestamp = timestamp;
        }
    }

    public static class PlayerData {
        public String playerId;
        public String playerName;
        public double x;
        public double y;
        public double z;
        public String world;
        public float yaw;
        public float pitch;
        public int riskScore;
        public String riskLevel;
        public long timestamp;

        public PlayerData(String playerId, String playerName, double x, double y, double z,
                          String world, float yaw, float pitch, int riskScore, String riskLevel, long timestamp) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.world = world;
            this.yaw = yaw;
            this.pitch = pitch;
            this.riskScore = riskScore;
            this.riskLevel = riskLevel;
            this.timestamp = timestamp;
        }
    }

    public static class OnlinePlayers {
        public List<PlayerData> players;
        public long timestamp;

        public OnlinePlayers(List<PlayerData> players, long timestamp) {
            this.players = players;
            this.timestamp = timestamp;
        }
    }

    public static class AlertData {
        public String id;
        public String type;
        public String title;
        public String message;
        public String playerId;
        public String playerName;
        public long timestamp;
        public Map<String, Object> data;

        public AlertData(String id, String type, String title, String message,
                        String playerId, String playerName, long timestamp) {
            this.id = id;
            this.type = type;
            this.title = title;
            this.message = message;
            this.playerId = playerId;
            this.playerName = playerName;
            this.timestamp = timestamp;
        }
    }

    public static class ViolationData {
        public String playerId;
        public String playerName;
        public String violationType;
        public String details;
        public long timestamp;
        public int riskScore;

        public ViolationData(String playerId, String playerName, String violationType,
                            String details, long timestamp, int riskScore) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.violationType = violationType;
            this.details = details;
            this.timestamp = timestamp;
            this.riskScore = riskScore;
        }
    }
}