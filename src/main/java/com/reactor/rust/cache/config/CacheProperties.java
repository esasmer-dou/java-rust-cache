package com.reactor.rust.cache.config;

import com.reactor.rust.cache.projection.ProjectionPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/**
 * Layered configuration shared by cache reader and writer applications.
 */
public final class CacheProperties implements ProjectionPropertySource {

    private static final System.Logger LOG = System.getLogger(CacheProperties.class.getName());
    private final Properties values;

    private CacheProperties(Properties values) {
        this.values = copy(values);
    }

    public static CacheProperties load(String classpathResource) {
        String resource = requireText(classpathResource, "classpathResource");
        Properties values = new Properties();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = CacheProperties.class.getClassLoader();
        }
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing classpath resource: " + resource);
            }
            values.load(input);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load classpath resource: " + resource, e);
        }
        loadOverlays(values);
        return new CacheProperties(values);
    }

    public static CacheProperties from(Properties properties) {
        return new CacheProperties(Objects.requireNonNull(properties, "properties"));
    }

    public Properties asProperties() {
        return copy(values);
    }

    @Override
    public String get(String key) {
        String value = find(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required property: " + key);
        }
        return value.trim();
    }

    public String get(String key, String defaultValue) {
        String value = find(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    @Override
    public String getOptional(String key) {
        String value = find(key);
        return value == null ? "" : value.trim();
    }

    @Override
    public String getRuntimeOverride(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(envKey(key));
        }
        return value == null ? null : value.trim();
    }

    @Override
    public String getFileOptional(String key) {
        String value = values.getProperty(key);
        return value == null ? "" : value.trim();
    }

    public int getInt(String key) {
        return parseInt(key, get(key));
    }

    public int getInt(String key, int defaultValue) {
        String value = find(key);
        return value == null || value.isBlank() ? defaultValue : parseInt(key, value.trim());
    }

    public long getLong(String key) {
        String value = get(key);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Property must be a long: " + key + "=" + value, e);
        }
    }

    public boolean getBoolean(String key) {
        String value = get(key);
        if ("true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value) || "no".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException("Property must be a boolean: " + key + "=" + value);
    }

    private String find(String key) {
        String runtime = getRuntimeOverride(key);
        return runtime == null || runtime.isBlank() ? values.getProperty(key) : runtime;
    }

    private static void loadOverlays(Properties values) {
        String configured = System.getProperty("reactor.config.file");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("REACTOR_CONFIG_FILE");
        }
        if (configured == null || configured.isBlank()) {
            return;
        }
        for (String rawPath : configured.split("[,;]")) {
            if (rawPath.isBlank()) {
                continue;
            }
            Path path = Path.of(rawPath.trim()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException("Configured reactor.config.file does not exist: " + path);
            }
            try (InputStream input = Files.newInputStream(path)) {
                values.load(input);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load reactor.config.file: " + path, e);
            }
            LOG.log(System.Logger.Level.INFO, "Properties overlay loaded from {0}", path);
        }
    }

    private static int parseInt(String key, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Property must be an integer: " + key + "=" + value, e);
        }
    }

    private static String envKey(String key) {
        return key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    private static Properties copy(Properties source) {
        Properties copy = new Properties();
        copy.putAll(source);
        return copy;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
