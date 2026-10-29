package com.reactor.rust.cache.internal.nativebridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class NativeCacheArtifactStager {

    static final long MAX_RESOURCE_BYTES = 64L * 1024 * 1024;
    private static final int BUFFER_BYTES = 16 * 1024;

    private NativeCacheArtifactStager() {}

    static Path stage(URL binaryResource, String platform, InputStream input, Path root,
                      String fileName, int expectedRedisAbi) throws IOException {
        Files.createDirectories(root);
        Path staged = Files.createTempFile(root, fileName, ".tmp");
        Throwable failure = null;
        try {
            long size = copy(input, staged);
            String hash = NativeCacheProvenance.verifyPackagedBinary(binaryResource, platform, staged, expectedRedisAbi);
            Path directory = root.resolve(hash.substring(0, 16));
            Files.createDirectories(directory);
            Path library = directory.resolve(fileName);
            if (matches(library, size, hash)) {
                return library;
            }
            try {
                try {
                    Files.move(staged, library, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(staged, library, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException moveError) {
                // A concurrent publisher may have installed a DLL that Windows now keeps mapped.
                try {
                    if (matches(library, size, hash)) {
                        return library;
                    }
                } catch (IOException verificationError) {
                    moveError.addSuppressed(verificationError);
                }
                throw moveError;
            }
            return library;
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            try {
                Files.deleteIfExists(staged);
            } catch (IOException cleanupError) {
                if (failure == null) {
                    throw cleanupError;
                }
                failure.addSuppressed(cleanupError);
            }
        }
    }

    private static long copy(InputStream input, Path staged) throws IOException {
        byte[] buffer = new byte[BUFFER_BYTES];
        long size = 0;
        try (OutputStream output = Files.newOutputStream(staged)) {
            int read;
            while ((read = input.read(buffer, 0, (int) Math.min(buffer.length, MAX_RESOURCE_BYTES - size + 1))) != -1) {
                if (read == 0) {
                    int value = input.read();
                    if (value == -1) {
                        break;
                    }
                    buffer[0] = (byte) value;
                    read = 1;
                }
                if (size + read > MAX_RESOURCE_BYTES) {
                    throw new IOException("Packaged Redis native resource exceeds " + MAX_RESOURCE_BYTES + " bytes");
                }
                output.write(buffer, 0, read);
                size += read;
            }
        }
        if (size == 0) {
            throw new IOException("Packaged Redis native resource is empty");
        }
        return size;
    }

    private static boolean matches(Path library, long size, String hash) throws IOException {
        try {
            return Files.size(library) == size && hash.equals(NativeCacheProvenance.sha256(library));
        } catch (NoSuchFileException missing) {
            return false;
        }
    }
}
