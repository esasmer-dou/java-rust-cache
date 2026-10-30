# 0.9.1 — ARM64 Packaging Fix

[English](RELEASE_NOTES_v0.9.1.md) | [Türkçe](RELEASE_NOTES_v0.9.1.tr.md)

Stable patch release dated 2026-10-30. Upgrade from 0.9.0 as described below.

Linux ARM64/macOS ARM64 users should upgrade to this patch.
The previous JAR has current binaries but stale platform manifests. The loader
selects that sidecar before the root manifest and rejects its outdated hash.

The patch regenerates sidecars from the packaged binary and adds a packaged-JAR
gate. ARM manifests keep `runtime.verified=false`; this is not an ARM runtime test.
The source revision remains `3418671b5010afb5dc049897f2a7287dc6a58daf`.
No native rebuild is needed, and all four native binaries remain byte-identical.

Use cache `0.9.1` with REST/platform `4.8.1` if REST is present. Rebuild your app
with the matching packaged resources. Samples use `0.8.1`, model `0.5.1`, and
utility `0.5.1`. Java APIs, optional defaults, Redis ABI 6 and all
other native ABI contracts remain unchanged. No new memory or throughput claim
is made by this packaging patch.
