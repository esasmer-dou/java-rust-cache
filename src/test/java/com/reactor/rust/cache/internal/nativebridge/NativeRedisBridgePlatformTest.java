package com.reactor.rust.cache.internal.nativebridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class NativeRedisBridgePlatformTest {

    @Test
    void resolvesMacosArm64ResourceAndPlatform() {
        String resource = NativeRedisBridge.platformResource("Mac OS X", "aarch64");

        assertEquals("native/macos-arm64/librust_hyper-macos-arm64.dylib", resource);
        assertEquals("macos-arm64", NativeRedisBridge.platformName(resource));
    }

    @Test
    void resolvesLinuxArm64ResourceAndPlatform() {
        String resource = NativeRedisBridge.platformResource("Linux", "arm64");

        assertEquals("native/linux-arm64/librust_hyper-linux-arm64.so", resource);
        assertEquals("linux-arm64", NativeRedisBridge.platformName(resource));
    }

    @Test
    void preservesExistingX64ResourceNames() {
        String windows = NativeRedisBridge.platformResource("Windows 11", "amd64");
        String linux = NativeRedisBridge.platformResource("Linux", "x86_64");

        assertEquals("native/windows-x64/rust_hyper-windows-x64.dll", windows);
        assertEquals("windows-x64", NativeRedisBridge.platformName(windows));
        assertEquals("native/linux-x64/librust_hyper-linux-x64.so", linux);
        assertEquals("linux-x64", NativeRedisBridge.platformName(linux));
    }
}
