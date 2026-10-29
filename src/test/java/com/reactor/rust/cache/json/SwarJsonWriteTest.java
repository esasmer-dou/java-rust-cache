package com.reactor.rust.cache.json;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class SwarJsonWriteTest {
    private static long word(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8))
                .order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    @Test void unalignedLiteralWordsPreserveEveryByteAcrossGrowth() {
        for (int alignment = 0; alignment < 8; alignment++) {
            var out = new Utf8JsonBuffer(0, 128).append("!".repeat(alignment));
            for (int i = 0; i < 12; i++) out.writePrebakedLong(word("01234567"));
            assertArrayEquals(("!".repeat(alignment) + "01234567".repeat(12))
                    .getBytes(StandardCharsets.UTF_8), out.toByteArray());
        }
    }

    @Test void arbitraryBytesAreLittleEndianOnEveryHost() {
        var out = new Utf8JsonBuffer(0, 8).writePrebakedLong(0xff807f0403020100L);
        assertArrayEquals(new byte[]{0, 1, 2, 3, 4, 127, (byte) 128, (byte) 255}, out.toByteArray());
    }

    @Test void exactLimitAndOverflowDoNotPartiallyAppendTheWord() {
        var out = new Utf8JsonBuffer(0, 9).append('x').writePrebakedLong(word("12345678"));
        assertThrows(IllegalStateException.class, () -> out.writePrebakedLong(0));
        assertArrayEquals("x12345678".getBytes(StandardCharsets.UTF_8), out.toByteArray());
        var shortOut = new Utf8JsonBuffer(0, 7);
        assertThrows(IllegalStateException.class, () -> shortOut.writePrebakedLong(0));
        assertArrayEquals(new byte[0], shortOut.toByteArray());
    }

    @Test void pendingSurrogateMatchesTheOrdinaryAppendContract() {
        var out = new Utf8JsonBuffer(0, 9).append('\ud800').writePrebakedLong(word("12345678"));
        assertArrayEquals("?12345678".getBytes(StandardCharsets.UTF_8), out.toByteArray());
        var shortOut = new Utf8JsonBuffer(0, 8).append('\ud800');
        assertThrows(IllegalStateException.class, () -> shortOut.writePrebakedLong(0));
        assertArrayEquals("?".getBytes(StandardCharsets.UTF_8), shortOut.toByteArray());
    }

    @Test void writerCompositionPreservesEscapingUnicodeNullsAndExactByteBound() {
        String controls = "\"\\\b\f\n\r\t\u0000\u001f";
        for (String value : new String[]{null, "", controls, "\u0130\u011f\ud83d\ude80", "\ud800x\udc00"}) {
            for (long number : new long[]{0, Long.MIN_VALUE, Long.MAX_VALUE}) {
                byte[] expected = new ScalarWriter().write(value, number);
                assertArrayEquals(expected, new LiteralWriter(expected.length).write(value, number));
                assertThrows(IllegalStateException.class,
                        () -> new LiteralWriter(expected.length - 1).write(value, number));
            }
        }
    }

    private static final class ScalarWriter extends Utf8JsonWriter {
        byte[] write(String value, long number) {
            var out = json(0).append('{');
            stringField(out, "name", value).append(',');
            field(out, "n", number).append('}');
            return utf8(out);
        }
    }

    private static final class LiteralWriter extends Utf8JsonWriter {
        LiteralWriter(int maxBytes) { super(maxBytes); }

        byte[] write(String value, long number) {
            var out = json(0).writePrebakedLong(0x3a22656d616e227bL); // {"name":
            quote(out, value).append(',');
            field(out, "n", number).append('}');
            return utf8(out);
        }
    }
}
