package net.danh.sincebooster.gui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.danh.sincebooster.SinceBooster;
import net.danh.sincebooster.data.PlayerDataHandler;
import net.danh.sincebooster.manager.Booster;
import net.danh.sincebooster.utils.ColorUtils;
import net.danh.sincebooster.utils.ConfigUtils;
import net.danh.sincebooster.utils.ItemBuilder;
import net.kyori.adventure.text.Component;
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
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Main GUI class for viewing owned and received boosters.
 * Dynamically constructs the inventory or Dialog mapped completely off the gui.yml config to reduce hardcoding.
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
        plugin.getFoliaScheduler().runGlobalTimer(() -> {
            if (plugin.getConfigFile().getString("gui.type", "INVENTORY").equalsIgnoreCase("DIALOG")) return;

            for (Player p : Bukkit.getOnlinePlayers()) {
                plugin.getFoliaScheduler().runEntity(p, () -> {
                    Inventory topInv = p.getOpenInventory().getTopInventory();
                    if (topInv.getHolder(false) instanceof BoosterHolder holder) {
                        updateContent(topInv, holder.getTargetUUID(), p);
                    }
                });
            }
        }, 20L, 20L);
    }

    public void open(Player p) {
        open(p, p);
    }

    public void open(Player viewer, Player target) {
        if (plugin.getConfigFile().getString("gui.type", "INVENTORY").equalsIgnoreCase("DIALOG")) {
            openDialog(viewer, target);
        } else {
            openInventory(viewer, target);
        }
    }

    private void openDialog(Player viewer, Player target) {
        String titleStr;
        if (viewer.getUniqueId().equals(target.getUniqueId())) {
            titleStr = getGui().getString("booster_list.dialog.title", "Boosters List");
        } else {
            titleStr = getGui().getString("booster_list.dialog.other_title", "Boosters: <target>").replace("<target>", target.getName());
        }

        List<DisplayBooster> displayList = getDisplayBoosters(target.getUniqueId());
        displayList.sort(getBoosterComparator());

        List<ActionButton> buttons = new ArrayList<>();

        for (DisplayBooster db : displayList) {
            buttons.add(createDialogButton(db, viewer));
        }

        if (viewer.getUniqueId().equals(target.getUniqueId())) {
            ConfigurationSection shareSec = getGui().getConfig().getConfigurationSection("booster_list.dialog.share_button");
            if (shareSec != null) {
                String tooltip = shareSec.isList("tooltip") ? String.join("<br>", shareSec.getStringList("tooltip")) : shareSec.getString("tooltip", "&7Click to share a booster.");
                buttons.add(ActionButton.builder(ColorUtils.parse(shareSec.getString("name", "&eShare Booster")))
                        .tooltip(ColorUtils.parse(tooltip))
                        .action(DialogAction.customClick((view, audience) -> {
                            if (audience instanceof Player p) {
                                plugin.getFoliaScheduler().runEntity(p, () -> {
                                    if (!p.hasPermission("sincebooster.share")) {
                                        p.sendMessage(ColorUtils.parseWithPrefix(getMsg().getString("share.no_permission")));
                                        p.closeDialog();
                                        return;
                                    }
                                    new ShareGUI(plugin).openPlayerSelector(p);
                                });
                            }
                        }, ClickCallback.Options.builder().uses(1).build()))
                        .build());
            }

            boolean hasPerm = viewer.hasPermission("sincebooster.share.offline");
            boolean isEnabled = false;
            if (hasPerm) {
                PlayerDataHandler.PlayerSession s = plugin.getPlayerDataHandler().getSession(viewer.getUniqueId());
                if (s != null) isEnabled = s.isOfflineShareEnabled();
            }

            String stateKey = !hasPerm ? "no_perm" : (isEnabled ? "enabled" : "disabled");
            ConfigurationSection offSec = getGui().getConfig().getConfigurationSection("booster_list.dialog.offline_toggle_button." + stateKey);

            if (offSec != null) {
                String tooltip = offSec.isList("tooltip") ? String.join("<br>", offSec.getStringList("tooltip")) : offSec.getString("tooltip", "");
                buttons.add(ActionButton.builder(ColorUtils.parse(offSec.getString("name", "&7Offline Share")))
                        .tooltip(ColorUtils.parse(tooltip))
                        .action(DialogAction.customClick((view, audience) -> {
                            if (audience instanceof Player p) {
                                plugin.getFoliaScheduler().runEntity(p, () -> {
                                    if (!p.hasPermission("sincebooster.share.offline")) {
                                        p.sendMessage(ColorUtils.parseWithPrefix(getMsg().getString("share.offline_no_perm")));
                                        return;
                                    }

                                    PlayerDataHandler.PlayerSession session = plugin.getPlayerDataHandler().getSession(p.getUniqueId());
                                    if (session != null) {
                                        boolean current = session.isOfflineShareEnabled();
                                        session.setOfflineShareEnabled(!current);
                                        plugin.getPlayerDataHandler().saveData(p.getUniqueId(), false);

                                        p.sendMessage(ColorUtils.parseWithPrefix(getMsg().getString(current ? "share.offline_toggle_off" : "share.offline_toggle_on")));
                                        openDialog(p, target);
                                    }
                                });
                            }
                        }, ClickCallback.Options.builder().uses(1).build()))
                        .build());
            }
        }

        if (buttons.isEmpty()) {
            ConfigurationSection emptyBtnSec = getGui().getConfig().getConfigurationSection("booster_list.dialog.empty_button");
            String emptyName = emptyBtnSec != null ? emptyBtnSec.getString("name", "&cNo Boosters") : "&cNo Boosters";
            String emptyTooltip = emptyBtnSec != null ? (emptyBtnSec.isList("tooltip") ? String.join("<br>", emptyBtnSec.getStringList("tooltip")) : emptyBtnSec.getString("tooltip", "&7Nothing to show here.")) : "&7Nothing to show here.";

            buttons.add(ActionButton.builder(ColorUtils.parse(emptyName))
                    .tooltip(ColorUtils.parse(emptyTooltip))
                    .build());
        }

        List<DialogBody> bodies = new ArrayList<>();
        double totalAdd = 0;
        Map<String, Double> profTotals = new HashMap<>();

        for (DisplayBooster db : displayList) {
            double bonus = displayBoosterBonus(db);
            if (db.booster.getProfession() == null) {
                totalAdd += bonus;
            } else {
                String p = db.booster.getProfession();
                profTotals.put(p, profTotals.getOrDefault(p, 0.0) + bonus);
            }
        }

        String classSummary = getGui().getString("booster_list.dialog.class_summary", "&aClass XP: &e<total_multiplier>x")
                .replace("<total_multiplier>", String.format("%.2f", 1.0 + totalAdd))
                .replace("<total_percent>", String.valueOf((int) (totalAdd * 100)));

        StringBuilder profListBuilder = new StringBuilder();
        String profFormat = getGui().getString("booster_list.dialog.prof_summary", "&6Profession <profession>: &e<multiplier>x");

        if (profTotals.isEmpty()) {
            profListBuilder.append(getGui().getString("booster_list.dialog.prof_none", "&7No profession boosters."));
        } else {
            for (Map.Entry<String, Double> entry : profTotals.entrySet()) {
                double val = 1.0 + entry.getValue();
                String f = profFormat.replace("<profession>", entry.getKey().toUpperCase())
                        .replace("<multiplier>", String.format("%.2f", val))
                        .replace("<percent>", String.valueOf((int) (entry.getValue() * 100)));
                if (!profListBuilder.isEmpty()) profListBuilder.append("\n");
                profListBuilder.append(f);
            }
        }

        bodies.add(DialogBody.plainMessage(ColorUtils.parse(getGui().getString("booster_list.dialog.summary_header", "&lSummaries:"))));
        bodies.add(DialogBody.plainMessage(ColorUtils.parse(classSummary)));
        bodies.add(DialogBody.plainMessage(ColorUtils.parse(profListBuilder.toString())));

        int columns = getGui().getInt("booster_list.dialog.columns", 3);

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(ColorUtils.parse(titleStr))
                        .body(bodies)
                        .build())
                .type(DialogType.multiAction(buttons, null, columns))
        );

        viewer.showDialog(dialog);
    }

    private ActionButton createDialogButton(DisplayBooster db, Player viewer) {
        Booster b = db.booster;
        String keyType = db.isOwner ? "own_button" : "received_button";
        ConfigurationSection sec = getGui().getConfig().getConfigurationSection("booster_list.dialog." + keyType);

        if (sec == null) {
            return ActionButton.builder(Component.text(b.getId())).build();
        }

        long left = (b.getEndTime() - System.currentTimeMillis()) / 1000;
        String timeStr = formatTime(Math.max(0, left));
        String id = b.getId().toUpperCase();

        String typeColor = (b.getProfession() == null) ? "<aqua>" : "<green>";
        String typeName = getBoosterTypeName(b);

        String nameFormatKey = b.isPermanent() ? "name_perm" : "name_time";
        String name = sec.getString(nameFormatKey, "&6<id> &7(<time>)").replace("<id>", id).replace("<time>", timeStr);
        String tooltip = sec.isList("tooltip") ? String.join("<br>", sec.getStringList("tooltip")) : sec.getString("tooltip", "");

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
                    String pName = (op.getName() != null) ? op.getName() : getGui().getString("booster_list.formats.unknown_player", "Unknown");
                    String format = getGui().getString("booster_list.items.shared_list_format", "&7- &f<player>");
                    sb.append(format.replace("<player>", pName)).append("<br>");
                }
                sharedListStr = sb.toString().trim();
                if (sharedListStr.endsWith("<br>"))
                    sharedListStr = sharedListStr.substring(0, sharedListStr.length() - 4);
            }

            tooltip = tooltip.replace("<type_color>", typeColor)
                    .replace("<type_name>", typeName)
                    .replace("<multiplier>", String.valueOf(b.getMultiplier()))
                    .replace("<percent>", String.valueOf((int) ((b.getMultiplier() - 1) * 100)))
                    .replace("<decay_rate>", String.format("%.1f", decayRate))
                    .replace("<efficiency>", String.format("%.0f", efficiency))
                    .replace("<shared_count>", String.valueOf(b.getSharedPlayers().size()))
                    .replace("<shared_list>", sharedListStr);

            String statusPath = b.isPermanent() ? "booster_list.items.status_perm" : "booster_list.items.status_time";
            String status = getGui().getString(statusPath).replace("<time_left>", timeStr);
            tooltip = tooltip.replace("<status>", status);

            return ActionButton.builder(ColorUtils.parse(name))
                    .tooltip(ColorUtils.parse(tooltip))
                    .action(DialogAction.customClick((view, audience) -> {
                        if (audience instanceof Player p) {
                            plugin.getFoliaScheduler().runEntity(p, () -> {
                                if (!p.hasPermission("sincebooster.share")) {
                                    p.sendMessage(ColorUtils.parseWithPrefix(getMsg().getString("share.no_permission")));
                                    return;
                                }
                                new ManageShareGUI(plugin).open(p, b.getId());
                            });
                        }
                    }, ClickCallback.Options.builder().uses(1).build()))
                    .build();

        } else {
            double baseMult = b.getMultiplier();
            double currentRate = plugin.getBoosterManager().getShareManager().getReceiverMultiplier(b, viewer.getUniqueId());
            double efficiency = currentRate * 100.0;
            double realMult = 1.0 + ((baseMult - 1.0) * currentRate);

            tooltip = tooltip.replace("<type_color>", typeColor)
                    .replace("<type_name>", typeName)
                    .replace("<owner_name>", db.ownerName)
                    .replace("<base_multiplier>", String.valueOf(baseMult))
                    .replace("<efficiency>", String.format("%.0f", efficiency))
                    .replace("<real_multiplier>", String.format("%.2f", realMult));

            String statusPath = b.isPermanent() ? "booster_list.items.status_perm" : "booster_list.items.status_time";
            String status = getGui().getString(statusPath).replace("<time_left>", timeStr);
            tooltip = tooltip.replace("<status>", status);

            return ActionButton.builder(ColorUtils.parse(name))
                    .tooltip(ColorUtils.parse(tooltip))
                    .action(DialogAction.customClick((view, audience) -> {
                        if (audience instanceof Player p) {
                            plugin.getFoliaScheduler().runEntity(p, () -> {
                                OfflinePlayer owner = Bukkit.getOfflinePlayer(db.ownerUUID);
                                plugin.getBoosterManager().getShareManager().leaveShare(p, owner);
                                p.closeDialog();
                            });
                        }
                    }, ClickCallback.Options.builder().uses(1).build()))
                    .build();
        }
    }

    private void openInventory(Player viewer, Player target) {
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

    private List<DisplayBooster> getDisplayBoosters(UUID targetUUID) {
        List<DisplayBooster> displayList = new ArrayList<>();

        List<Booster> ownList = plugin.getBoosterManager().getActiveBoosters(targetUUID);
        if (ownList != null) {
            for (Booster b : ownList) {
                if (b.isValid())
                    displayList.add(new DisplayBooster(b, true, getOfflineName(targetUUID), targetUUID, targetUUID));
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
                        displayList.add(new DisplayBooster(b, false, ownerName, ownerUUID, targetUUID));
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
                String ownerName = getOfflineName(ownerUUID) + " " + getGui().getString("booster_list.formats.offline_owner_suffix", "(Off)");

                for (Booster b : boosters) {
                    if (b.isValid() && b.getSharedPlayers().contains(targetUUID)) {
                        displayList.add(new DisplayBooster(b, false, ownerName, ownerUUID, targetUUID));
                    }
                }
            }
        }

        return displayList;
    }

    private Comparator<DisplayBooster> getBoosterComparator() {
        return (d1, d2) -> {
            if (d1.isOwner && !d2.isOwner) return -1;
            if (!d1.isOwner && d2.isOwner) return 1;
            if (d1.booster.isPermanent() && !d2.booster.isPermanent()) return -1;
            if (!d1.booster.isPermanent() && d2.booster.isPermanent()) return 1;
            return Long.compare(d1.booster.getEndTime(), d2.booster.getEndTime());
        };
    }

    private void updateContent(Inventory inv, UUID targetUUID, Player viewer) {
        List<DisplayBooster> displayList = getDisplayBoosters(targetUUID);

        List<DisplayBooster> classBoosters = new ArrayList<>();
        List<DisplayBooster> profBoosters = new ArrayList<>();

        for (DisplayBooster db : displayList) {
            if (db.booster.getProfession() == null) classBoosters.add(db);
            else profBoosters.add(db);
        }

        Comparator<DisplayBooster> sorter = getBoosterComparator();
        classBoosters.sort(sorter);
        profBoosters.sort(sorter);

        List<Integer> classSlots = getGui().getConfig().getIntegerList("booster_list.layout.class_boosters");
        List<Integer> profSlots = getGui().getConfig().getIntegerList("booster_list.layout.prof_boosters");
        fillSection(inv, classBoosters, classSlots, viewer.getUniqueId());
        fillSection(inv, profBoosters, profSlots, viewer.getUniqueId());

        ConfigurationSection sepSection = getGui().getConfig().getConfigurationSection("booster_list.items.separator");
        ItemStack glass = sepSection != null ? new ItemBuilder(plugin, Material.matchMaterial(sepSection.getString("material", "BLACK_STAINED_GLASS_PANE"))).applyConfig(sepSection, "").build() : new ItemStack(Material.BLACK_STAINED_GLASS_PANE);

        for (int slot : getGui().getConfig().getIntegerList("booster_list.layout.separators")) {
            inv.setItem(slot, glass);
        }

        inv.setItem(getGui().getInt("booster_list.items.class_summary.slot"), createClassSummary(displayList));
        inv.setItem(getGui().getInt("booster_list.items.prof_summary.slot"), createProfSummary(displayList));

        ConfigurationSection shareBtnSec = getGui().getConfig().getConfigurationSection("booster_list.items.share_button");
        if (shareBtnSec != null) {
            Material mat = Material.matchMaterial(shareBtnSec.getString("material", "OAK_SIGN"));
            inv.setItem(getGui().getInt("booster_list.items.share_button.slot"), new ItemBuilder(plugin, mat).applyConfig(shareBtnSec, "&e&lShare Booster").build());
        }

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
                if (list.isEmpty() && i == (limit / 2)) {
                    ConfigurationSection emptySec = getGui().getConfig().getConfigurationSection("booster_list.items.empty_slot");
                    Material mat = emptySec != null ? Material.matchMaterial(emptySec.getString("material", "BARRIER")) : Material.BARRIER;
                    newItem = new ItemBuilder(plugin, mat).applyConfig(emptySec, "&cEmpty Slot").build();
                } else {
                    newItem = null;
                }
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
        String keyType = db.isOwner ? "own" : "received";
        ConfigurationSection baseSec = getGui().getConfig().getConfigurationSection("booster_list.items." + keyType);

        Material mat = db.isOwner ? ((b.getProfession() == null) ? Material.NETHER_STAR : Material.ENCHANTED_BOOK) : Material.EXPERIENCE_BOTTLE;
        if (baseSec != null && baseSec.contains("material")) {
            Material overridden = Material.matchMaterial(baseSec.getString("material"));
            if (overridden != null) mat = overridden;
        }

        ItemBuilder builder = new ItemBuilder(plugin, mat);
        builder.setTag(boosterIdKey, PersistentDataType.STRING, b.getId());
        builder.setTag(ownerUuidKey, PersistentDataType.STRING, db.ownerUUID.toString());

        long left = (b.getEndTime() - System.currentTimeMillis()) / 1000;
        String timeStr = formatTime(Math.max(0, left));
        String id = b.getId().toUpperCase();

        String typeColor = (b.getProfession() == null) ? "<aqua>" : "<green>";
        String typeName = getBoosterTypeName(b);
        String statusPath = b.isPermanent() ? "booster_list.items.status_perm" : "booster_list.items.status_time";
        String status = getGui().getString(statusPath).replace("<time_left>", timeStr);

        if (baseSec != null) {
            String nameFormatKey = b.isPermanent() ? "name_perm" : "name_time";
            String baseName = baseSec.getString(nameFormatKey, "&6<id>");

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
                        String pName = (op.getName() != null) ? op.getName() : getGui().getString("booster_list.formats.unknown_player", "Unknown");
                        String format = getGui().getString("booster_list.items.shared_list_format", "&7- &f<player>");
                        sb.append(format.replace("<player>", pName)).append("<br>");
                    }
                    sharedListStr = sb.toString().trim();
                    if (sharedListStr.endsWith("<br>"))
                        sharedListStr = sharedListStr.substring(0, sharedListStr.length() - 4);
                }

                builder.applyConfig(baseSec, baseName,
                        "<id>", id,
                        "<time>", timeStr,
                        "<type_color>", typeColor,
                        "<type_name>", typeName,
                        "<status>", status,
                        "<multiplier>", String.valueOf(b.getMultiplier()),
                        "<percent>", String.valueOf((int) ((b.getMultiplier() - 1) * 100)),
                        "<decay_rate>", String.format("%.1f", decayRate),
                        "<efficiency>", String.format("%.0f", efficiency),
                        "<shared_count>", String.valueOf(b.getSharedPlayers().size()),
                        "<shared_list>", sharedListStr
                );
            } else {
                double baseMult = b.getMultiplier();
                double currentRate = plugin.getBoosterManager().getShareManager().getReceiverMultiplier(b, viewerUUID);
                double efficiency = currentRate * 100.0;
                double realMult = 1.0 + ((baseMult - 1.0) * currentRate);

                builder.applyConfig(baseSec, baseName,
                        "<id>", id,
                        "<time>", timeStr,
                        "<type_color>", typeColor,
                        "<type_name>", typeName,
                        "<status>", status,
                        "<owner_name>", db.ownerName,
                        "<base_multiplier>", String.valueOf(baseMult),
                        "<efficiency>", String.format("%.0f", efficiency),
                        "<real_multiplier>", String.format("%.2f", realMult)
                );
            }
        }
        return builder.build();
    }

    private ItemStack createClassSummary(List<DisplayBooster> list) {
        ConfigurationSection sec = getGui().getConfig().getConfigurationSection("booster_list.items.class_summary");
        Material mat = sec != null ? Material.matchMaterial(sec.getString("material", "BEACON")) : Material.BEACON;

        double totalAdd = 0, ownAdd = 0, sharedAdd = 0;
        for (DisplayBooster db : list) {
            if (db.booster.getProfession() == null) {
                double bonus = displayBoosterBonus(db);
                totalAdd += bonus;
                if (db.isOwner) ownAdd += bonus;
                else sharedAdd += bonus;
            }
        }

        return new ItemBuilder(plugin, mat).applyConfig(sec, "&aClass XP Summary",
                "<total_multiplier>", String.format("%.2f", 1.0 + totalAdd),
                "<total_percent>", String.valueOf((int) (totalAdd * 100)),
                "<own_percent>", String.valueOf((int) (ownAdd * 100)),
                "<shared_percent>", String.valueOf((int) (sharedAdd * 100))
        ).build();
    }

    private ItemStack createProfSummary(List<DisplayBooster> list) {
        ConfigurationSection sec = getGui().getConfig().getConfigurationSection("booster_list.items.prof_summary");
        Material mat = sec != null ? Material.matchMaterial(sec.getString("material", "KNOWLEDGE_BOOK")) : Material.KNOWLEDGE_BOOK;

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
        StringBuilder profListBuilder = new StringBuilder();

        if (totals.isEmpty()) {
            profListBuilder.append(none);
        } else {
            for (Map.Entry<String, Double> entry : totals.entrySet()) {
                double val = 1.0 + entry.getValue();
                String f = format.replace("<profession>", entry.getKey().toUpperCase())
                        .replace("<multiplier>", String.format("%.2f", val))
                        .replace("<percent>", String.valueOf((int) (entry.getValue() * 100)));
                if (!profListBuilder.isEmpty()) profListBuilder.append("<br>");
                profListBuilder.append(f);
            }
        }

        return new ItemBuilder(plugin, mat).applyConfig(sec, "&6Profession XP Summary",
                "<prof_list>", profListBuilder.toString()
        ).build();
    }

    private double displayBoosterBonus(DisplayBooster db) {
        return plugin.getBoosterManager().getShareManager().getFinalMultiplier(db.booster, db.subjectUUID) - 1.0;
    }

    private String getBoosterTypeName(Booster booster) {
        if (booster.getProfession() == null) {
            return getGui().getString("booster_list.formats.class_type", "Class XP");
        }
        return getGui().getString("booster_list.formats.profession_type", "Job: <profession>")
                .replace("<profession>", booster.getProfession().toUpperCase());
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
        ConfigurationSection sec = getGui().getConfig().getConfigurationSection("booster_list.items.offline_toggle_button." + stateKey);
        Material mat = sec != null ? Material.matchMaterial(sec.getString("material", "BARRIER")) : Material.BARRIER;

        return new ItemBuilder(plugin, mat).applyConfig(sec, "&7Offline Share").build();
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof BoosterHolder holder)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        e.setCancelled(true);
        UUID targetUUID = holder.getTargetUUID();

        if (e.getClickedInventory() == null || e.getClickedInventory() == e.getView().getTopInventory()) {
            int slot = e.getSlot();
            int shareSlot = getGui().getInt("booster_list.items.share_button.slot");
            int toggleSlot = getGui().getInt("booster_list.items.offline_toggle_button.slot");
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
        UUID subjectUUID;

        public DisplayBooster(Booster b, boolean o, String n, UUID u, UUID subjectUUID) {
            booster = b;
            isOwner = o;
            ownerName = n;
            ownerUUID = u;
            this.subjectUUID = subjectUUID;
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
