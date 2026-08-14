# java-rust-cache 0.7.4

`0.7.4` is a native provenance alignment release for applications that use
`rust-java-rest:4.5.0` and Redis in the same process.

## What Changed

- The packaged Windows DLL and Linux SO now come from the same clean `rust-spring v4.5.0` commit as
  Rust-Java REST `4.5.0`.
- The manifest records REST ABI `29`, Dubbo ABI `7`, Redis ABI `6`, and Glowroot ABI `3`.
- The optional Rust-Java REST dependency is aligned to `4.5.0`.
- Redis commands, topology behavior, public Java API, and Redis ABI remain unchanged.

Use `java-rust-cache:0.7.4` with `rust-java-rest:4.5.0` when both libraries are in one process. Do
not copy DLL/SO files from `0.7.3` or REST `4.4.x`; startup provenance checks reject mixed binaries.
