# 0.9.3: Faster Versioned JSON Reads by ID

[English](https://github.com/esasmer-dou/java-rust-cache/blob/v0.9.3/docs/RELEASE_NOTES_v0.9.3.md) | [Turkish](https://github.com/esasmer-dou/java-rust-cache/blob/v0.9.3/docs/RELEASE_NOTES_v0.9.3.tr.md)

Update the Maven dependency to `com.reactor:java-rust-cache:0.9.3`. If you use
the cache code generator, align its classifier dependency to the same version.
REST/platform, Redis settings and application code do not need to change.

`VersionedJsonCacheReader.getById` now constructs the Redis key prefix once
per snapshot version rather than on every read. It still reads exactly the same
key, so existing snapshots do not need rebuilding. TTL, response formats,
native binaries and Redis JNI ABI 6 are unchanged. This change targets
synchronous reads by ID, not segment searches or writes.

In a targeted EmaTech comparison on REST `4.9.0`, the Cache Reader detail
route at 16 concurrent connections gained 3.5% successful requests per second.
p99 was 47.4 → 46.5 ms. Loaded process RSS was effectively unchanged
(68.39 → 68.46 MiB) and the highest observed process RSS fell from 100.15 to
91.60 MiB. This is one measured workload, not a guarantee for all routes.

GitHub Packages access still requires Maven `settings.xml` with a token that
has `read:packages`. See [setup](../README.md#github-packages-access).
