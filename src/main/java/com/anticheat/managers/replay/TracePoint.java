package com.anticheat.managers.replay;

import com.google.gson.annotations.SerializedName;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 回放轨迹采样点 POJO。
 * 每次 PlayerMoveListener 采样时生成，写入 ReplayRecorder 的环形缓冲。
 */
public class TracePoint {

    /** 相对于 segment 起点的时间偏移（毫秒） */
    private long timeOffsetMs;

    private double x;
    private double y;
    private double z;

    private float yaw;
    private float pitch;

    private boolean onGround;

    /** 游戏模式名称，如 "SURVIVAL"、"CREATIVE"，兼容低/高版本 */
    private String gameMode;

    /** 主手物品，如 "DIAMOND_SWORD"；跨版本兼容，异常时为 null */
    private String mainHandItemId;

    /** 周围 7×7=49 方块高度采样（null 表示该帧未采样）；稀疏采样可省内存 */
    private int[] blockHeights;

    // ===== NEW: HUD 字段（跨版本兼容 + ZIP 存档紧凑短 key） =====

    /** 采样瞬间的绝对 System.currentTimeMillis()，用于和视频帧时间轴对齐 */
    @SerializedName("w")
    private long wallClockMs;

    /** 半心整数（= (int)Math.round(getHealth()*2)），0-40，40=20 心满血 */
    @SerializedName("h")
    private byte healthHalf;

    /** 饥饿值 0-20（getFoodLevel()） */
    @SerializedName("f")
    private byte hunger;

    /** 半护甲值（每件 armor piece 贡献半护甲板数 × 2），0-40 */
    @SerializedName("a")
    private byte armorHalf;

    /** 玩家经验等级（getLevel()），0-32000 */
    @SerializedName("lvl")
    private short expLevel;

    /** 百分比 0-100（本等级经验进度） */
    @SerializedName("xp")
    private byte expPercent;

    /** getInventory().getHeldItemSlot()，0-8 */
    @SerializedName("hs")
    private byte hotbarSlot;

    /** 热栏 9 格物品类型名（稀疏采样：每 20 点采一次，其余为 null），长度=9，元素允许 null */
    @SerializedName("inv")
    private String[] inventoryHotbar;

    /** 完整 36 格背包物品类型名（稀疏采样，长度=36，元素允许 null） */
    @SerializedName("finv")
    private String[] inventoryFull;

    // ===== 准星目标（需求 4）：0=无，1=实体，2=方块 =====

    /** 准星目标类别 */
    @SerializedName("tk")
    private byte targetKind;

    /** 准星目标实体类型名，如 "PLAYER"/"ZOMBIE"；无实体时为 null */
    @SerializedName("tt")
    private String targetEntityType;

    /** 准星目标实体名（玩家名/自定义名/掉落物类型）；无实体时为 null */
    @SerializedName("tn")
    private String targetEntityName;

    /** 准星目标距离（格） */
    @SerializedName("td")
    private float targetDistance;

    /** 准星目标方块类型名；无方块时为 null */
    @SerializedName("tb")
    private String targetBlockType;

    public TracePoint() {
    }

    public TracePoint(long timeOffsetMs, double x, double y, double z,
                      float yaw, float pitch, boolean onGround, String gameMode) {
        this.timeOffsetMs = timeOffsetMs;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.onGround = onGround;
        this.gameMode = gameMode;
    }

    public long getTimeOffsetMs() {
        return timeOffsetMs;
    }

    public void setTimeOffsetMs(long timeOffsetMs) {
        this.timeOffsetMs = timeOffsetMs;
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

    public float getYaw() {
        return yaw;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }

    public boolean isOnGround() {
        return onGround;
    }

    public void setOnGround(boolean onGround) {
        this.onGround = onGround;
    }

    public String getGameMode() {
        return gameMode;
    }

    public void setGameMode(String gameMode) {
        this.gameMode = gameMode;
    }

    public String getMainHandItemId() {
        return mainHandItemId;
    }

    public void setMainHandItemId(String mainHandItemId) {
        this.mainHandItemId = mainHandItemId;
    }

    public int[] getBlockHeights() {
        return blockHeights;
    }

    public void setBlockHeights(int[] blockHeights) {
        this.blockHeights = blockHeights;
    }

    // ===== NEW: HUD 字段 getter/setter（全部夹取约束） =====

    public long getWallClockMs() {
        return wallClockMs;
    }

    public void setWallClockMs(long wallClockMs) {
        this.wallClockMs = Math.max(0L, wallClockMs);
    }

    public byte getHealthHalf() {
        return healthHalf;
    }

    public void setHealthHalf(int healthHalf) {
        this.healthHalf = (byte) Math.max(0, Math.min(40, healthHalf));
    }

    public byte getHunger() {
        return hunger;
    }

    public void setHunger(int hunger) {
        this.hunger = (byte) Math.max(0, Math.min(20, hunger));
    }

    public byte getArmorHalf() {
        return armorHalf;
    }

    public void setArmorHalf(int armorHalf) {
        this.armorHalf = (byte) Math.max(0, Math.min(40, armorHalf));
    }

    public short getExpLevel() {
        return expLevel;
    }

    public void setExpLevel(short expLevel) {
        this.expLevel = expLevel < 0 ? 0 : expLevel;
    }

    public void setExpLevel(int expLevel) {
        if (expLevel < 0) this.expLevel = 0;
        else if (expLevel > Short.MAX_VALUE) this.expLevel = Short.MAX_VALUE;
        else this.expLevel = (short) expLevel;
    }

    public byte getExpPercent() {
        return expPercent;
    }

    public void setExpPercent(int expPercent) {
        this.expPercent = (byte) Math.max(0, Math.min(100, expPercent));
    }

    public byte getHotbarSlot() {
        return hotbarSlot;
    }

    public void setHotbarSlot(int hotbarSlot) {
        this.hotbarSlot = (byte) Math.max(0, Math.min(8, hotbarSlot));
    }

    public String[] getInventoryHotbar() {
        return inventoryHotbar;
    }

    public void setInventoryHotbar(String[] inventoryHotbar) {
        if (inventoryHotbar == null) {
            this.inventoryHotbar = null;
            return;
        }
        // 固定长度 9，多余截断，不足补 null
        String[] normalized = new String[9];
        for (int i = 0; i < 9 && i < inventoryHotbar.length; i++) {
            normalized[i] = inventoryHotbar[i];
        }
        this.inventoryHotbar = normalized;
    }

    // ===== 完整 36 格背包 =====

    public String[] getInventoryFull() {
        return inventoryFull;
    }

    public void setInventoryFull(String[] inventoryFull) {
        if (inventoryFull == null) {
            this.inventoryFull = null;
            return;
        }
        String[] normalized = new String[36];
        for (int i = 0; i < 36 && i < inventoryFull.length; i++) {
            normalized[i] = inventoryFull[i];
        }
        this.inventoryFull = normalized;
    }

    // ===== 准星目标 =====

    public byte getTargetKind() {
        return targetKind;
    }

    public void setTargetKind(int targetKind) {
        this.targetKind = (byte) Math.max(0, Math.min(2, targetKind));
    }

    public String getTargetEntityType() {
        return targetEntityType;
    }

    public void setTargetEntityType(String targetEntityType) {
        this.targetEntityType = targetEntityType;
    }

    public String getTargetEntityName() {
        return targetEntityName;
    }

    public void setTargetEntityName(String targetEntityName) {
        this.targetEntityName = targetEntityName;
    }

    public float getTargetDistance() {
        return targetDistance;
    }

    public void setTargetDistance(float targetDistance) {
        this.targetDistance = Math.max(0f, targetDistance);
    }

    public String getTargetBlockType() {
        return targetBlockType;
    }

    public void setTargetBlockType(String targetBlockType) {
        this.targetBlockType = targetBlockType;
    }

    /**
     * 半护甲值计算辅助类（TracePoint 公共静态内部类，满足单文件 public class 约束）。
     * <p>
     * 按单件装备贡献的半护甲板 × 2（即 1 格护甲板 = 2 点半护甲值）：
     * <ul>
     *     <li>皮革：头盔=1 / 护腿=2 / 胸甲=3 / 靴子=1</li>
     *     <li>铁：头盔=4 / 护腿=5 / 胸甲=6 / 靴子=4</li>
     *     <li>钻石：头盔=6 / 护腿=8 / 胸甲=8 / 靴子=6</li>
     *     <li>金：与铁同级</li>
     *     <li>下界合金：头盔=7 / 护腿=9 / 胸甲=11 / 靴子=7（1.8.8 不存在，try Material.valueOf 失败返回 0）</li>
     *     <li>链甲：铁 - 1：头盔=3 / 护腿=4 / 胸甲=5 / 靴子=3</li>
     * </ul>
     */
    public static final class ArmorHalfPoint {

        private ArmorHalfPoint() {}

        /**
         * 计算玩家总半护甲值（0-40 夹取）。
         * 对 1.8.8：getArmorContents() 顺序 [helmet, chestplate, leggings, boots]，长度 4。
         */
        public static int calcHalfArmorPoints(Player player) {
            if (player == null) return 0;
            ItemStack[] armor;
            try {
                armor = player.getEquipment().getArmorContents();
            } catch (Throwable t1) {
                try {
                    armor = player.getInventory().getArmorContents();
                } catch (Throwable t2) {
                    return 0;
                }
            }
            if (armor == null || armor.length == 0) return 0;

            int total = 0;
            // 槽顺序：0=helmet, 1=chestplate, 2=leggings, 3=boots
            for (int i = 0; i < Math.min(4, armor.length); i++) {
                ItemStack piece = armor[i];
                if (piece == null) continue;
                Material mat;
                try {
                    mat = piece.getType();
                } catch (Throwable t) {
                    continue;
                }
                if (mat == null) continue;
                String name = mat.name();
                total += getHalfPoints(name, i);
            }
            return Math.max(0, Math.min(40, total));
        }

        /**
         * slotIdx：0=helmet, 1=chestplate, 2=leggings, 3=boots
         */
        private static int getHalfPoints(String materialName, int slotIdx) {
            if (materialName == null) return 0;
            // 皮革
            if (materialName.startsWith("LEATHER_")) {
                switch (slotIdx) {
                    case 0: return 1; // helmet
                    case 1: return 3; // chestplate
                    case 2: return 2; // leggings
                    case 3: return 1; // boots
                }
            }
            // 铁 & 金
            if (materialName.startsWith("IRON_") || materialName.startsWith("GOLD_")
                    || materialName.startsWith("GOLDEN_")) {
                switch (slotIdx) {
                    case 0: return 4; // helmet
                    case 1: return 6; // chestplate
                    case 2: return 5; // leggings
                    case 3: return 4; // boots
                }
            }
            // 钻石
            if (materialName.startsWith("DIAMOND_")) {
                switch (slotIdx) {
                    case 0: return 6; // helmet
                    case 1: return 8; // chestplate
                    case 2: return 8; // leggings
                    case 3: return 6; // boots
                }
            }
            // 链甲 = 铁 - 1
            if (materialName.startsWith("CHAINMAIL_")) {
                switch (slotIdx) {
                    case 0: return 3; // helmet
                    case 1: return 5; // chestplate
                    case 2: return 4; // leggings
                    case 3: return 3; // boots
                }
            }
            // 下界合金（1.8.8 不存在，try Material.valueOf 失败返回 0，
            // 但这里若 Material 已存在，则按 slot 给值）
            if (materialName.startsWith("NETHERITE_")) {
                switch (slotIdx) {
                    case 0: return 7; // helmet
                    case 1: return 11; // chestplate
                    case 2: return 9; // leggings
                    case 3: return 7; // boots
                }
            }
            return 0;
        }
    }
}
