package com.anticheat.detection.physics;

import org.bukkit.Location;
import org.bukkit.util.Vector;

/**
 * Vector3D三维向量工具类，提供向量运算功能。
 * 支持Minecraft坐标转换，用于物理模拟和运动检测。
 */
public class Vector3D {

    private double x;
    private double y;
    private double z;

    public Vector3D() {
        this.x = 0;
        this.y = 0;
        this.z = 0;
    }

    public Vector3D(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vector3D(Vector vector) {
        this.x = vector.getX();
        this.y = vector.getY();
        this.z = vector.getZ();
    }

    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getY() {
        return y;
    }

    public void setY(double y) {
        this.y = y;
    }

    public double getZ() {
        return z;
    }

    public void setZ(double z) {
        this.z = z;
    }

    public Vector3D add(Vector3D other) {
        return new Vector3D(this.x + other.x, this.y + other.y, this.z + other.z);
    }

    public Vector3D subtract(Vector3D other) {
        return new Vector3D(this.x - other.x, this.y - other.y, this.z - other.z);
    }

    public Vector3D multiply(double scalar) {
        return new Vector3D(this.x * scalar, this.y * scalar, this.z * scalar);
    }

    public double dot(Vector3D other) {
        return this.x * other.x + this.y * other.y + this.z * other.z;
    }

    public Vector3D cross(Vector3D other) {
        return new Vector3D(
                this.y * other.z - this.z * other.y,
                this.z * other.x - this.x * other.z,
                this.x * other.y - this.y * other.x
        );
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double lengthSquared() {
        return x * x + y * y + z * z;
    }

    public Vector3D normalize() {
        double len = length();
        if (len == 0) {
            return new Vector3D(0, 0, 0);
        }
        return new Vector3D(x / len, y / len, z / len);
    }

    public Vector toBukkitVector() {
        return new Vector(x, y, z);
    }

    public static Vector3D fromLocation(Location location) {
        return new Vector3D(location.getX(), location.getY(), location.getZ());
    }

    public Location toLocation(org.bukkit.World world) {
        return new Location(world, x, y, z);
    }

    @Override
    public String toString() {
        return "Vector3D{" +
                "x=" + x +
                ", y=" + y +
                ", z=" + z +
                '}';
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Vector3D vector3D = (Vector3D) obj;
        return Math.abs(vector3D.x - x) < 0.0001 &&
                Math.abs(vector3D.y - y) < 0.0001 &&
                Math.abs(vector3D.z - z) < 0.0001;
    }

    @Override
    public int hashCode() {
        return Double.hashCode(x) + 31 * Double.hashCode(y) + 31 * 31 * Double.hashCode(z);
    }
}
