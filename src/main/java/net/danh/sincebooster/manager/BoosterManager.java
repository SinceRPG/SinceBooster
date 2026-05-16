package net.danh.sincebooster.manager;

import net.danh.sincebooster.SinceBooster;
import net.danh.sincebooster.utils.ColorUtils;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Core manager responsible for tracking active boosters, caching incoming shared boosters,
 * and performing logic validations before multiplying player experience.
 */
public class BoosterManager {
    private final SinceBooster plugin;

    // Map: Owner UUID -> List of Boosters owned by the player
    private final Map<UUID, List<Booster>> activeBoosters = new ConcurrentHashMap<>();

    // Map: Receiver UUID -> Set of incoming shared boosters (O(1) cache for fast experience calculation)
    private final Map<UUID, Set<Booster>> incomingShares = new ConcurrentHashMap<>();

    private final ShareManager shareManager;

    public BoosterManager(SinceBooster plugin) {
        this.plugin = plugin;
        this.shareManager = new ShareManager(plugin);
    }

    public ShareManager getShareManager() {
        return shareManager;
    }

    public void loadPlayerBoosters(UUID ownerId, List<Booster> boosters) {
        List<Booster> safeList = new CopyOnWriteArrayList<>(boosters);
        activeBoosters.put(ownerId, safeList);
        for (Booster b : safeList) refreshIncomingCache(b);
    }

    public void unloadPlayerBoosters(UUID ownerId) {
        List<Booster> removedList = activeBoosters.remove(ownerId);
        if (removedList != null) {
            for (Booster b : removedList) {
                for (UUID receiverId : b.getSharedPlayers()) {
                    if (incomingShares.containsKey(receiverId)) {
                        incomingShares.get(receiverId).remove(b);
                    }
                }
            }
        }
    }

    public void refreshIncomingCache(Booster b) {
        if (!b.isValid()) return;
        for (UUID receiverId : b.getSharedPlayers()) {
            incomingShares.computeIfAbsent(receiverId, k -> ConcurrentHashMap.newKeySet()).add(b);
        }
    }

    public void removeFromIncomingCache(Booster b, UUID receiverId) {
        if (incomingShares.containsKey(receiverId)) {
            incomingShares.get(receiverId).remove(b);
        }
    }

    public void giveBooster(Player p, String boosterId, double multiplier, long seconds, String profession, boolean isPermanent) {
        UUID uuid = p.getUniqueId();
        activeBoosters.putIfAbsent(uuid, new CopyOnWriteArrayList<>());
        List<Booster> list = activeBoosters.get(uuid);

        String finalId = boosterId.toLowerCase();
        String finalProf = (profession == null || profession.isBlank()) ? null : profession.trim().toLowerCase();
        boolean extended = false;

        for (Booster b : list) {
            if (b.getId().equalsIgnoreCase(finalId)) {
                String bProf = (b.getProfession() == null) ? "CLASS" : b.getProfession().toLowerCase();
                String targetProf = (finalProf == null) ? "CLASS" : finalProf;

                if (bProf.equals(targetProf)) {
                    if (b.isPermanent() && isPermanent) {
                        list.remove(b);
                        break;
                    }
                    if (!b.isPermanent() && !isPermanent) {
                        b.addTime(seconds);
                        sendMsg(p, "booster.receive.extend", finalId, 0, seconds);
                        extended = true;
                        break;
                    }
                }
            }
        }

        if (!extended) {
            Booster newBooster = new Booster(finalId, multiplier, seconds, finalProf, isPermanent, uuid);
            list.add(newBooster);
            if (isPermanent) sendMsg(p, "booster.receive.permanent", finalId, multiplier, 0);
            else sendMsg(p, "booster.receive.duration", finalId, multiplier, seconds);
        }

        plugin.getPlayerDataHandler().saveData(p.getUniqueId(), false);
    }

    public double getTotalMultiplier(Player p, String profession) {
        double totalAdded = 0.0;
        String targetProf = (profession == null) ? null : profession.toLowerCase();
        UUID myUUID = p.getUniqueId();

        List<Booster> myList = activeBoosters.get(myUUID);
        if (myList != null) {
            for (Booster b : myList) {
                if (!b.isValid()) continue;
                if (checkProf(b, targetProf)) {
                    double finalMult = shareManager.getFinalMultiplier(b, myUUID);
                    totalAdded += (finalMult - 1.0);
                }
            }
        }

        Set<Booster> sharedWithMe = incomingShares.get(myUUID);
        if (sharedWithMe != null) {
            for (Booster b : sharedWithMe) {
                if (!b.isValid()) continue;
                if (!b.getSharedPlayers().contains(myUUID)) continue;

                if (checkProf(b, targetProf)) {
                    double finalMult = shareManager.getFinalMultiplier(b, myUUID);
                    totalAdded += (finalMult - 1.0);
                }
            }
        }

        return 1.0 + totalAdded;
    }

    public void loadExternalBoosters(UUID ownerId, List<Booster> boosters) {
        if (activeBoosters.containsKey(ownerId)) return;

        List<Booster> safeList = new CopyOnWriteArrayList<>(boosters);
        activeBoosters.put(ownerId, safeList);
        for (Booster b : safeList) refreshIncomingCache(b);

        plugin.getLogger().info(plugin.getMessagesFile().getString("log.temp_load_boosters", "Temporarily loaded <count> boosters for <uuid> to process offline sharing.").replace("<count>", String.valueOf(boosters.size())).replace("<uuid>", ownerId.toString()));
    }

    private boolean checkProf(Booster b, String targetProf) {
        if (targetProf == null) return b.getProfession() == null;
        return b.getProfession() != null && b.getProfession().equalsIgnoreCase(targetProf);
    }

    private void sendMsg(Player p, String path, String id, double mult, long sec) {
        String msg = plugin.getMessagesFile().getString(path);
        if (msg != null) {
            msg = msg.replace("<id>", id.toUpperCase())
                    .replace("<multiplier>", String.valueOf(mult))
                    .replace("<time>", String.valueOf(sec));
            p.sendMessage(ColorUtils.parseWithPrefix(msg));
        }
    }

    public List<Booster> getActiveBoosters(UUID uuid) {
        return activeBoosters.get(uuid);
    }

    public List<Booster> getBoosters(UUID uuid) {
        return activeBoosters.getOrDefault(uuid, Collections.emptyList());
    }

    public void pruneExpiredBoosters() {
        for (Map.Entry<UUID, List<Booster>> entry : activeBoosters.entrySet()) {
            List<Booster> boosters = entry.getValue();
            if (boosters == null) continue;

            for (Booster booster : new ArrayList<>(boosters)) {
                if (booster.isValid()) continue;
                boosters.remove(booster);
                for (UUID receiverId : booster.getSharedPlayers()) {
                    removeFromIncomingCache(booster, receiverId);
                }
            }

            if (boosters.isEmpty() && Bukkit.getPlayer(entry.getKey()) == null) {
                activeBoosters.remove(entry.getKey(), boosters);
            }
        }

        incomingShares.entrySet().removeIf(entry -> {
            Set<Booster> boosters = entry.getValue();
            boosters.removeIf(booster -> !booster.isValid());
            return boosters.isEmpty();
        });
    }

    public void removeActiveBoosters(UUID uuid) {
        unloadPlayerBoosters(uuid);
    }

    public boolean removeBooster(Player target, String boosterId) {
        List<Booster> boosters = getActiveBoosters(target.getUniqueId());
        if (boosters == null) return false;

        Booster toRemove = null;
        for (Booster b : boosters) {
            if (b.getId().equalsIgnoreCase(boosterId)) {
                toRemove = b;
                break;
            }
        }

        if (toRemove != null) {
            for (UUID receiverId : new ArrayList<>(toRemove.getSharedPlayers())) {
                OfflinePlayer receiver = Bukkit.getPlayer(receiverId);
                if (receiver != null) {
                    getShareManager().kickShare(target, toRemove.getId(), receiver);
                }
            }
            boosters.remove(toRemove);
            return true;
        }
        return false;
    }

    public void removeAllBoosters(Player target) {
        List<Booster> boosters = getActiveBoosters(target.getUniqueId());
        if (boosters == null || boosters.isEmpty()) return;

        List<Booster> copy = new ArrayList<>(boosters);
        for (Booster b : copy) removeBooster(target, b.getId());
    }

    public Map<UUID, List<Booster>> getActiveBoosters() {
        return activeBoosters;
    }

    public Map<UUID, Set<Booster>> getIncomingShares() {
        return incomingShares;
    }
}
