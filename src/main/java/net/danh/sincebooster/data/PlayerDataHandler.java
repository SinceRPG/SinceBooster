package net.danh.sincebooster.data;

import net.danh.sincebooster.SinceBooster;
import net.danh.sincebooster.manager.Booster;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class PlayerDataHandler {
    private final SinceBooster plugin;
    private final Map<UUID, PlayerSession> sessionMap = new ConcurrentHashMap<>();

    public PlayerDataHandler(SinceBooster plugin) {
        this.plugin = plugin;
    }

    public void loadData(@NotNull Player p) {
        UUID uuid = p.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PlayerSession session = loadSessionFromDatabase(uuid);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) {
                    sessionMap.put(uuid, session);
                    plugin.getBoosterManager().loadPlayerBoosters(uuid, session.getBoosters());

                    // Refresh cache share offline
                    plugin.getBoosterManager().getShareManager().refreshOfflineShares(p);
                }
            });
        });
    }

    // Hỗ trợ Admin view người chơi Offline
    public PlayerSession loadOfflineData(OfflinePlayer p) {
        if (p.isOnline() && sessionMap.containsKey(p.getUniqueId())) {
            return sessionMap.get(p.getUniqueId());
        }
        return loadSessionFromDatabase(p.getUniqueId());
    }

    private PlayerSession loadSessionFromDatabase(UUID uuid) {
        List<Booster> boosters = new ArrayList<>();
        double shareRate = -1.0;
        double ownerBuffRate = -1.0;
        double receiverBuffRate = -1.0;
        int shareLimit = -1;
        boolean offlineShareEnabled = false;
        double offlineShareRate = -1.0;

        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            String queryUser = "SELECT * FROM " + plugin.getDatabaseManager().getUsersTable() + " WHERE uuid = ?";
            try (PreparedStatement ps = conn.prepareStatement(queryUser)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        shareRate = rs.getDouble("share_rate");
                        ownerBuffRate = rs.getDouble("owner_buff_rate");
                        receiverBuffRate = rs.getDouble("receiver_buff_rate");
                        shareLimit = rs.getInt("share_limit");
                        offlineShareEnabled = rs.getBoolean("offline_share_enabled");
                        offlineShareRate = rs.getDouble("offline_share_rate");
                    }
                }
            }

            String queryBooster = "SELECT * FROM " + plugin.getDatabaseManager().getBoostersTable() + " WHERE uuid = ?";
            try (PreparedStatement ps = conn.prepareStatement(queryBooster)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Booster b = parseBoosterFromResultSet(rs, uuid);
                        if (b != null) boosters.add(b);
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return new PlayerSession(boosters, shareRate, ownerBuffRate, receiverBuffRate, shareLimit, offlineShareEnabled, offlineShareRate);
    }

    private Booster parseBoosterFromResultSet(ResultSet rs, UUID ownerUUID) throws SQLException {
        boolean perm = rs.getBoolean("is_permanent");
        long remaining = rs.getLong("remaining_time");
        if (!perm && remaining <= 0) return null;

        String bId = rs.getString("booster_id");
        double mult = rs.getDouble("multiplier");
        String prof = rs.getString("profession");
        String sharedRaw = rs.getString("shared_with");

        long newEndTime = perm ? -1 : System.currentTimeMillis() + remaining;

        // [CẬP NHẬT] Truyền ownerUUID vào constructor
        Booster b = new Booster(bId, mult, newEndTime, prof, perm, true, ownerUUID, -1.0);

        if (sharedRaw != null && !sharedRaw.isEmpty()) {
            for (String sUUID : sharedRaw.split(",")) {
                if (!sUUID.isBlank()) {
                    try {
                        b.addSharedPlayer(UUID.fromString(sUUID.trim()));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
        return b;
    }

    public void saveData(UUID uuid, boolean removeDataFromMemory) {
        if (!sessionMap.containsKey(uuid)) return;
        PlayerSession session = sessionMap.get(uuid);

        // Cập nhật trạng thái permission trước khi lưu
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            if (!p.hasPermission("sincebooster.share.offline")) {
                session.setOfflineShareEnabled(false);
            }
        }

        List<Booster> saveBoosters = new ArrayList<>(plugin.getBoosterManager().getBoosters(uuid));

        if (removeDataFromMemory) {
            sessionMap.remove(uuid);
            plugin.getBoosterManager().unloadPlayerBoosters(uuid);
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> saveSessionToDatabase(uuid, session, saveBoosters));
    }

    private void saveSessionToDatabase(UUID uuid, PlayerSession session, List<Booster> boosters) {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            conn.setAutoCommit(false);

            String upsertUser = plugin.getDatabaseManager().isMySQL()
                    ? "INSERT INTO " + plugin.getDatabaseManager().getUsersTable() + " (uuid, share_rate, owner_buff_rate, receiver_buff_rate, share_limit, offline_share_enabled, offline_share_rate) VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE share_rate=VALUES(share_rate), owner_buff_rate=VALUES(owner_buff_rate), receiver_buff_rate=VALUES(receiver_buff_rate), share_limit=VALUES(share_limit), offline_share_enabled=VALUES(offline_share_enabled), offline_share_rate=VALUES(offline_share_rate)"
                    : "INSERT OR REPLACE INTO " + plugin.getDatabaseManager().getUsersTable() + " (uuid, share_rate, owner_buff_rate, receiver_buff_rate, share_limit, offline_share_enabled, offline_share_rate) VALUES (?, ?, ?, ?, ?, ?, ?)";

            try (PreparedStatement ps = conn.prepareStatement(upsertUser)) {
                ps.setString(1, uuid.toString());
                ps.setDouble(2, session.getShareRate());
                ps.setDouble(3, session.getOwnerBuffRate());
                ps.setDouble(4, session.getReceiverBuffRate());
                ps.setInt(5, session.getShareLimit());
                ps.setBoolean(6, session.isOfflineShareEnabled());
                ps.setDouble(7, session.getOfflineShareRate());
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + plugin.getDatabaseManager().getBoostersTable() + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }

            if (!boosters.isEmpty()) {
                String insBooster = "INSERT INTO " + plugin.getDatabaseManager().getBoostersTable() +
                        " (uuid, booster_id, multiplier, profession, is_permanent, remaining_time, shared_with) VALUES (?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(insBooster)) {
                    for (Booster b : boosters) {
                        if (b.isValid()) {
                            ps.setString(1, uuid.toString());
                            ps.setString(2, b.getId());
                            ps.setDouble(3, b.getMultiplier());
                            ps.setString(4, b.getProfession());
                            ps.setBoolean(5, b.isPermanent());
                            long remaining = b.isPermanent() ? 0 : Math.max(0, b.getEndTime() - System.currentTimeMillis());
                            ps.setLong(6, remaining);
                            String sharedStr = b.getSharedPlayers().stream().map(UUID::toString).collect(Collectors.joining(","));
                            ps.setString(7, sharedStr);
                            ps.addBatch();
                        }
                    }
                    ps.executeBatch();
                }
            }
            conn.commit();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // Thêm phương thức này vào class PlayerDataHandler
    public void forceLoadSession(UUID uuid) {
        if (sessionMap.containsKey(uuid)) return;

        // Load dữ liệu từ database và đưa vào sessionMap
        PlayerSession session = loadSessionFromDatabase(uuid);
        sessionMap.put(uuid, session);

        // Nạp luôn booster của người đó vào BoosterManager
        plugin.getBoosterManager().loadPlayerBoosters(uuid, session.getBoosters());
    }

    public void saveAllSync() {
        for (UUID uuid : new HashSet<>(sessionMap.keySet())) {
            // Sync thì không chạy async task mà gọi thẳng logic save
            PlayerSession session = sessionMap.get(uuid);
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                if (!p.hasPermission("sincebooster.share.offline")) {
                    session.setOfflineShareEnabled(false);
                }
            }
            List<Booster> boosters = new ArrayList<>(plugin.getBoosterManager().getBoosters(uuid));
            saveSessionToDatabase(uuid, session, boosters);
        }
    }

    public void saveAllAsync() {
        Set<UUID> keys = new HashSet<>(sessionMap.keySet());
        for (UUID uuid : keys) saveData(uuid, false);
    }

    public PlayerSession getSession(UUID uuid) {
        return sessionMap.get(uuid);
    }

    public static class PlayerSession {
        private final List<Booster> boosters;
        private double shareRate, ownerBuffRate, receiverBuffRate, offlineShareRate;
        private int shareLimit;
        private boolean offlineShareEnabled;

        public PlayerSession(List<Booster> boosters, double shareRate, double ownerBuffRate, double receiverBuffRate, int shareLimit, boolean offlineShareEnabled, double offlineShareRate) {
            this.boosters = boosters;
            this.shareRate = shareRate;
            this.ownerBuffRate = ownerBuffRate;
            this.receiverBuffRate = receiverBuffRate;
            this.shareLimit = shareLimit;
            this.offlineShareEnabled = offlineShareEnabled;
            this.offlineShareRate = offlineShareRate;
        }

        public double getShareRate() {
            return shareRate;
        }

        public void setShareRate(double v) {
            this.shareRate = v;
        }

        public double getOwnerBuffRate() {
            return ownerBuffRate;
        }

        public void setOwnerBuffRate(double v) {
            this.ownerBuffRate = v;
        }

        public double getReceiverBuffRate() {
            return receiverBuffRate;
        }

        public void setReceiverBuffRate(double v) {
            this.receiverBuffRate = v;
        }

        public int getShareLimit() {
            return shareLimit;
        }

        public void setShareLimit(int v) {
            this.shareLimit = v;
        }

        public boolean isOfflineShareEnabled() {
            return offlineShareEnabled;
        }

        public void setOfflineShareEnabled(boolean v) {
            this.offlineShareEnabled = v;
        }

        public double getOfflineShareRate() {
            return offlineShareRate;
        }

        public void setOfflineShareRate(double v) {
            this.offlineShareRate = v;
        }

        public List<Booster> getBoosters() {
            return boosters;
        }
    }
}