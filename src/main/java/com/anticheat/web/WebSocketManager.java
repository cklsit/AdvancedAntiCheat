package com.anticheat.web;

import com.anticheat.AdvancedAntiCheat;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebSocketManager implements HttpHandler {
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final Pattern KEY_PATTERN = Pattern.compile("Sec-WebSocket-Key: (.*)");
    
    private final AdvancedAntiCheat plugin;
    private final List<WebSocketConnection> connections = new CopyOnWriteArrayList<>();
    
    public WebSocketManager(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"websocket".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Upgrade"))) {
            exchange.sendResponseHeaders(400, 0);
            exchange.close();
            return;
        }
        
        String key = extractKey(exchange);
        if (key == null) {
            exchange.sendResponseHeaders(400, 0);
            exchange.close();
            return;
        }
        
        String acceptKey = generateAcceptKey(key);
        
        exchange.getResponseHeaders().set("Upgrade", "websocket");
        exchange.getResponseHeaders().set("Connection", "Upgrade");
        exchange.getResponseHeaders().set("Sec-WebSocket-Accept", acceptKey);
        exchange.sendResponseHeaders(101, 0);
        
        WebSocketConnection connection = new WebSocketConnection(plugin, exchange.getRequestBody(), exchange.getResponseBody());
        connections.add(connection);
        
        connection.start();
    }
    
    private String extractKey(HttpExchange exchange) {
        List<String> keyHeaders = exchange.getRequestHeaders().get("Sec-WebSocket-Key");
        if (keyHeaders != null && !keyHeaders.isEmpty()) {
            return keyHeaders.get(0);
        }
        
        for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
            Matcher matcher = KEY_PATTERN.matcher(entry.getKey() + ": " + String.join(", ", entry.getValue()));
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }
        return null;
    }
    
    private String generateAcceptKey(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update((key + WS_GUID).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
    
    public void broadcast(String message) {
        for (WebSocketConnection connection : connections) {
            connection.send(message);
        }
    }
    
    public void stop() {
        for (WebSocketConnection connection : connections) {
            connection.close();
        }
        connections.clear();
    }
}
