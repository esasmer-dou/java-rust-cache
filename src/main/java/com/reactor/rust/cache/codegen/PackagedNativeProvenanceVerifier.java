package com.reactor.rust.cache.codegen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Properties;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** JDK-only build/release gate; also runnable directly with the Java source-file launcher. */
public final class PackagedNativeProvenanceVerifier {

    private static final String ROOT_MANIFEST = "native/native-provenance.properties";

    private PackagedNativeProvenanceVerifier() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected: <runtime-jar> <contract-properties>");
        }
        int count = verify(Path.of(args[0]), Path.of(args[1]));
        System.out.println("PACKAGED_NATIVE_PROVENANCE_OK binaries=" + count + " jar=" + args[0]);
    }

    public static int verify(Path runtimeJar, Path contractFile) throws IOException {
        Properties contract;
        try (InputStream input = Files.newInputStream(contractFile)) {
            contract = readProperties(input);
        }
        requireEquals(contract, "schema", "2", contractFile.toString());
        TreeMap<String, String> binaries = new TreeMap<>();
        for (String key : contract.stringPropertyNames()) {
            if (key.startsWith("binary.")) {
                String platform = key.substring("binary.".length());
                String filename = required(contract, key, contractFile.toString());
                if (!platform.matches("[a-z0-9]+(?:-[a-z0-9]+)*")
                        || !filename.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
                    throw new IllegalArgumentException("Invalid native resource mapping: " + key);
                }
                binaries.put(platform, "native/" + platform + "/" + filename);
            }
        }
        if (binaries.isEmpty() || contract.stringPropertyNames().stream().noneMatch(k -> k.endsWith(".abi"))) {
            throw new IllegalArgumentException("Contract must declare native binaries and required ABIs");
        }
        try (JarFile jar = new JarFile(runtimeJar.toFile())) {
            HashSet<String> seen = new HashSet<>();
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("native/") && !seen.add(name)) {
                    throw new IllegalStateException("Duplicate native JAR entry: " + name);
                }
            }
            for (var binary : binaries.entrySet()) {
                String platform = binary.getKey();
                JarEntry nativeEntry = requiredEntry(jar, binary.getValue());
                String selected = "native/" + platform + "/native-provenance.properties";
                JarEntry manifestEntry = jar.getJarEntry(selected);
                // Only absence permits fallback, never a malformed or incorrect platform manifest.
                if (manifestEntry == null) {
                    selected = ROOT_MANIFEST;
                    manifestEntry = requiredEntry(jar, selected);
                }
                Properties manifest;
                try (InputStream input = jar.getInputStream(manifestEntry)) {
                    manifest = readProperties(input);
                }
                requireEquals(manifest, "schema", "2", selected);
                for (String key : contract.stringPropertyNames()) {
                    if (key.endsWith(".abi") || key.equals(platform + ".glibc-minimum")) {
                        requireEquals(manifest, key, required(contract, key, contractFile.toString()), selected);
                    }
                }
                String expected = required(manifest, platform + ".sha256", selected);
                if (!expected.matches("(?i)[0-9a-f]{64}")) {
                    throw new IllegalStateException("Invalid SHA-256 for " + platform + " in " + selected);
                }
                String actual;
                try (InputStream input = jar.getInputStream(nativeEntry)) {
                    actual = sha256(input);
                }
                if (!actual.equalsIgnoreCase(expected)) {
                    throw new IllegalStateException("Packaged native binary hash mismatch for " + platform
                            + " using " + selected + ": expected " + expected + " but found " + actual);
                }
            }
        }
        return binaries.size();
    }

    private static JarEntry requiredEntry(JarFile jar, String name) {
        JarEntry entry = jar.getJarEntry(name);
        if (entry == null || entry.isDirectory()) {
            throw new IllegalStateException("Missing native JAR entry: " + name);
        }
        return entry;
    }

    private static Properties readProperties(InputStream input) throws IOException {
        Properties properties = new Properties();
        properties.load(input);
        return properties;
    }

    private static void requireEquals(Properties properties, String key, String expected, String resource) {
        String actual = required(properties, key, resource);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(resource + ": " + key + " must be " + expected + " but was " + actual);
        }
    }

    private static String required(Properties properties, String key, String resource) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(resource + ": missing provenance field " + key);
        }
        return value.trim();
    }

    private static String sha256(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
