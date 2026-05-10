package net.danh.sincebooster.gui;

import net.danh.sincebooster.SinceBooster;
import net.danh.sincebooster.data.PlayerDataHandler;
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
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Main GUI class for viewing owned and received boosters.
 * Reads layout entirely from gui.yml.
 */
public class BoosterGUI implements Listener {

    private final SinceBooster plugin;
    private final NamespacedKey boosterIdKey;
    private final NamespacedKey ownerUuidKey;
    private final NamespacedKey cooldownKey;

    public BoosterGUI(SinceBooster plugin) {
        this.plugin = plugin;
        this.boosterIdKey = new NamespacedKey(plugin, "gui_booster_id");
        this.ownerUuidKey = new NamespacedKey(plugin, "gui_owner_uuid");
        this.cooldownKey = new NamespacedKey(plugin, "gui_cooldown");
    }

    private ConfigUtils getGui() {
        return plugin.getGuiFile();
    }

    private ConfigUtils getMsg() {
        return plugin.getMessagesFile();
    }

    public void startUpdateTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    Inventory topInv = p.getOpenInventory().getTopInventory();
                    if (topInv.getHolder(false) instanceof BoosterHolder holder) {
                        updateContent(topInv, holder.getTargetUUID(), p);
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    public void open(Player p) {
        open(p, p);
    }

    public void open(Player viewer, Player target) {
        String titleStr;
        if (viewer.getUniqueId().equals(target.getUniqueId())) {
            titleStr = getGui().getString("booster_list.title", "Boosters List");
        } else {
            titleStr = getGui().getString("booster_list.other_title", "Boosters: <target>").replace("<target>", target.getName());
        }

        int size = getGui().getInt("booster_list.size", 54);
        BoosterHolder holder = new BoosterHolder(target.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, size, ColorUtils.parse(titleStr));
        holder.setInventory(inv);

        updateContent(inv, target.getUniqueId(), viewer);
        viewer.openInventory(inv);
    }

    private void updateContent(Inventory inv, UUID targetUUID, Player viewer) {
        List<DisplayBooster> displayList = new ArrayList<>();

        List<Booster> ownList = plugin.getBoosterManager().getActiveBoosters(targetUUID);
        if (ownList != null) {
            for (Booster b : ownList) {
                if (b.isValid())
                    displayList.add(new DisplayBooster(b, true, Bukkit.getOfflinePlayer(targetUUID).getName(), targetUUID));
            }
        }

        for (Map.Entry<UUID, List<Booster>> entry : plugin.getBoosterManager().getActiveBoosters().entrySet()) {
            UUID ownerUUID = entry.getKey();
            if (ownerUUID.equals(targetUUID)) continue;

            List<Booster> otherList = entry.getValue();
            if (otherList != null) {
                for (Booster b : otherList) {
                    if (b.isValid() && b.getSharedPlayers().contains(targetUUID)) {
                        Player onlineOwner = Bukkit.getPlayer(ownerUUID);
                        String ownerName = (onlineOwner != null) ? onlineOwner.getName() : getOfflineName(ownerUUID);
                        displayList.add(new DisplayBooster(b, false, ownerName, ownerUUID));
                    }
                }
            }
        }

        Map<UUID, List<Booster>> offlineShares = plugin.getBoosterManager().getShareManager().getCachedOfflineShares(targetUUID);
        if (offlineShares != null) {
            for (Map.Entry<UUID, List<Booster>> entry : offlineShares.entrySet()) {
                UUID ownerUUID = entry.getKey();
                if (Bukkit.getPlayer(ownerUUID) != null) continue;

                List<Booster> boosters = entry.getValue();
                String ownerName = getOfflineName(ownerUUID) + " (Off)";

                for (Booster b : boosters) {
                    if (b.isValid() && b.getSharedPlayers().contains(targetUUID)) {
                        displayList.add(new DisplayBooster(b, false, ownerName, ownerUUID));
                    }
                }
            }
        }

        List<DisplayBooster> classBoosters = new ArrayList<>();
        List<DisplayBooster> profBoosters = new ArrayList<>();

        for (DisplayBooster db : displayList) {
            if (db.booster.getProfession() == null) classBoosters.add(db);
            else profBoosters.add(db);
        }

        Comparator<DisplayBooster> sorter = (d1, d2) -> {
            if (d1.isOwner && !d2.isOwner) return -1;
            if (!d1.isOwner && d2.isOwner) return 1;
            if (d1.booster.isPermanent() && !d2.booster.isPermanent()) return -1;
            if (!d1.booster.isPermanent() && d2.booster.isPermanent()) return 1;
            return Long.compare(d1.booster.getEndTime(), d2.booster.getEndTime());
        };
        classBoosters.sort(sorter);
        profBoosters.sort(sorter);

        List<Integer> classSlots = getGui().getConfig().getIntegerList("booster_list.layout.class_boosters");
        List<Integer> profSlots = getGui().getConfig().getIntegerList("booster_list.layout.prof_boosters");
        fillSection(inv, classBoosters, classSlots, viewer.getUniqueId());
        fillSection(inv, profBoosters, profSlots, viewer.getUniqueId());

        // Fill separators
        ItemStack glass = createItemFromConfig("booster_list.items.separator");
        for (int slot : getGui().getConfig().getIntegerList("booster_list.layout.separators")) {
            inv.setItem(slot, glass);
        }

        // Set static summary & buttons
        inv.setItem(getGui().getInt("booster_list.items.class_summary.slot"), createClassSummary(displayList));
        inv.setItem(getGui().getInt("booster_list.items.prof_summary.slot"), createProfSummary(displayList));
        inv.setItem(getGui().getInt("booster_list.items.share_button.slot"), createItemFromConfig("booster_list.items.share_button"));

        if (viewer.getUniqueId().equals(targetUUID)) {
            inv.setItem(getGui().getInt("booster_list.items.offline_toggle_button.slot"), createOfflineToggleItem(viewer));
        }
    }

    private String getOfflineName(UUID uuid) {
        OfflinePlayer p = Bukkit.getOfflinePlayer(uuid);
        return p.getName() != null ? p.getName() : uuid.toString().substring(0, 8);
    }

    private void fillSection(Inventory inv, List<DisplayBooster> list, List<Integer> slots, UUID viewerUUID) {
        int limit = slots.size();
        for (int i = 0; i < limit; i++) {
            int slot = slots.get(i);
            ItemStack newItem;

            if (i < list.size()) {
                newItem = createBoosterItem(list.get(i), viewerUUID);
            } else {
                if (list.isEmpty() && i == (limit / 2)) newItem = createItemFromConfig("booster_list.items.empty_slot");
                else newItem = null;
            }

            ItemStack currentItem = inv.getItem(slot);

            if (newItem == null) {
                if (currentItem != null) inv.setItem(slot, null);
                continue;
            }

            if (currentItem == null || currentItem.getType() != newItem.getType()) {
                inv.setItem(slot, newItem);
            } else {
                ItemMeta currentMeta = currentItem.getItemMeta();
                ItemMeta newMeta = newItem.getItemMeta();
                if (!Bukkit.getItemFactory().equals(currentMeta, newMeta)) {
                    currentItem.setItemMeta(newMeta);
                }
            }
        }
    }

    private ItemStack createBoosterItem(DisplayBooster db, UUID viewerUUID) {
        Booster b = db.booster;
        Material mat = db.isOwner ? ((b.getProfession() == null) ? Material.NETHER_STAR : Material.ENCHANTED_BOOK) : Material.EXPERIENCE_BOTTLE;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        meta.getPersistentDataContainer().set(boosterIdKey, PersistentDataType.STRING, b.getId());
        meta.getPersistentDataContainer().set(ownerUuidKey, PersistentDataType.STRING, db.ownerUUID.toString());

        String keyType = db.isOwner ? "own" : "received";
        String keyDur = b.isPermanent() ? "perm" : "time";
        String basePath = "booster_list.items." + keyType + ".";

        long left = (b.getEndTime() - System.currentTimeMillis()) / 1000;
        String timeStr = formatTime(Math.max(0, left));
        String id = b.getId().toUpperCase();

        String name = getGui().getString(basePath + "name_" + keyDur);
        if (name != null) meta.displayName(ColorUtils.parse(name.replace("<id>", id).replace("<time>", timeStr)));

        List<String> loreRaw = getGui().getConfig().getStringList(basePath + "lore");
        List<Component> lore = new ArrayList<>();

        String typeColor = (b.getProfession() == null) ? "<aqua>" : "<green>";
        String typeName = (b.getProfession() == null) ? "Class XP" : "Job: " + b.getProfession().toUpperCase();
        String statusPath = b.isPermanent() ? "booster_list.items.status_perm" : "booster_list.items.status_time";
        String status = getGui().getString(statusPath).replace("<time_left>", timeStr);

        for (String line : loreRaw) {
            line = line.replace("<type_color>", typeColor).replace("<type_name>", typeName).replace("<status>", status);

            if (db.isOwner) {
                double decayRate = 1.0;
                double efficiency = 100.0;
                if (!b.getSharedPlayers().isEmpty()) {
                    if (b.isPermanent()) {
                        efficiency = plugin.getBoosterManager().getShareManager().getOwnerBuffRate(db.ownerUUID) * 100.0;
                    } else {
                        Player p = Bukkit.getPlayer(db.ownerUUID);
                        if (p != null) decayRate = plugin.getBoosterManager().getShareManager().getDecayRate(p);
                        else decayRate = plugin.getConfigFile().getDouble("share.default-rate", 2.0);
                    }
                }

                String sharedListStr;
                if (b.getSharedPlayers().isEmpty()) {
                    sharedListStr = getGui().getString("booster_list.items.shared_list_none", "&7- (No active shares)");
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (UUID uid : b.getSharedPlayers()) {
                        OfflinePlayer op = Bukkit.getOfflinePlayer(uid);
                        String pName = (op.getName() != null) ? op.getName() : "Unknown";
                        String format = getGui().getString("booster_list.items.shared_list_format", "&7- &f<player>");
                        sb.append(format.replace("<player>", pName)).append("\n");
                    }
                    sharedListStr = sb.toString().trim();
                }

                line = line.replace("<multiplier>", String.valueOf(b.getMultiplier()))
                        .replace("<percent>", String.valueOf((int) ((b.getMultiplier() - 1) * 100)))
                        .replace("<decay_rate>", String.format("%.1f", decayRate))
                        .replace("<efficiency>", String.format("%.0f", efficiency))
                        .replace("<shared_count>", String.valueOf(b.getSharedPlayers().size()))
                        .replace("<shared_list>", sharedListStr);
            } else {
                double baseMult = b.getMultiplier();
                double currentRate = plugin.getBoosterManager().getShareManager().getReceiverMultiplier(b, viewerUUID);
                double efficiency = currentRate * 100.0;
                double realMult = 1.0 + ((baseMult - 1.0) * currentRate);

                line = line.replace("<owner_name>", db.ownerName)
                        .replace("<base_multiplier>", String.valueOf(baseMult))
                        .replace("<efficiency>", String.format("%.0f", efficiency))
                        .replace("<real_multiplier>", String.format("%.2f", realMult));
            }

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

    private ItemStack createItemFromConfig(String path) {
        String matName = getGui().getString(path + ".material", "STONE");
        ItemStack item = new ItemStack(Material.valueOf(matName));
        ItemMeta meta = item.getItemMeta();
        if (getGui().getString(path + ".name") != null) {
            meta.displayName(ColorUtils.parse(getGui().getString(path + ".name")));
        }
        List<Component> lore = new ArrayList<>();
        for (String s : getGui().getConfig().getStringList(path + ".lore")) lore.add(ColorUtils.parse(s));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createClassSummary(List<DisplayBooster> list) {
        ItemStack item = createItemFromConfig("booster_list.items.class_summary");
        ItemMeta meta = item.getItemMeta();
        double totalAdd = 0, ownAdd = 0, sharedAdd = 0;
        for (DisplayBooster db : list) {
            if (db.booster.getProfession() == null) {
                double bonus = displayBoosterBonus(db);
                totalAdd += bonus;
                if (db.isOwner) ownAdd += bonus;
                else sharedAdd += bonus;
            }
        }
        List<Component> lore = new ArrayList<>();
        for (String line : getGui().getConfig().getStringList("booster_list.items.class_summary.lore")) {
            line = line.replace("<total_multiplier>", String.format("%.2f", 1.0 + totalAdd))
                    .replace("<total_percent>", String.valueOf((int) (totalAdd * 100)))
                    .replace("<own_percent>", String.valueOf((int) (ownAdd * 100)))
                    .replace("<shared_percent>", String.valueOf((int) (sharedAdd * 100)));
            lore.add(ColorUtils.parse(line));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createProfSummary(List<DisplayBooster> list) {
        ItemStack item = createItemFromConfig("booster_list.items.prof_summary");
        ItemMeta meta = item.getItemMeta();
        Map<String, Double> totals = new HashMap<>();
        for (DisplayBooster db : list) {
            String p = db.booster.getProfession();
            if (p != null) {
                double bonus = displayBoosterBonus(db);
                totals.put(p, totals.getOrDefault(p, 0.0) + bonus);
            }
        }
        String format = getGui().getString("booster_list.items.prof_summary.prof_format");
        String none = getGui().getString("booster_list.items.prof_summary.prof_none");
        List<Component> lore = new ArrayList<>();
        for (String line : getGui().getConfig().getStringList("booster_list.items.prof_summary.lore")) {
            if (line.contains("<prof_list>")) {
                if (totals.isEmpty()) lore.add(ColorUtils.parse(none));
                else {
                    for (Map.Entry<String, Double> entry : totals.entrySet()) {
                        double val = 1.0 + entry.getValue();
                        String f = format.replace("<profession>", entry.getKey().toUpperCase())
                                .replace("<multiplier>", String.format("%.2f", val))
                                .replace("<percent>", String.valueOf((int) (entry.getValue() * 100)));
                        lore.add(ColorUtils.parse(f));
                    }
                }
            } else {
                lore.add(ColorUtils.parse(line));
            }
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private double displayBoosterBonus(DisplayBooster db) {
        double bonus = db.booster.getMultiplier() - 1.0;
        if (db.booster.isPermanent() && !db.booster.getSharedPlayers().isEmpty()) {
            double rate = db.isOwner ? plugin.getBoosterManager().getShareManager().getOwnerBuffRate(db.ownerUUID) : plugin.getBoosterManager().getShareManager().getReceiverBuffRate(db.ownerUUID);
            bonus *= rate;
        }
        return bonus;
    }

    private String formatTime(long seconds) {
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        String format = getGui().getString("booster_list.formats.time_left");
        return format.replace("<day>", String.valueOf(days)).replace("<hour>", String.valueOf(hours)).replace("<min>", String.valueOf(minutes)).replace("<sec>", String.valueOf(secs));
    }

    private ItemStack createOfflineToggleItem(Player p) {
        boolean hasPerm = p.hasPermission("sincebooster.share.offline");
        boolean isEnabled = false;

        if (hasPerm) {
            PlayerDataHandler.PlayerSession s = plugin.getPlayerDataHandler().getSession(p.getUniqueId());
            if (s != null) isEnabled = s.isOfflineShareEnabled();
        }
        String stateKey = !hasPerm ? "no_perm" : (isEnabled ? "enabled" : "disabled");
        return createItemFromConfig("booster_list.items.offline_toggle_button." + stateKey);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof BoosterHolder holder)) return;

        e.setCancelled(true);
        UUID targetUUID = holder.getTargetUUID();

        if (e.getClickedInventory() == null || e.getClickedInventory() == e.getView().getTopInventory()) {
            int slot = e.getSlot();
            int shareSlot = getGui().getInt("booster_list.items.share_button.slot");
            int toggleSlot = getGui().getInt("booster_list.items.offline_toggle_button.slot");
            Player p = (Player) e.getWhoClicked();
            boolean isSelfView = targetUUID.equals(p.getUniqueId());

            if (slot == shareSlot) {
                if (isSelfView) {
                    if (!p.hasPermission("sincebooster.share")) {
                        p.sendMessage(ColorUtils.parseWithPrefix(getMsg().getString("share.no_permission")));
                        p.closeInventory();
                        return;
                    }
                    new ShareGUI(plugin).openPlayerSelector(p);
                }
                return;
            }

            if (slot == toggleSlot && isSelfView) {
                if (!p.hasPermission("sincebooster.share.offline")) {
                    p.sendMessage(ColorUtils.parseWithPrefix(getMsg().getString("share.offline_no_perm")));
                    return;
                }

                if (p.getPersistentDataContainer().has(cooldownKey, PersistentDataType.LONG)) {
                    Long lastClick = p.getPersistentDataContainer().get(cooldownKey, PersistentDataType.LONG);
                    if (lastClick != null && System.currentTimeMillis() - lastClick < 2000) {
                        p.sendMessage(ColorUtils.parseWithPrefix(getMsg().getString("booster.gui_action_cooldown", "&cPlease slow down!")));
                        return;
                    }
                }
                p.getPersistentDataContainer().set(cooldownKey, PersistentDataType.LONG, System.currentTimeMillis());

                PlayerDataHandler.PlayerSession session = plugin.getPlayerDataHandler().getSession(p.getUniqueId());
                if (session != null) {
                    boolean current = session.isOfflineShareEnabled();
                    session.setOfflineShareEnabled(!current);
                    plugin.getPlayerDataHandler().saveData(p.getUniqueId(), false);

                    p.sendMessage(ColorUtils.parseWithPrefix(getMsg().getString(current ? "share.offline_toggle_off" : "share.offline_toggle_on")));
                    updateContent(e.getInventory(), targetUUID, p);
                }
                return;
            }

            ItemStack item = e.getCurrentItem();
            if (item != null && item.getType() != Material.AIR && item.hasItemMeta()) {
                ItemMeta meta = item.getItemMeta();
                if (meta.getPersistentDataContainer().has(boosterIdKey, PersistentDataType.STRING)) {
                    String bId = meta.getPersistentDataContainer().get(boosterIdKey, PersistentDataType.STRING);
                    String ownerUuidStr = meta.getPersistentDataContainer().get(ownerUuidKey, PersistentDataType.STRING);
                    UUID boosterOwnerUUID = (ownerUuidStr != null) ? UUID.fromString(ownerUuidStr) : null;

                    if (Objects.equals(boosterOwnerUUID, p.getUniqueId())) {
                        if (isSelfView) {
                            if (!p.hasPermission("sincebooster.share")) {
                                p.sendMessage(ColorUtils.parseWithPrefix(getMsg().getString("share.no_permission")));
                                p.closeInventory();
                                return;
                            }
                            new ManageShareGUI(plugin).open(p, bId);
                        }
                    } else {
                        if (e.getClick().isShiftClick() && isSelfView) {
                            OfflinePlayer owner = (boosterOwnerUUID != null) ? Bukkit.getOfflinePlayer(boosterOwnerUUID) : null;
                            if (owner != null) plugin.getBoosterManager().getShareManager().leaveShare(p, owner);
                            p.closeInventory();
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder(false) instanceof BoosterHolder) e.setCancelled(true);
    }

    private static class DisplayBooster {
        Booster booster;
        boolean isOwner;
        String ownerName;
        UUID ownerUUID;

        public DisplayBooster(Booster b, boolean o, String n, UUID u) {
            booster = b;
            isOwner = o;
            ownerName = n;
            ownerUUID = u;
        }
    }

    public static class BoosterHolder implements InventoryHolder {
        private final UUID targetUUID;
        private Inventory inventory;

        public BoosterHolder(UUID targetUUID) {
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