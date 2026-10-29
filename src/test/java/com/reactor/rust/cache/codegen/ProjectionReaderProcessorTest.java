package com.reactor.rust.cache.codegen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectionReaderProcessorTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesBoundReaderContract() throws Exception {
        Path source = tempDir.resolve("src/generated/fixture/CustomerCache.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package generated.fixture;

                import com.reactor.rust.cache.api.CacheReadResult;
                import com.reactor.rust.cache.projection.*;

                interface BaseCache {
                    @ProjectionMetaRead(projection = "meta")
                    CacheReadResult meta();

                    default String readerKind() { return "customer"; }
                }

                @GenerateProjectionReader(
                        rootPrefix = "sample.customer",
                        generatedName = "CustomerCacheReader",
                        restBean = true)
                interface CustomerCache extends BaseCache {
                    @ProjectionIdRead(projection = "detail")
                    CacheReadResult customer(long id);

                    @ProjectionIndexRead(projection = "segment", index = "segment", defaultValue = "standard")
                    CacheReadResult bySegment(String segment);

                    @CacheMetricsRead
                    String metricsJson();
                }
                """, StandardCharsets.UTF_8);
        Path generated = Files.createDirectories(tempDir.resolve("generated"));
        Path classes = Files.createDirectories(tempDir.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var task = compiler.getTask(
                    null,
                    files,
                    null,
                    List.of(
                            "--release", "21",
                            "-proc:full",
                            "-classpath", System.getProperty("java.class.path"),
                            "-d", classes.toString(),
                            "-s", generated.toString()),
                    null,
                    files.getJavaFileObjects(source.toFile()));
            task.setProcessors(List.of(new ProjectionReaderProcessor()));
            assertTrue(task.call());
        }
        String output = Files.readString(
                generated.resolve("generated/fixture/CustomerCacheReader.java"),
                StandardCharsets.UTF_8);
        assertTrue(output.contains("() -> \"detail\")"));
        assertTrue(output.contains("() -> \"segment\")"));
        assertTrue(output.contains("CacheReadResult meta()"));
        assertFalse(output.contains("readerKind()"));
        assertTrue(output.contains("String normalizedKey = segment == null ? null : segment.trim()"));
        assertTrue(output.contains("normalizedKey = \"standard\""));
        assertTrue(output.contains(".get(normalizedKey)"));
        assertTrue(output.contains("return cache.metricsJson()"));
        assertFalse(output.contains("ConfiguredJsonProjectionReaders"));
        assertFalse(output.contains("resolveAllWithL1"));
        assertFalse(output.contains("AutoCloseable"));
        String configuration = Files.readString(
                generated.resolve("generated/fixture/CustomerCache__ReactorConfiguration.java"),
                StandardCharsets.UTF_8);
        assertTrue(configuration.contains("@com.reactor.rust.di.annotation.Configuration"));
        assertTrue(configuration.contains("CustomerCacheReader.create(cache"));
    }

    @Test
    void explicitL1GeneratesStartupSelectionAndOwnedShutdownWithoutChangingMethodContracts() throws Exception {
        Path source = tempDir.resolve("l1-src/generated/fixture/CustomerCache.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package generated.fixture;
                import com.reactor.rust.cache.api.CacheReadResult;
                import com.reactor.rust.cache.projection.*;
                @GenerateProjectionReader(rootPrefix="sample.customer", generatedName="Reader", restBean=true, allowL1=true)
                interface CustomerCache {
                    @ProjectionIdRead(projection="detail") CacheReadResult customer(long id);
                    @ProjectionIdRead(projection="detail") com.reactor.rust.cache.api.CacheReadSnapshot borrowed(long id);
                    @ProjectionIdRead(projection="detail") com.reactor.rust.cache.api.CacheReadValue selected(long id);
                    @ProjectionMetaRead(projection="meta") com.reactor.rust.cache.api.CacheReadValue selectedMeta();
                    @ProjectionIndexRead(projection="segment", index="segment")
                    com.reactor.rust.cache.api.CacheReadValue selectedIndex(String value);
                    @ProjectionIndexRead(projection="segment", index="segment", defaultValue="standard")
                    CacheReadResult segment(String value);
                    @CacheMetricsRead String metricsJson();
                }
                """, StandardCharsets.UTF_8);
        Path generated = Files.createDirectories(tempDir.resolve("l1-generated"));
        Path classes = Files.createDirectories(tempDir.resolve("l1-classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var task = compiler.getTask(null, files, null,
                    List.of("--release", "21", "-proc:full", "-classpath", System.getProperty("java.class.path"),
                            "-d", classes.toString(), "-s", generated.toString()), null,
                    files.getJavaFileObjects(source.toFile()));
            task.setProcessors(List.of(new ProjectionReaderProcessor()));
            assertTrue(task.call());
        }
        String reader = Files.readString(generated.resolve("generated/fixture/Reader.java"));
        assertTrue(reader.contains("ConfiguredJsonProjectionReaders.BoundProjection"));
        assertTrue(reader.contains("resolveAllWithL1(properties"));
        assertTrue(reader.contains("readers.metricsJson(cache)"));
        assertTrue(reader.contains("readers.close()"));
        assertTrue(reader.contains("CacheReadResult customer(long id)"));
        assertTrue(reader.contains(".snapshotById(id)"));
        assertTrue(reader.contains(".readById(id)"));
        assertTrue(reader.contains(".readMeta()"));
        assertTrue(reader.contains(".read(normalizedKey)"));
        assertFalse(reader.contains("getProperty("));
        String configuration = Files.readString(generated.resolve("generated/fixture/CustomerCache__ReactorConfiguration.java"));
        assertTrue(configuration.contains("@com.reactor.rust.di.annotation.PreDestroy"));
        assertTrue(configuration.contains("reader.close()"));
    }

    @Test
    void generatesMinimalRustCacheLifecycleConfiguration() throws Exception {
        Path source = tempDir.resolve("cache-src/generated/fixture/Application.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package generated.fixture;
                import com.reactor.rust.cache.integration.EnableRustCache;
                @EnableRustCache
                public final class Application {}
                """, StandardCharsets.UTF_8);
        Path generated = Files.createDirectories(tempDir.resolve("cache-generated"));
        Path classes = Files.createDirectories(tempDir.resolve("cache-classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var task = compiler.getTask(
                    null,
                    files,
                    null,
                    List.of(
                            "--release", "21",
                            "-proc:full",
                            "-classpath", System.getProperty("java.class.path"),
                            "-d", classes.toString(),
                            "-s", generated.toString()),
                    null,
                    files.getJavaFileObjects(source.toFile()));
            task.setProcessors(List.of(new RustCacheConfigurationProcessor()));
            assertTrue(task.call());
        }
        String configuration = Files.readString(
                generated.resolve("generated/fixture/Application__RustCacheConfiguration.java"),
                StandardCharsets.UTF_8);
        assertTrue(configuration.contains("RustCaches.create"));
        assertTrue(configuration.contains("@com.reactor.rust.di.annotation.PreDestroy"));
    }
}
