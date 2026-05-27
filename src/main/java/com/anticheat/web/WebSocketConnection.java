package com.anticheat.web;

import com.anticheat.AdvancedAntiCheat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public class WebSocketConnection extends Thread {
    private final AdvancedAntiCheat plugin;
    private final InputStream in;
    private final OutputStream out;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
    public WebSocketConnection(AdvancedAntiCheat plugin, InputStream in, OutputStream out) {
        this.plugin = plugin;
        this.in = in;
        this.out = out;
    }
    
    @Override
    public void run() {
        try {
            while (running.get()) {
                readFrame();
            }
        } catch (IOException e) {
            if (running.get()) {
                plugin.getLogger().warning("WebSocket 连接异常: " + e.getMessage());
            }
        } finally {
            close();
        }
    }
    
    private void readFrame() throws IOException {
        int firstByte = in.read();
        if (firstByte == -1) {
            running.set(false);
            return;
        }
        
        int secondByte = in.read();
        boolean masked = (secondByte & 0x80) != 0;
        int opcode = firstByte & 0x0F;
        
        if (opcode == 0x8) { // Close frame
            running.set(false);
            return;
        }
        
        long payloadLength = secondByte & 0x7F;
        if (payloadLength == 126) {
            byte[] lenBytes = new byte[2];
            in.read(lenBytes);
            payloadLength = ByteBuffer.wrap(lenBytes).getShort() & 0xFFFF;
        } else if (payloadLength == 127) {
            byte[] lenBytes = new byte[8];
            in.read(lenBytes);
            payloadLength = ByteBuffer.wrap(lenBytes).getLong();
        }
        
        byte[] mask = new byte[4];
        if (masked) {
            in.read(mask);
        }
        
        byte[] payload = new byte[(int) payloadLength];
        in.read(payload);
        
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i % 4];
            }
        }
        
        if (opcode == 0x1) { // Text frame
            handleMessage(new String(payload, StandardCharsets.UTF_8));
        }
    }
    
    private void handleMessage(String message) {
        plugin.getLogger().info("WebSocket 收到消息: " + message);
    }
    
    public void send(String message) {
        if (closed.get() || !running.get()) {
            return;
        }

        try {
            byte[] data = message.getBytes(StandardCharsets.UTF_8);
            byte[] frame = createFrame(data);
            synchronized (out) {
                if (closed.get()) return;
                out.write(frame);
                out.flush();
            }
        } catch (IOException e) {
            plugin.getLogger().warning("发送 WebSocket 消息失败: " + e.getMessage());
            close();
        }
    }
    
    private byte[] createFrame(byte[] data) {
        int frameLength = 2;
        if (data.length > 125) {
            frameLength += data.length <= 0xFFFF ? 2 : 8;
        }
        frameLength += data.length;
        
        byte[] frame = new byte[frameLength];
        int index = 0;
        
        frame[index++] = (byte) 0x81;
        
        if (data.length <= 125) {
            frame[index++] = (byte) data.length;
        } else if (data.length <= 0xFFFF) {
            frame[index++] = (byte) 126;
            frame[index++] = (byte) ((data.length >> 8) & 0xFF);
            frame[index++] = (byte) (data.length & 0xFF);
        } else {
            frame[index++] = (byte) 127;
            for (int i = 7; i >= 0; i--) {
                frame[index++] = (byte) ((data.length >> (i * 8)) & 0xFF);
            }
        }
        
        System.arraycopy(data, 0, frame, index, data.length);
        return frame;
    }
    
    public void close() {
        if (closed.compareAndSet(false, true)) {
            running.set(false);
            try {
                out.close();
            } catch (IOException e) {
                // Ignore
            }
            try {
                in.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    public boolean isClosed() {
        return closed.get();
    }
}
