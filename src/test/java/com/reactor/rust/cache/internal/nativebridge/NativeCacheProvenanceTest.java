package com.reactor.rust.cache.internal.nativebridge;

import com.reactor.rust.cache.core.RedisCacheException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeCacheProvenanceTest {

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;

    @Test void resolvesManifestFromSelectedJarInsteadOfClasspathOrder() throws Exception {
        byte[] binary = {1, 2, 3, 4};
        String hash = NativeCacheProvenance.sha256(binary);
        java.nio.file.Path shadow=directory.resolve("agent.jar"), selected=directory.resolve("cache.jar");
        for (var path : new java.nio.file.Path[]{shadow, selected}) {
            try(var zip=new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(path))) {
                zip.putNextEntry(new java.util.zip.ZipEntry("native/native-provenance.properties"));
                zip.write((path.equals(shadow) ? "schema=1\nglowroot.abi=6\n" :
                        "schema=2\nredis.abi=6\nwindows-x64.sha256="+hash+"\n").getBytes(StandardCharsets.ISO_8859_1));
                zip.closeEntry();
                if(path.equals(selected)) {
                    zip.putNextEntry(new java.util.zip.ZipEntry("native/windows-x64/rust_hyper-windows-x64.dll"));
                    zip.write(binary);zip.closeEntry();
                }
            }
        }
        try(var loader=new java.net.URLClassLoader(new java.net.URL[]{shadow.toUri().toURL(),selected.toUri().toURL()},null)) {
            assertThrows(RedisCacheException.class,()->NativeCacheProvenance.verifyPackagedBinary(loader,"windows-x64",binary,6));
            assertEquals(hash,NativeCacheProvenance.verifyPackagedBinary(
                    loader.getResource("native/windows-x64/rust_hyper-windows-x64.dll"),"windows-x64",binary,6));
            var staged = java.nio.file.Files.write(directory.resolve("staged.bin"), binary);
            assertEquals(hash,NativeCacheProvenance.verifyPackagedBinary(
                    loader.getResource("native/windows-x64/rust_hyper-windows-x64.dll"),"windows-x64",staged,6));
        }
    }

    @Test
    void verifiesPackagedBinaryHashAndRedisAbi() {
        byte[] binary = {1, 2, 3, 4};
        String hash = NativeCacheProvenance.sha256(binary);
        ClassLoader loader = manifestLoader("""
                schema=2
                redis.abi=6
                windows-x64.sha256=%s
                """.formatted(hash));

        assertEquals(hash, NativeCacheProvenance.verifyPackagedBinary(loader, "windows-x64", binary, 6));
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
                () -> NativeCacheProvenance.verifyPackagedBinary(loader, "windows-x64", binary, 6)
        );
    }

    @Test
    void pathVerifierRejectsInvalidSchemaAbiHashAndMissingManifest() throws Exception {
        byte[] binary = {1, 2, 3, 4};
        var folder = java.nio.file.Files.createDirectories(directory.resolve("native/linux-x64"));
        var file = java.nio.file.Files.write(folder.resolve("library.bin"), binary);
        var resource = file.toUri().toURL();
        var manifest = folder.getParent().resolve("native-provenance.properties");
        String hash = NativeCacheProvenance.sha256(binary);
        for (String invalid : new String[]{
                "schema=1\nredis.abi=6\nlinux-x64.sha256=" + hash,
                "schema=2\nredis.abi=5\nlinux-x64.sha256=" + hash,
                "schema=2\nredis.abi=6\nlinux-x64.sha256=wrong",
                "schema=2\nredis.abi=6\n"}) {
            java.nio.file.Files.writeString(manifest, invalid);
            assertThrows(RedisCacheException.class,
                    () -> NativeCacheProvenance.verifyPackagedBinary(resource, "linux-x64", file, 6));
        }
        java.nio.file.Files.delete(manifest);
        assertThrows(RedisCacheException.class,
                () -> NativeCacheProvenance.verifyPackagedBinary(resource, "linux-x64", file, 6));
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
