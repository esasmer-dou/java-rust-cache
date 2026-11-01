# Changelog

## [0.9.2] - 2026-11-01

- Align with REST/platform 4.8.2 and the clean bounded-response native runtime.
- Keep Redis ABI 6, configuration and topology behavior unchanged.
- [Upgrade notes](docs/RELEASE_NOTES_v0.9.2.md).

## [0.9.1] - 2026-10-30

- Fix stale Linux ARM64/macOS ARM64 provenance sidecars using actual packaged
  binary hashes. Keep compile-only ARM qualification explicit.
- Verify packaged JARs, not only source resources. Reject mismatched sidecars.
- Align REST/platform to 4.8.1. No cache API, ABI or native binary changes.
- [Upgrade notes](docs/RELEASE_NOTES_v0.9.1.md).

## [0.9.0] - 2026-10-29

See the [published release notes](docs/RELEASE_NOTES_v0.9.0.md).
