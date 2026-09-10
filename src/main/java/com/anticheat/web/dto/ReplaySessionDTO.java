package com.anticheat.web.dto;

import com.anticheat.managers.replay.TracePoint;
import com.anticheat.managers.replay.ViolationMarker;

import java.util.List;

/**
 * 单个玩家完整 session 快照 DTO（实时回放数据）。
 * points 中 t = TracePoint.timeOffsetMs
 * violations 中 t = ViolationMarker.pointIndex 对应 TracePoint.timeOffsetMs
 */
public class ReplaySessionDTO {
    public String playerUuid;
    public String playerName;
    public long startTime;     // epoch ms
    public long endTime;       // epoch ms（当前快照时刻）
    public List<Point> points; // t = timeOffsetMs
    public List<Violation> violations;

    public static class Point {
        public long t;           // timeOffsetMs
        public double x, y, z;
        public float yaw, pitch;
        public boolean onGround;
        public String gameMode;
        public String mainHandItemId;
        public int[] blockHeights;
    }

    public static class Violation {
        public long t;     // 对应 TracePoint.timeOffsetMs
        public String type;
        public String level;
    }

    /** 从 TracePoint 构造 Point 的便捷方法。 */
    public static Point fromTracePoint(TracePoint tp) {
        Point p = new Point();
        p.t = tp.getTimeOffsetMs();
        p.x = tp.getX();
        p.y = tp.getY();
        p.z = tp.getZ();
        p.yaw = tp.getYaw();
        p.pitch = tp.getPitch();
        p.onGround = tp.isOnGround();
        p.gameMode = tp.getGameMode();
        p.mainHandItemId = tp.getMainHandItemId();
        p.blockHeights = tp.getBlockHeights();
        return p;
    }

    /** 从 ViolationMarker + TracePoint(对应 pointIndex) 构造 Violation。 */
    public static Violation fromMarker(ViolationMarker m, TracePoint correspondingPoint) {
        Violation v = new Violation();
        v.type = m.type;
        v.level = m.level;
        v.t = 0;
        if (correspondingPoint != null) {
            v.t = correspondingPoint.getTimeOffsetMs();
        }
        return v;
    }
}
