package com.anticheat.detection.core;

/**
 * DetectionCatalog —— 全项检测目录（重构核心）。
 *
 * <p>将《基础反作弊全项检测规范》中的 9 大类、51 项检测统一枚举为第一等公民，
 * 每项检测都携带：唯一 id、所属类别、中文名称、目标作弊、实现模块提示。</p>
 *
 * <p>该目录是"全项检测"的唯一事实来源（single source of truth）：
 * 配置中心、Web 面板模块状态、融合引擎权重、检测统计均以此枚举为基准，
 * 新增检测只需在此追加枚举常量，并在对应检测模块中落地实现。</p>
 */
public final class DetectionCatalog {

    private DetectionCatalog() {
    }

    /** 九大检测类别 */
    public enum Category {
        MOVEMENT("移动类", "水平/垂直速度、预测物理、空中行为、相位、液体行走、Timer、微时序"),
        COMBAT("战斗类", "攻击距离/角度、CPS、杀戮光环、自瞄频谱、击退、自动图腾/盔甲/喝药"),
        MINING("挖掘与建筑类", "快速破坏、破坏一致性、挖掘移动协调、脚手架、非法放置"),
        INVENTORY("背包与物品交互类", "背包状态机、物品移动、容器开关、副手切换"),
        NETWORK("网络与协议类", "数据包结构、品牌通道、协议版本、时钟漂移"),
        FINGERPRINT("客户端与环境指纹类", "渲染距离、GUI响应指纹、隐写特征标记"),
        HONEYPOT("蜜罐与陷阱类", "幻象矿石、幽灵实体、不可能破坏进度、虚假掉落物、假逃脱"),
        BEHAVIOR("行为分析与自适应学习类", "个人基线、全局异常、生物特征、反侦察、动态贝叶斯融合"),
        ASSOCIATION("关联与数据库类", "设备指纹关联、行为孪生、社交图谱、历史回溯、风险分累计衰减");

        private final String displayName;
        private final String summary;

        Category(String displayName, String summary) {
            this.displayName = displayName;
            this.summary = summary;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getSummary() {
            return summary;
        }
    }

    /** 51 项检测项枚举 */
    public enum Check {
        // ============ 一、移动类 ============
        HORIZONTAL_SPEED(Category.MOVEMENT, "水平速度检测", "加速/Speed"),
        VERTICAL_SPEED(Category.MOVEMENT, "垂直速度检测", "飞行/高跳"),
        PREDICTIVE_PHYSICS(Category.MOVEMENT, "预测式物理模拟", "微加速/无减速/Timer"),
        AIR_BEHAVIOR(Category.MOVEMENT, "空中行为检测", "AirJump/空中加速"),
        NO_FALL(Category.MOVEMENT, "无摔落伤害检测", "NoFall"),
        PHASE(Category.MOVEMENT, "相位/穿墙检测", "Phase"),
        JESUS(Category.MOVEMENT, "水面/岩浆行走检测", "Jesus"),
        SPIDER(Category.MOVEMENT, "蜘蛛攀爬检测", "Spider"),
        TIMER(Category.MOVEMENT, "Timer变速检测", "Timer"),
        MICRO_TIMING(Category.MOVEMENT, "微时序分析", "假延迟"),

        // ============ 二、战斗类 ============
        REACH(Category.COMBAT, "攻击距离检测", "Reach"),
        ATTACK_ANGLE(Category.COMBAT, "攻击角度检测", "Aimbot/KillAura"),
        CPS(Category.COMBAT, "CPS检测", "自动点击"),
        KILLAURA(Category.COMBAT, "杀戮光环检测", "KillAura"),
        AIMBOT_SPECTRUM(Category.COMBAT, "自瞄频谱分析", "Aimbot"),
        KNOCKBACK_ENTROPY(Category.COMBAT, "击退熵分析", "AntiKnockback"),
        ANTI_KNOCKBACK(Category.COMBAT, "反击退相位锁定", "AntiKB"),
        AUTO_TOTEM_ARMOR(Category.COMBAT, "自动图腾/盔甲", "AutoTotem/Armor"),
        AUTO_POTION(Category.COMBAT, "自动喝药", "AutoPotion"),

        // ============ 三、挖掘与建筑类 ============
        FAST_BREAK(Category.MINING, "快速破坏", "FastBreak"),
        BREAK_CONSISTENCY(Category.MINING, "破坏曲线一致性", "自动矿工"),
        MINING_MOVEMENT(Category.MINING, "挖掘与移动协调", "自动挖掘"),
        SCAFFOLD(Category.MINING, "自动搭路/脚手架", "Scaffold"),
        ILLEGAL_PLACE(Category.MINING, "非法放置检测", "任意放置"),

        // ============ 四、背包与物品交互类 ============
        INVENTORY_STATE_MACHINE(Category.INVENTORY, "背包操作状态机", "AutoTotem/InvMove"),
        ITEM_MOVE_SPEED(Category.INVENTORY, "物品移动速度", "自动整理"),
        CONTAINER_FREQUENCY(Category.INVENTORY, "容器开关频率", "自动仓库"),
        OFFHAND_PRECISION(Category.INVENTORY, "副手切换精度", "AutoTotem"),

        // ============ 五、网络与协议类 ============
        MALFORMED_PACKET(Category.NETWORK, "非法数据包结构", "协议利用"),
        BRAND_CHANNEL(Category.NETWORK, "品牌通道验证", "伪造客户端"),
        PROTOCOL_VERSION(Category.NETWORK, "协议版本匹配", "版本欺骗"),
        CLOCK_DRIFT(Category.NETWORK, "时钟漂移检测", "Timer"),

        // ============ 六、客户端与环境指纹类 ============
        RENDER_DISTANCE(Category.FINGERPRINT, "渲染距离验证", "矿透"),
        GUI_FINGERPRINT(Category.FINGERPRINT, "GUI响应时间指纹", "注入客户端"),
        STEGANO_MARKER(Category.FINGERPRINT, "隐写特征标记", "小号追踪"),

        // ============ 七、蜜罐与陷阱类 ============
        HOLOGRAM_ORE(Category.HONEYPOT, "幻象矿石", "透视/矿透"),
        GHOST_ENTITY(Category.HONEYPOT, "幽灵实体", "ESP/杀戮光环"),
        IMPOSSIBLE_BREAK(Category.HONEYPOT, "不可能破坏进度", "自动矿工"),
        FAKE_DROP(Category.HONEYPOT, "虚假掉落物", "自动拾取/ESP"),
        FAKE_ESCAPE(Category.HONEYPOT, "假逃脱蜜罐", "作弊者情报收集"),

        // ============ 八、行为分析与自适应学习类 ============
        PERSONAL_BASELINE(Category.BEHAVIOR, "个人行为基线", "账号共享/突变"),
        GLOBAL_ANOMALY(Category.BEHAVIOR, "全局异常检测", "未知作弊"),
        KEYSTROKE_BIOMETRICS(Category.BEHAVIOR, "操作生物特征", "宏/脚本"),
        ANTI_RECON(Category.BEHAVIOR, "反侦察行为检测", "作弊者规避"),
        BAYESIAN_FUSION(Category.BEHAVIOR, "动态贝叶斯融合", "最终决策"),

        // ============ 九、关联与数据库类 ============
        DEVICE_FINGERPRINT(Category.ASSOCIATION, "设备指纹关联", "小号"),
        BEHAVIOR_TWIN(Category.ASSOCIATION, "行为孪生匹配", "小号"),
        SOCIAL_GRAPH(Category.ASSOCIATION, "社交图谱分析", "作弊团伙"),
        RULE_BACKTRACE(Category.ASSOCIATION, "历史规则回溯", "漏网之鱼"),
        RISK_DECAY(Category.ASSOCIATION, "风险分累计与衰减", "累犯管理");

        private final Category category;
        private final String displayName;
        private final String targetCheat;

        Check(Category category, String displayName, String targetCheat) {
            this.category = category;
            this.displayName = displayName;
            this.targetCheat = targetCheat;
        }

        public Category getCategory() {
            return category;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getTargetCheat() {
            return targetCheat;
        }

        /** 检测项在 config.yml 中的配置键名（小写，与 enabled 开关对应） */
        public String getConfigKey() {
            return name().toLowerCase();
        }

        public static int count() {
            return values().length;
        }

        public static int countByCategory(Category category) {
            int n = 0;
            for (Check check : values()) {
                if (check.category == category) {
                    n++;
                }
            }
            return n;
        }
    }
}
