package net.danh.sincebooster.gui;

import net.danh.sincebooster.SinceBooster;
import net.danh.sincebooster.manager.Booster;
import net.danh.sincebooster.utils.ColorUtils;
import net.danh.sincebooster.utils.ConfigUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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

public class ShareGUI implements Listener {

    private final SinceBooster plugin;
    private final NamespacedKey boosterIdKey;

    public ShareGUI(SinceBooster plugin) {
        this.plugin = plugin;
        this.boosterIdKey = new NamespacedKey(plugin, "gui_booster_id");
    }

    private ConfigUtils getMsg() {
        return plugin.getMessagesFile();
    }

    // --- HELPER METHODS ---
    public boolean isPlayerSelector(Component viewTitle) {
        String configTitleStr = getMsg().getString("share_gui.player_selector.title", "Chọn người nhận");
        Component configTitle = ColorUtils.parse(configTitleStr);
        if (viewTitle.equals(configTitle)) return true;
        String viewPlain = PlainTextComponentSerializer.plainText().serialize(viewTitle);
        String configPlain = PlainTextComponentSerializer.plainText().serialize(configTitle);
        return viewPlain.equals(configPlain);
    }

    public boolean isBoosterSelector(Component viewTitle) {
        String configTitleStr = getMsg().getString("share_gui.booster_selector.title", "Chọn Booster");
        Component configTitle = ColorUtils.parse(configTitleStr);
        if (viewTitle.equals(configTitle)) return true;
        String viewPlain = PlainTextComponentSerializer.plainText().serialize(viewTitle);
        String configPlain = PlainTextComponentSerializer.plainText().serialize(configTitle);
        return viewPlain.equals(configPlain);
    }

    // --- GUI OPENERS ---

    public void openPlayerSelector(Player p) {
        if (!p.hasPermission("sincebooster.share")) {
            p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.no_permission")));
            return;
        }
        String titleStr = getMsg().getString("share_gui.player_selector.title", "Chọn người nhận");
        Inventory inv = Bukkit.createInventory(new PlayerSelectorHolder(), 54, ColorUtils.parse(titleStr));

        int slot = 0;
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.getUniqueId().equals(p.getUniqueId())) continue;
            if (slot >= 53) break;

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(target);

            String nameFormat = getMsg().getString("share_gui.player_selector.item_name", "<yellow><player_name>");
            meta.displayName(ColorUtils.parse(nameFormat.replace("<player_name>", target.getName())));

            List<Component> lore = new ArrayList<>();
            for (String s : getMsg().getConfig().getStringList("share_gui.player_selector.lore")) {
                lore.add(ColorUtils.parse(s));
            }
            meta.lore(lore);
            head.setItemMeta(meta);

            inv.setItem(slot++, head);
        }
        inv.setItem(45, createBackButton());
        p.openInventory(inv);
    }

    public void openBoosterSelector(Player p, Player target) {
        if (!p.hasPermission("sincebooster.share")) {
            p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.no_permission")));
            return;
        }
        String titleStr = getMsg().getString("share_gui.booster_selector.title", "Chọn Booster");
        Inventory inv = Bukkit.createInventory(new BoosterSelectorHolder(target), 54, ColorUtils.parse(titleStr));

        List<Booster> boosters = plugin.getBoosterManager().getActiveBoosters(p.getUniqueId());
        if (boosters != null) {
            int slot = 0;
            int maxShare = plugin.getBoosterManager().getShareManager().getPlayerShareLimit(p);

            for (Booster b : boosters) {
                if (!b.isValid()) continue;
                if (slot >= 53) break;

                ItemStack item = createDetailedShareItem(b, p, maxShare);
                inv.setItem(slot++, item);
            }
        }
        inv.setItem(45, createBackButton());
        p.openInventory(inv);
    }

    private ItemStack createDetailedShareItem(Booster b, Player p, int maxShare) {
        Material mat = (b.getProfession() == null) ? Material.NETHER_STAR : Material.ENCHANTED_BOOK;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        // 1. Lưu Booster ID vào item để xử lý khi click
        meta.getPersistentDataContainer().set(boosterIdKey, PersistentDataType.STRING, b.getId());

        // 2. Xác định tên và key
        String keyDur = b.isPermanent() ? "name_perm" : "name_time";
        String basePath = "share_gui.booster_selector.item.";

        long left = (b.getEndTime() - System.currentTimeMillis()) / 1000;
        String timeStr = formatTime(Math.max(0, left));
        String id = b.getId().toUpperCase();

        String name = getMsg().getString(basePath + keyDur);
        if (name != null) meta.displayName(ColorUtils.parse(name.replace("<id>", id).replace("<time>", timeStr)));

        List<String> loreRaw = getMsg().getConfig().getStringList(basePath + "lore");
        List<Component> lore = new ArrayList<>();

        String typeColor = (b.getProfession() == null) ? "<aqua>" : "<green>";
        String typeName = (b.getProfession() == null) ? "Class XP" : "Job: " + b.getProfession().toUpperCase();

        // 3. Tính toán chỉ số cho người nhận
        double baseMult = b.getMultiplier();
        double recEfficiency;

        // Lấy thông số efficiency từ ShareManager (Giả sử bạn có method này, nếu không thì mặc định 100 hoặc lấy từ PlayerSession)
        // Đây là efficiency của người CHIA SẺ, nhưng ta cần hiển thị người NHẬN sẽ nhận được bao nhiêu
        // Mặc định lấy efficiency của chính người xem (p) hoặc config global
        double receiverRateConfig = plugin.getPlayerDataHandler().getSession(p.getUniqueId()).getReceiverBuffRate();
        recEfficiency = receiverRateConfig * 100.0;

        double recMult = 1.0 + ((baseMult - 1.0) * (recEfficiency / 100.0));

        // 4. Kiểm tra giới hạn
        int currentShare = b.getSharedPlayers().size();
        boolean isFull = currentShare >= maxShare;
        String slotColor = isFull ? "<red>" : "<green>";
        String statusText = getMsg().getString(isFull ? "share_gui.booster_selector.item.status_full" : "share_gui.booster_selector.item.status_available");
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
        if (meta.getPersistentDataContainer().has(boosterIdKey, PersistentDataType.STRING)) {
            return meta.getPersistentDataContainer().get(boosterIdKey, PersistentDataType.STRING);
        }
        return null;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        Component title = e.getView().title();
        InventoryHolder holder = e.getInventory().getHolder();

        // 1. Player Selector
        if (holder instanceof PlayerSelectorHolder || isPlayerSelector(title)) {
            e.setCancelled(true);
            ItemStack item = e.getCurrentItem();
            if (item == null || item.getType() == Material.AIR) return;
            if (e.getClickedInventory() != e.getView().getTopInventory()) return;

            if (isBackButton(item)) {
                new BoosterGUI(plugin).open(p);
                return;
            }

            if (item.getType() == Material.PLAYER_HEAD) {
                SkullMeta meta = (SkullMeta) item.getItemMeta();
                if (meta.getOwningPlayer() != null && meta.getOwningPlayer().isOnline()) {
                    openBoosterSelector(p, meta.getOwningPlayer().getPlayer());
                } else {
                    p.sendMessage(ColorUtils.parseWithPrefix(getMsg().getString("share.invalid_target")));
                    p.closeInventory();
                }
            }
        }

        // 2. Booster Selector
        else if (holder instanceof BoosterSelectorHolder || isBoosterSelector(title)) {
            e.setCancelled(true);
            ItemStack item = e.getCurrentItem();
            if (item == null || item.getType() == Material.AIR) return;
            if (e.getClickedInventory() != e.getView().getTopInventory()) return;

            if (isBackButton(item)) {
                openPlayerSelector(p);
                return;
            }

            // Xử lý gửi lời mời
            if (holder instanceof BoosterSelectorHolder(Player target)) {
                String bId = getBoosterIdFromItem(item);
                if (bId == null) return;

                // FIX: Không cần check maxShare ở đây vì sendInvite đã check
                plugin.getBoosterManager().getShareManager().sendInvite(p, target, bId);
                p.closeInventory();
            }
        }
    }

    private ItemStack createBackButton() {
        String mat = getMsg().getString("share_gui.back_button.material", "ARROW");
        ItemStack item = new ItemStack(Material.valueOf(mat));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ColorUtils.parse(getMsg().getString("share_gui.back_button.name")));
        item.setItemMeta(meta);
        return item;
    }

    private boolean isBackButton(ItemStack item) {
        String matName = getMsg().getString("share_gui.back_button.material", "ARROW");
        return item.getType().name().equals(matName);
    }

    private String formatTime(long seconds) {
        long d = seconds / 86400;
        long h = (seconds % 86400) / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        String format = getMsg().getString("booster.gui.formats.time_left");
        return format.replace("<day>", String.valueOf(d)).replace("<hour>", String.valueOf(h)).replace("<min>", String.valueOf(m)).replace("<sec>", String.valueOf(s));
    }

    // --- HOLDERS ---
    public static class PlayerSelectorHolder implements InventoryHolder {
        @Override
        public @Nullable Inventory getInventory() {
            return null;
        }
    }

    public record BoosterSelectorHolder(Player target) implements InventoryHolder {
        @Override
        public @Nullable Inventory getInventory() {
            return null;
        }
    }
}