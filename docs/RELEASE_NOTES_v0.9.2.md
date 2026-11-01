# 0.9.2: Aligned Native Response Lifecycle

[English](RELEASE_NOTES_v0.9.2.md) | [Turkish](RELEASE_NOTES_v0.9.2.tr.md)

Use `com.reactor:java-rust-cache:0.9.2` with REST/platform `4.8.2`.
Redis commands, topology selection, TTL and projection settings are unchanged.

The bundled native library now uses the same clean revision as REST 4.8.2.
Bounded response-size hints reduce repeated JNI buffer growth. Untouched JSON
response headers avoid a Java map allocation in the coordinated REST package.
`CacheResponses.reply` remains the shared mapping from a cache read to an HTTP
response; no manual body copying or new handler annotation is required.

Replace an explicitly configured native DLL/SO/dylib along with the JAR. Redis
ABI remains 6. Do not mix native files from another release.

The EmaTech detail/segment c16/c64 comparison passed the existing relative
RPS/p99/RSS limits with zero unexpected errors. It compares the coordinated
Java/native change, not this library in isolation. Platform compatibility and
soak receipts are supplied as release evidence, not inferred from compilation.

GitHub Packages requires a Maven `settings.xml` server matching the repository
ID and a token with `read:packages`. SSH access to the repository is not Maven
package authentication. Existing English/Turkish README examples show the setup.
