# 0.9.1 — Prepared Patch

[English](RELEASE_NOTES_v0.9.1.md) | [Türkçe](RELEASE_NOTES_v0.9.1.tr.md)

Not published yet. The current published version remains 0.9.0.

Linux ARM64/macOS ARM64 users should upgrade when this patch is published.
The previous JAR has current binaries but stale platform manifests. The loader
selects that sidecar before the root manifest and rejects its outdated hash.

The patch regenerates sidecars from the packaged binary and adds a packaged-JAR
gate. ARM manifests keep `runtime.verified=false`; this is not an ARM runtime test.
The source revision remains `3418671b5010afb5dc049897f2a7287dc6a58daf`.
No native rebuild is needed, and all four native binaries remain byte-identical.

Use cache `0.9.1` with REST/platform `4.8.1` if REST is present. Rebuild your app
with the matching packaged resources. Samples are prepared as `0.8.1`; shared
model/utility stay at `0.5.0`. Java APIs, optional defaults, Redis ABI 6 and all
other native ABI contracts remain unchanged. No new memory or throughput claim
is made by this packaging patch.
