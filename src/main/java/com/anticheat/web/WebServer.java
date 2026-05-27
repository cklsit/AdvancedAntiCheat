package com.anticheat.web;

import com.anticheat.AdvancedAntiCheat;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public class WebServer {
    private final AdvancedAntiCheat plugin;
    private final int port;
    private HttpServer server;
    private final WebSocketManager webSocketManager;
    
    public WebServer(AdvancedAntiCheat plugin, int port) {
        this.plugin = plugin;
        this.port = port;
        this.webSocketManager = new WebSocketManager(plugin);
    }
    
    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            
            // 静态文件处理器
            server.createContext("/", new StaticFileHandler(plugin));
            server.createContext("/api", new ApiHandler(plugin, webSocketManager));
            server.createContext("/ws", webSocketManager);
            
            server.start();
            plugin.getLogger().info("§a[WebPanel] 反作弊指挥中心已启动，端口: " + port);
        } catch (IOException e) {
            plugin.getLogger().severe("§c[WebPanel] 无法启动 Web 服务器: " + e.getMessage());
        }
    }
    
    public void stop() {
        if (server != null) {
            server.stop(0);
            webSocketManager.stop();
            plugin.getLogger().info("§e[WebPanel] Web 服务器已关闭");
        }
    }
    
    public WebSocketManager getWebSocketManager() {
        return webSocketManager;
    }
}
