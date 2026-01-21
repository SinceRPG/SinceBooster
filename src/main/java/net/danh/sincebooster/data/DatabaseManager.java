package net.danh.sincebooster.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.danh.sincebooster.SinceBooster;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {

    private final SinceBooster plugin;
    private HikariDataSource dataSource;

    public DatabaseManager(SinceBooster plugin) {
        this.plugin = plugin;
        setupDataSource();
        createTables();
    }

    private void setupDataSource() {
        String type = plugin.getConfigFile().getString("database.type", "SQLITE");
        HikariConfig config = new HikariConfig();

        if (type.equalsIgnoreCase("MYSQL")) {
            String host = plugin.getConfigFile().getString("database.host");
            String port = plugin.getConfigFile().getString("database.port");
            String db = plugin.getConfigFile().getString("database.database");
            String user = plugin.getConfigFile().getString("database.username");
            String pass = plugin.getConfigFile().getString("database.password");

            config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + db + "?useSSL=false&autoReconnect=true&characterEncoding=UTF-8");
            config.setUsername(user);
            config.setPassword(pass);
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            config.addDataSourceProperty("useServerPrepStmts", "true");
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

    private void createTables() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            String usersTable = getUsersTable();
            String boostersTable = getBoostersTable();

            // Bảng User: Lưu thông tin Pet và các Rate tùy chỉnh
            String sqlUsers = "CREATE TABLE IF NOT EXISTS " + usersTable + " (" +
                    "uuid VARCHAR(36) PRIMARY KEY, " +
                    "share_rate DOUBLE DEFAULT -1, " +
                    "owner_buff_rate DOUBLE DEFAULT -1, " +
                    "receiver_buff_rate DOUBLE DEFAULT -1, " +
                    "share_limit INT DEFAULT -1" + // [NEW]
                    ");";
            stmt.execute(sqlUsers);

            // Bảng Booster: Lưu thông tin booster và danh sách người được share
            String autoInc = isMySQL() ? "AUTO_INCREMENT" : "AUTOINCREMENT";
            String sqlBoosters = "CREATE TABLE IF NOT EXISTS " + boostersTable + " (" +
                    "id INTEGER PRIMARY KEY " + autoInc + ", " +
                    "uuid VARCHAR(36) NOT NULL, " +
                    "booster_id VARCHAR(64) NOT NULL, " +
                    "multiplier DOUBLE NOT NULL, " +
                    "profession VARCHAR(64), " +
                    "is_permanent BOOLEAN NOT NULL, " +
                    "remaining_time BIGINT NOT NULL, " +
                    "shared_with TEXT" + // Danh sách UUID được share (cách nhau bởi dấu phẩy)
                    ");";
            stmt.execute(sqlBoosters);

            // Index để load nhanh hơn
            try {
                stmt.execute("CREATE INDEX idx_booster_uuid ON " + boostersTable + " (uuid);");
            } catch (SQLException ignored) {
            }

        } catch (SQLException e) {
            plugin.getLogger().severe("Could not create database tables!");
            e.printStackTrace();
        }
    }

    public boolean isMySQL() {
        return plugin.getConfigFile().getString("database.type", "SQLITE").equalsIgnoreCase("MYSQL");
    }

    public String getUsersTable() {
        return plugin.getConfigFile().getString("database.table_users", "sincebooster_users");
    }

    public String getBoostersTable() {
        return plugin.getConfigFile().getString("database.table_boosters", "sincebooster_boosters");
    }
}