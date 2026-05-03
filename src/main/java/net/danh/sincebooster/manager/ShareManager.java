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
    private final Map<UUID, Map<UUID, List<Booster>>> offlineSharesCache = new ConcurrentHashMap<>();

    private double globalShareRate, globalOwnerBuff, globalReceiverBuff, globalOfflineRate;
    private int globalShareLimit;
    private boolean offlineShareEnabled;

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
        this.offlineShareEnabled = plugin.getConfigFile().getBoolean("share.offline.enabled", false);
        this.globalOfflineRate = plugin.getConfigFile().getDouble("share.offline.default-offline-rate", 0.25);
    }

    public void refreshOfflineShares(Player receiver) {
        if (!offlineShareEnabled) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<UUID, List<Booster>> shares = plugin.getDatabaseManager().getIncomingOfflineShares(receiver.getUniqueId());

            if (!shares.isEmpty()) {
                offlineSharesCache.put(receiver.getUniqueId(), shares);

                shares.keySet().forEach(ownerId -> {
                    if (Bukkit.getPlayer(ownerId) == null) {
                        plugin.getPlayerDataHandler().forceLoadSession(ownerId);
                    }
                });
            }
        });
    }

    public Map<UUID, List<Booster>> getCachedOfflineShares(UUID receiverId) {
        return offlineSharesCache.get(receiverId);
    }

    public double getFinalMultiplier(Booster booster, UUID receiverUUID) {
        if (booster.getOwnerUUID().equals(receiverUUID)) {
            double bonus = booster.getMultiplier() - 1.0;
            if (!booster.getSharedPlayers().isEmpty()) {
                bonus *= getOwnerBuffRate(receiverUUID);
            }
            return 1.0 + bonus;
        }

        if (booster.getSharedPlayers().contains(receiverUUID)) {
            double baseBonus = booster.getMultiplier() - 1.0;
            double rate = getReceiverMultiplier(booster, receiverUUID);
            return 1.0 + (baseBonus * rate);
        }

        return 1.0;
    }

    public void updateOfflineSharesOnJoin(Player owner) {
        UUID ownerId = owner.getUniqueId();
        for (UUID receiverId : offlineSharesCache.keySet()) {
            Map<UUID, List<Booster>> map = offlineSharesCache.get(receiverId);
            if (map != null) {
                map.remove(ownerId);
                if (map.isEmpty()) offlineSharesCache.remove(receiverId);
            }
        }
    }

    public void updateOfflineSharesOnQuit(Player owner) {
        if (!owner.hasPermission("sincebooster.share.offline")) return;

        PlayerDataHandler.PlayerSession session = plugin.getPlayerDataHandler().getSession(owner.getUniqueId());
        if (session == null || !session.isOfflineShareEnabled()) return;

        List<Booster> boosters = plugin.getBoosterManager().getActiveBoosters(owner.getUniqueId());
        if (boosters == null || boosters.isEmpty()) return;

        List<Booster> offlineList = new ArrayList<>(boosters);

        Set<UUID> onlineReceivers = new HashSet<>();
        for (Booster b : offlineList) {
            for (UUID uid : b.getSharedPlayers()) {
                if (Bukkit.getPlayer(uid) != null) {
                    onlineReceivers.add(uid);
                }
            }
        }

        for (UUID receiverId : onlineReceivers) {
            offlineSharesCache.computeIfAbsent(receiverId, k -> new ConcurrentHashMap<>())
                    .put(owner.getUniqueId(), offlineList);
        }
    }

    public boolean checkSharePermission(Player p) {
        if (!p.hasPermission("sincebooster.share")) {
            p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.no_permission", "You do not have permission to use the share feature.")));
            return false;
        }
        return true;
    }

    public double getReceiverMultiplier(Booster b, UUID receiverId) {
        UUID ownerId = b.getOwnerUUID();
        Player owner = Bukkit.getPlayer(ownerId);

        if (owner != null) {
            PlayerDataHandler.PlayerSession s = plugin.getPlayerDataHandler().getSession(ownerId);
            if (s != null && s.getReceiverBuffRate() >= 0) return s.getReceiverBuffRate();
            return globalReceiverBuff;
        } else if (offlineShareEnabled) {
            if (b.getCachedOfflineRate() >= 0) {
                return b.getCachedOfflineRate();
            }
            return globalOfflineRate;
        }
        return 0.0;
    }

    public void setGlobalValue(String key, double value) {
        if (key.equals("default-share-limit")) plugin.getConfigFile().set("share." + key, (int) value);
        else plugin.getConfigFile().set("share." + key, value);
        plugin.getConfigFile().save();
        reloadConfigValues();
    }

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

        if (offlineShareEnabled && offlineSharesCache.containsKey(rId)) {
            Map<UUID, List<Booster>> offlineData = offlineSharesCache.get(rId);
            for (UUID ownerId : offlineData.keySet()) {
                if (Bukkit.getPlayer(ownerId) == null) {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(ownerId);
                    owners.add(op.getName() != null ? op.getName() : "Unknown (Off)");
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

    public void sendInviteBatch(Player sender, Player receiver, List<Booster> boosters) {
        if (boosters == null || boosters.isEmpty()) {
            sender.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.no_boosters_to_share", "No boosters available to share.")));
            return;
        }

        UUID rId = receiver.getUniqueId();
        UUID sId = sender.getUniqueId();
        int maxShares = getPlayerShareLimit(sender);

        List<String> validBoosterIds = new ArrayList<>();
        List<String> displayNames = new ArrayList<>();

        for (Booster b : boosters) {
            if (b.getSharedPlayers().size() >= maxShares) continue;
            if (b.getSharedPlayers().contains(rId)) continue;

            validBoosterIds.add(b.getId());
            displayNames.add(getBoosterDisplayName(b));
        }

        if (validBoosterIds.isEmpty()) {
            sender.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.no_boosters_to_share", "No boosters available to share.")));
            return;
        }

        pendingInvites.putIfAbsent(rId, new HashMap<>());
        Map<UUID, List<String>> senderMap = pendingInvites.get(rId);
        senderMap.put(sId, new ArrayList<>(validBoosterIds));

        if (validBoosterIds.size() == 1) {
            String msgSent = plugin.getMessagesFile().getString("share.invite_sent");
            if (msgSent != null)
                sender.sendMessage(ColorUtils.parseWithPrefix(msgSent.replace("<target>", receiver.getName()).replace("<booster_display>", displayNames.getFirst()).replace("<time>", "60")));
            String msgRec = plugin.getMessagesFile().getString("share.invite_received");
            if (msgRec != null)
                receiver.sendMessage(ColorUtils.parseWithPrefix(msgRec.replace("<player>", sender.getName()).replace("<booster_display>", displayNames.getFirst())));
        } else {
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
            sender.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.booster_not_found", "Booster <id> not found.").replace("<id>", boosterId)));
            return;
        }
        sendInviteBatch(sender, receiver, Collections.singletonList(target));
    }

    public void acceptInvite(Player receiver, Player sender) {
        UUID rId = receiver.getUniqueId();
        UUID sId = sender.getUniqueId();

        if (!pendingInvites.containsKey(rId) || !pendingInvites.get(rId).containsKey(sId)) {
            String msg = plugin.getMessagesFile().getString("share.no_invite", "No active invite found.");
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
                    plugin.getBoosterManager().refreshIncomingCache(b);
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
            receiver.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.limit_reached", "Limit reached: <current>/<max>").replace("<current>", "?").replace("<max>", String.valueOf(maxShares))));
        }
    }

    public void kickShare(Player owner, String boosterId, OfflinePlayer target) {
        List<Booster> boosters = plugin.getBoosterManager().getActiveBoosters(owner.getUniqueId());
        if (boosters != null) {
            for (Booster b : boosters) {
                if (b.getId().equalsIgnoreCase(boosterId)) {
                    if (b.getSharedPlayers().contains(target.getUniqueId())) {
                        b.removeSharedPlayer(target.getUniqueId());
                        plugin.getBoosterManager().removeFromIncomingCache(b, target.getUniqueId());
                        plugin.getPlayerDataHandler().saveData(owner.getUniqueId(), false);

                        String targetName = target.getName() != null ? target.getName() : plugin.getMessagesFile().getString("share.unknown_player", "<red>Player does not exist");
                        owner.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.kick_success", "Stopped sharing <booster> with <target>.").replace("<booster>", getBoosterDisplayName(b)).replace("<target>", targetName)));

                        if (target.isOnline() && target.getPlayer() != null)
                            target.getPlayer().sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.kick_target_notify", "<player> stopped sharing <booster> with you.").replace("<player>", owner.getName()).replace("<booster>", getBoosterDisplayName(b))));
                        return;
                    }
                }
            }
        }
        owner.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.booster_not_found", "Booster <id> not found.").replace("<id>", boosterId)));
    }

    public void leaveShare(Player receiver, OfflinePlayer owner) {
        if (!owner.isOnline()) {
            receiver.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.owner_offline", "Owner is currently offline.")));
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
                    plugin.getBoosterManager().removeFromIncomingCache(b, receiver.getUniqueId());
                    leftAny = true;
                }
            }
        }
        if (leftAny) {
            plugin.getPlayerDataHandler().saveData(ownerP.getUniqueId(), false);
            receiver.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.leave_success", "Successfully left <owner>'s share.").replace("<owner>", ownerP.getName())));
            ownerP.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.leave_owner_notify", "<player> left your shared booster.").replace("<player>", receiver.getName())));
        } else {
            receiver.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.not_sharing_with_owner", "You are not sharing any boosters with this player.")));
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