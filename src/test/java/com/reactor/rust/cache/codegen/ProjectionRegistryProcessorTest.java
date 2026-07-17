package com.reactor.rust.cache.codegen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectionRegistryProcessorTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesRegistryFromSharedEnum() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("src/generated/fixture"));
        Path generatedDir = Files.createDirectories(tempDir.resolve("generated"));
        Path classesDir = Files.createDirectories(tempDir.resolve("classes"));
        Path source = sourceDir.resolve("Materializer.java");
        Files.writeString(source, """
                package generated.fixture;

                import com.reactor.rust.cache.projection.GenerateProjectionRegistry;
                import com.reactor.rust.cache.projection.VersionedJsonProjectionMaterializer.ProjectionTarget;
                import com.reactor.rust.cache.versioned.VersionedJsonCacheWriter.SnapshotResult;
                import java.util.function.Supplier;

                enum Projection implements Supplier<String> {
                    DETAIL, CUSTOMER_META;
                    public String get() { return name().toLowerCase(); }
                }

                @GenerateProjectionRegistry(Projection.class)
                final class Materializer {
                    SnapshotResult writeDetail(ProjectionTarget target) { return null; }
                    SnapshotResult writeCustomerMeta(ProjectionTarget target) { return null; }
                }
                """, StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    files,
                    null,
                    List.of(
                            "--release", "21",
                            "-proc:only",
                            "-classpath", System.getProperty("java.class.path"),
                            "-d", classesDir.toString(),
                            "-s", generatedDir.toString()),
                    null,
                    files.getJavaFileObjects(source.toFile()));
            task.setProcessors(List.of(new ProjectionRegistryProcessor()));
            assertTrue(task.call());
        }

        String generated = Files.readString(
                generatedDir.resolve("generated/fixture/MaterializerProjectionRegistry.java"),
                StandardCharsets.UTF_8);
        assertTrue(generated.contains("Projection.DETAIL, owner::writeDetail"));
        assertTrue(generated.contains("Projection.CUSTOMER_META, owner::writeCustomerMeta"));
    }
}
