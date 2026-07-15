package com.reactor.rust.cache.internal.nativebridge;

import com.reactor.rust.cache.core.RedisCacheException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeCacheProvenanceTest {

    @Test
    void verifiesPackagedBinaryHashAndRedisAbi() {
        byte[] binary = {1, 2, 3, 4};
        String hash = NativeCacheProvenance.sha256(binary);
        ClassLoader loader = manifestLoader("""
                schema=2
                redis.abi=5
                windows-x64.sha256=%s
                """.formatted(hash));

        assertEquals(hash, NativeCacheProvenance.verifyPackagedBinary(loader, "windows-x64", binary, 5));
    }

    @Test
    void rejectsStalePackagedAbiBeforeSystemLoad() {
        byte[] binary = {1, 2, 3, 4};
        ClassLoader loader = manifestLoader("""
                schema=2
                redis.abi=4
                windows-x64.sha256=%s
                """.formatted(NativeCacheProvenance.sha256(binary)));

        assertThrows(
                RedisCacheException.class,
                () -> NativeCacheProvenance.verifyPackagedBinary(loader, "windows-x64", binary, 5)
        );
    }

    private static ClassLoader manifestLoader(String manifest) {
        byte[] bytes = manifest.getBytes(StandardCharsets.ISO_8859_1);
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return "native/native-provenance.properties".equals(name)
                        ? new ByteArrayInputStream(bytes)
                        : null;
            }
        };
    }
}
