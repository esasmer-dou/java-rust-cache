package com.reactor.rust.cache.codegen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RustCacheConfigurationProcessorTest {
    @TempDir Path directory;

    @Test void absentEdgeOptInKeepsTheExistingStartupPath() throws Exception {
        var result = compile("", "");
        assertTrue(result.success, result.diagnostics);
        assertFalse(result.source.contains("installPublicEdgePolicies"));
        assertFalse(result.source.contains("catch (RuntimeException"));
        assertFalse(result.source.contains(".reflect."));
    }

    @Test void explicitPublicControllersGenerateDirectInstallationAndFailureCleanup() throws Exception {
        var result = compile("publicEdgePolicies = Catalog.class", """
                @com.reactor.rust.annotations.RestController("/catalog")
                public final class Catalog {
                    @com.reactor.rust.annotations.GetMapping("/{id}")
                    @com.reactor.rust.annotations.RustEdgeCache(versionKey="catalog:current",
                        dataKeyPrefix="catalog:v:{version}:id:", pathParam="id")
                    public String get() { return "{}"; }
                }
                """);
        assertTrue(result.success, result.diagnostics);
        assertTrue(result.source.contains("cache.installPublicEdgePolicies(id -> fixture.Catalog_EdgePolicies.install(id, true))"));
        assertTrue(result.source.contains("failure.addSuppressed(closeFailure)"));
        assertTrue(result.source.contains("cache = null"));
        assertFalse(result.source.contains("Class.forName"));
    }

    @Test void unannotatedAndDuplicateControllersFailAtCompilation() throws Exception {
        assertFalse(compile("publicEdgePolicies = Catalog.class", "public final class Catalog {}").success);
        var duplicate = compile("publicEdgePolicies = {Catalog.class, Catalog.class}", """
                public final class Catalog {
                    @com.reactor.rust.annotations.GetMapping("/ping")
                    @com.reactor.rust.annotations.RustEdgeRateLimit(permitsPerSecond=1, burst=1)
                    public String ping() { return "{}"; }
                }
                """);
        assertFalse(duplicate.success);
        assertTrue(duplicate.diagnostics.contains("distinct controllers"));
    }

    @Test void startupSwitchIsExplicitAndDoesNotPermitUnboundProperties() throws Exception {
        var result = compile("publicEdgePolicies = Catalog.class, publicEdgeEnabledProperty = \"sample.edge.enabled\"", """
                public final class Catalog {
                    @com.reactor.rust.annotations.GetMapping("/ping")
                    @com.reactor.rust.annotations.RustEdgeRateLimit(permitsPerSecond=1, burst=1)
                    public String ping() { return "{}"; }
                }
                """);
        assertTrue(result.success, result.diagnostics);
        assertTrue(result.source.contains("PropertiesLoader.getBoolean(\"sample.edge.enabled\", false)"));
        assertFalse(compile("publicEdgeEnabledProperty = \"sample.edge.enabled\"", "").success);
        assertFalse(compile("publicEdgePolicies = Catalog.class, publicEdgeEnabledProperty = \"bad key\"",
                "public final class Catalog {}").success);
    }

    private Compilation compile(String options, String controller) throws Exception {
        Path root = Files.createTempDirectory(directory, "compile-");
        Path sources = Files.createDirectories(root.resolve("src/fixture"));
        Path application = sources.resolve("App.java");
        Files.writeString(application, "package fixture; @com.reactor.rust.cache.integration.EnableRustCache("
                + options + ") public final class App {}", StandardCharsets.UTF_8);
        var inputs = new java.util.ArrayList<java.io.File>();
        inputs.add(application.toFile());
        if (!controller.isEmpty()) {
            Path catalog = sources.resolve("Catalog.java");
            Files.writeString(catalog, "package fixture; " + controller, StandardCharsets.UTF_8);
            inputs.add(catalog.toFile());
        }
        Path generated = Files.createDirectories(root.resolve("generated"));
        Path classes = Files.createDirectories(root.resolve("classes"));
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var task = compiler.getTask(null, files, diagnostics, List.of("--release", "21", "-proc:full",
                    "-classpath", System.getProperty("java.class.path"), "-d", classes.toString(),
                    "-s", generated.toString()), null, files.getJavaFileObjectsFromFiles(inputs));
            task.setProcessors(List.of(new RustCacheConfigurationProcessor(),
                    new com.reactor.rust.codegen.EdgePolicyProcessor()));
            boolean success = task.call();
            Path output = generated.resolve("fixture/App__RustCacheConfiguration.java");
            return new Compilation(success, Files.exists(output) ? Files.readString(output) : "",
                    diagnostics.getDiagnostics().toString());
        }
    }

    private record Compilation(boolean success, String source, String diagnostics) {}
}
