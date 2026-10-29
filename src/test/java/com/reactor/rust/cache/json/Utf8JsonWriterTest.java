package com.reactor.rust.cache.json;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class Utf8JsonWriterTest {
    static final class Prepared extends Utf8JsonWriter {
        final JsonField text;
        final JsonField number = key("n");
        Prepared(String name) { text = key(name); }
        byte[] write(String value, long n) {
            var out = json(16); text.string(out, value).append(','); number.number(out, n); return utf8(out);
        }
    }

    @Test void preparedFieldsKeepExactUtf8ForChangingValuesAndDoNotRetainDocuments() {
        var prepared = new Prepared("value");
        var expected = new Old();
        for (String value : new String[]{null, "", "İıÇçĞğÖöÜü", "🚀", "\ud800x\udc00", "\u0000\t\r\n\"\\"})
            for (long n : new long[]{0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE})
                assertArrayEquals(expected.write(value, n), prepared.write(value, n));
        byte[] first = prepared.write("unchanged", 42);
        prepared.write("other", 43);
        assertArrayEquals(expected.write("unchanged", 42), first);
    }

    @Test void preparedNamesAreEscapedOnceAndNeverBypassTheOutputByteLimit() {
        var prepared = new Prepared("İ\"\\\n🚀");
        var plain = new Utf8JsonWriter() {
            byte[] write() { var out = json(16); stringField(out, "İ\"\\\n🚀", "body").append(','); field(out, "n", 4); return utf8(out); }
        };
        assertArrayEquals(plain.write(), prepared.write("body", 4));
        var bounded = new Utf8JsonBuffer(0, 1);
        assertThrows(IllegalStateException.class, () -> prepared.text.string(bounded, "body"));
        assertEquals(0, bounded.toByteArray().length);
    }
    static class Old extends JsonWriter {
        byte[] write(String value, long n) { var out=json(16); stringField(out,"value",value).append(','); field(out,"n",n); return utf8(out); }
    }
    static class New extends Utf8JsonWriter {
        byte[] write(String value, long n) { var out=json(16); stringField(out,"value",value).append(','); field(out,"n",n); return utf8(out); }
    }
    @Test void byteExactUtf8EscapingAndPrimitiveBounds() {
        var old = new Old(); var candidate = new New();
        for (String value : new String[]{null,"", "İıÇçĞğÖöÜü", "\ud83d\ude80", "\ud800x\udc00", "\u0000\t\r\n\"\\"})
            for (long number : new long[]{0,1,-1,Long.MIN_VALUE,Long.MAX_VALUE})
                assertArrayEquals(old.write(value,number),candidate.write(value,number));
        var random = new Random(42);
        for (int i=0; i<1000; i++) {
            char[] chars = new char[64]; for(int c=0;c<chars.length;c++) chars[c]=(char)random.nextInt(65536);
            long n = random.nextLong();
            assertArrayEquals(old.write(new String(chars),n),candidate.write(new String(chars),n));
        }
    }
    @Test void limitIsBytesNotCharacters() {
        var output = new Utf8JsonBuffer(1,4);
        output.append("ğğ");
        assertEquals(4,output.toByteArray().length);
        assertThrows(IllegalStateException.class, () -> output.append('x'));
    }
    @Test void asciiRunsPreserveSurrogatesAcrossAppendBoundaries() {
        var output = new Utf8JsonBuffer(1, 1024);
        output.append("abc\ud83d").append("\ude80def").append("\ud800").append("ghi");
        output.append("\udc00jkl").append('\ud83d').append("\ude80mn");
        assertArrayEquals("abc\ud83d\ude80def?ghi?jkl\ud83d\ude80mn".getBytes(StandardCharsets.UTF_8), output.toByteArray());
        var bounded = new Utf8JsonBuffer(0, 3);
        bounded.append("abc");
        assertThrows(IllegalStateException.class, () -> bounded.append("d"));
        assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), bounded.toByteArray());
    }
    @Test void mixedAsciiUnicodeAndEveryControlCharacterMatchOriginalWriter() {
        var value = new StringBuilder("literal-prefix-İstanbul-");
        for (char c = 0; c < 32; c++) value.append(c).append("middle-ğ-");
        value.append("\"\\suffix\ud83d\ude80");
        assertArrayEquals(new Old().write(value.toString(), Long.MIN_VALUE),
                new New().write(value.toString(), Long.MIN_VALUE));
    }
    @Test void singlePassPreservesExactByteLimitsAndPendingSurrogates() {
        for (String value : new String[]{"plain-ascii", "İstanbul-🚀", "\ud800x", "\udc00"}) {
            byte[] expected = value.getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(expected, new Utf8JsonBuffer(0, expected.length).append(value).toByteArray());
        }
        var full = new Utf8JsonBuffer(0, 4);
        full.append("abc").append('\ud800');
        assertArrayEquals("abc?".getBytes(StandardCharsets.UTF_8), full.toByteArray());
        assertThrows(IllegalStateException.class, () -> full.append('d'));
    }
}
