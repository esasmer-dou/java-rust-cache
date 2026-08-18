# java-rust-cache 0.7.5

[English](RELEASE_NOTES_v0.7.5.md) | [Turkish](RELEASE_NOTES_v0.7.5.tr.md)

`0.7.5` aligns the cache library with the stable `rust-java-rest:4.5.5` native runtime.

## What Users Get

- The optional REST dependency now targets `4.5.5`.
- Standalone cache applications package the same clean Windows and Linux native revision used by
  REST `4.5.5`.
- REST ABI `29`, Dubbo ABI `7`, Redis ABI `6`, and Glowroot ABI `3` are unchanged.
- Cache APIs, Redis topology configuration, projection contracts, TTL behavior, locks, and Java
  business code are unchanged.

Use the packaged native files. Do not copy a DLL or SO from an unrelated release.
