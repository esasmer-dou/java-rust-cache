# java-rust-cache 0.8.0

Use with Rust-Java REST/platform 4.7.0. Redis ABI stays 6.

- Choose a maximum snapshot age with `CacheFreshness`. Keep age zero to preserve existing behavior. The check reuses the version pointer and adds no Redis command.
- Choose whether stale snapshots may be served. Use the explicit problem-response adapter for missing-item 404 versus unavailable-snapshot 503. The legacy JSON adapter retains its existing contract.
- Extend `Utf8JsonWriter` for bounded UTF-8 JSON without a full intermediate String. Buffers are not retained in a ThreadLocal. A final byte-array copy remains.
- Database writers explicitly add `rust-java-jdbc:4.7.0`. Readers do not inherit this optional module.
- Safe ASCII cache keys avoid temporary UTF-8 arrays. Redis RESP buffering avoids small fragment writes without adding connections, workers or retries.

Matching Windows x64, Linux x64/ARM64 and macOS ARM64 native files are packaged with clean provenance. Linux compatibility remains GLIBC 2.17. Use the packaged native runtime instead of mixing older DLL/SO files.

No allocator, huge-page or nursery tuning experiment becomes a default.

## Qualification Note

Matched Linux measurements improved cache c16/c64 RPS and p99. Writer reconfirmation reduced mean peak RSS from 92.417 to 91.382 MiB, but final-idle RSS rose from 77.268 to 78.591 MiB. This idle increase is an accepted release risk. The coordinated consumer comparison also retains accepted exceptions. See the [full measurement scope and unchanged strict failures](https://github.com/esasmer-dou/rust-java-rest/blob/v4.7.0/docs/release-notes/v4.7.0.md#measured-scope-and-accepted-deviations).

[Examples and costs](functional-operations.md) | [Türkçe](RELEASE_NOTES_v0.8.0.tr.md)
