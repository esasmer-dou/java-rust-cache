package com.reactor.rust.cache.codegen;

import java.io.IOException;
import java.nio.file.Path;
import java.util.jar.JarFile;

/** Maven verify-phase guard that keeps cache processors out of the runtime JAR. */
public final class ArtifactLayoutVerifier {

    private static final String PROCESSOR_SERVICE = "META-INF/services/javax.annotation.processing.Processor";

    private ArtifactLayoutVerifier() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected: <runtime-jar> <codegen-jar> <class-prefix>");
        }
        if (contains(Path.of(args[0]), args[2])) {
            throw new IllegalStateException("Runtime artifact contains build-time classes: " + args[2]);
        }
        if (!contains(Path.of(args[1]), args[2])) {
            throw new IllegalStateException("Codegen artifact is missing build-time classes: " + args[2]);
        }
        Path runtime = Path.of(args[0]);
        Path codegen = Path.of(args[1]);
        if (containsExact(runtime, PROCESSOR_SERVICE)) {
            throw new IllegalStateException("Runtime artifact contains annotation processor metadata");
        }
        if (!containsExact(codegen, PROCESSOR_SERVICE)) {
            throw new IllegalStateException("Codegen artifact is missing annotation processor metadata");
        }
    }

    private static boolean contains(Path jar, String prefix) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.stream().anyMatch(entry -> entry.getName().startsWith(prefix));
        }
    }

    private static boolean containsExact(Path jar, String name) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.getJarEntry(name) != null;
        }
    }
}
