package com.reactor.rust.cache.versioned;

import com.reactor.rust.cache.api.CacheReadResult;
import com.reactor.rust.cache.config.RustCacheConfig;
import com.reactor.rust.cache.core.RustCaches;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="reactor.cache.redis.integration", matches="true")
class CacheFreshnessLiveTest {
    @Test void rejectsOldSnapshotsAndNeverPublishesPartialRefresh() throws Exception {
        var config = RustCacheConfig.builder().host("127.0.0.1")
                .port(Integer.getInteger("reactor.cache.redis.integration.port",6379)).build();
        try (var cache = RustCaches.create(config)) {
            String namespace="freshness-"+UUID.randomUUID();
            var reader=VersionedJsonCacheReader.create(cache,namespace,0,new CacheFreshness(60_000,false));
            var staleReader=VersionedJsonCacheReader.create(cache,namespace,0,new CacheFreshness(60_000,true));
            assertEquals(CacheReadResult.Status.CACHE_NOT_READY,reader.getMeta().status());
            String old=(System.currentTimeMillis()-120_000)+"-1";
            cache.setString(namespace+":v:"+old+":meta","{\"ok\":true}",60_000);
            cache.setString(namespace+":current","1|"+old,60_000);
            assertEquals(CacheReadResult.Status.STALE,reader.getMeta().status());
            assertEquals(CacheReadResult.Status.STALE_HIT,staleReader.getMeta().status());
            var writer=VersionedJsonCacheWriter.create(cache,namespace);
            assertThrows(Exception.class,()->writer.refreshSnapshot(60_000,s->{throw new IllegalStateException("failed refresh");}));
            assertEquals("1|"+old,cache.getString(namespace+":current"));
            writer.refreshSnapshot(60_000,s->s.putMeta("{\"ok\":true}"));
            assertEquals(CacheReadResult.Status.HIT,reader.getMeta().status());
        }
    }
}
