package com.anticheat.bounty;

import org.bukkit.Material;

/**
 * 赏金任务目录。
 *
 * <h3>任务目标必须"不作弊就做不到"</h3>
 * 判定里的 [Objective] 是"绕过"判定的**准入条件**：只有真的完成了目标、却没有任何
 * 检测报警，才算一次绕过。因此目标被刻意设计成**只有作弊才可能达成**的形态
 * （限时、禁落地、锁定不可见目标…）。若目标本身"人肉也能过"，那"完成了但没被抓到"
 * 就什么也证明不了——判据会退化成"这个玩家会不会玩"。
 *
 * <h3>赏金数值</h3>
 * 沿用设计文档的档位（10 / 10 / 50 / 100 / 30 / 150）。绕过档的实际发放还要乘
 * `bounty.reward.bypass-multiplier`，高危档固定为 `bounty.reward.zero-day`。
 */
public enum BountyTaskType {

    MOVE_BASIC(
            "移动检测·初级",
            "在 30 秒内从 A 点到达 B 点，且全程不得落地（步行做不到）",
            10, 300, Objective.REACH_POINT, Material.FEATHER),

    MOVE_ADVANCED(
            "移动检测·高级",
            "在空中完成一次直角变向（连续离地 ≥ 10 tick 且期间偏航角变化 ≥ 60°）",
            50, 300, Objective.AIR_TURN, Material.SUGAR),

    COMBAT_BASIC(
            "战斗检测·初级",
            "在 10 秒内击杀全部 5 个持续移动的傀儡",
            10, 180, Objective.KILL_ALL, Material.DIAMOND_SWORD),

    COMBAT_ADVANCED(
            "战斗检测·高级",
            "对不可见的幽灵实体保持准星锁定累计 3 秒而不被识别",
            100, 300, Objective.LOCK_GHOST, Material.BLAZE_POWDER),

    INVENTORY_CHALLENGE(
            "背包检测·挑战",
            "在 3 秒内完成 8 次背包交互（人手极难，自动图腾/自动换装很容易）",
            30, 180, Objective.FAST_SWAP, Material.CHEST),

    FREE_TEST(
            "自由测试",
            "无特定目标：自由尝试任何作弊功能；出现系统未记录的异常行为模式即判为高危",
            150, 600, Objective.NONE, Material.NETHER_STAR);

    /** 任务目标的可机判类型。 */
    public enum Objective {
        /** 到达指定点（水平距离 ≤ [BountySession#REACH_POINT_RADIUS]）。 */
        REACH_POINT,
        /** 一次空中直角变向。 */
        AIR_TURN,
        /** 击杀全部傀儡。 */
        KILL_ALL,
        /** 累计锁定幽灵实体指定时长。 */
        LOCK_GHOST,
        /** 限时完成指定次数的背包交互。 */
        FAST_SWAP,
        /** 没有可机判目标（由多维异常分单独定论）。 */
        NONE
    }

    private final String displayName;
    private final String objective;
    /** 名义赏金（代币）。 */
    private final int bounty;
    /** 任务时长上限（秒）。 */
    private final int durationSeconds;
    private final Objective objectiveType;
    /** 任务板图标。 */
    private final Material icon;

    BountyTaskType(String displayName, String objective, int bounty, int durationSeconds,
                   Objective objectiveType, Material icon) {
        this.displayName = displayName;
        this.objective = objective;
        this.bounty = bounty;
        this.durationSeconds = durationSeconds;
        this.objectiveType = objectiveType;
        this.icon = icon;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getObjective() {
        return objective;
    }

    public int getBounty() {
        return bounty;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public long getDurationMinutes() {
        return Math.max(1L, durationSeconds / 60L);
    }

    public Objective getObjectiveType() {
        return objectiveType;
    }

    public Material getIcon() {
        return icon;
    }

    /** 稳定的短 id（案例表、证据文件名都用它；枚举名将来可能被重排）。 */
    public String getId() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    /**
     * 解析任务类型；无法识别时返回 null（调用方据此提示可选项）。
     *
     * <p>同时接受 `MOVE_BASIC`、`move_basic`、`move-basic` 三种写法：
     * 命令行的参数是玩家手敲的，只接受一种写法会变成一类无谓的挫败。</p>
     */
    public static BountyTaskType byId(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        for (BountyTaskType type : values()) {
            if (type.name().toLowerCase(java.util.Locale.ROOT).equals(normalized)) {
                return type;
            }
        }
        return null;
    }

    /** 供命令提示使用：`move-basic(移动检测·初级)` 形式。 */
    public static String optionsText() {
        StringBuilder sb = new StringBuilder();
        for (BountyTaskType type : values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(type.getId()).append('(').append(type.displayName).append(')');
        }
        return sb.toString();
    }
}
