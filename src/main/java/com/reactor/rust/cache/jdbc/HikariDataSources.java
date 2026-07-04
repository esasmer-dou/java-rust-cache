package com.reactor.rust.cache.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.util.Locale;
import java.util.Properties;

public final class HikariDataSources {

    private HikariDataSources() {}

    public static HikariDataSource create(Properties properties, String prefix) {
        String root = requirePrefix(prefix);
        HikariConfig config = new HikariConfig();
        config.setPoolName(get(properties, root + ".pool-name"));
        config.setDriverClassName(get(properties, root + ".driver-class-name"));
        config.setJdbcUrl(get(properties, root + ".jdbc-url"));
        config.setUsername(get(properties, root + ".username"));
        config.setPassword(getOptional(properties, root + ".password"));
        config.setMaximumPoolSize(getInt(properties, root + ".maximum-pool-size"));
        config.setMinimumIdle(getInt(properties, root + ".minimum-idle"));
        config.setConnectionTimeout(getLong(properties, root + ".connection-timeout-ms"));
        config.setValidationTimeout(getLong(properties, root + ".validation-timeout-ms"));
        config.setIdleTimeout(getLong(properties, root + ".idle-timeout-ms"));
        config.setMaxLifetime(getLong(properties, root + ".max-lifetime-ms"));
        config.setLeakDetectionThreshold(getLong(properties, root + ".leak-detection-threshold-ms"));
        config.setInitializationFailTimeout(getLong(properties, root + ".initialization-fail-timeout-ms"));
        config.setAutoCommit(getBoolean(properties, root + ".auto-commit"));
        config.setReadOnly(getBoolean(properties, root + ".read-only"));
        config.setRegisterMbeans(getBoolean(properties, root + ".register-mbeans"));

        String applicationName = getOptional(properties, root + ".postgresql.application-name");
        if (!applicationName.isBlank()) {
            config.addDataSourceProperty("ApplicationName", applicationName);
        }
        return new HikariDataSource(config);
    }

    private static String get(Properties properties, String key) {
        String value = getOptional(properties, key);
        if (value.isBlank()) {
            throw new IllegalStateException("Missing required JDBC property: " + key);
        }
        return value;
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

    private static int getInt(Properties properties, String key) {
        String value = get(properties, key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("JDBC property must be an integer: " + key + "=" + value, e);
        }
    }

    private static long getLong(Properties properties, String key) {
        String value = get(properties, key);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("JDBC property must be a long: " + key + "=" + value, e);
        }
    }

    private static boolean getBoolean(Properties properties, String key) {
        String value = get(properties, key);
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
