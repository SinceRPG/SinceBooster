package net.danh.sincebooster.gui;

import net.danh.sincebooster.SinceBooster;
import net.danh.sincebooster.data.PlayerDataHandler;
import net.danh.sincebooster.manager.Booster;
import net.danh.sincebooster.utils.ColorUtils;
import net.danh.sincebooster.utils.ConfigUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
import org.jetbrains.annotations.Nullable;

import java.util.*;

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

    private ConfigUtils getMsg() {
        return plugin.getMessagesFile();
    }

    public void startUpdateTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    Inventory topInv = p.getOpenInventory().getTopInventory();
                    if (topInv.getHolder() instanceof BoosterHolder(UUID targetUUID)) {
                        updateContent(topInv, targetUUID, p);
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    // View self
    public void open(Player p) {
        open(p, p);
    }

    // View other (Admin)
    public void open(Player viewer, Player target) {
        String titleStr;
        if (viewer.getUniqueId().equals(target.getUniqueId())) {
            titleStr = getMsg().getString("booster.gui.title", "Danh Sách Booster");
        } else {
            titleStr = getMsg().getString("admin.view_title", "Kho: <target>").replace("<target>", target.getName());
        }

        Inventory inv = Bukkit.createInventory(new BoosterHolder(target.getUniqueId()), 54, ColorUtils.parse(titleStr));
        updateContent(inv, target.getUniqueId(), viewer);
        viewer.openInventory(inv);
    }

    private void updateContent(Inventory inv, UUID targetUUID, Player viewer) {
        List<DisplayBooster> displayList = new ArrayList<>();

        // 1. Target's Own Boosters
        List<Booster> ownList = plugin.getBoosterManager().getActiveBoosters(targetUUID);
        if (ownList != null) {
            for (Booster b : ownList) {
                if (b.isValid())
                    displayList.add(new DisplayBooster(b, true, Bukkit.getOfflinePlayer(targetUUID).getName(), targetUUID));
            }
        }

        // 2. Target's Received Boosters (Quét dữ liệu đã nạp trong RAM)
        for (Map.Entry<UUID, List<Booster>> entry : plugin.getBoosterManager().getActiveBoosters().entrySet()) {
            UUID ownerUUID = entry.getKey();
            if (ownerUUID.equals(targetUUID)) continue; // Bỏ qua nếu là chính mình

            List<Booster> otherList = entry.getValue();
            if (otherList != null) {
                for (Booster b : otherList) {
                    if (b.isValid() && b.getSharedPlayers().contains(targetUUID)) {
                        // Lấy tên: Ưu tiên Online, nếu không thì lấy tên từ cache OfflinePlayer
                        Player onlineOwner = Bukkit.getPlayer(ownerUUID);
                        String ownerName;
                        if (onlineOwner != null) {
                            ownerName = onlineOwner.getName();
                        } else {
                            OfflinePlayer offP = Bukkit.getOfflinePlayer(ownerUUID);
                            ownerName = (offP.getName() != null) ? offP.getName() : ownerUUID.toString().substring(0, 8);
                        }

                        displayList.add(new DisplayBooster(b, false, ownerName, ownerUUID));
                    }
                }
            }
        }

        // [THÊM MỚI] 3. Target's Received Boosters (FROM OFFLINE PLAYERS)
        Map<UUID, List<Booster>> offlineShares = plugin.getBoosterManager().getShareManager().getCachedOfflineShares(targetUUID);
        if (offlineShares != null) {
            for (Map.Entry<UUID, List<Booster>> entry : offlineShares.entrySet()) {
                UUID ownerUUID = entry.getKey();
                // Bỏ qua nếu owner đang Online (để tránh trùng lặp với mục 2)
                // (Mặc dù updateOfflineSharesOnJoin đã xử lý, nhưng check thêm cho chắc chắn)
                if (Bukkit.getPlayer(ownerUUID) != null) continue;

                List<Booster> boosters = entry.getValue();
                OfflinePlayer offlineOwner = Bukkit.getOfflinePlayer(ownerUUID);
                String ownerName = offlineOwner.getName() != null ? offlineOwner.getName() : "Unknown (Off)";

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
        fillSection(inv, classBoosters, 0, 18, targetUUID);

        ItemStack glass = createSeparator();
        for (int i = 18; i < 27; i++) {
            if (i == 21) inv.setItem(i, createClassSummary(displayList));
            else if (i == 23) inv.setItem(i, createProfSummary(displayList));
            else {
                ItemStack current = inv.getItem(i);
                if (current == null || !current.isSimilar(glass)) {
                    inv.setItem(i, glass);
                }
            }
        }
        fillSection(inv, profBoosters, 27, 54, targetUUID);

        int shareSlot = getMsg().getInt("booster.gui.share_button.slot", 49);
        inv.setItem(shareSlot, createShareButton());
        if (viewer.getUniqueId().equals(targetUUID)) {
            int toggleSlot = getMsg().getInt("booster.gui.offline_toggle_button.slot", 50);
            inv.setItem(toggleSlot, createOfflineToggleItem(viewer));
        }
    }

    private void fillSection(Inventory inv, List<DisplayBooster> list, int start, int end, UUID viewerUUID) {
        int limit = end - start;
        for (int i = 0; i < limit; i++) {
            int slot = start + i;
            ItemStack newItem;

            if (i < list.size()) {
                newItem = createBoosterItem(list.get(i), viewerUUID);
            } else {
                if (list.isEmpty() && i == (limit / 2)) newItem = createEmptyItem();
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
        String basePath = "booster.gui.item." + keyType + ".";

        long left = (b.getEndTime() - System.currentTimeMillis()) / 1000;
        String timeStr = formatTime(Math.max(0, left));
        String id = b.getId().toUpperCase();

        String name = getMsg().getString(basePath + "name_" + keyDur);
        if (name != null) meta.displayName(ColorUtils.parse(name.replace("<id>", id).replace("<time>", timeStr)));

        List<String> loreRaw = getMsg().getConfig().getStringList(basePath + "lore");
        List<Component> lore = new ArrayList<>();

        String typeColor = (b.getProfession() == null) ? "<aqua>" : "<green>";
        String typeName = (b.getProfession() == null) ? "Class XP" : "Job: " + b.getProfession().toUpperCase();
        String statusPath = b.isPermanent() ? "booster.gui.item.status_perm" : "booster.gui.item.status_time";
        String status = getMsg().getString(statusPath).replace("<time_left>", timeStr);

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
                    sharedListStr = getMsg().getString("booster.gui.item.shared_list_none");
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (UUID uid : b.getSharedPlayers()) {
                        OfflinePlayer op = Bukkit.getOfflinePlayer(uid);
                        String pName = (op.getName() != null) ? op.getName() : "Unknown";
                        String format = getMsg().getString("booster.gui.item.shared_list_format");
                        sb.append(format.replace("<player>", pName)).append("\n");
                    }
                    sharedListStr = sb.toString().trim();
                }

                line = line.replace("<multiplier>", String.valueOf(b.getMultiplier())).replace("<percent>", String.valueOf((int) ((b.getMultiplier() - 1) * 100))).replace("<decay_rate>", String.format("%.1f", decayRate)).replace("<efficiency>", String.format("%.0f", efficiency)).replace("<shared_count>", String.valueOf(b.getSharedPlayers().size())).replace("<shared_list>", sharedListStr);
            } else {
                // --- ĐÂY LÀ ĐOẠN TÍNH TOÁN LẠI CHO ĐÚNG ---
                double baseMult = b.getMultiplier();

                // 1. Lấy Tỷ lệ chính xác (Rate)
                // Hàm này sẽ trả về 0.25 (Offline) hoặc 1.0 (Online) tùy tình trạng thực tế
                // Quan trọng: Dùng viewerUUID làm người nhận để tính đúng cho người đang xem
                double currentRate = plugin.getBoosterManager().getShareManager().getReceiverMultiplier(b, viewerUUID);

                // 2. Tính Efficiency (Hiệu suất) để hiển thị %
                // Ví dụ: Rate 0.25 -> Hiển thị 25%
                double efficiency = currentRate * 100.0;

                // 3. Tính Real Multiplier (Thực nhận)
                // Công thức: 1 + (Bonus Gốc * Tỷ lệ)
                // Ví dụ: Gốc x3 (Bonus +2), Rate 0.25 -> Thực nhận: 1 + (2 * 0.25) = 1.5x
                double realMult = 1.0 + ((baseMult - 1.0) * currentRate);

                line = line.replace("<owner_name>", db.ownerName).replace("<base_multiplier>", String.valueOf(baseMult)).replace("<efficiency>", String.format("%.0f", efficiency)) // Hiển thị 25
                        .replace("<real_multiplier>", String.format("%.2f", realMult)); // Hiển thị 1.50
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

    private ItemStack createShareButton() {
        String matName = getMsg().getString("booster.gui.share_button.material", "OAK_SIGN");
        ItemStack item = new ItemStack(Material.valueOf(matName));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ColorUtils.parse(getMsg().getString("booster.gui.share_button.name")));
        List<Component> lore = new ArrayList<>();
        for (String s : getMsg().getConfig().getStringList("booster.gui.share_button.lore"))
            lore.add(ColorUtils.parse(s));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createClassSummary(List<DisplayBooster> list) {
        String matName = getMsg().getString("booster.gui.summary_class.material", "BEACON");
        ItemStack item = new ItemStack(Material.valueOf(matName));
        ItemMeta meta = item.getItemMeta();
        double totalAdd = 0, ownAdd = 0, sharedAdd = 0;
        for (DisplayBooster db : list) {
            if (db.booster.getProfession() == null) {
                double bonus = db.booster.getMultiplier() - 1.0;
                if (db.booster.isPermanent() && !db.booster.getSharedPlayers().isEmpty()) {
                    double rate = db.isOwner ? plugin.getBoosterManager().getShareManager().getOwnerBuffRate(db.ownerUUID) : plugin.getBoosterManager().getShareManager().getReceiverBuffRate(db.ownerUUID);
                    bonus *= rate;
                }
                totalAdd += bonus;
                if (db.isOwner) ownAdd += bonus;
                else sharedAdd += bonus;
            }
        }
        meta.displayName(ColorUtils.parse(getMsg().getString("booster.gui.summary_class.name")));
        List<Component> lore = new ArrayList<>();
        for (String line : getMsg().getConfig().getStringList("booster.gui.summary_class.lore")) {
            line = line.replace("<total_multiplier>", String.format("%.2f", 1.0 + totalAdd)).replace("<total_percent>", String.valueOf((int) (totalAdd * 100))).replace("<booster_add>", String.valueOf((int) (totalAdd * 100))).replace("<own_percent>", String.valueOf((int) (ownAdd * 100))).replace("<shared_percent>", String.valueOf((int) (sharedAdd * 100)));
            lore.add(ColorUtils.parse(line));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createProfSummary(List<DisplayBooster> list) {
        String matName = getMsg().getString("booster.gui.summary_prof.material", "KNOWLEDGE_BOOK");
        ItemStack item = new ItemStack(Material.valueOf(matName));
        ItemMeta meta = item.getItemMeta();
        Map<String, Double> totals = new HashMap<>();
        for (DisplayBooster db : list) {
            String p = db.booster.getProfession();
            if (p != null) {
                double bonus = db.booster.getMultiplier() - 1.0;
                if (db.booster.isPermanent() && !db.booster.getSharedPlayers().isEmpty()) {
                    double rate = db.isOwner ? plugin.getBoosterManager().getShareManager().getOwnerBuffRate(db.ownerUUID) : plugin.getBoosterManager().getShareManager().getReceiverBuffRate(db.ownerUUID);
                    bonus *= rate;
                }
                totals.put(p, totals.getOrDefault(p, 0.0) + bonus);
            }
        }
        meta.displayName(ColorUtils.parse(getMsg().getString("booster.gui.summary_prof.name")));
        List<Component> lore = new ArrayList<>();
        String format = getMsg().getString("booster.gui.summary_prof.prof_format");
        String none = getMsg().getString("booster.gui.summary_prof.prof_none");
        for (String line : getMsg().getConfig().getStringList("booster.gui.summary_prof.lore")) {
            if (line.contains("<prof_list>")) {
                if (totals.isEmpty()) {
                    lore.add(ColorUtils.parse(none));
                } else {
                    for (Map.Entry<String, Double> entry : totals.entrySet()) {
                        double val = 1.0 + entry.getValue();
                        String f = format.replace("<profession>", entry.getKey().toUpperCase()).replace("<multiplier>", String.format("%.2f", val)).replace("<percent>", String.valueOf((int) (entry.getValue() * 100)));
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

    private ItemStack createSeparator() {
        String matName = getMsg().getString("booster.gui.separator.material", "BLACK_STAINED_GLASS_PANE");
        ItemStack item = new ItemStack(Material.valueOf(matName));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createEmptyItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ColorUtils.parse(getMsg().getString("booster.gui.empty_slot.name")));
        List<Component> lore = new ArrayList<>();
        for (String s : getMsg().getConfig().getStringList("booster.gui.empty_slot.lore"))
            lore.add(ColorUtils.parse(s));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private String formatTime(long seconds) {
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        String format = getMsg().getString("booster.gui.formats.time_left");
        return format.replace("<day>", String.valueOf(days)).replace("<hour>", String.valueOf(hours)).replace("<min>", String.valueOf(minutes)).replace("<sec>", String.valueOf(secs));
    }

    public boolean isBoosterGUI(Component viewTitle) {
        String configTitleStr = getMsg().getString("booster.gui.title", "Danh Sách Booster");
        Component configTitle = ColorUtils.parse(configTitleStr);
        if (viewTitle.equals(configTitle)) return true;
        String viewPlain = PlainTextComponentSerializer.plainText().serialize(viewTitle);
        String configPlain = PlainTextComponentSerializer.plainText().serialize(configTitle);
        return viewPlain.equals(configPlain);
    }

    private ItemStack createOfflineToggleItem(Player p) {
        boolean hasPerm = p.hasPermission("sincebooster.share.offline");
        boolean isEnabled = false;

        if (hasPerm) {
            PlayerDataHandler.PlayerSession s = plugin.getPlayerDataHandler().getSession(p.getUniqueId());
            if (s != null) isEnabled = s.isOfflineShareEnabled();
        }

        String stateKey;
        if (!hasPerm) stateKey = "no_perm";
        else stateKey = isEnabled ? "enabled" : "disabled";

        String basePath = "booster.gui.offline_toggle_button." + stateKey;

        // Fallback safety
        String matName = getMsg().getString(basePath + ".material", "BARRIER");
        Material mat;
        try {
            mat = Material.valueOf(matName);
        } catch (Exception e) {
            mat = Material.BARRIER;
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        String name = getMsg().getString(basePath + ".name", "&cOffline Share");
        meta.displayName(ColorUtils.parse(name));

        List<Component> lore = new ArrayList<>();
        for (String s : getMsg().getConfig().getStringList(basePath + ".lore")) {
            lore.add(ColorUtils.parse(s));
        }
        meta.lore(lore);

        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof BoosterHolder(UUID targetUUID))) return;

        e.setCancelled(true);

        if (e.getClickedInventory() == null || e.getClickedInventory() == e.getView().getTopInventory()) {
            int slot = e.getSlot();
            int shareSlot = getMsg().getInt("booster.gui.share_button.slot", 49);
            int toggleSlot = getMsg().getInt("booster.gui.offline_toggle_button.slot", 50);
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

            // [MỚI] Xử lý click Toggle Offline với Cooldown
            if (slot == toggleSlot && isSelfView) {
                if (!p.hasPermission("sincebooster.share.offline")) {
                    p.sendMessage(ColorUtils.parseWithPrefix(getMsg().getString("share.offline_no_perm")));
                    return;
                }

                // --- COOLDOWN CHECK (UPDATED: PersistentDataContainer) ---
                if (p.getPersistentDataContainer().has(cooldownKey, PersistentDataType.LONG)) {
                    Long lastClick = p.getPersistentDataContainer().get(cooldownKey, PersistentDataType.LONG);
                    if (lastClick != null && System.currentTimeMillis() - lastClick < 2000) {
                        p.sendMessage(ColorUtils.parseWithPrefix(getMsg().getString("booster.gui.action_cooldown", "&cVui lòng thao tác chậm lại!")));
                        return;
                    }
                }
                p.getPersistentDataContainer().set(cooldownKey, PersistentDataType.LONG, System.currentTimeMillis());
                // ----------------------

                PlayerDataHandler.PlayerSession session = plugin.getPlayerDataHandler().getSession(p.getUniqueId());
                if (session != null) {
                    boolean current = session.isOfflineShareEnabled();
                    session.setOfflineShareEnabled(!current);

                    // Lưu dữ liệu
                    plugin.getPlayerDataHandler().saveData(p.getUniqueId(), false);

                    if (!current)
                        p.sendMessage(ColorUtils.parseWithPrefix(getMsg().getString("share.offline_toggle_on")));
                    else p.sendMessage(ColorUtils.parseWithPrefix(getMsg().getString("share.offline_toggle_off")));

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
                    UUID boosterOwnerUUID = null;
                    if (ownerUuidStr != null) {
                        boosterOwnerUUID = UUID.fromString(ownerUuidStr);
                    }

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
                            OfflinePlayer owner = null;
                            if (boosterOwnerUUID != null) {
                                owner = Bukkit.getOfflinePlayer(boosterOwnerUUID);
                            }
                            if (owner != null) {
                                plugin.getBoosterManager().getShareManager().leaveShare(p, owner);
                            }
                            p.closeInventory();
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof BoosterHolder) e.setCancelled(true);
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

    public record BoosterHolder(UUID targetUUID) implements InventoryHolder {

        @Override
        public @Nullable Inventory getInventory() {
            return null;
        }
    }
}