package net.danh.sincebooster.manager;

import net.danh.sincebooster.SinceBooster;
import net.danh.sincebooster.utils.ColorUtils;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class BoosterManager {
    private final SinceBooster plugin;
    // Map: Owner UUID -> List<Booster> (Booster mình sở hữu)
    private final Map<UUID, List<Booster>> activeBoosters = new ConcurrentHashMap<>();

    // [NEW] Map: Receiver UUID -> List<Booster> (Cache danh sách booster người khác share cho mình)
    // Giúp tính toán Exp nhanh hơn O(1) thay vì O(N)
    private final Map<UUID, Set<Booster>> incomingShares = new ConcurrentHashMap<>();

    private final ShareManager shareManager;

    public BoosterManager(SinceBooster plugin) {
        this.plugin = plugin;
        this.shareManager = new ShareManager(plugin);
    }

    public ShareManager getShareManager() {
        return shareManager;
    }

    // Được gọi từ PlayerDataHandler khi load xong
    public void loadPlayerBoosters(UUID ownerId, List<Booster> boosters) {
        List<Booster> safeList = new CopyOnWriteArrayList<>(boosters);
        activeBoosters.put(ownerId, safeList);

        // Update cache incomingShares cho những người được share
        for (Booster b : safeList) {
            refreshIncomingCache(b);
        }
    }

    public void unloadPlayerBoosters(UUID ownerId) {
        List<Booster> removedList = activeBoosters.remove(ownerId);
        if (removedList != null) {
            for (Booster b : removedList) {
                // Xóa booster này khỏi incomingShares của người nhận
                for (UUID receiverId : b.getSharedPlayers()) {
                    if (incomingShares.containsKey(receiverId)) {
                        incomingShares.get(receiverId).remove(b);
                    }
                }
            }
        }
    }

    // Helper: Cập nhật cache khi 1 booster thay đổi danh sách share
    public void refreshIncomingCache(Booster b) {
        for (UUID receiverId : b.getSharedPlayers()) {
            incomingShares.computeIfAbsent(receiverId, k -> ConcurrentHashMap.newKeySet()).add(b);
        }
    }

    // Helper: Xóa 1 người khỏi cache của booster (khi kick/leave)
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
                        list.remove(b); // Xóa để add lại (reset stats) hoặc bỏ qua tùy logic, ở đây giữ nguyên
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

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> plugin.getPlayerDataHandler().saveData(p.getUniqueId(), false));
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
                    // Cleanup cache
                    for (UUID receiver : b.getSharedPlayers()) removeFromIncomingCache(b, receiver);
                    if (p.isOnline()) sendMsg(p, "booster.expired", b.getId(), 0, 0);
                    continue;
                }

                if (checkProf(b, targetProf)) {
                    double bonus = b.getMultiplier() - 1.0;
                    if (b.isPermanent() && !b.getSharedPlayers().isEmpty()) {
                        bonus *= shareManager.getOwnerBuffRate(myUUID);
                    }
                    totalAdded += bonus;
                }
            }
        }

        // 2. BOOSTER ĐƯỢC SHARE (Đã tối ưu O(1) Lookup)
        Set<Booster> sharedWithMe = incomingShares.get(myUUID);
        if (sharedWithMe != null) {
            // Dùng Iterator để có thể remove an toàn nếu expired
            Iterator<Booster> it = sharedWithMe.iterator();
            while (it.hasNext()) {
                Booster b = it.next();

                // Double check valid (phòng trường hợp owner offline hoặc hết hạn mà task chưa quét)
                if (!b.isValid()) {
                    it.remove();
                    continue;
                }

                // Double check xem mình còn trong list share của booster đó không
                if (!b.getSharedPlayers().contains(myUUID)) {
                    it.remove();
                    continue;
                }

                if (checkProf(b, targetProf)) {
                    double bonus = b.getMultiplier() - 1.0;
                    if (b.isPermanent()) {
                        // Cần tìm owner của Booster này để lấy Rate của họ?
                        // Booster class chưa lưu owner UUID field, nhưng ta có thể tìm cách khác hoặc chấp nhận global/cached.
                        // Ở đây logic cũ dùng UUID của owner.
                        // [FIX] Để tối ưu, Booster nên biết ai là chủ.
                        // Tạm thời giả định hệ thống lấy rate người nhận (theo logic cũ: receiverBuffRate(ownerUUID))
                        // Vì Booster không lưu Owner UUID, ta cần map ngược hoặc lưu owner vào Booster.
                        // -> Giải pháp nhanh: Logic cũ dùng loop để biết owner.
                        // -> Giải pháp tối ưu: Ở đây ta chấp nhận lấy Rate của "Mình" hoặc Global nếu không muốn sửa class Booster quá nhiều.
                        // Nhưng để giữ đúng logic cũ:
                        // bonus *= shareManager.getReceiverBuffRate(ownerUUID);

                        // Để fix triệt để mà không loop, tôi sẽ thêm field `ownerUUID` vào Class Booster (xem bên dưới).
                        bonus *= shareManager.getReceiverBuffRate(b.getOwnerUUID());
                    }
                    totalAdded += bonus;
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

    public List<Booster> getActiveBoosters(UUID uuid) {
        return activeBoosters.get(uuid);
    }

    // Alias cho load/save
    public List<Booster> getBoosters(UUID uuid) {
        return activeBoosters.getOrDefault(uuid, Collections.emptyList());
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
            // 1. Kick tất cả người được share booster này trước khi xóa
            for (java.util.UUID receiverId : new java.util.ArrayList<>(toRemove.getSharedPlayers())) {
                OfflinePlayer receiver = Bukkit.getPlayer(receiverId);
                if (receiver != null) {
                    getShareManager().kickShare(target, toRemove.getId(), receiver);
                }
            }

            // 2. Xóa khỏi danh sách
            boosters.remove(toRemove);
            return true;
        }
        return false;
    }

    public void removeAllBoosters(Player target) {
        List<Booster> boosters = getActiveBoosters(target.getUniqueId());
        if (boosters == null || boosters.isEmpty()) return;

        // Tạo bản sao để tránh ConcurrentModificationException khi loop
        List<Booster> copy = new java.util.ArrayList<>(boosters);

        for (Booster b : copy) {
            removeBooster(target, b.getId());
        }
    }
}