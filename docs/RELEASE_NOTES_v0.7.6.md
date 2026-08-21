# java-rust-cache 0.7.6

[English](RELEASE_NOTES_v0.7.6.md) | [Turkish](RELEASE_NOTES_v0.7.6.tr.md)

`0.7.6` aligns the standalone cache runtime with `rust-java-rest:4.6.0` and Glowroot native ABI `4`.

## What Users Get

- The optional REST dependency now targets `4.6.0`.
- Standalone cache applications package the same clean Windows and GLIBC 2.17 Linux native
  revision used by REST `4.6.0`.
- REST ABI `29`, Dubbo ABI `7`, and Redis ABI `6` are unchanged. Glowroot ABI moves from `3` to `4`.
- Cache APIs, Redis topology configuration, projection contracts, TTL behavior, locks, and Java
  business code are unchanged.

Use the DLL/SO carried by this package. Do not combine `java-rust-cache:0.7.5` with the coordinated
REST `4.6.0` runtime in one process.
