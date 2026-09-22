package com.anticheat.bounty;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.utils.VersionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 任务板（文档第三节的"任务面板"）。
 *
 * <h3>与文档的一处刻意偏离</h3>
 * 文档写的是"用多个物品展示框和告示牌构建"，但**物品展示框无法被点击**——
 * 用它做出来的面板只能"看"，起不到"点击任务即可开始"的作用。
 * 因此改用箱子 GUI（真正的可点击面板），并保留同样的信息结构：
 * 每个任务一格，图标 + 名称 + 目标描述 + 赏金 + 时长。
 *
 * <p>跨版本：图标只用 1.8 与 1.21 都存在的 {@link Material} 常量，
 * 填充物走 {@link VersionUtil#compatBarrier()}。</p>
 */
public class BountyBoard implements InventoryHolder {

    private static final int SIZE = 27;

    private static final int SLOT_TOKEN = 21;
    private static final int SLOT_RANK = 22;
    private static final int SLOT_SHOP = 23;
    private static final int SLOT_CASES = 24;

    /** 任务图标占用 10..15（中间一行）。 */
    private static final int TASK_SLOT_START = 10;

    private final Inventory inventory;
    /** 槽位 → 任务；非任务槽位为 null。 */
    private final BountyTaskType[] slotTasks = new BountyTaskType[SIZE];

    private BountyBoard(AdvancedAntiCheat plugin, BountyManager manager, Player viewer) {
        this.inventory = Bukkit.createInventory(this, SIZE, "§8漏洞赏金 · 任务板");
        ItemStack filler = BountyShop.named(new ItemStack(VersionUtil.compatBarrier(), 1), "§8", null);
        for (int i = 0; i < SIZE; i++) {
            inventory.setItem(i, filler);
        }

        BountySession session = manager.getSession(viewer);
        int slot = TASK_SLOT_START;
        for (BountyTaskType task : BountyTaskType.values()) {
            if (slot >= SLOT_TOKEN) break;
            List<String> lore = new ArrayList<>();
            lore.add("§7" + task.getObjective());
            lore.add("§7赏金: §e" + task.getBounty() + " 代币");
            lore.add("§7时长: §f" + task.getDurationMinutes() + " 分钟");
            if (session != null && session.getCurrentTask() == task) {
                lore.add("§a▶ 进行中");
            } else {
                lore.add("§8点击开始");
            }
            inventory.setItem(slot, BountyShop.named(new ItemStack(task.getIcon(), 1),
                    "§6" + task.getDisplayName(), lore));
            slotTasks[slot] = task;
            slot++;
        }

        long tokens = manager.tokensOf(viewer);
        inventory.setItem(SLOT_TOKEN, BountyShop.named(new ItemStack(Material.EMERALD, 1),
                "§e我的代币: §f" + tokens,
                Collections.singletonList("§7完成赏金任务或提交漏洞可获得")));

        inventory.setItem(SLOT_RANK, BountyShop.named(new ItemStack(Material.NETHER_STAR, 1),
                "§b赏金猎人排行",
                Collections.singletonList("§7点击查看（也可用 §f/ac ranking§7）")));

        inventory.setItem(SLOT_SHOP, BountyShop.named(new ItemStack(Material.CHEST, 1),
                "§d赏金商城",
                Collections.singletonList("§7用代币兑换称号/特效（不卖战力）")));

        inventory.setItem(SLOT_CASES, BountyShop.named(new ItemStack(Material.BOOK, 1),
                "§f我的提交记录",
                Collections.singletonList("§7查看你提交过的案例与审核状态")));

        if (session != null) {
            inventory.setItem(SIZE - 1, BountyShop.named(new ItemStack(Material.FEATHER, 1),
                    "§c退出沙箱",
                    Collections.singletonList("§7也可用 §f/bounty leave§7")));
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public static void open(AdvancedAntiCheat plugin, BountyManager manager, Player player) {
        player.openInventory(new BountyBoard(plugin, manager, player).getInventory());
    }

    /**
     * 处理点击。
     *
     * @return true = 本次点击属于本界面（调用方应取消事件）
     */
    public static boolean handleClick(AdvancedAntiCheat plugin, BountyManager manager,
                                      Player player, BountyBoard holder, int slot) {
        if (slot < 0 || slot >= SIZE) return true;

        BountyTaskType task = holder.slotTasks[slot];
        if (task != null) {
            manager.startTask(player, task);
            return true;
        }
        if (slot == SLOT_RANK) {
            player.closeInventory();
            manager.showLeaderboard(player);
            return true;
        }
        if (slot == SLOT_SHOP) {
            BountyShop.open(plugin, manager, player);
            return true;
        }
        if (slot == SLOT_CASES) {
            player.closeInventory();
            manager.showMyCases(player);
            return true;
        }
        if (slot == SIZE - 1) {
            player.closeInventory();
            manager.leaveBounty(player);
            return true;
        }
        return true;
    }
}
