package com.anticheat.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 实验室特征维度注册表。
 * <p>
 * 全系统统一使用 48 维行为特征向量，维度顺序固定，
 * 所有模型（个人基线 K-Means / 全局孤立森林 / 监督逻辑回归）共享该向量空间。
 * 特征每秒由 {@link AILabFeatureCollector} 计算一次。
 */
public final class FeatureDimensions {

    /** 特征向量维度总数。 */
    public static final int DIMS = 48;

    /** 维度名称（稳定 ID，用于 API / 持久化 / 模拟器）。 */
    public static final String[] NAMES = {
            // ---- 移动 (0-15) ----
            "hSpeedMean",          // 0  水平速度均值
            "hSpeedVar",           // 1  水平速度方差
            "vSpeedMean",          // 2  垂直速度均值
            "vSpeedVar",           // 3  垂直速度方差
            "accelMean",           // 4  加速度均值
            "accelKurtosis",       // 5  加速度峰度（急动度）
            "airTickRatio",        // 6  空中样本占比
            "yawRateMean",         // 7  转向角速度均值
            "yawRateEntropy",      // 8  转向角速度熵
            "yawRateVar",          // 9  转向角速度方差
            "pitchVar",            // 10 俯仰角方差
            "pitchExtremeRatio",   // 11 俯仰角极值占比（±接近90°）
            "viewScanCoverage",    // 12 视角扫描覆盖度
            "verticalStareRatio",  // 13 垂直视角特殊停留占比
            "jumpFreq",            // 14 跳跃频率（次/秒）
            "sneakToggleFreq",     // 15 潜行切换频率
            // ---- 移动补充 (16) ----
            "sprintRatio",         // 16 疾跑占比
            // ---- 战斗 (17-25) ----
            "cpsMean",             // 17 攻击频率 CPS
            "cpsVar",              // 18 CPS 方差
            "attackIntervalEntropy", // 19 攻击间隔熵
            "attackDistanceMean",  // 20 攻击距离均值
            "attackAngleDeviation",// 21 攻击角度偏差
            "targetSwitchFreq",    // 22 攻击目标切换频率
            "hitRatio",            // 23 命中率
            "attackCount",         // 24 攻击次数（1s 窗口）
            "attackWhileMovingRatio", // 25 移动中攻击占比
            // ---- 挖掘/放置 (26-32) ----
            "breakIntervalMean",   // 26 破坏方块间隔均值
            "breakIntervalVar",    // 27 破坏方块间隔方差
            "breakCount",          // 28 破坏次数（1s 窗口）
            "placeCount",          // 29 放置次数（1s 窗口）
            "placeHeightMean",     // 30 放置相对高度均值
            "placeHeightVar",      // 31 放置相对高度方差
            "breakYSpread",        // 32 破坏方块 Y 分布跨度
            // ---- 背包交互 (33-36) ----
            "invClickRate",        // 33 背包点击速率
            "invShiftClickRatio",  // 34 Shift 点击占比
            "containerToggleFreq", // 35 容器打开频率
            "invActionRate",       // 36 背包动作总速率
            // ---- 社交/环境 (37-47) ----
            "chatFreq",            // 37 聊天频率
            "commandFreq",         // 38 命令频率
            "moveVariance",        // 39 移动采样方差（1s 粒度位移抖动）
            "turnSpeedMean",       // 40 转向速度均值（profile 长期）
            "jumpIntervalMean",    // 41 跳跃间隔均值（profile 长期）
            "interfaceActionMean", // 42 界面操作均值（profile 长期）
            "walkStayRatio",       // 43 走停比（profile 长期）
            "profileCpsStd",       // 44 CPS 长期标准差
            "tpsEnv",              // 45 当前 TPS 环境
            "sessionMinutes",      // 46 本次在线时长（分钟）
            "riskScore",           // 47 现有风控风险分
    };

    /** 维度中文描述（前端展示 / 可解释性）。 */
    public static final String[] DESCRIPTIONS = {
            "水平速度均值", "水平速度方差", "垂直速度均值", "垂直速度方差",
            "加速度均值", "加速度峰度", "空中样本占比",
            "转向角速度均值", "转向角速度熵", "转向角速度方差",
            "俯仰角方差", "俯仰角极值占比", "视角扫描覆盖度", "垂直视角停留占比",
            "跳跃频率", "潜行切换频率", "疾跑占比",
            "攻击频率CPS", "CPS方差", "攻击间隔熵", "攻击距离均值",
            "攻击角度偏差", "目标切换频率", "命中率", "攻击次数", "移动中攻击占比",
            "破坏间隔均值", "破坏间隔方差", "破坏次数", "放置次数",
            "放置相对高度均值", "放置相对高度方差", "破坏Y跨度",
            "背包点击速率", "Shift点击占比", "容器打开频率", "背包动作速率",
            "聊天频率", "命令频率", "移动采样方差",
            "转向速度(长期)", "跳跃间隔(长期)", "界面操作(长期)", "走停比(长期)",
            "CPS标准差(长期)", "TPS环境", "在线时长(分)", "风控风险分",
    };

    /** 特征类别分组：移动 0-16，战斗 17-25，挖掘/放置 26-32，背包 33-36，社交/环境 37-47。 */
    public static final String[] GROUP_OF = buildGroups();

    private static String[] buildGroups() {
        String[] g = new String[DIMS];
        for (int i = 0; i <= 16; i++) g[i] = "movement";
        for (int i = 17; i <= 25; i++) g[i] = "combat";
        for (int i = 26; i <= 32; i++) g[i] = "mining";
        for (int i = 33; i <= 36; i++) g[i] = "inventory";
        for (int i = 37; i < DIMS; i++) g[i] = "social";
        return g;
    }

    /** 按维度名取下标，未知名称返回 -1。 */
    public static int indexOf(String name) {
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(name)) return i;
        }
        return -1;
    }

    /** @return [name, description, group] 列表，用于前端特征字典。 */
    public static List<String[]> describeAll() {
        List<String[]> list = new ArrayList<>(DIMS);
        for (int i = 0; i < DIMS; i++) {
            list.add(new String[]{NAMES[i], DESCRIPTIONS[i], GROUP_OF[i]});
        }
        return list;
    }

    private FeatureDimensions() {
    }
}
