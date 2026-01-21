package net.danh.sincebooster.data;

import net.danh.sincebooster.SinceBooster;
import net.danh.sincebooster.manager.Booster;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerDataHandler {
    private final SinceBooster plugin;
    private final Map<UUID, PlayerSession> sessionMap = new ConcurrentHashMap<>();

    public PlayerDataHandler(SinceBooster plugin) {
        this.plugin = plugin;
    }

    public void loadData(@NotNull Player p) {
        UUID uuid = p.getUniqueId();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Booster> boosters = new ArrayList<>();

            // Default Values (-1 means use Global Config)
            double shareRate = -1.0;
            double ownerBuffRate = -1.0;
            double receiverBuffRate = -1.0;
            int shareLimit = -1;

            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                // 1. Load User Data (Rate & Limit)
                String queryUser = "SELECT * FROM " + plugin.getDatabaseManager().getUsersTable() + " WHERE uuid = ?";
                try (PreparedStatement ps = conn.prepareStatement(queryUser)) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            try {
                                shareRate = rs.getDouble("share_rate");
                            } catch (SQLException ignored) {
                            }
                            try {
                                ownerBuffRate = rs.getDouble("owner_buff_rate");
                            } catch (SQLException ignored) {
                            }
                            try {
                                receiverBuffRate = rs.getDouble("receiver_buff_rate");
                            } catch (SQLException ignored) {
                            }
                            try {
                                shareLimit = rs.getInt("share_limit");
                            } catch (SQLException ignored) {
                            }
                        }
                    }
                }

                // 2. Load Boosters
                String queryBooster = "SELECT * FROM " + plugin.getDatabaseManager().getBoostersTable() + " WHERE uuid = ?";
                try (PreparedStatement ps = conn.prepareStatement(queryBooster)) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String bId = rs.getString("booster_id");
                            double mult = rs.getDouble("multiplier");
                            String prof = rs.getString("profession");
                            boolean perm = rs.getBoolean("is_permanent");
                            long remaining = rs.getLong("remaining_time");
                            String sharedRaw = rs.getString("shared_with");

                            if (perm || remaining > 0) {
                                long newEndTime = perm ? -1 : System.currentTimeMillis() + remaining;
                                Booster b = new Booster(bId, mult, newEndTime, prof, perm, true);

                                if (sharedRaw != null && !sharedRaw.isEmpty()) {
                                    String[] split = sharedRaw.split(",");
                                    for (String sUUID : split) {
                                        if (sUUID != null && !sUUID.trim().isEmpty()) {
                                            try {
                                                b.addSharedPlayer(UUID.fromString(sUUID.trim()));
                                            } catch (IllegalArgumentException ignored) {
                                                // Log warning nếu cần
                                            }
                                        }
                                    }
                                }
                                boosters.add(b);
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Could not load data for " + p.getName());
                e.printStackTrace();
            }

            // Final variables for lambda
            double fShare = shareRate;
            double fOwner = ownerBuffRate;
            double fReceiver = receiverBuffRate;
            int fLimit = shareLimit;

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) {
                    PlayerSession session = new PlayerSession(boosters, fShare, fOwner, fReceiver, fLimit);
                    sessionMap.put(uuid, session);
                    plugin.getBoosterManager().setActiveBoosters(uuid, boosters);
                }
            });
        });
    }

    public void saveData(UUID uuid, boolean removeDataFromMemory) {
        if (!sessionMap.containsKey(uuid)) return;
        PlayerSession session = sessionMap.get(uuid);

        // Snapshot session data
        double shareRate = session.getShareRate();
        double ownerBuff = session.getOwnerBuffRate();
        double recBuff = session.getReceiverBuffRate();
        int shareLimit = session.getShareLimit();
        List<Booster> currentBoosters = plugin.getBoosterManager().getActiveBoosters(uuid);
        List<Booster> saveBoosters = (currentBoosters != null) ? new ArrayList<>(currentBoosters) : new ArrayList<>();

        if (removeDataFromMemory) {
            sessionMap.remove(uuid);
            plugin.getBoosterManager().removeActiveBoosters(uuid);
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                conn.setAutoCommit(false);

                // A. Save User (Updated with share_limit)
                String upsertUser = plugin.getDatabaseManager().isMySQL()
                        ? "INSERT INTO " + plugin.getDatabaseManager().getUsersTable() + " (uuid, share_rate, owner_buff_rate, receiver_buff_rate, share_limit) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE share_rate=VALUES(share_rate), owner_buff_rate=VALUES(owner_buff_rate), receiver_buff_rate=VALUES(receiver_buff_rate), share_limit=VALUES(share_limit)"
                        : "INSERT OR REPLACE INTO " + plugin.getDatabaseManager().getUsersTable() + " (uuid, share_rate, owner_buff_rate, receiver_buff_rate, share_limit) VALUES (?, ?, ?, ?, ?)";

                try (PreparedStatement ps = conn.prepareStatement(upsertUser)) {
                    ps.setString(1, uuid.toString());
                    ps.setDouble(2, shareRate);
                    ps.setDouble(3, ownerBuff);
                    ps.setDouble(4, recBuff);
                    ps.setInt(5, shareLimit);
                    ps.executeUpdate();
                }

                // B. Save Boosters
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + plugin.getDatabaseManager().getBoostersTable() + " WHERE uuid = ?")) {
                    ps.setString(1, uuid.toString());
                    ps.executeUpdate();
                }

                if (!saveBoosters.isEmpty()) {
                    String insBooster = "INSERT INTO " + plugin.getDatabaseManager().getBoostersTable() +
                            " (uuid, booster_id, multiplier, profession, is_permanent, remaining_time, shared_with) VALUES (?, ?, ?, ?, ?, ?, ?)";

                    try (PreparedStatement ps = conn.prepareStatement(insBooster)) {
                        for (Booster b : saveBoosters) {
                            if (b.isValid()) { // Double check valid
                                ps.setString(1, uuid.toString());
                                ps.setString(2, b.getId());
                                ps.setDouble(3, b.getMultiplier());
                                ps.setString(4, b.getProfession());
                                ps.setBoolean(5, b.isPermanent());
                                long remaining = b.isPermanent() ? 0 : Math.max(0, b.getEndTime() - System.currentTimeMillis());
                                ps.setLong(6, remaining);

                                StringBuilder sb = new StringBuilder();
                                for (UUID u : b.getSharedPlayers()) {
                                    if (sb.length() > 0) sb.append(",");
                                    sb.append(u.toString());
                                }
                                ps.setString(7, sb.toString());
                                ps.addBatch();
                            }
                        }
                        ps.executeBatch();
                    }
                }
                conn.commit(); // [GOOD] Commit transaction
            } catch (SQLException e) {
                plugin.getLogger().severe("Could not save data for UUID: " + uuid);
                e.printStackTrace();
            }
        });
    }

    public void saveAllSync() {
        for (UUID uuid : new HashSet<>(sessionMap.keySet())) {
            saveData(uuid, false);
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
        private double shareRate;
        private double ownerBuffRate;
        private double receiverBuffRate;
        private int shareLimit;

        public PlayerSession(List<Booster> boosters, double shareRate, double ownerBuffRate, double receiverBuffRate, int shareLimit) {
            this.boosters = boosters;
            this.shareRate = shareRate;
            this.ownerBuffRate = ownerBuffRate;
            this.receiverBuffRate = receiverBuffRate;
            this.shareLimit = shareLimit;
        }

        public double getShareRate() {
            return shareRate;
        }

        public void setShareRate(double shareRate) {
            this.shareRate = shareRate;
        }

        public double getOwnerBuffRate() {
            return ownerBuffRate;
        }

        public void setOwnerBuffRate(double ownerBuffRate) {
            this.ownerBuffRate = ownerBuffRate;
        }

        public double getReceiverBuffRate() {
            return receiverBuffRate;
        }

        public void setReceiverBuffRate(double receiverBuffRate) {
            this.receiverBuffRate = receiverBuffRate;
        }

        public int getShareLimit() {
            return shareLimit;
        }

        public void setShareLimit(int shareLimit) {
            this.shareLimit = shareLimit;
        }

        public List<Booster> getBoosters() {
            return boosters;
        }
    }
}