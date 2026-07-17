package com.reactor.rust.cache.projection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectionIdentifiersTest {

    private enum Projection implements ProjectionId {
        DETAIL;

        @Override
        public String value() {
            return "detail";
        }
    }

    private enum Index implements ProjectionIndex {
        CUSTOMER_NO;

        @Override
        public String value() {
            return "customer-no";
        }
    }

    @Test
    void enumIdentifiersExposeStableValuesWithoutConversion() {
        assertEquals("detail", Projection.DETAIL.value());
        assertEquals("customer-no", Index.CUSTOMER_NO.value());
        assertEquals("segment", ProjectionId.of("segment").value());
        assertEquals("status", ProjectionIndex.of("status").value());
        assertThrows(IllegalArgumentException.class, () -> ProjectionId.of(" "));
        assertThrows(IllegalArgumentException.class, () -> ProjectionIndex.of(null));
    }
}
