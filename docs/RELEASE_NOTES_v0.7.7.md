# java-rust-cache 0.7.7

[English](RELEASE_NOTES_v0.7.7.md) | [Turkish](RELEASE_NOTES_v0.7.7.tr.md)

Adds packaged Linux ARM64 and macOS ARM64 loading alongside Windows/Linux x64. Uses the same clean native source as REST 4.6.3. Redis ABI remains 6; key, TTL, lock, Cluster and Sentinel contracts are unchanged.

## Upgrade

Update the Maven dependency or parent and rebuild the application. Keep annotation processors on the aligned platform version. Use the packaged native library for your OS and CPU. Do not replace an old release's assets in place.

Java business logic stays in Java. No runtime reflection, new Java I/O stack or default telemetry activation is added. ARM64 binaries are cross/native-built in CI; runtime application checks for this release use x64. This patch does not claim a new universal RSS or throughput result.

GitHub Packages requires a valid `read:packages` token in Maven `settings.xml`, even for a public repository. Git SSH access is separate from Maven authentication.

