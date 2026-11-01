package com.example.coderunlauncher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Opens connections to the Aiven MySQL database.
 *
 * Settings come from environment variables (CODERUN_DB_HOST, CODERUN_DB_PORT, CODERUN_DB_NAME,
 * CODERUN_DB_USER, CODERUN_DB_PASSWORD) or from an untracked db.properties file in the working
 * directory (keys: db.host, db.port, db.name, db.user, db.password). Never hardcode them here.
 * See db.properties.example.
 */
public class DatabaseManager {

    private static final String SETUP_HINT =
            "Database is not set up. Copy db.properties.example to db.properties and fill it in.";

    public static Connection getConnection() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("MySQL JDBC Driver not found in classpath.", e);
        }

        Properties file = loadFile();
        String host = setting("CODERUN_DB_HOST", file, "db.host", null);
        String port = setting("CODERUN_DB_PORT", file, "db.port", "3306");
        String name = setting("CODERUN_DB_NAME", file, "db.name", "defaultdb");
        String user = setting("CODERUN_DB_USER", file, "db.user", null);
        String password = setting("CODERUN_DB_PASSWORD", file, "db.password", null);

        if (host == null || user == null || password == null) {
            throw new SQLException(SETUP_HINT);
        }

        // Aiven requires SSL connections by default
        String url = String.format("jdbc:mysql://%s:%s/%s?sslmode=REQUIRED", host, port, name);
        return DriverManager.getConnection(url, user, password);
    }

    /** Environment variable first, then db.properties, then the default (may be null). */
    private static String setting(String envName, Properties file, String key, String fallback) {
        String value = System.getenv(envName);
        if (value == null || value.isBlank()) value = file.getProperty(key);
        if (value == null || value.isBlank()) return fallback;
        return value.trim();
    }

    /**
     * True when MySQL refused a statement because this login may not do it (for example the limited
     * player account bundled with the installer cannot CREATE or ALTER tables - they already exist).
     */
    public static boolean noPermission(SQLException e) {
        int c = e.getErrorCode();
        String m = String.valueOf(e.getMessage()).toLowerCase();
        return c == 1142 || c == 1044 || c == 1227 || c == 1143 || m.contains("command denied");
    }

    private static Properties loadFile() {
        Properties p = new Properties();
        Path file = AppPaths.config("db.properties");
        if (Files.isReadable(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            } catch (IOException ignored) {
                // treated as "not configured"; getConnection() reports a clear message
            }
        }
        return p;
    }
}
