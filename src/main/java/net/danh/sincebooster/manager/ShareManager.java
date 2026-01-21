package net.danh.sincebooster.manager;


import net.danh.sincebooster.SinceBooster;
import net.danh.sincebooster.data.PlayerDataHandler;
import net.danh.sincebooster.utils.ColorUtils;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ShareManager {
    private final SinceBooster plugin;
    private final Map<UUID, Map<UUID, List<String>>> pendingInvites = new ConcurrentHashMap<>();

    private double globalShareRate;
    private double globalOwnerBuff;
    private double globalReceiverBuff;
    private int globalShareLimit;

    public ShareManager(SinceBooster plugin) {
        this.plugin = plugin;
        reloadConfigValues();
        startDecayTask();
    }

    public void reloadConfigValues() {
        this.globalShareRate = plugin.getConfigFile().getDouble("share.default-rate", 2.0);
        this.globalOwnerBuff = plugin.getConfigFile().getDouble("share.default-owner-buff", 1.0);
        this.globalReceiverBuff = plugin.getConfigFile().getDouble("share.default-receiver-buff", 0.25);
        this.globalShareLimit = plugin.getConfigFile().getInt("share.default-share-limit", 1);
    }

    public void setGlobalValue(String key, double value) {
        if (key.equals("default-share-limit")) plugin.getConfigFile().set("share." + key, (int) value);
        else plugin.getConfigFile().set("share." + key, value);
        plugin.getConfigFile().save();
        reloadConfigValues();
    }

    // --- GETTERS ---
    public double getDecayRate(Player p) {
        PlayerDataHandler.PlayerSession s = plugin.getPlayerDataHandler().getSession(p.getUniqueId());
        if (s != null && s.getShareRate() > 0) return s.getShareRate();
        return globalShareRate;
    }

    public double getOwnerBuffRate(UUID uuid) {
        PlayerDataHandler.PlayerSession s = plugin.getPlayerDataHandler().getSession(uuid);
        if (s != null && s.getOwnerBuffRate() >= 0) return s.getOwnerBuffRate();
        return globalOwnerBuff;
    }

    public double getReceiverBuffRate(UUID uuid) {
        PlayerDataHandler.PlayerSession s = plugin.getPlayerDataHandler().getSession(uuid);
        if (s != null && s.getReceiverBuffRate() >= 0) return s.getReceiverBuffRate();
        return globalReceiverBuff;
    }

    public int getPlayerShareLimit(Player p) {
        PlayerDataHandler.PlayerSession s = plugin.getPlayerDataHandler().getSession(p.getUniqueId());
        if (s != null && s.getShareLimit() >= 0) return s.getShareLimit();
        return globalShareLimit;
    }

    // --- HELPERS ---
    public List<String> getPendingSenders(Player receiver) {
        List<String> names = new ArrayList<>();
        UUID rId = receiver.getUniqueId();
        if (pendingInvites.containsKey(rId)) {
            Map<UUID, List<String>> senders = pendingInvites.get(rId);
            for (UUID sId : senders.keySet()) {
                Player sender = Bukkit.getPlayer(sId);
                if (sender != null) names.add(sender.getName());
            }
        }
        return names;
    }

    // Helper cho Tab Complete lệnh Leave (Lấy list Owner đang share cho mình)
    public List<String> getOwnersSharingWith(Player receiver) {
        List<String> owners = new ArrayList<>();
        UUID rId = receiver.getUniqueId();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getUniqueId().equals(rId)) continue;
            List<Booster> boosters = plugin.getBoosterManager().getActiveBoosters(p.getUniqueId());
            if (boosters != null) {
                for (Booster b : boosters) {
                    if (b.getSharedPlayers().contains(rId)) {
                        owners.add(p.getName());
                        break;
                    }
                }
            }
        }
        return owners;
    }

    public String getBoosterDisplayName(Booster b) {
        String formatKey = b.isPermanent() ? "share.format.permanent" : "share.format.duration";
        String format = plugin.getMessagesFile().getString(formatKey, "<id> (x<mult>)");
        long left = (b.getEndTime() - System.currentTimeMillis()) / 1000;
        String timeStr = formatTime(Math.max(0, left));
        return format.replace("<id>", b.getId().toUpperCase())
                .replace("<mult>", String.valueOf(b.getMultiplier()))
                .replace("<time>", timeStr);
    }

    private String formatTime(long seconds) {
        long d = seconds / 86400;
        long h = (seconds % 86400) / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return d + "d " + h + "h " + m + "m " + s + "s";
    }

    // --- SHARE BATCH (Dùng cho cả Single và All) ---
    public void sendInviteBatch(Player sender, Player receiver, List<Booster> boosters) {
        if (boosters == null || boosters.isEmpty()) {
            sender.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.no_boosters_to_share")));
            return;
        }

        UUID rId = receiver.getUniqueId();
        UUID sId = sender.getUniqueId();
        int maxShares = getPlayerShareLimit(sender);

        List<String> validBoosterIds = new ArrayList<>();
        List<String> displayNames = new ArrayList<>();

        for (Booster b : boosters) {
            // Check Limit
            if (b.getSharedPlayers().size() >= maxShares) continue;
            // Check Already Shared
            if (b.getSharedPlayers().contains(rId)) continue;

            validBoosterIds.add(b.getId());
            displayNames.add(getBoosterDisplayName(b));
        }

        if (validBoosterIds.isEmpty()) {
            sender.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.no_boosters_to_share")));
            return;
        }

        // Add to Pending
        pendingInvites.putIfAbsent(rId, new HashMap<>());
        Map<UUID, List<String>> senderMap = pendingInvites.get(rId);

        // Check if already invited same list? (Optional, here we overwrite)
        senderMap.put(sId, new ArrayList<>(validBoosterIds));

        // Send Message
        if (validBoosterIds.size() == 1) {
            // Single Message
            String msgSent = plugin.getMessagesFile().getString("share.invite_sent");
            if (msgSent != null)
                sender.sendMessage(ColorUtils.parseWithPrefix(msgSent.replace("<target>", receiver.getName()).replace("<booster_display>", displayNames.getFirst()).replace("<time>", "60")));
            String msgRec = plugin.getMessagesFile().getString("share.invite_received");
            if (msgRec != null)
                receiver.sendMessage(ColorUtils.parseWithPrefix(msgRec.replace("<player>", sender.getName()).replace("<booster_display>", displayNames.getFirst())));
        } else {
            // Batch Message
            String separator = plugin.getMessagesFile().getString("share.batch_separator", ", ");
            String listStr = String.join(separator, displayNames);

            String msgSent = plugin.getMessagesFile().getString("share.invite_batch_sent");
            if (msgSent != null)
                sender.sendMessage(ColorUtils.parseWithPrefix(msgSent.replace("<target>", receiver.getName()).replace("<list>", listStr)));
            String msgRec = plugin.getMessagesFile().getString("share.invite_batch_received");
            if (msgRec != null)
                receiver.sendMessage(ColorUtils.parseWithPrefix(msgRec.replace("<player>", sender.getName()).replace("<list>", listStr)));
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                if (pendingInvites.containsKey(rId)) {
                    pendingInvites.get(rId).remove(sId);
                }
            }
        }.runTaskLater(plugin, 60 * 20L);
    }

    public void sendInvite(Player sender, Player receiver, String boosterId) {
        List<Booster> all = plugin.getBoosterManager().getActiveBoosters(sender.getUniqueId());
        Booster target = null;
        if (all != null) {
            for (Booster b : all)
                if (b.getId().equalsIgnoreCase(boosterId)) {
                    target = b;
                    break;
                }
        }

        if (target == null) {
            sender.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.booster_not_found").replace("<id>", boosterId)));
            return;
        }

        // Single invite reusing batch logic
        sendInviteBatch(sender, receiver, Collections.singletonList(target));
    }

    public void acceptInvite(Player receiver, Player sender) {
        UUID rId = receiver.getUniqueId();
        UUID sId = sender.getUniqueId();

        if (!pendingInvites.containsKey(rId) || !pendingInvites.get(rId).containsKey(sId)) {
            String msg = plugin.getMessagesFile().getString("share.no_invite");
            if (msg != null) receiver.sendMessage(ColorUtils.parseWithPrefix(msg));
            return;
        }

        List<String> ids = pendingInvites.get(rId).remove(sId);
        if (ids == null || ids.isEmpty()) return;

        List<Booster> senderBoosters = plugin.getBoosterManager().getActiveBoosters(sId);
        if (senderBoosters == null) return;

        boolean success = false;
        int maxShares = getPlayerShareLimit(sender);

        for (Booster b : senderBoosters) {
            if (ids.contains(b.getId())) {
                if (b.getSharedPlayers().size() < maxShares) {
                    b.addSharedPlayer(rId);
                    success = true;
                }
            }
        }

        if (success) {
            String msg1 = plugin.getMessagesFile().getString("share.accepted_sender");
            if (msg1 != null)
                sender.sendMessage(ColorUtils.parseWithPrefix(msg1.replace("<target>", receiver.getName())));
            String msg2 = plugin.getMessagesFile().getString("share.accepted_receiver");
            if (msg2 != null)
                receiver.sendMessage(ColorUtils.parseWithPrefix(msg2.replace("<player>", sender.getName())));
            plugin.getPlayerDataHandler().saveData(sId, false);
        } else {
            receiver.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.limit_reached").replace("<current>", "?").replace("<max>", String.valueOf(maxShares))));
        }
    }

    // --- KICK & LEAVE ---
    public void kickShare(Player owner, String boosterId, OfflinePlayer target) {
        List<Booster> boosters = plugin.getBoosterManager().getActiveBoosters(owner.getUniqueId());
        if (boosters != null) {
            for (Booster b : boosters) {
                if (b.getId().equalsIgnoreCase(boosterId)) {
                    if (b.getSharedPlayers().contains(target.getUniqueId())) {
                        b.removeSharedPlayer(target.getUniqueId());
                        plugin.getPlayerDataHandler().saveData(owner.getUniqueId(), false);

                        String targetName = target.getName() != null ? target.getName() : plugin.getMessagesFile().getString("share.unknown_player", "<red>Người chơi không tồn tại");
                        owner.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.kick_success").replace("<booster>", getBoosterDisplayName(b)).replace("<target>", targetName)));

                        if (target.isOnline() && target.getPlayer() != null)
                            target.getPlayer().sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.kick_target_notify").replace("<player>", owner.getName()).replace("<booster>", getBoosterDisplayName(b))));
                        return;
                    }
                }
            }
        }
        owner.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.booster_not_found").replace("<id>", boosterId)));
    }

    public void leaveShare(Player receiver, OfflinePlayer owner) {
        if (!owner.isOnline()) {
            receiver.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.owner_offline")));
            return;
        }
        Player ownerP = owner.getPlayer();
        List<Booster> boosters = null;
        if (ownerP != null) {
            boosters = plugin.getBoosterManager().getActiveBoosters(ownerP.getUniqueId());
        }
        boolean leftAny = false;

        if (boosters != null) {
            for (Booster b : boosters) {
                if (b.getSharedPlayers().contains(receiver.getUniqueId())) {
                    b.removeSharedPlayer(receiver.getUniqueId());
                    leftAny = true;
                }
            }
        }
        if (leftAny) {
            plugin.getPlayerDataHandler().saveData(ownerP.getUniqueId(), false);
            receiver.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.leave_success").replace("<owner>", ownerP.getName())));
            ownerP.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.leave_owner_notify").replace("<player>", receiver.getName())));
        } else {
            receiver.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.not_sharing_with_owner")));
        }
    }

    private void startDecayTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    List<Booster> boosters = plugin.getBoosterManager().getActiveBoosters(p.getUniqueId());
                    if (boosters == null) continue;
                    double rate = getDecayRate(p);
                    if (rate <= 1.0) continue;
                    long extraDecayMillis = (long) ((rate - 1.0) * 1000);
                    for (Booster b : boosters) {
                        if (!b.isPermanent() && !b.getSharedPlayers().isEmpty()) b.reduceTime(extraDecayMillis);
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }
}