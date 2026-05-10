package net.danh.sincebooster.gui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.danh.sincebooster.SinceBooster;
import net.danh.sincebooster.manager.Booster;
import net.danh.sincebooster.utils.ColorUtils;
import net.danh.sincebooster.utils.ConfigUtils;
import net.danh.sincebooster.utils.ItemBuilder;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
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
 * Strictly uses ItemBuilder combined with gui.yml definitions for completely dynamic rendering.
 * Fully supports modern Paper 1.21.6+ Dialog APIs.
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

        if (plugin.getConfigFile().getString("gui.type", "INVENTORY").equalsIgnoreCase("DIALOG")) {
            openDialogPlayerSelector(p);
        } else {
            openInventoryPlayerSelector(p);
        }
    }

    private void openDialogPlayerSelector(Player p) {
        String titleStr = getGui().getString("share_gui.dialog.player_selector.title", "Select a Player");
        List<ActionButton> buttons = new ArrayList<>();
        ConfigurationSection headSec = getGui().getConfig().getConfigurationSection("share_gui.dialog.player_selector.player_button");

        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.getUniqueId().equals(p.getUniqueId())) continue;

            String name = headSec != null ? headSec.getString("name", "&e<player_name>") : "&e<player_name>";
            String tooltip = headSec != null ? (headSec.isList("tooltip") ? String.join("<br>", headSec.getStringList("tooltip")) : headSec.getString("tooltip", "&7Click to select this player")) : "&7Click to select this player";
            name = name.replace("<player_name>", target.getName());

            buttons.add(ActionButton.builder(ColorUtils.parse(name))
                    .tooltip(ColorUtils.parse(tooltip))
                    .action(DialogAction.customClick((view, audience) -> {
                        if (audience instanceof Player clicker) {
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                if (target.isOnline()) {
                                    openBoosterSelector(clicker, target);
                                } else {
                                    clicker.sendMessage(ColorUtils.parseWithPrefix(getMsg().getString("share.invalid_target")));
                                    clicker.closeDialog();
                                }
                            });
                        }
                    }, ClickCallback.Options.builder().uses(1).build()))
                    .build());
        }

        if (buttons.isEmpty()) {
            p.sendMessage(ColorUtils.parseWithPrefix(getMsg().getString("share.no_players", "&cNo other players are currently online.")));
            return;
        }

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(ColorUtils.parse(titleStr)).build())
                .type(DialogType.multiAction(buttons, null, getGui().getInt("share_gui.dialog.player_selector.columns", 3)))
        );
        p.showDialog(dialog);
    }

    private void openInventoryPlayerSelector(Player p) {
        String titleStr = getGui().getString("share_gui.player_selector.title", "Select a Player");
        int size = getGui().getInt("share_gui.player_selector.size", 54);
        PlayerSelectorHolder holder = new PlayerSelectorHolder();
        Inventory inv = Bukkit.createInventory(holder, size, ColorUtils.parse(titleStr));
        holder.setInventory(inv);

        List<Integer> slots = getGui().getConfig().getIntegerList("share_gui.player_selector.layout.player_slots");
        int slotIdx = 0;
        ConfigurationSection headSec = getGui().getConfig().getConfigurationSection("share_gui.player_selector.items.player_head");

        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.getUniqueId().equals(p.getUniqueId())) continue;
            if (slotIdx >= slots.size()) break;

            ItemStack head = new ItemBuilder(plugin, Material.PLAYER_HEAD).applyConfig(headSec, "&e<player_name>",
                    "<player_name>", target.getName()
            ).build();

            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (meta != null) {
                meta.setOwningPlayer(target);
                head.setItemMeta(meta);
            }

            inv.setItem(slots.get(slotIdx++), head);
        }

        ConfigurationSection backSec = getGui().getConfig().getConfigurationSection("share_gui.player_selector.items.back_button");
        if (backSec != null) {
            Material mat = Material.matchMaterial(backSec.getString("material", "ARROW"));
            inv.setItem(getGui().getInt("share_gui.player_selector.items.back_button.slot"), new ItemBuilder(plugin, mat).applyConfig(backSec, "&cBack").build());
        }
        p.openInventory(inv);
    }

    public void openBoosterSelector(Player p, Player target) {
        if (!p.hasPermission("sincebooster.share")) {
            p.sendMessage(ColorUtils.parseWithPrefix(getMsg().getString("share.no_permission")));
            return;
        }

        if (target == null || !target.isOnline()) {
            p.sendMessage(ColorUtils.parseWithPrefix(getMsg().getString("share.invalid_target")));
            return;
        }

        if (plugin.getConfigFile().getString("gui.type", "INVENTORY").equalsIgnoreCase("DIALOG")) {
            openDialogBoosterSelector(p, target);
        } else {
            openInventoryBoosterSelector(p, target);
        }
    }

    private void openDialogBoosterSelector(Player p, Player target) {
        String titleStr = getGui().getString("share_gui.dialog.booster_selector.title", "Select a Booster");
        List<ActionButton> buttons = new ArrayList<>();
        ConfigurationSection baseSec = getGui().getConfig().getConfigurationSection("share_gui.dialog.booster_selector.booster_button");

        List<Booster> boosters = plugin.getBoosterManager().getActiveBoosters(p.getUniqueId());
        if (boosters != null) {
            int maxShare = plugin.getBoosterManager().getShareManager().getPlayerShareLimit(p);

            for (Booster b : boosters) {
                if (!b.isValid()) continue;

                String nameFormatKey = b.isPermanent() ? "name_perm" : "name_time";
                long left = (b.getEndTime() - System.currentTimeMillis()) / 1000;
                String timeStr = formatTime(Math.max(0, left));
                String id = b.getId().toUpperCase();

                String typeColor = (b.getProfession() == null) ? "<aqua>" : "<green>";
                String typeName = (b.getProfession() == null) ? "Class XP" : "Job: " + b.getProfession().toUpperCase();

                double baseMult = b.getMultiplier();
                double receiverRateConfig = plugin.getPlayerDataHandler().getSession(p.getUniqueId()).getReceiverBuffRate();
                double recEfficiency = receiverRateConfig * 100.0;
                double recMult = 1.0 + ((baseMult - 1.0) * (recEfficiency / 100.0));

                int currentShare = b.getSharedPlayers().size();
                boolean isFull = currentShare >= maxShare;
                String slotColor = isFull ? "<red>" : "<green>";
                String statusText = "";

                String name = "&6<id> &7(<time>)";
                String tooltip = "&7Click to share!";

                if (baseSec != null) {
                    name = baseSec.getString(nameFormatKey, name);
                    if (baseSec.isList("tooltip")) tooltip = String.join("<br>", baseSec.getStringList("tooltip"));
                    else tooltip = baseSec.getString("tooltip", tooltip);

                    statusText = baseSec.getString(isFull ? "status_full" : "status_available", "");
                    statusText = statusText.replace("<current>", String.valueOf(currentShare)).replace("<max>", String.valueOf(maxShare));

                    name = name.replace("<id>", id).replace("<time>", timeStr);
                    tooltip = tooltip.replace("<type_color>", typeColor)
                            .replace("<type_name>", typeName)
                            .replace("<multiplier>", String.valueOf(baseMult))
                            .replace("<percent>", String.valueOf((int) ((baseMult - 1.0) * 100)))
                            .replace("<rec_multiplier>", String.format("%.2f", recMult))
                            .replace("<rec_percent>", String.valueOf((int) ((recMult - 1.0) * 100)))
                            .replace("<slot_color>", slotColor)
                            .replace("<status_text>", statusText);
                }

                buttons.add(ActionButton.builder(ColorUtils.parse(name))
                        .tooltip(ColorUtils.parse(tooltip))
                        .action(DialogAction.customClick((view, audience) -> {
                            if (audience instanceof Player clicker) {
                                plugin.getServer().getScheduler().runTask(plugin, () -> {
                                    if (target != null && target.isOnline()) {
                                        plugin.getBoosterManager().getShareManager().sendInvite(clicker, target, b.getId());
                                    } else {
                                        clicker.sendMessage(ColorUtils.parseWithPrefix(getMsg().getString("share.invalid_target")));
                                    }
                                    clicker.closeDialog();
                                });
                            }
                        }, ClickCallback.Options.builder().uses(1).build()))
                        .build());
            }
        }

        if (buttons.isEmpty()) {
            p.sendMessage(ColorUtils.parseWithPrefix(getMsg().getString("share.no_boosters_to_share", "&cYou do not have any active boosters available to share.")));
            return;
        }

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(ColorUtils.parse(titleStr)).build())
                .type(DialogType.multiAction(buttons, null, getGui().getInt("share_gui.dialog.booster_selector.columns", 3)))
        );
        p.showDialog(dialog);
    }

    private void openInventoryBoosterSelector(Player p, Player target) {
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

        ConfigurationSection backSec = getGui().getConfig().getConfigurationSection("share_gui.booster_selector.items.back_button");
        if (backSec != null) {
            Material mat = Material.matchMaterial(backSec.getString("material", "ARROW"));
            inv.setItem(getGui().getInt("share_gui.booster_selector.items.back_button.slot"), new ItemBuilder(plugin, mat).applyConfig(backSec, "&cBack").build());
        }
        p.openInventory(inv);
    }

    private ItemStack createDetailedShareItem(Booster b, Player p, int maxShare) {
        Material mat = (b.getProfession() == null) ? Material.NETHER_STAR : Material.ENCHANTED_BOOK;
        ConfigurationSection baseSec = getGui().getConfig().getConfigurationSection("share_gui.booster_selector.items.booster");

        if (baseSec != null && baseSec.contains("material")) {
            Material overridden = Material.matchMaterial(baseSec.getString("material"));
            if (overridden != null) mat = overridden;
        }

        ItemBuilder builder = new ItemBuilder(plugin, mat);
        builder.setTag(boosterIdKey, PersistentDataType.STRING, b.getId());

        String keyDur = b.isPermanent() ? "name_perm" : "name_time";
        long left = (b.getEndTime() - System.currentTimeMillis()) / 1000;
        String timeStr = formatTime(Math.max(0, left));
        String id = b.getId().toUpperCase();

        String typeColor = (b.getProfession() == null) ? "<aqua>" : "<green>";
        String typeName = (b.getProfession() == null) ? "Class XP" : "Job: " + b.getProfession().toUpperCase();

        double baseMult = b.getMultiplier();
        double receiverRateConfig = plugin.getPlayerDataHandler().getSession(p.getUniqueId()).getReceiverBuffRate();
        double recEfficiency = receiverRateConfig * 100.0;
        double recMult = 1.0 + ((baseMult - 1.0) * (recEfficiency / 100.0));

        int currentShare = b.getSharedPlayers().size();
        boolean isFull = currentShare >= maxShare;
        String slotColor = isFull ? "<red>" : "<green>";
        String statusText = "";

        if (baseSec != null) {
            statusText = baseSec.getString(isFull ? "status_full" : "status_available", "");
            statusText = statusText.replace("<current>", String.valueOf(currentShare)).replace("<max>", String.valueOf(maxShare));

            builder.applyConfig(baseSec, baseSec.getString(keyDur, "&6<id>"),
                    "<id>", id,
                    "<time>", timeStr,
                    "<type_color>", typeColor,
                    "<type_name>", typeName,
                    "<multiplier>", String.valueOf(baseMult),
                    "<percent>", String.valueOf((int) ((baseMult - 1.0) * 100)),
                    "<rec_multiplier>", String.format("%.2f", recMult),
                    "<rec_percent>", String.valueOf((int) ((recMult - 1.0) * 100)),
                    "<slot_color>", slotColor,
                    "<status_text>", statusText
            );
        }

        return builder.build();
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

            if (isBackButton(item, "share_gui.player_selector.items.back_button.material")) {
                plugin.getBoosterGUI().open(p);
                return;
            }

            if (item.getType() == Material.PLAYER_HEAD) {
                SkullMeta meta = (SkullMeta) item.getItemMeta();
                if (meta != null && meta.getOwningPlayer() != null && meta.getOwningPlayer().isOnline()) {
                    openInventoryBoosterSelector(p, meta.getOwningPlayer().getPlayer());
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

            if (isBackButton(item, "share_gui.booster_selector.items.back_button.material")) {
                openInventoryPlayerSelector(p);
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

    private boolean isBackButton(ItemStack item, String path) {
        String matName = getGui().getString(path, "ARROW");
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