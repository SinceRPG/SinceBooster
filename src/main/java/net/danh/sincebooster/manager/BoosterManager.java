package net.danh.sincebooster.manager;

import net.danh.sincebooster.SinceBooster;
import net.danh.sincebooster.utils.ColorUtils;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class BoosterManager {
    private final SinceBooster plugin;
    private final Map<UUID, List<Booster>> activeBoosters = new ConcurrentHashMap<>();
    private final ShareManager shareManager;

    public BoosterManager(SinceBooster plugin) {
        this.plugin = plugin;
        this.shareManager = new ShareManager(plugin);
    }

    public ShareManager getShareManager() {
        return shareManager;
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
            Booster newBooster = new Booster(finalId, multiplier, seconds, finalProf, isPermanent);
            list.add(newBooster);
            if (isPermanent) sendMsg(p, "booster.receive.permanent", finalId, multiplier, 0);
            else sendMsg(p, "booster.receive.duration", finalId, multiplier, seconds);
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.getPlayerDataHandler().saveData(p.getUniqueId(), false);
        });
    }

    public double getTotalMultiplier(Player p, String profession) {
        double totalAdded = 0.0;
        String targetProf = (profession == null) ? null : profession.toLowerCase();
        UUID myUUID = p.getUniqueId();

        // 1. BOOSTER CỦA MÌNH
        List<Booster> myList = activeBoosters.get(myUUID);
        if (myList != null) {
            for (Booster b : myList) {
                if (!b.isValid()) {
                    myList.remove(b);
                    if (p.isOnline()) sendMsg(p, "booster.expired", b.getId(), 0, 0);
                    continue;
                }
                if (checkProf(b, targetProf)) {
                    double bonus = b.getMultiplier() - 1.0;

                    // Nếu là Perm Booster đang share -> Giảm hiệu quả của Owner
                    if (b.isPermanent() && !b.getSharedPlayers().isEmpty()) {
                        bonus *= shareManager.getOwnerBuffRate(myUUID);
                    }

                    totalAdded += bonus;
                }
            }
        }

        // 2. BOOSTER ĐƯỢC SHARE
        for (Map.Entry<UUID, List<Booster>> entry : activeBoosters.entrySet()) {
            UUID ownerUUID = entry.getKey();
            if (ownerUUID.equals(myUUID)) continue;

            List<Booster> otherList = entry.getValue();
            for (Booster b : otherList) {
                if (b.isValid() && b.getSharedPlayers().contains(myUUID)) {
                    if (checkProf(b, targetProf)) {
                        double bonus = b.getMultiplier() - 1.0;

                        // Nếu là Perm Booster -> Giảm hiệu quả cho Receiver
                        if (b.isPermanent()) {
                            bonus *= shareManager.getReceiverBuffRate(ownerUUID);
                        }
                        // Nếu Time Booster -> Giữ 100% (Owner đã chịu trừ giờ)

                        totalAdded += bonus;
                    }
                }
            }
        }
        return 1.0 + totalAdded;
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

    public void setActiveBoosters(UUID uuid, List<Booster> boosters) {
        activeBoosters.put(uuid, new CopyOnWriteArrayList<>(boosters));
    }

    public List<Booster> getActiveBoosters(UUID uuid) {
        return activeBoosters.get(uuid);
    }

    public void removeActiveBoosters(UUID uuid) {
        activeBoosters.remove(uuid);
    }
}