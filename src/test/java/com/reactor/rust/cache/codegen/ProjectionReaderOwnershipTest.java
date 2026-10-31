package com.reactor.rust.cache.codegen;

import com.reactor.rust.cache.api.CacheReadResult;
import com.reactor.rust.cache.api.CacheReadSnapshot;
import com.reactor.rust.cache.api.CacheReadValue;
import com.reactor.rust.cache.http.CacheResponses;
import com.reactor.rust.http.ResponseEntity;
import com.reactor.rust.json.ManagedJsonBodyProducer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectionReaderOwnershipTest {
    @Test
    void valueAndSnapshotReturnsTransferOwnershipWithoutIntermediatePayloadArrays() {
        for (boolean valueContract : new boolean[]{false, true}) {
            for (boolean problemDetails : new boolean[]{false, true}) {
                var snapshot = new DirectSnapshot(false);
                var producer = body(snapshot, valueContract, problemDetails);
                assertEquals(0, snapshot.releases);
                assertEquals(-2, producer.write(ByteBuffer.allocateDirect(1), 0));
                assertEquals(0, snapshot.copies);
                assertEquals(0, snapshot.releases);

                var response = ByteBuffer.allocateDirect(4);
                assertEquals(2, producer.write(response, 2));
                assertEquals('{', response.get(2));
                assertEquals('}', response.get(3));
                assertEquals(1, snapshot.copies);
                assertEquals(1, snapshot.releases);
                producer.close();
                assertEquals(1, snapshot.releases);
                assertThrows(IllegalStateException.class, () -> snapshot.writeTo(response, 0));
            }
        }
    }

    @Test
    void abortedAndFailedRepliesReleaseValueAndSnapshotOwnersExactlyOnce() {
        for (boolean valueContract : new boolean[]{false, true}) {
            for (boolean problemDetails : new boolean[]{false, true}) {
                var aborted = new DirectSnapshot(false);
                var pending = body(aborted, valueContract, problemDetails);
                assertEquals(-2, pending.write(ByteBuffer.allocateDirect(1), 0));
                pending.close();
                pending.close();
                assertEquals(1, aborted.releases);
                assertEquals(0, aborted.copies);

                var failed = new DirectSnapshot(true);
                var producer = body(failed, valueContract, problemDetails);
                assertThrows(IllegalStateException.class,
                        () -> producer.write(ByteBuffer.allocateDirect(2), 0));
                producer.close();
                assertEquals(1, failed.releases);
            }
        }
    }

    private static ManagedJsonBodyProducer body(
            DirectSnapshot snapshot, boolean valueContract, boolean problemDetails) {
        ResponseEntity<?> response = valueContract
                ? CacheResponses.reply((CacheReadValue) snapshot, "missing", "not_ready", problemDetails)
                : CacheResponses.reply(snapshot, "missing", "not_ready", problemDetails);
        return assertInstanceOf(ManagedJsonBodyProducer.class, response.getBody());
    }

    private static final class DirectSnapshot extends CacheReadSnapshot {
        private final ByteBuffer payload = ByteBuffer.allocateDirect(2);
        private final boolean failWrite;
        private int copies;
        private int releases;

        private DirectSnapshot(boolean failWrite) {
            super(CacheReadResult.Status.HIT, "v1", 2);
            payload.put(0, (byte) '{');
            payload.put(1, (byte) '}');
            this.failWrite = failWrite;
        }

        @Override protected void copyTo(ByteBuffer out, int offset) {
            if (failWrite) throw new IllegalStateException("Simulated response write failure");
            copies++;
            out.put(offset, payload, 0, payload.capacity());
        }

        @Override protected void release() {
            releases++;
        }
    }
}
