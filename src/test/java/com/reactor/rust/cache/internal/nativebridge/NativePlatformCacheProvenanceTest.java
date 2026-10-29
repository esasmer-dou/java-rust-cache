package com.reactor.rust.cache.internal.nativebridge;

import com.reactor.rust.cache.core.RedisCacheException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises metadata only, including simulated ARM resources on AMD64 hosts. */
class NativePlatformCacheProvenanceTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"linux-arm64", "macos-arm64"})
    void historicalPlatformManifestTakesPrecedenceOverCurrentGlobal(String platform) throws Exception {
        byte[] binary = {1, 2, 3};
        Path folder = Files.createDirectories(directory.resolve("native/" + platform));
        Path file = Files.write(folder.resolve("library.bin"), binary);
        Files.writeString(folder.getParent().resolve("native-provenance.properties"), "schema=2\nredis.abi=6\n" + platform + ".sha256=wrong\n");
        Files.writeString(folder.resolve("native-provenance.properties"), "schema=2\nredis.abi=6\nsource.revision=historical\n" + platform + ".sha256=" + NativeCacheProvenance.sha256(binary) + "\n");
        assertEquals(NativeCacheProvenance.sha256(binary), NativeCacheProvenance.verifyPackagedBinary(file.toUri().toURL(), platform, binary, 6));
        assertEquals(NativeCacheProvenance.sha256(binary), NativeCacheProvenance.verifyPackagedBinary(file.toUri().toURL(), platform, file, 6));
    }

    @Test void presentCorruptSidecarNeverFallsBackToValidRoot() throws Exception {
        byte[] binary = {1};
        Path folder = Files.createDirectories(directory.resolve("native/linux-arm64"));
        Path file = Files.write(folder.resolve("library.bin"), binary);
        Files.writeString(folder.getParent().resolve("native-provenance.properties"), "schema=2\nredis.abi=6\nlinux-arm64.sha256=" + NativeCacheProvenance.sha256(binary) + "\n");
        Files.writeString(folder.resolve("native-provenance.properties"), "schema=1\n");
        assertThrows(RedisCacheException.class, () -> NativeCacheProvenance.verifyPackagedBinary(file.toUri().toURL(), "linux-arm64", binary, 6));
        assertThrows(RedisCacheException.class, () -> NativeCacheProvenance.verifyPackagedBinary(file.toUri().toURL(), "linux-arm64", file, 6));
    }

    @Test void jarSidecarRemainsAuthoritativeForPathVerification() throws Exception {
        byte[] binary = {1, 2, 3};
        String hash = NativeCacheProvenance.sha256(binary);
        Path staged = Files.write(directory.resolve("staged.bin"), binary);
        for (boolean valid : new boolean[]{true, false}) {
            Path jar = directory.resolve(valid ? "historical.jar" : "invalid.jar");
            try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
                zip.putNextEntry(new java.util.zip.ZipEntry("native/native-provenance.properties"));
                zip.write(("schema=2\nredis.abi=6\nsource.revision=current-dirty\nlinux-arm64.sha256="
                        + (valid ? "wrong" : hash) + "\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                zip.closeEntry();
                zip.putNextEntry(new java.util.zip.ZipEntry("native/linux-arm64/native-provenance.properties"));
                zip.write((valid ? "schema=2\nredis.abi=6\nsource.revision=historical-clean\nlinux-arm64.sha256=" + hash + "\n"
                        : "schema=1\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                zip.closeEntry();
                zip.putNextEntry(new java.util.zip.ZipEntry("native/linux-arm64/library.bin"));
                zip.write(binary);
                zip.closeEntry();
            }
            var resource = java.net.URI.create("jar:" + jar.toUri() + "!/native/linux-arm64/library.bin").toURL();
            if (valid) {
                assertEquals(hash, NativeCacheProvenance.verifyPackagedBinary(resource, "linux-arm64", staged, 6));
            } else {
                assertThrows(RedisCacheException.class,
                        () -> NativeCacheProvenance.verifyPackagedBinary(resource, "linux-arm64", staged, 6));
            }
        }
    }
}
