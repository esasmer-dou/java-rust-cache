package com.reactor.rust.cache.codegen;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.HashSet;
import java.util.Set;

/** Generates REST DI configuration while keeping cache creation out of application code. */
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedAnnotationTypes("com.reactor.rust.cache.integration.EnableRustCache")
public final class RustCacheConfigurationProcessor extends AbstractProcessor {

    private static final String ENABLE = "com.reactor.rust.cache.integration.EnableRustCache";
    private final Set<String> generated = new HashSet<>();

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        TypeElement marker = processingEnv.getElementUtils().getTypeElement(
                "com.reactor.rust.cache.integration.EnableRustCache");
        if (marker == null) {
            return false;
        }
        for (Element element : roundEnv.getElementsAnnotatedWith(marker)) {
            if (!(element instanceof TypeElement application) || element.getKind() != ElementKind.CLASS) {
                error(element, "@EnableRustCache requires an application class");
                continue;
            }
            generate(application);
        }
        return false;
    }

    private void generate(TypeElement application) {
        AnnotationMirror annotation = annotation(application, ENABLE);
        String packageName = processingEnv.getElementUtils().getPackageOf(application)
                .getQualifiedName().toString();
        String configuredName = value(annotation, "generatedConfigurationName");
        String simpleName = configuredName.isBlank()
                ? application.getSimpleName() + "__RustCacheConfiguration"
                : configuredName.trim();
        if (!SourceVersion.isIdentifier(simpleName)) {
            error(application, "generatedConfigurationName must be a Java identifier: " + simpleName);
            return;
        }
        String qualifiedName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        if (!generated.add(qualifiedName)) {
            return;
        }
        try {
            JavaFileObject source = processingEnv.getFiler().createSourceFile(qualifiedName, application);
            try (Writer out = source.openWriter()) {
                if (!packageName.isEmpty()) {
                    out.write("package " + packageName + ";\n\n");
                }
                out.write("@com.reactor.rust.di.annotation.Configuration\n");
                out.write("public final class " + simpleName + " {\n");
                out.write("    private com.reactor.rust.cache.core.RustCache cache;\n\n");
                out.write("    @com.reactor.rust.di.annotation.Bean\n");
                out.write("    public com.reactor.rust.cache.core.RustCache rustCache() {\n");
                out.write("        cache = com.reactor.rust.cache.core.RustCaches.create("
                        + "com.reactor.rust.config.PropertiesLoader.getAll());\n");
                out.write("        return cache;\n");
                out.write("    }\n\n");
                out.write("    @com.reactor.rust.di.annotation.PreDestroy\n");
                out.write("    public void close() {\n");
                out.write("        if (cache != null) cache.close();\n");
                out.write("    }\n");
                out.write("}\n");
            }
        } catch (IOException failure) {
            error(application, "Failed to generate Rust cache configuration: " + failure.getMessage());
        }
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private AnnotationMirror annotation(Element element, String type) {
        return element.getAnnotationMirrors().stream()
                .filter(candidate -> candidate.getAnnotationType().toString().equals(type))
                .findFirst()
                .orElseThrow();
    }

    private String value(AnnotationMirror annotation, String name) {
        return processingEnv.getElementUtils().getElementValuesWithDefaults(annotation).entrySet().stream()
                .filter(entry -> entry.getKey().getSimpleName().contentEquals(name))
                .map(entry -> entry.getValue().getValue().toString())
                .findFirst()
                .orElse("");
    }
}
