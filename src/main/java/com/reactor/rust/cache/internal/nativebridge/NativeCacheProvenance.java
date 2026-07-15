package com.reactor.rust.cache.internal.nativebridge;

import com.reactor.rust.cache.core.RedisCacheException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;

final class NativeCacheProvenance {

    private static final String MANIFEST_RESOURCE = "native/native-provenance.properties";

    private NativeCacheProvenance() {}

    static String verifyPackagedBinary(
            ClassLoader classLoader,
            String platform,
            byte[] binary,
            int expectedRedisAbi) {
        Properties manifest = loadManifest(classLoader);
        requireEquals(manifest, "schema", "2");
        requireEquals(manifest, "redis.abi", Integer.toString(expectedRedisAbi));
        String expectedHash = required(manifest, platform + ".sha256");
        String actualHash = sha256(binary);
        if (!actualHash.equalsIgnoreCase(expectedHash)) {
            throw new RedisCacheException(
                    "Packaged Redis native binary hash mismatch for " + platform
                            + ": expected " + expectedHash + " but found " + actualHash
            );
        }
        return actualHash;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new RedisCacheException("SHA-256 digest is not available", error);
        }
    }

    static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new RedisCacheException("SHA-256 digest is not available", error);
        }
    }

    private static Properties loadManifest(ClassLoader classLoader) {
        try (InputStream input = classLoader.getResourceAsStream(MANIFEST_RESOURCE)) {
            if (input == null) {
                throw new RedisCacheException(
                        "Packaged native provenance manifest is missing: " + MANIFEST_RESOURCE
                );
            }
            Properties manifest = new Properties();
            manifest.load(input);
            return manifest;
        } catch (IOException error) {
            throw new RedisCacheException("Cannot read packaged native provenance manifest", error);
        }
    }

    private static void requireEquals(Properties properties, String key, String expected) {
        String actual = required(properties, key);
        if (!expected.equals(actual)) {
            throw new RedisCacheException(
                    "Native provenance field " + key + " must be " + expected + " but was " + actual
            );
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new RedisCacheException("Native provenance field is missing: " + key);
        }
        return value.trim();
    }
}
