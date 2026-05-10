package net.danh.sincebooster.gui;

import net.danh.sincebooster.SinceBooster;
import net.danh.sincebooster.manager.Booster;
import net.danh.sincebooster.utils.ColorUtils;
import net.danh.sincebooster.utils.ConfigUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
 * Multi-stage GUI for selecting a player and a booster to share.
 * Loads purely from gui.yml.
 */
public class ShareGUI implements Listener {

    private final SinceBooster plugin;
    private final NamespacedKey boosterIdKey;

    public ShareGUI(SinceBooster plugin) {
        this.plugin = plugin;
        this.boosterIdKey = new NamespacedKey(plugin, "gui_booster_id");
    }

    private ConfigUtils getGui() {
        return plugin.getGuiFile();
    }

    private ConfigUtils getMsg() {
        return plugin.getMessagesFile();
    }

    public void openPlayerSelector(Player p) {
        if (!p.hasPermission("sincebooster.share")) {
            p.sendMessage(ColorUtils.parseWithPrefix(getMsg().getString("share.no_permission")));
            return;
        }
        String titleStr = getGui().getString("share_gui.player_selector.title", "Select a Player");
        int size = getGui().getInt("share_gui.player_selector.size", 54);
        PlayerSelectorHolder holder = new PlayerSelectorHolder();
        Inventory inv = Bukkit.createInventory(holder, size, ColorUtils.parse(titleStr));
        holder.setInventory(inv);

        List<Integer> slots = getGui().getConfig().getIntegerList("share_gui.player_selector.layout.player_slots");
        int slotIdx = 0;

        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.getUniqueId().equals(p.getUniqueId())) continue;
            if (slotIdx >= slots.size()) break;

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(target);

            String nameFormat = getGui().getString("share_gui.player_selector.items.player_head.name", "<yellow><player_name>");
            meta.displayName(ColorUtils.parse(nameFormat.replace("<player_name>", target.getName())));

            List<Component> lore = new ArrayList<>();
            for (String s : getGui().getConfig().getStringList("share_gui.player_selector.items.player_head.lore")) {
                lore.add(ColorUtils.parse(s));
            }
            meta.lore(lore);
            head.setItemMeta(meta);

            inv.setItem(slots.get(slotIdx++), head);
        }
        inv.setItem(getGui().getInt("share_gui.player_selector.items.back_button.slot"), createBackButton());
        p.openInventory(inv);
    }

    public void openBoosterSelector(Player p, Player target) {
        if (!p.hasPermission("sincebooster.share")) {
            p.sendMessage(ColorUtils.parseWithPrefix(getMsg().getString("share.no_permission")));
            return;
        }
        String titleStr = getGui().getString("share_gui.booster_selector.title", "Select a Booster");
        int size = getGui().getInt("share_gui.booster_selector.size", 54);
        BoosterSelectorHolder holder = new BoosterSelectorHolder(target.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, size, ColorUtils.parse(titleStr));
        holder.setInventory(inv);

        List<Booster> boosters = plugin.getBoosterManager().getActiveBoosters(p.getUniqueId());
        List<Integer> slots = getGui().getConfig().getIntegerList("share_gui.booster_selector.layout.booster_slots");

        if (boosters != null) {
            int slotIdx = 0;
            int maxShare = plugin.getBoosterManager().getShareManager().getPlayerShareLimit(p);

            for (Booster b : boosters) {
                if (!b.isValid()) continue;
                if (slotIdx >= slots.size()) break;
                inv.setItem(slots.get(slotIdx++), createDetailedShareItem(b, p, maxShare));
            }
        }
        inv.setItem(getGui().getInt("share_gui.booster_selector.items.back_button.slot"), createBackButton());
        p.openInventory(inv);
    }

    private ItemStack createDetailedShareItem(Booster b, Player p, int maxShare) {
        Material mat = (b.getProfession() == null) ? Material.NETHER_STAR : Material.ENCHANTED_BOOK;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        meta.getPersistentDataContainer().set(boosterIdKey, PersistentDataType.STRING, b.getId());

        String keyDur = b.isPermanent() ? "name_perm" : "name_time";
        String basePath = "share_gui.booster_selector.items.booster.";

        long left = (b.getEndTime() - System.currentTimeMillis()) / 1000;
        String timeStr = formatTime(Math.max(0, left));
        String id = b.getId().toUpperCase();

        String name = getGui().getString(basePath + keyDur);
        if (name != null) meta.displayName(ColorUtils.parse(name.replace("<id>", id).replace("<time>", timeStr)));

        List<String> loreRaw = getGui().getConfig().getStringList(basePath + "lore");
        List<Component> lore = new ArrayList<>();

        String typeColor = (b.getProfession() == null) ? "<aqua>" : "<green>";
        String typeName = (b.getProfession() == null) ? "Class XP" : "Job: " + b.getProfession().toUpperCase();

        double baseMult = b.getMultiplier();
        double receiverRateConfig = plugin.getPlayerDataHandler().getSession(p.getUniqueId()).getReceiverBuffRate();
        double recEfficiency = receiverRateConfig * 100.0;
        double recMult = 1.0 + ((baseMult - 1.0) * (recEfficiency / 100.0));

        int currentShare = b.getSharedPlayers().size();
        boolean isFull = currentShare >= maxShare;
        String slotColor = isFull ? "<red>" : "<green>";
        String statusText = getGui().getString(isFull ? basePath + "status_full" : basePath + "status_available");
        statusText = statusText.replace("<current>", String.valueOf(currentShare)).replace("<max>", String.valueOf(maxShare));

        for (String line : loreRaw) {
            line = line.replace("<type_color>", typeColor)
                    .replace("<type_name>", typeName)
                    .replace("<multiplier>", String.valueOf(baseMult))
                    .replace("<percent>", String.valueOf((int) ((baseMult - 1.0) * 100)))
                    .replace("<rec_multiplier>", String.format("%.2f", recMult))
                    .replace("<rec_percent>", String.valueOf((int) ((recMult - 1.0) * 100)))
                    .replace("<slot_color>", slotColor)
                    .replace("<status_text>", statusText);

            if (line.contains("\n")) {
                for (String part : line.split("\n")) lore.add(ColorUtils.parse(part));
            } else {
                lore.add(ColorUtils.parse(line));
            }
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private String getBoosterIdFromItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().getOrDefault(boosterIdKey, PersistentDataType.STRING, null);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        InventoryHolder holder = e.getInventory().getHolder(false);

        if (holder instanceof PlayerSelectorHolder) {
            e.setCancelled(true);
            ItemStack item = e.getCurrentItem();
            if (item == null || item.getType() == Material.AIR) return;
            if (e.getClickedInventory() != e.getView().getTopInventory()) return;

            if (isBackButton(item)) {
                plugin.getBoosterGUI().open(p);
                return;
            }

            if (item.getType() == Material.PLAYER_HEAD) {
                SkullMeta meta = (SkullMeta) item.getItemMeta();
                if (meta.getOwningPlayer() != null && meta.getOwningPlayer().isOnline()) {
                    openBoosterSelector(p, meta.getOwningPlayer().getPlayer());
                } else {
                    p.sendMessage(ColorUtils.parseWithPrefix(getMsg().getString("share.invalid_target", "&cPlayer is no longer online!")));
                    p.closeInventory();
                }
            }
        } else if (holder instanceof BoosterSelectorHolder bHolder) {
            e.setCancelled(true);
            ItemStack item = e.getCurrentItem();
            if (item == null || item.getType() == Material.AIR) return;
            if (e.getClickedInventory() != e.getView().getTopInventory()) return;

            if (isBackButton(item)) {
                openPlayerSelector(p);
                return;
            }

            String bId = getBoosterIdFromItem(item);
            if (bId == null) return;

            Player target = Bukkit.getPlayer(bHolder.getTargetUUID());
            if (target != null) {
                plugin.getBoosterManager().getShareManager().sendInvite(p, target, bId);
            } else {
                p.sendMessage(ColorUtils.parseWithPrefix(getMsg().getString("share.invalid_target", "&cPlayer is no longer online!")));
            }
            p.closeInventory();
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder holder = e.getInventory().getHolder(false);
        if (holder instanceof PlayerSelectorHolder || holder instanceof BoosterSelectorHolder) e.setCancelled(true);
    }

    private ItemStack createBackButton() {
        String mat = getGui().getString("share_gui.items.back_button.material", "ARROW");
        ItemStack item = new ItemStack(Material.valueOf(mat));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ColorUtils.parse(getGui().getString("share_gui.items.back_button.name", "&cBack")));
        item.setItemMeta(meta);
        return item;
    }

    private boolean isBackButton(ItemStack item) {
        String matName = getGui().getString("share_gui.items.back_button.material", "ARROW");
        return item.getType().name().equals(matName);
    }

    private String formatTime(long seconds) {
        long d = seconds / 86400, h = (seconds % 86400) / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        return getGui().getString("booster_list.formats.time_left").replace("<day>", String.valueOf(d)).replace("<hour>", String.valueOf(h)).replace("<min>", String.valueOf(m)).replace("<sec>", String.valueOf(s));
    }

    public static class PlayerSelectorHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory != null ? inventory : Bukkit.createInventory(null, 9);
        }

        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }
    }

    public static class BoosterSelectorHolder implements InventoryHolder {
        private final UUID targetUUID;
        private Inventory inventory;

        public BoosterSelectorHolder(UUID targetUUID) {
            this.targetUUID = targetUUID;
        }

        public UUID getTargetUUID() {
            return targetUUID;
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