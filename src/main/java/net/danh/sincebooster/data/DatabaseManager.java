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
    private final boolean isMySQL; // Cache giá trị này
    private HikariDataSource dataSource;

    public DatabaseManager(SinceBooster plugin) {
        this.plugin = plugin;
        // Cache ngay từ đầu để không phải check string mỗi lần gọi
        this.isMySQL = plugin.getConfigFile().getString("database.type", "SQLITE").equalsIgnoreCase("MYSQL");
        setupDataSource();
        createTables();
    }

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
            // Các thuộc tính tối ưu cho MySQL
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            config.addDataSourceProperty("useServerPrepStmts", "true");
            config.addDataSourceProperty("useLocalSessionState", "true");
            config.addDataSourceProperty("rewriteBatchedStatements", "true"); // Quan trọng cho việc save hàng loạt
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

            // Sử dụng Text Block cho dễ đọc
            String sqlUsers = """
                    CREATE TABLE IF NOT EXISTS %s (
                        uuid VARCHAR(36) PRIMARY KEY,
                        share_rate DOUBLE DEFAULT -1,
                        owner_buff_rate DOUBLE DEFAULT -1,
                        receiver_buff_rate DOUBLE DEFAULT -1,
                        share_limit INT DEFAULT -1
                    );
                    """.formatted(usersTable);
            stmt.execute(sqlUsers);

            String sqlBoosters = getString(boostersTable);
            stmt.execute(sqlBoosters);

            try {
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_booster_uuid ON " + boostersTable + " (uuid);");
            } catch (SQLException ignored) {
            }

        } catch (SQLException e) {
            plugin.getLogger().severe("Could not create database tables!");
            e.printStackTrace();
        }
    }

    private String getString(String boostersTable) {
        String autoInc = isMySQL ? "AUTO_INCREMENT" : "AUTOINCREMENT";
        return """
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