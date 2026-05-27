package com.anticheat.web;

import com.anticheat.AdvancedAntiCheat;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class StaticFileHandler implements HttpHandler {
    private final AdvancedAntiCheat plugin;
    
    public StaticFileHandler(AdvancedAntiCheat plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        
        if ("/".equals(path)) {
            path = "/index.html";
        }
        
        String contentType = getContentType(path);
        InputStream in = getClass().getClassLoader().getResourceAsStream("web" + path);
        
        if (in == null) {
            String notFound = "<html><body><h1>404 Not Found</h1></body></html>";
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(404, notFound.length());
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(notFound.getBytes(StandardCharsets.UTF_8));
            }
            return;
        }
        
        byte[] content;
        try {
            content = in.readAllBytes();
        } finally {
            in.close();
        }
        
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(content);
        }
    }
    
    private String getContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        return "text/plain; charset=utf-8";
    }
}
