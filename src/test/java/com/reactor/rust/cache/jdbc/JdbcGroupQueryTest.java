package com.reactor.rust.cache.jdbc;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JdbcGroupQueryTest {
    @Test
    void planRejectsUnboundedOrDynamicOrdering() {
        assertThrows(IllegalArgumentException.class, () -> JdbcRepository.GroupQuery.bounded("select 1", 0, "id"));
        assertThrows(IllegalArgumentException.class, () -> JdbcRepository.GroupQuery.bounded("select 1", 257, "id"));
        assertThrows(IllegalArgumentException.class, () -> JdbcRepository.GroupQuery.bounded("select 1", 64, "id; drop table x"));
        assertThrows(IllegalArgumentException.class, () -> JdbcRepository.GroupQuery.bounded(" ", 64, "id"));
        assertDoesNotThrow(() -> JdbcRepository.GroupQuery.bounded("select id, segment from x;", 64, "segment,id"));
    }
}
