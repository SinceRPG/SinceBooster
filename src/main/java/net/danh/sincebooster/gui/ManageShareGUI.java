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
import org.bukkit.OfflinePlayer;
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
 * GUI for managing players that a specific booster is shared with.
 * Incorporates ItemBuilder logic directly tied to the gui.yml mappings, and supports Dialog API natively.
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

        if (plugin.getConfigFile().getString("gui.type", "INVENTORY").equalsIgnoreCase("DIALOG")) {
            openDialog(p, boosterId);
        } else {
            openInventory(p, boosterId);
        }
    }

    private void openDialog(Player p, String boosterId) {
        Booster booster = getBooster(p, boosterId);
        if (booster == null) return;

        if (booster.getSharedPlayers().isEmpty()) {
            p.sendMessage(ColorUtils.parseWithPrefix(getMsg().getString("manage_share.dialog.empty_notify", "&cNot sharing with anyone.")));
            return;
        }

        String title = getGui().getString("manage_share.dialog.title", "Manage Shares");
        List<ActionButton> buttons = new ArrayList<>();
        ConfigurationSection headSec = getGui().getConfig().getConfigurationSection("manage_share.dialog.player_button");

        for (UUID targetUUID : booster.getSharedPlayers()) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetUUID);
            String name = headSec != null ? headSec.getString("name", "&cKick: <player>") : "&cKick: <player>";
            String tooltip = headSec != null ? (headSec.isList("tooltip") ? String.join("\n", headSec.getStringList("tooltip")) : headSec.getString("tooltip", "&7Click to kick.")) : "&7Click to kick.";

            name = name.replace("<player>", target.getName() != null ? target.getName() : "Unknown");

            buttons.add(ActionButton.builder(ColorUtils.parse(name))
                    .tooltip(ColorUtils.parse(tooltip))
                    .action(DialogAction.customClick((view, audience) -> {
                        if (audience instanceof Player clicker) {
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                plugin.getBoosterManager().getShareManager().kickShare(clicker, boosterId, target);
                                openDialog(clicker, boosterId);
                            });
                        }
                    }, ClickCallback.Options.builder().uses(1).build()))
                    .build());
        }

        if (buttons.isEmpty()) {
            ConfigurationSection emptyBtnSec = getGui().getConfig().getConfigurationSection("manage_share.dialog.empty_button");
            String emptyName = emptyBtnSec != null ? emptyBtnSec.getString("name", "&cNot Sharing") : "&cNot Sharing";
            String emptyTooltip = emptyBtnSec != null ? (emptyBtnSec.isList("tooltip") ? String.join("\n", emptyBtnSec.getStringList("tooltip")) : emptyBtnSec.getString("tooltip", "&7Nothing to show here.")) : "&7Nothing to show here.";

            buttons.add(ActionButton.builder(ColorUtils.parse(emptyName))
                    .tooltip(ColorUtils.parse(emptyTooltip))
                    .build());
        }

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(ColorUtils.parse(title)).build())
                .type(DialogType.multiAction(buttons, null, getGui().getInt("manage_share.dialog.columns", 3)))
        );

        p.showDialog(dialog);
    }

    private void openInventory(Player p, String boosterId) {
        Booster booster = getBooster(p, boosterId);
        if (booster == null) return;

        String title = getGui().getString("manage_share.title", "Manage Shares");
        int size = getGui().getInt("manage_share.size", 27);
        ManageHolder holder = new ManageHolder(boosterId);
        Inventory inv = Bukkit.createInventory(holder, size, ColorUtils.parse(title));
        holder.setInventory(inv);

        List<Integer> playerSlots = getGui().getConfig().getIntegerList("manage_share.layout.player_slots");
        int slotIdx = 0;

        if (booster.getSharedPlayers().isEmpty()) {
            ConfigurationSection emptySec = getGui().getConfig().getConfigurationSection("manage_share.items.empty_slot");
            Material emptyMat = emptySec != null ? Material.matchMaterial(emptySec.getString("material", "BARRIER")) : Material.BARRIER;
            inv.setItem(getGui().getInt("manage_share.items.empty_slot.slot", 13), new ItemBuilder(plugin, emptyMat).applyConfig(emptySec, "&cNot Sharing").build());
        } else {
            for (UUID targetUUID : booster.getSharedPlayers()) {
                if (slotIdx >= playerSlots.size()) break;
                OfflinePlayer target = Bukkit.getOfflinePlayer(targetUUID);
                inv.setItem(playerSlots.get(slotIdx++), createPlayerHead(target));
            }
        }

        p.openInventory(inv);
    }

    private Booster getBooster(Player p, String boosterId) {
        List<Booster> list = plugin.getBoosterManager().getActiveBoosters(p.getUniqueId());
        if (list != null) {
            for (Booster b : list) {
                if (b.getId().equalsIgnoreCase(boosterId)) {
                    return b;
                }
            }
        }
        return null;
    }

    private ItemStack createPlayerHead(OfflinePlayer target) {
        ConfigurationSection headSec = getGui().getConfig().getConfigurationSection("manage_share.items.player_head");
        ItemStack item = new ItemBuilder(plugin, Material.PLAYER_HEAD).applyConfig(headSec, "&cKick: <player>",
                "<player>", target.getName() != null ? target.getName() : "Unknown"
        ).setTag(targetKey, PersistentDataType.STRING, target.getUniqueId().toString()).build();

        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(target);
            item.setItemMeta(meta);
        }
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
            if (meta != null && meta.getPersistentDataContainer().has(targetKey, PersistentDataType.STRING)) {
                String uuidStr = meta.getPersistentDataContainer().get(targetKey, PersistentDataType.STRING);
                if (uuidStr != null) {
                    OfflinePlayer target = Bukkit.getOfflinePlayer(UUID.fromString(uuidStr));
                    plugin.getBoosterManager().getShareManager().kickShare(p, holder.getBoosterId(), target);
                    openInventory(p, holder.getBoosterId());
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