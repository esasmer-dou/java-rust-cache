package com.reactor.rust.cache.internal.nativebridge;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Checks shipped resources and their selected provenance without loading native code. */
class PackagedNativeProvenanceConsistencyTest {

    private static final int EXPECTED_REDIS_ABI = 6;
    private static final ClassLoader RESOURCES = PackagedNativeProvenanceConsistencyTest.class.getClassLoader();

    @ParameterizedTest(name = "{0} classloader provenance")
    @MethodSource("packagedBinaries")
    void packagedBinaryMatchesClassloaderProvenance(String platform, String filename) throws IOException {
        byte[] binary = readBinary(requiredResource(platform, filename));

        assertEquals(NativeCacheProvenance.sha256(binary),
                NativeCacheProvenance.verifyPackagedBinary(RESOURCES, platform, binary, EXPECTED_REDIS_ABI));
    }

    @ParameterizedTest(name = "{0} URL provenance")
    @MethodSource("packagedBinaries")
    void packagedBinaryMatchesUrlProvenance(String platform, String filename) throws IOException {
        URL resource = requiredResource(platform, filename);
        byte[] binary = readBinary(resource);

        assertEquals(NativeCacheProvenance.sha256(binary),
                NativeCacheProvenance.verifyPackagedBinary(resource, platform, binary, EXPECTED_REDIS_ABI));
    }

    private static Stream<Arguments> packagedBinaries() {
        return Stream.of(
                Arguments.of("windows-x64", "rust_hyper-windows-x64.dll"),
                Arguments.of("linux-x64", "librust_hyper-linux-x64.so"),
                Arguments.of("linux-arm64", "librust_hyper-linux-arm64.so"),
                Arguments.of("macos-arm64", "librust_hyper-macos-arm64.dylib"));
    }

    private static URL requiredResource(String platform, String filename) {
        String path = "native/" + platform + "/" + filename;
        URL resource = RESOURCES.getResource(path);
        assertNotNull(resource, "Missing packaged cache native resource: " + path);
        return resource;
    }

    private static byte[] readBinary(URL resource) throws IOException {
        try (InputStream input = resource.openStream()) {
            return input.readAllBytes();
        }
    }
}
