package com.reactor.rust.cache.jdbc;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HikariDataSourcesTest {

    @Test
    void createsLowRssConfigFromMinimumRequiredJdbcProperties() {
        Properties properties = new Properties();
        properties.setProperty("sample.db.driver-class-name", DummyDriver.class.getName());
        properties.setProperty("sample.db.jdbc-url", "jdbc:postgresql://localhost:5432/app");
        properties.setProperty("sample.db.username", "app");

        HikariConfig config = HikariDataSources.createConfig(properties, "sample.db");

        assertEquals("sample.db-pool", config.getPoolName());
        assertEquals(2, config.getMaximumPoolSize());
        assertEquals(0, config.getMinimumIdle());
        assertEquals(3_000L, config.getConnectionTimeout());
        assertEquals(1_000L, config.getValidationTimeout());
        assertEquals(30_000L, config.getIdleTimeout());
        assertEquals(300_000L, config.getMaxLifetime());
        assertEquals(-1L, config.getInitializationFailTimeout());
        assertTrue(config.isAutoCommit());
        assertFalse(config.isReadOnly());
        assertFalse(config.isRegisterMbeans());
    }

    @Test
    void explicitPoolSettingsOverrideLowRssDefaults() {
        Properties properties = new Properties();
        properties.setProperty("sample.db.driver-class-name", DummyDriver.class.getName());
        properties.setProperty("sample.db.jdbc-url", "jdbc:postgresql://localhost:5432/app");
        properties.setProperty("sample.db.username", "app");
        properties.setProperty("sample.db.maximum-pool-size", "5");
        properties.setProperty("sample.db.minimum-idle", "1");
        properties.setProperty("sample.db.read-only", "true");

        HikariConfig config = HikariDataSources.createConfig(properties, "sample.db");

        assertEquals(5, config.getMaximumPoolSize());
        assertEquals(1, config.getMinimumIdle());
        assertTrue(config.isReadOnly());
    }

    public static final class DummyDriver implements Driver {
        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            throw new SQLException("not used");
        }

        @Override
        public boolean acceptsURL(String url) {
            return false;
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }
    }
}
