# java-rust-cache 0.7.2

`0.7.2` aligns the cache library with `rust-java-rest:4.4.0` and the clean native runtime built from
Rust revision `28688c3fca2618d469267ea4e61481a4295387f1`.

- Redis behavior, Java APIs, topology support, scheduler integration, and business code are
  unchanged.
- The packaged DLL/SO carries REST ABI `28`, Dubbo ABI `7`, Redis ABI `6`, and Glowroot ABI `1`.
- Release CI validates the full source revision and both platform SHA-256 values before publishing.
- Native Redis timing aggregates can feed the optional bounded Glowroot micro telemetry plane when
  it is enabled by the host application.

Use `java-rust-cache:0.7.2` with `rust-java-rest:4.4.0`. Do not copy a native binary from an older
release.

