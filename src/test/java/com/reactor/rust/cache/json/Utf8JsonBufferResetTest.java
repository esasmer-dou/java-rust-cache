package com.reactor.rust.cache.json;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class Utf8JsonBufferResetTest {
    @Test void previousOutputAndSurrogateStateDoNotEscapeIntoNextDocument() {
        var out = new Utf8JsonBuffer(32, 4096).append("İstanbul 🚀");
        byte[] first = out.toByteArray();
        out.append('\ud800').reset(32).append("next");
        assertArrayEquals("İstanbul 🚀".getBytes(StandardCharsets.UTF_8), first);
        assertArrayEquals("next".getBytes(StandardCharsets.UTF_8), out.toByteArray());
    }

    @Test void oversizedScratchIsDiscardedWithoutChangingLimits() {
        var out = new Utf8JsonBuffer(32, 4096).append("x".repeat(3000));
        out.reset(32).append("small");
        assertArrayEquals("small".getBytes(StandardCharsets.UTF_8), out.toByteArray());
        assertThrows(IllegalStateException.class, () -> out.append("x".repeat(4096)));
        out.reset(32).append("again");
        assertArrayEquals("again".getBytes(StandardCharsets.UTF_8), out.toByteArray());
    }

    @Test void invalidResetDoesNotMutateTheCurrentDocument() {
        var out = new Utf8JsonBuffer(32, 64).append("kept");
        assertThrows(IllegalArgumentException.class, () -> out.reset(0));
        assertThrows(IllegalArgumentException.class, () -> out.reset(65));
        assertArrayEquals("kept".getBytes(StandardCharsets.UTF_8), out.toByteArray());
    }
}
