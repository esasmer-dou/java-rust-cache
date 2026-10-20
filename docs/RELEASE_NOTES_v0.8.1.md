# java-rust-cache 0.8.1

[English](RELEASE_NOTES_v0.8.1.md) | [Türkçe](RELEASE_NOTES_v0.8.1.tr.md)

This coordinated patch refreshes the native runtime used when the cache runs without REST.
It fixes exporter ownership after a very quick telemetry start/close. Cancellation before the
exporter's first execution now releases the claim, so a later startup can succeed.

Redis commands, access modes, TTL, snapshots, lock/fencing rules and Java APIs do not change.
No worker, queue or pool default is increased. Redis ABI 6 and Glowroot ABI 6 remain unchanged.
REST integration aligns to 4.7.1. The bundled Windows x64, Linux x64/ARM64 and macOS ARM64 files
come from the same clean source; Linux retains the GLIBC 2.17 floor.

Upgrade the library and use its bundled native file. If REST is also present, align its version
to 4.7.1. Do not combine new JARs with an explicitly configured older DLL/SO. Telemetry stays
optional; ordinary cache operations do not start an exporter.

Publication requires native/correctness checks and the coordinated telemetry gate. A source branch
is not a published package. HTTP micro-agent memory qualification is not a guarantee for every
Redis workload or richer diagnostic profile.
