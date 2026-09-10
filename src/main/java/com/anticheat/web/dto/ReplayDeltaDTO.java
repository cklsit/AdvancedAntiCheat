package com.anticheat.web.dto;

import com.anticheat.managers.replay.TracePoint;
import com.anticheat.managers.replay.ViolationMarker;

import java.util.List;

/**
 * 增量拉取 DTO —— 返回上次拉取后新增的点和违规。
 */
public class ReplayDeltaDTO {
    public List<ReplaySessionDTO.Point> newPoints;      // timeOffsetMs > sinceMs 的点
    public List<ReplaySessionDTO.Violation> newViolations; // 上次拉取后新追加的违规

    public static class PointWrap {
        public long t;
        public double x, y, z;
        public float yaw, pitch;
        public boolean onGround;
        public String gameMode;
        public String mainHandItemId;
        public int[] blockHeights;
    }

    public static class ViolationWrap {
        public long t;
        public String type;
        public String level;
    }
}
