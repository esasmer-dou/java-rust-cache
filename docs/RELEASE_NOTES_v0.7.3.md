# java-rust-cache 0.7.3

`0.7.3` is the stable native provenance patch aligned with `rust-java-rest:4.4.1` and
`rust-spring v4.4.2`.

- Windows and Linux native libraries come from the same clean CI source commit.
- Release CI verifies hashes, ABI values, the complete 40-character source revision, and the
  revision embedded in both native binaries.
- Redis standalone, Sentinel, Cluster, read-only, write-only, and read-write behavior is unchanged.
- Java APIs, projection scheduling, locking, snapshot publishing, and application business logic
  are unchanged.

Use `java-rust-cache:0.7.3` with `rust-java-rest:4.4.1` when both libraries are in one process. Do
not copy a DLL/SO from another release.
