package com.anticheat.gui;

import com.anticheat.AdvancedAntiCheat;
import com.anticheat.listeners.ProfileGUIListener;
import com.anticheat.profiles.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

public class ProfileGUI {
    private final AdvancedAntiCheat plugin;
    private final ProfileGUIListener listener;

    public ProfileGUI(AdvancedAntiCheat plugin, ProfileGUIListener listener) {
        this.plugin = plugin;
        this.listener = listener;
    }

    public void openProfileGUI(Player viewer, Player target) {
        PlayerProfile profile = plugin.getProfileManager().getOrCreateProfile(target);
        Inventory inv = Bukkit.createInventory(null, 54, ChatColor.DARK_GRAY + "Profile");

        fillHeader(inv, target, profile);
        fillActionBar(inv, target);

        listener.registerGUI(viewer, target);
        viewer.openInventory(inv);
    }

    private void fillHeader(Inventory inv, Player target, PlayerProfile profile) {
        ItemStack head = createPlayerHead(target, profile);
        inv.setItem(4, head);
    }

    private void fillActionBar(Inventory inv, Player target) {
        ItemStack observeItem = createActionItem(Material.ENDER_PEARL, ChatColor.AQUA + "Observe Silently", "Teleport to player invisibly");
        inv.setItem(45, observeItem);

        ItemStack captchaItem = createActionItem(Material.ANVIL, ChatColor.GOLD + "Force Captcha", "Trigger captcha immediately");
        inv.setItem(47, captchaItem);

        ItemStack ban1dItem = createActionItem(Material.REDSTONE_BLOCK, ChatColor.RED + "Ban 1 Day", "Ban for 1 day");
        inv.setItem(48, ban1dItem);

        ItemStack ban7dItem = createActionItem(Material.REDSTONE_BLOCK, ChatColor.RED + "Ban 7 Days", "Ban for 7 days");
        inv.setItem(49, ban7dItem);

        ItemStack banPermItem = createActionItem(Material.REDSTONE_BLOCK, ChatColor.DARK_RED + "Perma-Ban", "Ban permanently");
        inv.setItem(50, banPermItem);

        ItemStack resetItem = createActionItem(Material.BARRIER, ChatColor.YELLOW + "Reset Baseline", "Clear behavior history");
        inv.setItem(51, resetItem);
    }

    private ItemStack createPlayerHead(Player player, PlayerProfile profile) {
        Material headMaterial;
        short durability = 0;
        
        try {
            headMaterial = Material.valueOf("SKULL_ITEM");
            durability = 3;
        } catch (IllegalArgumentException e) {
            try {
                headMaterial = Material.valueOf("PLAYER_HEAD");
            } catch (IllegalArgumentException e2) {
                headMaterial = Material.GOLD_BLOCK;
            }
        }
        
        ItemStack head;
        if (durability > 0) {
            head = new ItemStack(headMaterial, 1, durability);
        } else {
            head = new ItemStack(headMaterial, 1);
        }
        
        ItemMeta meta = head.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + player.getName());
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "UUID: " + player.getUniqueId());
        meta.setLore(lore);
        head.setItemMeta(meta);
        return head;
    }

    private ItemStack createActionItem(Material mat, String name, String desc) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + desc);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }
}

