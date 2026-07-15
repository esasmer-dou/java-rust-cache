# java-rust-cache 0.3.1

`0.3.1` simplifies projection-writer application startup while preserving the explicit business
transformation and bounded scheduler model.

## What's New

- Added `ProjectionWriterApplication.run(...)` and `start(...)` terminal launchers.
- Exposed the already loaded `CacheProperties` and root prefix through `ModuleContext`.
- Added optional property-backed scheduler stack, first-run timeout, thread-name, and shutdown-name
  settings.
- Added typed default overloads for long and boolean cache properties.
- Kept the builder API available for advanced scheduler customization.

## Recommended Bootstrap

```java
ProjectionWriterApplication.run(
        "cache-writer.properties",
        "sample.writer",
        CacheWriterModule.INSTANCE);
```

## Dependency

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-cache</artifactId>
  <version>0.3.1</version>
</dependency>
```

## Compatibility

- Existing cache, projection, lock, and builder APIs remain available.
- Redis native ABI remains `5`; packaged native files are unchanged.
