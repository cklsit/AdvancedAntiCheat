package com.anticheat.bounty;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.utils.VersionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 赏金商城（文档第五节）。
 *
 * <h3>只卖"不破坏平衡"的东西</h3>
 * 目录里刻意只有三类：**称号**（授权一个权限节点，由服务端已有的前缀插件去显示）、
 * **粒子特效**（击杀时的纯视觉反馈）、**纪念品**（纯记录，只用来展示）。
 * 绝不出现装备、材料、经验、领地这类影响生存与竞争的东西——代币是"贡献的证明"，
 * 一旦能换成战力，赏金计划就会退化成"用外挂刷代币"，与"化敌为友"的初衷正好相反。
 *
 * <h3>为什么称号用"授权权限"而不是自己写聊天格式</h3>
 * 服务端上通常已经有聊天/前缀插件（本项目生产环境就有），自己再实现一套显示逻辑
 * 会与它们打架（重复前缀、颜色错乱）。授权 `bounty.title.&lt;id&gt;` 权限，
 * 让现有的前缀插件去读，是唯一不会互相干扰的做法。
 *
 * <h3>跨版本</h3>
 * 图标只用 1.8 与 1.21 都存在的 {@link Material} 枚举常量；粒子走
 * {@link Effect#MOBSPAWNER_FLAMES} + {@link org.bukkit.World#playEffect}，
 * 调用点包在 try/catch 里——它纯粹是装饰，任何版本差异都不该影响玩法。
 */
public class BountyShop implements InventoryHolder {

    /** 商城条目类型。 */
    public enum Kind {
        /** 称号：授权 `bounty.title.<id>` 权限。 */
        TITLE,
        /** 粒子特效：击杀时播放。 */
        PARTICLE,
        /** 纪念品：纯记录。 */
        SOUVENIR
    }

    /** 一个商城条目。 */
    public static class ShopItem {
        private final String id;
        private final String displayName;
        private final String description;
        private final int cost;
        private final boolean oneTime;
        private final Kind kind;
        private final Material icon;

        ShopItem(String id, String displayName, String description, int cost,
                 boolean oneTime, Kind kind, Material icon) {
            this.id = id;
            this.displayName = displayName;
            this.description = description;
            this.cost = cost;
            this.oneTime = oneTime;
            this.kind = kind;
            this.icon = icon;
        }

        public String getId() {
            return id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDescription() {
            return description;
        }

        public int getCost() {
            return cost;
        }

        public boolean isOneTime() {
            return oneTime;
        }

        public Kind getKind() {
            return kind;
        }

        public Material getIcon() {
            return icon;
        }

        /** 称号对应的权限节点。 */
        public String getPermission() {
            return "bounty.title." + id;
        }
    }

    /**
     * 目录。价格刻意拉开档次：称号 200~2000，特效 500。
     * 参照物是任务赏金（10~150）与高危赏金（500），所以一次真发现就能买一件东西，
     * 而攒齐全部称号需要长期贡献。
     */
    private static final List<ShopItem> CATALOG = Collections.unmodifiableList(Arrays.asList(
            new ShopItem("apprentice", "§a监察学徒", "称号（需配合服务端前缀插件显示）", 200, true, Kind.TITLE, Material.PAPER),
            new ShopItem("elite", "§b监察精英", "称号（需配合服务端前缀插件显示）", 800, true, Kind.TITLE, Material.BOOK),
            new ShopItem("master", "§6试炼大师", "称号（需配合服务端前缀插件显示）", 2000, true, Kind.TITLE, Material.NETHER_STAR),
            new ShopItem("flame", "§c击杀火焰特效", "击杀时播放火焰粒子（纯视觉）", 500, true, Kind.PARTICLE, Material.BLAZE_POWDER),
            new ShopItem("badge", "§f白帽徽章", "纪念品，记录你为服务器做过的事", 100, true, Kind.SOUVENIR, Material.GOLD_NUGGET)
    ));

    private static final int ROWS = 3;
    private static final int SIZE = ROWS * 9;
    private static final String TITLE = "§8赏金商城";

    private final Inventory inventory;
    /** 槽位 → 条目；点不到的槽位为 null。 */
    private final ShopItem[] slotItems = new ShopItem[SIZE];

    private BountyShop(AdvancedAntiCheat plugin, BountyManager manager, Player viewer) {
        this.inventory = Bukkit.createInventory(this, SIZE, TITLE);
        // 填充物走项目的 compatBarrier()：1.8 没有 Material.BARRIER，
        // 而直接写 Material.STAINED_GLASS_PANE 这类「只在旧版存在」的常量会在
        // 高版本编译期就找不到（或反过来在 1.8 运行期 NoSuchFieldError）。
        ItemStack filler = named(new ItemStack(VersionUtil.compatBarrier(), 1), "§8", null);
        for (int i = 0; i < SIZE; i++) {
            inventory.setItem(i, filler);
        }
        int slot = 10;
        for (ShopItem item : CATALOG) {
            if (slot >= SIZE) break;
            boolean owned = manager.hasPurchased(viewer, item);
            List<String> lore = new ArrayList<>();
            lore.add("§7" + item.getDescription());
            lore.add("§7价格: §e" + item.getCost() + " 代币");
            if (item.isOneTime()) {
                lore.add(owned ? "§a已拥有" : "§7一次性");
            } else {
                lore.add("§7可重复购买");
            }
            lore.add("§8点击" + (owned && item.isOneTime() ? "（已拥有）" : "兑换"));
            inventory.setItem(slot, named(new ItemStack(item.getIcon(), 1), item.getDisplayName(), lore));
            slotItems[slot] = item;
            slot++;
        }
        // 余额提示放最后一格
        long balance = manager.tokensOf(viewer);
        inventory.setItem(SIZE - 1, named(new ItemStack(Material.EMERALD, 1),
                "§e你的代币: §f" + balance, Collections.singletonList("§7赏金任务与漏洞发现可获得")));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public static void open(AdvancedAntiCheat plugin, BountyManager manager, Player player) {
        player.openInventory(new BountyShop(plugin, manager, player).getInventory());
    }

    /**
     * 处理一次点击。
     *
     * @return true = 本次点击属于本界面（调用方应取消事件）
     */
    public static boolean handleClick(AdvancedAntiCheat plugin, BountyManager manager,
                                      Player player, BountyShop holder, int slot) {
        if (slot < 0 || slot >= SIZE) return true;
        ShopItem item = holder.slotItems[slot];
        if (item == null) return true;

        if (item.isOneTime() && manager.hasPurchased(player, item)) {
            player.sendMessage("§e你已经拥有 " + item.getDisplayName() + " §e了");
            return true;
        }
        long balance = manager.tokensOf(player);
        if (balance < item.getCost()) {
            player.sendMessage("§c代币不足：需要 " + item.getCost() + "，你有 " + balance);
            return true;
        }

        // 扣款与记录在仓储层是同一个事务（见 BountyRepository.purchase）
        boolean ok = manager.purchase(player, item);
        if (!ok) {
            player.sendMessage("§c兑换失败（代币不足或已拥有）");
            return true;
        }

        if (item.getKind() == Kind.TITLE) {
            grantTitle(plugin, player, item);
            player.sendMessage("§a已获得称号 " + item.getDisplayName() + "§a（由服务端前缀插件显示）");
        } else if (item.getKind() == Kind.PARTICLE) {
            player.sendMessage("§a已获得击杀火焰特效");
        } else {
            player.sendMessage("§a已获得纪念品 " + item.getDisplayName());
        }
        // 刷新界面，让"已拥有/余额"立刻正确
        Bukkit.getScheduler().runTask(plugin, () -> open(plugin, manager, player));
        return true;
    }

    /** 把已购称号以权限附件的形式挂上（`bounty_purchase` 才是持久化的真相）。 */
    public static void applyTitles(AdvancedAntiCheat plugin, BountyManager manager, Player player) {
        for (ShopItem item : CATALOG) {
            if (item.getKind() != Kind.TITLE) continue;
            if (!manager.hasPurchased(player, item)) continue;
            grantTitle(plugin, player, item);
        }
    }

    private static void grantTitle(AdvancedAntiCheat plugin, Player player, ShopItem item) {
        try {
            player.addAttachment(plugin, item.getPermission(), true);
        } catch (Throwable t) {
            plugin.getLogger().warning("[Bounty] 授权称号失败: " + item.getId() + " -> " + t.getMessage());
        }
    }

    /** 是否已购买该特效（击杀时播放用）。 */
    public static ShopItem particleItem() {
        for (ShopItem item : CATALOG) {
            if (item.getKind() == Kind.PARTICLE) return item;
        }
        return null;
    }

    /**
     * 击杀特效（纯装饰）。
     *
     * <p>整段包 try/catch：粒子 API 在两个服务端版本上差异较大，而它坏掉
     * 不该影响任何玩法判定。</p>
     */
    public static void playKillEffect(Player killer) {
        try {
            Location location = killer.getLocation();
            location.getWorld().playEffect(location, Effect.MOBSPAWNER_FLAMES, 0);
        } catch (Throwable ignored) {
            // 装饰性效果，失败静默即可
        }
    }

    public static List<ShopItem> catalog() {
        return CATALOG;
    }

    /** 建一个带名字与描述的物品。 */
    static ItemStack named(ItemStack stack, String name, List<String> lore) {
        try {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(name);
                if (lore != null) meta.setLore(lore);
                stack.setItemMeta(meta);
            }
        } catch (Throwable ignored) {
            // 元数据失败时退回裸物品，界面仍可用
        }
        return stack;
    }

    static String money(long tokens) {
        return String.format(Locale.ROOT, "%,d", tokens);
    }
}
