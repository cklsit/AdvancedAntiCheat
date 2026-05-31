package com.anticheat.detection.core;

import org.bukkit.entity.Player;

/**
 * DetectionModule接口定义了所有检测模块的基本行为。
 * 所有具体的检测模块都应该实现此接口。
 * 用于Phase 1的核心框架，提供统一的检测模块接口。
 */
public interface DetectionModule {

    /**
     * 对指定玩家执行检测逻辑
     * @param player 要检测的玩家
     */
    void check(Player player);

    /**
     * 检测模块是否启用
     * @return 如果启用返回true，否则返回false
     */
    boolean isEnabled();

    /**
     * 获取检测模块的名称
     * @return 检测模块的名称
     */
    String getName();

    /**
     * 获取检测概率值（0.0-1.0）
     * 用于权重计算和风险评估
     * @return 概率值
     */
    double getProbability();
}
