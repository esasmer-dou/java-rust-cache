package com.reactor.rust.cache.codegen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackagedNativeProvenanceVerifierTest {

    private static final String ROOT = "native/native-provenance.properties";
    private static final Map<String, String> BINARIES = Map.of(
            "windows-x64", "rust_hyper-windows-x64.dll",
            "linux-x64", "librust_hyper-linux-x64.so",
            "linux-arm64", "librust_hyper-linux-arm64.so",
            "macos-arm64", "librust_hyper-macos-arm64.dylib");

    @TempDir
    Path temporary;

    @Test
    void acceptsRootFallbackForAllFourCacheAliases() throws IOException {
        assertEquals(4, verify(entries(false)));
    }

    @Test
    void acceptsSelectedSidecarsDespiteUnrelatedRootHashes() throws IOException {
        Map<String, byte[]> entries = entries(false);
        Properties root = read(entries.get(ROOT));
        for (String platform : new String[]{"linux-arm64", "macos-arm64"}) {
            entries.put(sidecar(platform), manifest(platform));
            root.setProperty(platform + ".sha256", "0".repeat(64));
        }
        entries.put(ROOT, bytes(root));
        assertEquals(4, verify(entries));
    }

    @Test
    void acceptsAllSelectedSidecarsWithoutRootManifest() throws IOException {
        Map<String, byte[]> entries = entries(true);
        entries.remove(ROOT);
        assertEquals(4, verify(entries));
    }

    @ParameterizedTest
    @ValueSource(strings = {"linux-arm64", "macos-arm64"})
    void rejectsStaleArmSidecarEvenWhenRootMatchesBinary(String platform) throws IOException {
        Map<String, byte[]> entries = entries(false);
        Properties stale = read(manifest(platform));
        stale.setProperty(platform + ".sha256", "0".repeat(64));
        entries.put(sidecar(platform), bytes(stale));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> verify(entries));
        assertTrue(failure.getMessage().contains("hash mismatch for " + platform));
        assertTrue(failure.getMessage().contains(sidecar(platform)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"windows-x64", "linux-x64", "linux-arm64", "macos-arm64"})
    void rejectsTamperedBinaryOnEveryPlatform(String platform) throws IOException {
        Map<String, byte[]> entries = entries(true);
        entries.put(binary(platform), "tampered".getBytes(StandardCharsets.US_ASCII));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> verify(entries));
        assertTrue(failure.getMessage().contains("hash mismatch for " + platform));
    }

    @ParameterizedTest
    @ValueSource(strings = {"windows-x64", "linux-x64", "linux-arm64", "macos-arm64"})
    void rejectsMissingBinaryOnEveryPlatform(String platform) throws IOException {
        Map<String, byte[]> entries = entries(false);
        entries.remove(binary(platform));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> verify(entries));
        assertTrue(failure.getMessage().contains(binary(platform)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"schema", "rest.abi", "dubbo.abi", "redis.abi", "glowroot.abi"})
    void rejectsSelectedSidecarContractMismatchWithoutFallback(String key) throws IOException {
        Map<String, byte[]> entries = entries(false);
        Properties sidecar = read(manifest("linux-arm64"));
        sidecar.setProperty(key, "0");
        entries.put(sidecar("linux-arm64"), bytes(sidecar));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> verify(entries));
        assertTrue(failure.getMessage().contains(key + " must be"));
    }

    @Test
    void rejectsMissingSelectedHashWithoutFallback() throws IOException {
        Map<String, byte[]> entries = entries(false);
        Properties selected = read(manifest("macos-arm64"));
        selected.remove("macos-arm64.sha256");
        entries.put(sidecar("macos-arm64"), bytes(selected));
        assertThrows(IllegalStateException.class, () -> verify(entries));
    }

    @Test
    void rejectsMalformedSelectedSidecarWithoutFallback() throws IOException {
        Map<String, byte[]> entries = entries(false);
        entries.put(sidecar("macos-arm64"), "schema=2\nredis.abi=\\uZZZZ\n".getBytes(StandardCharsets.US_ASCII));
        assertThrows(IllegalArgumentException.class, () -> verify(entries));
    }

    @Test
    void rejectsMissingFallbackManifest() throws IOException {
        Map<String, byte[]> entries = entries(false);
        entries.remove(ROOT);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> verify(entries));
        assertTrue(failure.getMessage().contains(ROOT));
    }

    @Test
    void rejectsChangedLinuxGlibcContract() throws IOException {
        Map<String, byte[]> entries = entries(false);
        Properties selected = read(manifest("linux-arm64"));
        selected.setProperty("linux-arm64.glibc-minimum", "2.28");
        entries.put(sidecar("linux-arm64"), bytes(selected));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> verify(entries));
        assertTrue(failure.getMessage().contains("glibc-minimum must be 2.17"));
    }

    @Test
    void rejectsMissingHashInSelectedRoot() throws IOException {
        Map<String, byte[]> entries = entries(false);
        Properties root = read(entries.get(ROOT));
        root.remove("windows-x64.sha256");
        entries.put(ROOT, bytes(root));
        assertThrows(IllegalStateException.class, () -> verify(entries));
    }

    @Test
    void rejectsInvalidConfiguredResourcePath() throws IOException {
        Path contract = contract();
        Files.writeString(contract, Files.readString(contract).replace(
                "binary.linux-arm64=librust_hyper-linux-arm64.so", "binary.linux-arm64=../outside.so"));
        assertThrows(IllegalArgumentException.class, () -> PackagedNativeProvenanceVerifier.verify(
                jar(entries(false)), contract));
    }

    @Test
    void commandLineVerifiesJarAndRejectsMissingArguments() throws IOException {
        assertDoesNotThrow(() -> PackagedNativeProvenanceVerifier.main(
                new String[]{jar(entries(false)).toString(), contract().toString()}));
        assertThrows(IllegalArgumentException.class, () -> PackagedNativeProvenanceVerifier.main(new String[0]));
    }

    private int verify(Map<String, byte[]> entries) throws IOException {
        return PackagedNativeProvenanceVerifier.verify(jar(entries), contract());
    }

    private Path contract() throws IOException {
        StringBuilder content = new StringBuilder("schema=2\nrest.abi=29\ndubbo.abi=7\nredis.abi=6\nglowroot.abi=6\n");
        content.append("linux-x64.glibc-minimum=2.17\nlinux-arm64.glibc-minimum=2.17\n");
        BINARIES.forEach((platform, filename) -> content.append("binary.").append(platform)
                .append('=').append(filename).append('\n'));
        Path contract = temporary.resolve("contract.properties");
        Files.writeString(contract, content);
        return contract;
    }

    private Path jar(Map<String, byte[]> entries) throws IOException {
        Path jar = temporary.resolve("runtime.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return jar;
    }

    private static Map<String, byte[]> entries(boolean allSidecars) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        Properties root = new Properties();
        for (String platform : BINARIES.keySet()) {
            byte[] manifest = manifest(platform);
            root.putAll(read(manifest));
            entries.put(binary(platform), binaryBytes(platform));
            if (allSidecars) entries.put(sidecar(platform), manifest);
        }
        entries.put(ROOT, bytes(root));
        return entries;
    }

    private static byte[] manifest(String platform) throws IOException {
        Properties manifest = new Properties();
        manifest.setProperty("schema", "2");
        manifest.setProperty("rest.abi", "29");
        manifest.setProperty("dubbo.abi", "7");
        manifest.setProperty("redis.abi", "6");
        manifest.setProperty("glowroot.abi", "6");
        manifest.setProperty(platform + ".sha256", sha256(binaryBytes(platform)));
        if (platform.startsWith("linux-")) manifest.setProperty(platform + ".glibc-minimum", "2.17");
        return bytes(manifest);
    }

    private static Properties read(byte[] bytes) throws IOException {
        Properties properties = new Properties();
        properties.load(new java.io.ByteArrayInputStream(bytes));
        return properties;
    }

    private static byte[] bytes(Properties properties) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        properties.store(output, null);
        return output.toByteArray();
    }

    private static String binary(String platform) {
        return "native/" + platform + "/" + BINARIES.get(platform);
    }

    private static String sidecar(String platform) {
        return "native/" + platform + "/native-provenance.properties";
    }

    private static byte[] binaryBytes(String platform) {
        return ("fixture native bytes: " + platform).getBytes(StandardCharsets.US_ASCII);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }
}
