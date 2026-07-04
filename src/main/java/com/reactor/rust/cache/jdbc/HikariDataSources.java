package com.reactor.rust.cache.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.util.Locale;
import java.util.Properties;

public final class HikariDataSources {

    private HikariDataSources() {}

    public static HikariDataSource create(Properties properties, String prefix) {
        return new HikariDataSource(createConfig(properties, prefix));
    }

    static HikariConfig createConfig(Properties properties, String prefix) {
        String root = requirePrefix(prefix);
        HikariConfig config = new HikariConfig();
        config.setPoolName(get(properties, root + ".pool-name", root + "-pool"));
        config.setDriverClassName(get(properties, root + ".driver-class-name"));
        config.setJdbcUrl(get(properties, root + ".jdbc-url"));
        config.setUsername(get(properties, root + ".username"));
        config.setPassword(getOptional(properties, root + ".password"));
        config.setMaximumPoolSize(getInt(properties, root + ".maximum-pool-size", 2));
        config.setMinimumIdle(getInt(properties, root + ".minimum-idle", 0));
        config.setConnectionTimeout(getLong(properties, root + ".connection-timeout-ms", 3_000L));
        config.setValidationTimeout(getLong(properties, root + ".validation-timeout-ms", 1_000L));
        config.setIdleTimeout(getLong(properties, root + ".idle-timeout-ms", 30_000L));
        config.setMaxLifetime(getLong(properties, root + ".max-lifetime-ms", 300_000L));
        config.setLeakDetectionThreshold(getLong(properties, root + ".leak-detection-threshold-ms", 0L));
        config.setInitializationFailTimeout(getLong(properties, root + ".initialization-fail-timeout-ms", -1L));
        config.setAutoCommit(getBoolean(properties, root + ".auto-commit", true));
        config.setReadOnly(getBoolean(properties, root + ".read-only", false));
        config.setRegisterMbeans(getBoolean(properties, root + ".register-mbeans", false));

        String applicationName = getOptional(properties, root + ".postgresql.application-name");
        if (!applicationName.isBlank()) {
            config.addDataSourceProperty("ApplicationName", applicationName);
        }
        return config;
    }

    private static String get(Properties properties, String key) {
        String value = getOptional(properties, key);
        if (value.isBlank()) {
            throw new IllegalStateException("Missing required JDBC property: " + key);
        }
        return value;
    }

    private static String get(Properties properties, String key, String fallback) {
        String value = getOptional(properties, key);
        return value.isBlank() ? fallback : value;
    }

    private static String getOptional(Properties properties, String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(toEnvKey(key));
        }
        if ((value == null || value.isBlank()) && properties != null) {
            value = properties.getProperty(key);
        }
        return value == null ? "" : value.trim();
    }

    private static int getInt(Properties properties, String key, int fallback) {
        String value = getOptional(properties, key);
        if (value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("JDBC property must be an integer: " + key + "=" + value, e);
        }
    }

    private static long getLong(Properties properties, String key, long fallback) {
        String value = getOptional(properties, key);
        if (value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("JDBC property must be a long: " + key + "=" + value, e);
        }
    }

    private static boolean getBoolean(Properties properties, String key, boolean fallback) {
        String value = getOptional(properties, key);
        if (value.isBlank()) {
            return fallback;
        }
        if ("true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value) || "no".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException("JDBC property must be a boolean: " + key + "=" + value);
    }

    private static String requirePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must not be blank");
        }
        return prefix.trim();
    }

    private static String toEnvKey(String key) {
        return key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }
}
