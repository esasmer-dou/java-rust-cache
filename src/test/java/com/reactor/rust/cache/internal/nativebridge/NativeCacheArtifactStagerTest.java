package com.reactor.rust.cache.internal.nativebridge;

import com.reactor.rust.cache.core.RedisCacheException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NativeCacheArtifactStagerTest {

    private static final byte[] BINARY = {1, 2, 3, 4, 5};
    private static final String FILE_NAME = "library.bin";
    @TempDir Path directory;

    @Test
    void streamsWithoutWholeResourceReadsAndPublishesUnderSuppliedRoot() throws Exception {
        URL resource = resource(BINARY);
        Path root = directory.resolve("home/.java-rust-cache/native");
        byte[] binary = new byte[48 * 1024 + 3];
        Arrays.fill(binary, (byte) 7);
        writeManifest(NativeCacheProvenance.sha256(binary));
        try (InputStream input = boundedStream(binary)) {
            Path library = NativeCacheArtifactStager.stage(resource, "linux-x64", input, root, FILE_NAME, 6);
            assertEquals(root.resolve(NativeCacheProvenance.sha256(binary).substring(0, 16)).resolve(FILE_NAME), library);
            assertArrayEquals(binary, Files.readAllBytes(library));
            assertNoStagingFiles(root);
        }
    }

    @Test
    void reusesVerifiedExistingCacheWithoutReplacingIt() throws Exception {
        URL resource = resource(BINARY);
        Path root = directory.resolve("cache");
        Path first = stage(resource, BINARY, root);
        Files.setLastModifiedTime(first, FileTime.fromMillis(1_000));
        FileTime original = Files.getLastModifiedTime(first);
        assertEquals(first, stage(resource, BINARY, root));
        assertEquals(original, Files.getLastModifiedTime(first));
        assertNoStagingFiles(root);
    }

    @Test
    void replacesCorruptExistingCacheOnlyWithVerifiedContent() throws Exception {
        URL resource = resource(BINARY);
        Path root = directory.resolve("cache");
        Path first = stage(resource, BINARY, root);
        Files.write(first, new byte[]{5, 4, 3, 2, 1});
        assertEquals(first, stage(resource, BINARY, root));
        assertArrayEquals(BINARY, Files.readAllBytes(first));
        assertNoStagingFiles(root);
    }

    @Test
    void rejectsHashMismatchEvenWhenValidCachedLibraryAlreadyExists() throws Exception {
        URL resource = resource(BINARY);
        Path root = directory.resolve("cache");
        Path cached = stage(resource, BINARY, root);
        assertThrows(RedisCacheException.class, () -> stage(resource, new byte[]{9, 9, 9}, root));
        assertArrayEquals(BINARY, Files.readAllBytes(cached));
        assertNoStagingFiles(root);
    }

    @Test
    void rejectsTruncatedResourceAndRemovesStagingFile() throws Exception {
        URL resource = resource(BINARY);
        Path root = directory.resolve("cache");
        assertThrows(RedisCacheException.class, () -> stage(resource, new byte[]{1, 2, 3}, root));
        assertEmpty(root);
    }

    @Test
    void rejectsEmptyResourceAndRemovesStagingFile() throws Exception {
        URL resource = resource(BINARY);
        Path root = directory.resolve("cache");
        assertThrows(IOException.class, () -> stage(resource, new byte[0], root));
        assertEmpty(root);
    }

    @Test
    void cleansUpAfterReadFailure() throws Exception {
        URL resource = resource(BINARY);
        Path root = directory.resolve("cache");
        IOException failure = new IOException("fixture read failure");
        try (InputStream input = new InputStream() {
            private boolean started;
            @Override public int read() throws IOException { throw failure; }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (started) throw failure;
                started = true;
                bytes[offset] = 1;
                return 1;
            }
        }) {
            assertSame(failure, assertThrows(IOException.class,
                    () -> NativeCacheArtifactStager.stage(resource, "linux-x64", input, root, FILE_NAME, 6)));
        }
        assertEmpty(root);
    }

    @Test
    void refusesMoreThan64MiBWithoutWritingBeyondTheBudget() throws Exception {
        URL resource = resource(BINARY);
        Path root = directory.resolve("cache");
        long[] consumed = {0};
        try (InputStream input = new InputStream() {
            @Override public int read() { fail("bulk reads expected"); return -1; }
            @Override public byte[] readAllBytes() { throw new AssertionError("unbounded read"); }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                assertTrue(length <= 16 * 1024);
                try (var files = Files.list(root)) {
                    Path staged = files.findFirst().orElseThrow();
                    assertTrue(Files.size(staged) <= NativeCacheArtifactStager.MAX_RESOURCE_BYTES);
                }
                Arrays.fill(bytes, offset, offset + length, (byte) 0);
                consumed[0] += length;
                return length;
            }
        }) {
            IOException failure = assertThrows(IOException.class,
                    () -> NativeCacheArtifactStager.stage(resource, "linux-x64", input, root, FILE_NAME, 6));
            assertTrue(failure.getMessage().contains("exceeds 67108864"));
        }
        assertEquals(64L * 1024 * 1024 + 1, consumed[0]);
        assertEmpty(root);
    }

    @Test
    void handlesAZeroLengthReadWithoutSpinning() throws Exception {
        URL resource = resource(BINARY);
        Path root = directory.resolve("cache");
        try (InputStream input = new ByteArrayInputStream(BINARY) {
            private boolean first = true;
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                if (first) { first = false; return 0; }
                return super.read(bytes, offset, length);
            }
            @Override public byte[] readAllBytes() { throw new AssertionError("unbounded read"); }
        }) {
            Path library = NativeCacheArtifactStager.stage(resource, "linux-x64", input, root, FILE_NAME, 6);
            assertArrayEquals(BINARY, Files.readAllBytes(library));
        }
        assertNoStagingFiles(root);
    }

    @Test
    void concurrentPublishersReturnTheSameVerifiedCacheFile() throws Exception {
        URL resource = resource(BINARY);
        Path root = directory.resolve("cache");
        CountDownLatch copying = new CountDownLatch(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Path> publish = () -> {
                try (InputStream input = new ByteArrayInputStream(BINARY) {
                    @Override public synchronized int read(byte[] bytes, int offset, int length) {
                        copying.countDown();
                        try {
                            if (!copying.await(10, TimeUnit.SECONDS)) throw new AssertionError("publisher did not start");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                        return super.read(bytes, offset, length);
                    }
                    @Override public byte[] readAllBytes() { throw new AssertionError("unbounded read"); }
                }) {
                    return NativeCacheArtifactStager.stage(resource, "linux-x64", input, root, FILE_NAME, 6);
                }
            };
            var first = executor.submit(publish);
            var second = executor.submit(publish);
            Path library = first.get(15, TimeUnit.SECONDS);
            assertEquals(library, second.get(15, TimeUnit.SECONDS));
            assertArrayEquals(BINARY, Files.readAllBytes(library));
        }
        assertNoStagingFiles(root);
    }

    @Test
    void cleansUpIfCacheDestinationCannotBeCreated() throws Exception {
        URL resource = resource(BINARY);
        Path root = Files.createDirectories(directory.resolve("cache"));
        Files.writeString(root.resolve(NativeCacheProvenance.sha256(BINARY).substring(0, 16)), "not a directory");
        assertThrows(IOException.class, () -> stage(resource, BINARY, root));
        assertNoStagingFiles(root);
    }

    private URL resource(byte[] binary) throws Exception {
        Path folder = Files.createDirectories(directory.resolve("native/linux-x64"));
        Path resource = Files.write(folder.resolve(FILE_NAME), binary);
        writeManifest(NativeCacheProvenance.sha256(binary));
        return resource.toUri().toURL();
    }

    private void writeManifest(String hash) throws IOException {
        Files.writeString(directory.resolve("native/native-provenance.properties"),
                "schema=2\nredis.abi=6\nsource.revision=fixture\nlinux-x64.sha256=" + hash + "\n");
    }

    private static Path stage(URL resource, byte[] binary, Path root) throws IOException {
        try (InputStream input = boundedStream(binary)) {
            return NativeCacheArtifactStager.stage(resource, "linux-x64", input, root, FILE_NAME, 6);
        }
    }

    private static InputStream boundedStream(byte[] binary) {
        return new ByteArrayInputStream(binary) {
            @Override public byte[] readAllBytes() { throw new AssertionError("unbounded read"); }
            @Override public byte[] readNBytes(int length) { throw new AssertionError("whole resource allocation"); }
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                assertTrue(length <= 16 * 1024, "read buffer exceeds 16 KiB");
                return super.read(bytes, offset, length);
            }
        };
    }

    private static void assertNoStagingFiles(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    private static void assertEmpty(Path root) throws IOException {
        try (var paths = Files.list(root)) {
            assertEquals(0, paths.count());
        }
    }
}
