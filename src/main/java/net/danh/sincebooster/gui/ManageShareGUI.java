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
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * GUI for managing players that a specific booster is shared with.
 * Layout completely derived from gui.yml.
 */
public class ManageShareGUI implements Listener {
    private final SinceBooster plugin;
    private final NamespacedKey targetKey;

    public ManageShareGUI(SinceBooster plugin) {
        this.plugin = plugin;
        this.targetKey = new NamespacedKey(plugin, "manage_target_uuid");
    }

    private ConfigUtils getGui() {
        return plugin.getGuiFile();
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

        String title = getGui().getString("manage_share.title", "Manage Shares");
        int size = getGui().getInt("manage_share.size", 27);
        ManageHolder holder = new ManageHolder(boosterId);
        Inventory inv = Bukkit.createInventory(holder, size, ColorUtils.parse(title));
        holder.setInventory(inv);

        List<Integer> playerSlots = getGui().getConfig().getIntegerList("manage_share.layout.player_slots");
        int slotIdx = 0;

        if (booster.getSharedPlayers().isEmpty()) {
            inv.setItem(getGui().getInt("manage_share.items.empty_slot.slot", 13), createEmptyItem());
        } else {
            for (UUID targetUUID : booster.getSharedPlayers()) {
                if (slotIdx >= playerSlots.size()) break;
                OfflinePlayer target = Bukkit.getOfflinePlayer(targetUUID);
                inv.setItem(playerSlots.get(slotIdx++), createPlayerHead(target));
            }
        }

        p.openInventory(inv);
    }

    private ItemStack createPlayerHead(OfflinePlayer target) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(target);

        String name = getGui().getString("manage_share.items.player_head.name").replace("<player>", target.getName() != null ? target.getName() : "Unknown");
        meta.displayName(ColorUtils.parse(name));

        List<Component> lore = new ArrayList<>();
        for (String s : getGui().getConfig().getStringList("manage_share.items.player_head.lore")) {
            lore.add(ColorUtils.parse(s));
        }
        meta.lore(lore);

        meta.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING, target.getUniqueId().toString());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createEmptyItem() {
        String matName = getGui().getString("manage_share.items.empty_slot.material", "BARRIER");
        ItemStack item = new ItemStack(Material.valueOf(matName));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ColorUtils.parse(getGui().getString("manage_share.items.empty_slot.name", "&cNo Shares Active")));
        List<Component> lore = new ArrayList<>();
        for (String s : getGui().getConfig().getStringList("manage_share.items.empty_slot.lore"))
            lore.add(ColorUtils.parse(s));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!(e.getInventory().getHolder(false) instanceof ManageHolder holder)) return;

        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;

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
                if (uuidStr != null) {
                    OfflinePlayer target = Bukkit.getOfflinePlayer(UUID.fromString(uuidStr));
                    plugin.getBoosterManager().getShareManager().kickShare(p, holder.getBoosterId(), target);
                    open(p, holder.getBoosterId());
                }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder(false) instanceof ManageHolder) e.setCancelled(true);
    }

    public static class ManageHolder implements InventoryHolder {
        private final String boosterId;
        private Inventory inventory;

        public ManageHolder(String id) {
            this.boosterId = id;
        }

        public String getBoosterId() {
            return boosterId;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory != null ? inventory : Bukkit.createInventory(null, 9);
        }

        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }
    }
}