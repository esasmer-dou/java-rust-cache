package com.reactor.rust.cache.lock;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LeaseOwnerTokenTest {
    @Test
    void everyAcquisitionHasAnIndependentBoundedOwner() {
        var owners = new HashSet<String>();
        String prefix = null;
        for (int i = 0; i < 10_000; i++) {
            String owner = RustCacheLocks.ownerToken();
            assertTrue(owner.matches("[0-9a-f]{32}:[0-9a-f]{1,16}"));
            assertTrue(owner.length() <= 49);
            assertTrue(owners.add(owner));
            if (prefix == null) prefix = owner.substring(0, 32);
            assertEquals(prefix, owner.substring(0, 32));
        }
    }

    @Test
    void concurrentReplicasTasksWithinJvmNeverReuseAnOwner() throws Exception {
        var owners = ConcurrentHashMap.<String>newKeySet();
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int worker = 0; worker < 2; worker++) futures.add(workers.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) return false;
                for (int i = 0; i < 10_000; i++) if (!owners.add(RustCacheLocks.ownerToken())) return false;
                return true;
            }));
            start.countDown();
            for (var result : futures) assertTrue(result.get(10, TimeUnit.SECONDS));
        }
        assertEquals(20_000, owners.size());
    }
}
