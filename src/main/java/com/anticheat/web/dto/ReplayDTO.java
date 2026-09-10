package com.anticheat.web.dto;

import java.util.List;

/**
 * 违规回放 DTO。用于 Web API 序列化输出。
 */
public class ReplayDTO {

    public long id;
    public String playerName;
    public String uuid;
    public String violationType;
    public String violationLevel;
    public long startTime;
    public long endTime;
    public long violationOffsetMs;

    public List<Point> points;

    public static class Point {
        public long t;       // timeOffsetMs
        public double x, y, z;
        public float yaw, pitch;
        public boolean onGround;
        public String gameMode;
    }
}
