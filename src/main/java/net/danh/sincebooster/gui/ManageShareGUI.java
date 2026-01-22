package net.danh.sincebooster.gui;

import net.danh.sincebooster.SinceBooster;
import net.danh.sincebooster.manager.Booster;
import net.danh.sincebooster.utils.ColorUtils;
import net.danh.sincebooster.utils.ConfigUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ManageShareGUI implements Listener {
    private final SinceBooster plugin;
    private final NamespacedKey targetKey;

    public ManageShareGUI(SinceBooster plugin) {
        this.plugin = plugin;
        this.targetKey = new NamespacedKey(plugin, "manage_target_uuid");
    }

    private ConfigUtils getMsg() {
        return plugin.getMessagesFile();
    }

    public void open(Player p, String boosterId) {
        if (!p.hasPermission("sincebooster.share")) {
            p.sendMessage(ColorUtils.parseWithPrefix(getMsg().getString("share.no_permission")));
            return;
        }
        Booster booster = null;
        List<Booster> list = plugin.getBoosterManager().getActiveBoosters(p.getUniqueId());
        if (list != null) {
            for (Booster b : list) {
                if (b.getId().equalsIgnoreCase(boosterId)) {
                    booster = b;
                    break;
                }
            }
        }

        if (booster == null) return;

        String title = getMsg().getString("manage_share_gui.title", "Quản lý Share");
        Inventory inv = Bukkit.createInventory(new ManageHolder(boosterId), 27, ColorUtils.parse(title));

        int slot = 0;
        if (booster.getSharedPlayers().isEmpty()) {
            inv.setItem(13, createEmptyItem());
        } else {
            for (UUID targetUUID : booster.getSharedPlayers()) {
                OfflinePlayer target = Bukkit.getOfflinePlayer(targetUUID);
                inv.setItem(slot++, createPlayerHead(target));
            }
        }

        p.openInventory(inv);
    }

    private ItemStack createPlayerHead(OfflinePlayer target) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(target);

        String name = getMsg().getString("manage_share_gui.item_name").replace("<player>", target.getName() != null ? target.getName() : "Unknown");
        meta.displayName(ColorUtils.parse(name));

        List<Component> lore = new ArrayList<>();
        for (String s : getMsg().getConfig().getStringList("manage_share_gui.lore")) {
            lore.add(ColorUtils.parse(s));
        }
        meta.lore(lore);

        meta.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING, target.getUniqueId().toString());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createEmptyItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ColorUtils.parse(getMsg().getString("manage_share_gui.empty.name")));
        List<Component> lore = new ArrayList<>();
        for (String s : getMsg().getConfig().getStringList("manage_share_gui.empty.lore"))
            lore.add(ColorUtils.parse(s));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!(e.getInventory().getHolder() instanceof ManageHolder holder)) return;

        e.setCancelled(true);
        ItemStack item = e.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) return;

        if (item.getType() == Material.PLAYER_HEAD) {
            if (!p.hasPermission("sincebooster.share")) {
                p.sendMessage(ColorUtils.parseWithPrefix(getMsg().getString("share.no_permission")));
                p.closeInventory();
                return;
            }
            ItemMeta meta = item.getItemMeta();
            if (meta.getPersistentDataContainer().has(targetKey, PersistentDataType.STRING)) {
                String uuidStr = meta.getPersistentDataContainer().get(targetKey, PersistentDataType.STRING);
                UUID targetUUID = null;
                if (uuidStr != null) {
                    targetUUID = UUID.fromString(uuidStr);
                }
                OfflinePlayer target = null;
                if (targetUUID != null) {
                    target = Bukkit.getOfflinePlayer(targetUUID);
                }

                // KICK logic
                plugin.getBoosterManager().getShareManager().kickShare(p, holder.boosterId, target);

                // Refresh
                open(p, holder.boosterId);
            }
        }
    }

    public static class ManageHolder implements InventoryHolder {
        public final String boosterId;

        public ManageHolder(String id) {
            this.boosterId = id;
        }

        @Override
        public @Nullable Inventory getInventory() {
            return null;
        }
    }
}