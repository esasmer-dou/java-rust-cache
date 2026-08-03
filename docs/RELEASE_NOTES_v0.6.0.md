# java-rust-cache 0.6.0

`0.6.0` reduces cache reader and writer setup code while keeping Redis transport, topology, pooling,
timeouts, and backpressure in Rust.

## Cache Reader Setup

```java
@EnableRustCache
@ReactorApplication(scanBasePackages = "com.example.cache")
public final class CacheReaderApplication {
    public static void main(String[] args) {
        RestApplication.run(CacheReaderApplication.class, args);
    }
}

@GenerateProjectionReader(rootPrefix = "app.cache.customer", restBean = true)
interface CustomerCacheReads {
    @ProjectionIdRead(projection = "detail")
    CacheReadResult customer(long id);

    @ProjectionIndexRead(projection = "segment", index = "segment")
    CacheReadResult bySegment(String segment);
}
```

Projection and index bindings are resolved once at startup. Generated methods call the bound native
reader directly.

## Cache Writer Setup

```java
ProjectionWriterApplication.runCache(
        "cache-writer.properties",
        "app.cache.customer",
        PostgresRepository::open,
        CustomerMaterializer::new);
```

The launcher owns resource shutdown and startup rollback. SQL selection and JSON mapping remain
explicit application code.

## What Is New

- `@EnableRustCache` for one managed native cache bean.
- `@GenerateProjectionReader` with ID, index, metadata, and metrics read declarations.
- Inherited non-generic projection reader contracts and default helper methods.
- Build-time validation for generic contracts, blank prefixes, invalid generated names, and invalid
  method signatures.
- Managed writer resource and materializer factories.
- Separate `codegen` JAR; annotation processors do not enter the production runtime artifact.

## Dependency

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-cache</artifactId>
  <version>0.6.0</version>
</dependency>
```

Use `rust-java-rest:4.1.0` when the same process exposes REST endpoints.

## Compatibility

Redis keys, projection namespaces, TTL rules, standalone/Sentinel/Cluster configuration, Java cache
interfaces, and native Redis ABI `6` are unchanged. No new DLL or SO contract is introduced.

## Verification

- Library and processor tests passed with no failures or errors.
- Reader and writer sample integration tests passed.
- Runtime JAR contains no annotation processor service metadata.
- Exact-HEAD Docker A/B cache gates kept non-2xx at zero and did not increase reader thread count.
