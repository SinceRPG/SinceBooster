package net.danh.sincebooster.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.danh.sincebooster.SinceBooster;
import net.danh.sincebooster.manager.Booster;

import java.io.File;
import java.sql.*;
import java.util.*;

/**
 * Handles all database interactions and connection pooling using HikariCP.
 * Supports both MySQL and local SQLite storage.
 */
public class DatabaseManager {

    private final SinceBooster plugin;
    private final boolean isMySQL;
    private HikariDataSource dataSource;

    public DatabaseManager(SinceBooster plugin) {
        this.plugin = plugin;
        this.isMySQL = plugin.getConfigFile().getString("database.type", "SQLITE").equalsIgnoreCase("MYSQL");
        setupDataSource();
        createTables();
    }

    /**
     * Initializes the HikariCP connection pool with optimized parameters.
     */
    private void setupDataSource() {
        HikariConfig config = new HikariConfig();

        if (isMySQL) {
            String host = plugin.getConfigFile().getString("database.host");
            String port = plugin.getConfigFile().getString("database.port");
            String db = plugin.getConfigFile().getString("database.database");
            String user = plugin.getConfigFile().getString("database.username");
            String pass = plugin.getConfigFile().getString("database.password");

            config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + db + "?useSSL=false&autoReconnect=true&characterEncoding=UTF-8");
            config.setUsername(user);
            config.setPassword(pass);

            // Optimal connection properties for MySQL to prevent JIT and GC lag spikes
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            config.addDataSourceProperty("useServerPrepStmts", "true");
            config.addDataSourceProperty("useLocalSessionState", "true");
            config.addDataSourceProperty("rewriteBatchedStatements", "true");
        } else {
            File file = new File(plugin.getDataFolder(), "database.db");
            config.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
        }

        config.setPoolName("SinceBooster-Pool");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        this.dataSource = new HikariDataSource(config);
    }

    public Connection getConnection() throws SQLException {
        if (dataSource == null) throw new SQLException("DataSource is null");
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    /**
     * Creates the necessary tables upon startup if they do not exist.
     */
    private void createTables() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            String usersTable = getUsersTable();
            String boostersTable = getBoostersTable();

            String sqlUsers = """
                    CREATE TABLE IF NOT EXISTS %s (
                        uuid VARCHAR(36) PRIMARY KEY,
                        share_rate DOUBLE DEFAULT -1,
                        owner_buff_rate DOUBLE DEFAULT -1,
                        receiver_buff_rate DOUBLE DEFAULT -1,
                        share_limit INT DEFAULT -1,
                        offline_share_enabled BOOLEAN DEFAULT 0,
                        offline_share_rate DOUBLE DEFAULT -1
                    );
                    """.formatted(usersTable);
            stmt.execute(sqlUsers);

            String autoInc = isMySQL ? "AUTO_INCREMENT" : "AUTOINCREMENT";
            String sqlBoosters = """
                    CREATE TABLE IF NOT EXISTS %s (
                        id INTEGER PRIMARY KEY %s,
                        uuid VARCHAR(36) NOT NULL,
                        booster_id VARCHAR(64) NOT NULL,
                        multiplier DOUBLE NOT NULL,
                        profession VARCHAR(64),
                        is_permanent BOOLEAN NOT NULL,
                        remaining_time BIGINT NOT NULL,
                        shared_with TEXT
                    );
                    """.formatted(boostersTable, autoInc);
            stmt.execute(sqlBoosters);

            try {
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_booster_uuid ON " + boostersTable + " (uuid);");
            } catch (SQLException ignored) {
                // Index might already exist
            }

        } catch (SQLException e) {
            plugin.getLogger().severe(plugin.getMessagesFile().getString("log.db_create_fail", "Could not create database tables!"));
            e.printStackTrace();
        }
    }

    /**
     * Retrieves boosters shared by offline players intended for a specific receiver.
     */
    public Map<UUID, List<Booster>> getIncomingOfflineShares(UUID receiverId) {
        Map<UUID, List<Booster>> result = new HashMap<>();
        String query = "SELECT b.*, u.offline_share_enabled, u.offline_share_rate FROM " + getBoostersTable() + " b " +
                "JOIN " + getUsersTable() + " u ON b.uuid = u.uuid " +
                "WHERE b.shared_with LIKE ? AND u.offline_share_enabled = 1";

        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, "%" + receiverId.toString() + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID ownerId = UUID.fromString(rs.getString("uuid"));

                    String sharedRaw = rs.getString("shared_with");
                    if (!Arrays.asList(sharedRaw.split(",")).contains(receiverId.toString())) continue;

                    double offlineRate = rs.getDouble("offline_share_rate");
                    Booster b = new Booster(
                            rs.getString("booster_id"),
                            rs.getDouble("multiplier"),
                            rs.getBoolean("is_permanent") ? -1 : System.currentTimeMillis() + rs.getLong("remaining_time"),
                            rs.getString("profession"),
                            rs.getBoolean("is_permanent"),
                            true, ownerId, offlineRate
                    );

                    result.computeIfAbsent(ownerId, k -> new ArrayList<>()).add(b);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return result;
    }

    public boolean isMySQL() {
        return isMySQL;
    }

    public String getUsersTable() {
        return plugin.getConfigFile().getString("database.table_users", "sincebooster_users");
    }

    public String getBoostersTable() {
        return plugin.getConfigFile().getString("database.table_boosters", "sincebooster_boosters");
    }
}