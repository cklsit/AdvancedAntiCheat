package com.anticheat.captcha;

import com.anticheat.utils.VersionUtil;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * 查端/验证码期间的玩家数据快照（背包 + 盔甲 + 末影箱 + 经验条 + 移动速度）。
 *
 * <p><b>为什么必须有这个类</b>：验证码流程会把玩家传送到 {@code captcha_void_world} 并
 * <b>清空背包与末影箱</b>（防止玩家用物品脱离平台），同时用 {@code setExp/setLevel} 占用经验条做倒计时。
 * 原实现清空后再也没有还原 —— 玩家完成查端回到主世界，背包全空、等级归零
 * （2026-09-13 线上事故：{@code /captcha}、{@code /ac config → 查证} 完成后物品全部丢失）。
 *
 * <p>快照支持 Base64 序列化，供 {@link CaptchaManager} 落盘，保证「查端过程中服务器重启/玩家退出」
 * 这类场景下物品依然能找回（下次登录时还原）。
 *
 * <p>序列化用 {@link BukkitObjectOutputStream}（1.8 与 1.21 均存在，且能完整保留 NBT），
 * null 槽位用 boolean 标记，避免依赖不同版本对 {@code writeObject(null)} 的处理差异。
 */
public class CaptchaInventoryBackup {

    /** 主背包（1.8 = getContents() 36 格；1.9+ = getStorageContents() 36 格） */
    private final ItemStack[] storage;
    /** 盔甲 4 格 */
    private final ItemStack[] armor;
    /** 末影箱 27 格 */
    private final ItemStack[] enderChest;

    private final int level;
    private final float exp;
    private final float walkSpeed;
    private final float flySpeed;

    /** 快照创建时间（毫秒），用于日志与过期排查 */
    private final long createTime;

    public CaptchaInventoryBackup(ItemStack[] storage, ItemStack[] armor, ItemStack[] enderChest,
                                  int level, float exp, float walkSpeed, float flySpeed, long createTime) {
        this.storage = storage == null ? new ItemStack[0] : storage;
        this.armor = armor == null ? new ItemStack[0] : armor;
        this.enderChest = enderChest == null ? new ItemStack[0] : enderChest;
        this.level = level;
        this.exp = exp;
        this.walkSpeed = walkSpeed;
        this.flySpeed = flySpeed;
        this.createTime = createTime;
    }

    // ================================================================
    // 采集 / 还原
    // ================================================================

    /** 采集玩家当前状态（必须在清空之前调用）。 */
    public static CaptchaInventoryBackup capture(Player player) {
        PlayerInventory inv = player.getInventory();

        ItemStack[] storage = readMain36(inv);
        ItemStack[] armor = safeArmor(inv);
        ItemStack[] enderChest = safeEnderChest(player);

        int level = 0;
        float exp = 0f;
        float walkSpeed = 0.2f;
        float flySpeed = 0.2f;
        try { level = player.getLevel(); } catch (Throwable ignored) {}
        try { exp = player.getExp(); } catch (Throwable ignored) {}
        try { walkSpeed = player.getWalkSpeed(); } catch (Throwable ignored) {}
        try { flySpeed = player.getFlySpeed(); } catch (Throwable ignored) {}

        return new CaptchaInventoryBackup(storage, armor, enderChest,
                level, exp, walkSpeed, flySpeed, System.currentTimeMillis());
    }

    /** 还原到玩家（必须在主线程调用）。单项失败不影响其它项，避免一处异常导致整包物品丢失。 */
    public void restore(Player player) {
        PlayerInventory inv = player.getInventory();

        try {
            if (!writeMain36(inv, storage)) {
                // 主背包还原失败：记录但不静默吞掉，交由上层日志告警
                throw new IllegalStateException("主背包写入失败");
            }
        } catch (Throwable t) {
            throw new IllegalStateException("还原主背包失败: " + t.getMessage(), t);
        }

        try {
            inv.setArmorContents(armor);
        } catch (Throwable ignored) {
        }

        try {
            player.getEnderChest().setContents(enderChest);
        } catch (Throwable ignored) {
        }

        try {
            player.setLevel(level);
            player.setExp(Math.max(0f, Math.min(1f, exp)));
        } catch (Throwable ignored) {
        }

        try {
            player.setWalkSpeed(walkSpeed);
            player.setFlySpeed(flySpeed);
        } catch (Throwable ignored) {
        }
    }

    /** 是否是一份"什么都没有"的快照（无物品、无等级），纯空时可以不落盘。 */
    public boolean isEmpty() {
        return !hasItem(storage) && !hasItem(armor) && !hasItem(enderChest)
                && level == 0 && exp <= 0f;
    }

    public long getCreateTime() {
        return createTime;
    }

    public int getItemCount() {
        int n = 0;
        n += count(storage);
        n += count(armor);
        n += count(enderChest);
        return n;
    }

    private static boolean hasItem(ItemStack[] items) {
        return count(items) > 0;
    }

    private static int count(ItemStack[] items) {
        if (items == null) return 0;
        int n = 0;
        for (ItemStack it : items) {
            if (it != null && it.getType() != null && !it.getType().name().equals("AIR")) n++;
        }
        return n;
    }

    // ================================================================
    // 跨版本背包读写（1.8 / 1.21 对称）
    // ================================================================

    private static ItemStack[] readMain36(PlayerInventory inv) {
        try {
            ItemStack[] a = inv.getStorageContents();
            if (a != null && a.length >= 36) return a;
        } catch (Throwable ignored) {
            // 1.8 无 getStorageContents
        }
        ItemStack[] b = inv.getContents();
        return b == null ? new ItemStack[36] : b;
    }

    private static boolean writeMain36(PlayerInventory inv, ItemStack[] items) {
        try {
            inv.setStorageContents(items);
            return true;
        } catch (Throwable ignored) {
            // 1.8 无 setStorageContents
        }
        try {
            inv.setContents(items);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static ItemStack[] safeArmor(PlayerInventory inv) {
        try {
            ItemStack[] a = inv.getArmorContents();
            return a == null ? new ItemStack[4] : a;
        } catch (Throwable ignored) {
            return new ItemStack[4];
        }
    }

    private static ItemStack[] safeEnderChest(Player player) {
        try {
            ItemStack[] a = player.getEnderChest().getContents();
            return a == null ? new ItemStack[27] : a;
        } catch (Throwable ignored) {
            return new ItemStack[27];
        }
    }

    // ================================================================
    // 持久化（Base64）
    // ================================================================

    /** 序列化为 Base64 字符串。失败抛 IOException，由调用方决定是否放弃落盘（内存快照仍在）。 */
    public String serialize() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        writeItems(out, storage);
        writeItems(out, armor);
        writeItems(out, enderChest);
        out.writeInt(level);
        out.writeFloat(exp);
        out.writeFloat(walkSpeed);
        out.writeFloat(flySpeed);
        out.writeLong(createTime);
        out.flush();
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    public static CaptchaInventoryBackup deserialize(String data) throws IOException {
        byte[] raw = Base64.getDecoder().decode(data);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        ItemStack[] storage = readItems(in);
        ItemStack[] armor = readItems(in);
        ItemStack[] enderChest = readItems(in);
        int level = in.readInt();
        float exp = in.readFloat();
        float walkSpeed = in.readFloat();
        float flySpeed = in.readFloat();
        long createTime = in.readLong();
        return new CaptchaInventoryBackup(storage, armor, enderChest,
                level, exp, walkSpeed, flySpeed, createTime);
    }

    private static void writeItems(DataOutputStream out, ItemStack[] items) throws IOException {
        ByteArrayOutputStream ib = new ByteArrayOutputStream();
        BukkitObjectOutputStream oos = new BukkitObjectOutputStream(ib);
        oos.writeInt(items.length);
        for (ItemStack item : items) {
            // 先写占位标记再写对象：避免不同版本对 writeObject(null) 的处理差异导致整包损坏
            oos.writeBoolean(item != null);
            if (item != null) {
                oos.writeObject(item);
            }
        }
        oos.close();
        byte[] raw = ib.toByteArray();
        out.writeInt(raw.length);
        out.write(raw);
    }

    private static ItemStack[] readItems(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 256) {
            throw new IOException("非法的快照长度: " + len);
        }
        byte[] raw = new byte[len];
        in.readFully(raw);

        BukkitObjectInputStream ois = new BukkitObjectInputStream(new ByteArrayInputStream(raw));
        try {
            int size = ois.readInt();
            if (size < 0 || size > 256) {
                throw new IOException("非法的槽位数量: " + size);
            }
            ItemStack[] items = new ItemStack[size];
            for (int i = 0; i < size; i++) {
                if (ois.readBoolean()) {
                    items[i] = (ItemStack) ois.readObject();
                }
            }
            return items;
        } catch (ClassNotFoundException e) {
            throw new IOException("反序列化物品失败: " + e.getMessage(), e);
        } finally {
            try {
                ois.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 便于调试：快照摘要（不含具体物品，避免刷日志）。 */
    public String summary() {
        return "items=" + getItemCount() + ", armor=" + count(armor) + ", ender=" + count(enderChest)
                + ", level=" + level + ", ver=" + VersionUtil.getMajorVersion();
    }
}
