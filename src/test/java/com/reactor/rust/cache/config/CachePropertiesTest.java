package com.reactor.rust.cache.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CachePropertiesTest {

    @AfterEach
    void clearOverrides() {
        System.clearProperty("sample.value");
    }

    @Test
    void runtimeValueOverridesFileValue() {
        Properties values = new Properties();
        values.setProperty("sample.value", "4");
        System.setProperty("sample.value", "8");

        assertEquals(8, CacheProperties.from(values).getInt("sample.value"));
    }

    @Test
    void rejectsInvalidBooleanInsteadOfGuessing() {
        Properties values = new Properties();
        values.setProperty("sample.enabled", "sometimes");

        assertThrows(
                IllegalArgumentException.class,
                () -> CacheProperties.from(values).getBoolean("sample.enabled"));
    }
}
