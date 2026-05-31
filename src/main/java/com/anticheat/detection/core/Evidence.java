package com.anticheat.detection.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Evidence证据类用于存储检测过程中的详细证据数据。
 * 使用HashMap存储各种类型的关键数据，便于后续分析和调试。
 * 支持添加、获取和批量获取证据数据。
 */
public class Evidence {

    private final Map<String, Object> data;

    public Evidence() {
        this.data = new HashMap<>();
    }

    /**
     * 添加证据数据
     * @param key 证据数据的键名
     * @param value 证据数据的值（可以是任何类型）
     */
    public void addData(String key, Object value) {
        if (key != null && value != null) {
            data.put(key, value);
        }
    }

    /**
     * 获取特定键的证据数据
     * @param key 证据数据的键名
     * @return 对应的值，如果不存在返回null
     */
    public Object getData(String key) {
        return data.get(key);
    }

    /**
     * 获取所有证据数据
     * @return 包含所有证据数据的Map
     */
    public Map<String, Object> getAllData() {
        return new HashMap<>(data);
    }

    /**
     * 检查是否存在特定键的证据数据
     * @param key 证据数据的键名
     * @return 如果存在返回true，否则返回false
     */
    public boolean hasData(String key) {
        return data.containsKey(key);
    }

    /**
     * 清除所有证据数据
     */
    public void clear() {
        data.clear();
    }

    /**
     * 获取证据数据的数量
     * @return 证据数据的数量
     */
    public int size() {
        return data.size();
    }
}
