package com.reactor.rust.cache.codegen;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Generates typed projection readers without runtime reflection or registry lookups. */
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedAnnotationTypes("com.reactor.rust.cache.projection.GenerateProjectionReader")
public final class ProjectionReaderProcessor extends AbstractProcessor {

    private static final String GENERATE_READER =
            "com.reactor.rust.cache.projection.GenerateProjectionReader";
    private static final String ID_READ = "com.reactor.rust.cache.projection.ProjectionIdRead";
    private static final String INDEX_READ = "com.reactor.rust.cache.projection.ProjectionIndexRead";
    private static final String META_READ = "com.reactor.rust.cache.projection.ProjectionMetaRead";
    private static final String METRICS_READ = "com.reactor.rust.cache.projection.CacheMetricsRead";
    private final Set<String> generated = new HashSet<>();

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        TypeElement marker = processingEnv.getElementUtils().getTypeElement(
                "com.reactor.rust.cache.projection.GenerateProjectionReader");
        if (marker == null) return false;
        for (Element element : roundEnv.getElementsAnnotatedWith(marker)) {
            if (!(element instanceof TypeElement contract) || contract.getKind() != ElementKind.INTERFACE) {
                error(element, "@GenerateProjectionReader requires an interface");
                continue;
            }
            generate(contract);
        }
        return false;
    }

    private void generate(TypeElement contract) {
        if (!contract.getTypeParameters().isEmpty()) {
            error(contract, "Generic projection reader interfaces are not supported");
            return;
        }
        var annotation = annotation(contract, GENERATE_READER);
        String packageName = processingEnv.getElementUtils().getPackageOf(contract)
                .getQualifiedName().toString();
        String configuredName = value(annotation, "generatedName");
        String simpleName = configuredName.isBlank()
                ? contract.getSimpleName() + "Generated"
                : configuredName.trim();
        if (!SourceVersion.isIdentifier(simpleName)) {
            error(contract, "generatedName must be a Java identifier: " + simpleName);
            return;
        }
        String rootPrefix = value(annotation, "rootPrefix").trim();
        if (rootPrefix.isEmpty()) {
            error(contract, "rootPrefix must not be blank");
            return;
        }
        String qualifiedName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        if (!generated.add(qualifiedName)) return;
        List<ExecutableElement> methods = readerMethods(contract);
        if (methods.isEmpty()) {
            error(contract, "Projection reader interface has no annotated abstract methods");
            return;
        }
        validate(methods);
        try {
            JavaFileObject source = processingEnv.getFiler().createSourceFile(qualifiedName, contract);
            try (Writer writer = source.openWriter()) {
                writeSource(writer, packageName, simpleName, contract, methods, rootPrefix);
            }
            if (Boolean.parseBoolean(value(annotation, "restBean"))) {
                writeRestConfiguration(packageName, simpleName, contract, rootPrefix);
            }
        } catch (IOException failure) {
            error(contract, "Failed to generate projection reader: " + failure.getMessage());
        }
    }

    private List<ExecutableElement> readerMethods(TypeElement contract) {
        Map<String, ExecutableElement> methods = new LinkedHashMap<>();
        collectReaderMethods(contract, methods, new HashSet<>());
        return List.copyOf(methods.values());
    }

    private void collectReaderMethods(
            TypeElement contract,
            Map<String, ExecutableElement> methods,
            Set<String> visited) {
        if (!visited.add(contract.getQualifiedName().toString())) return;
        for (TypeMirror parentMirror : contract.getInterfaces()) {
            Element parent = processingEnv.getTypeUtils().asElement(parentMirror);
            if (parent instanceof TypeElement parentInterface) {
                if (!parentInterface.getTypeParameters().isEmpty()) {
                    error(contract, "Generic projection reader parent interfaces are not supported: "
                            + parentInterface.getQualifiedName());
                    continue;
                }
                collectReaderMethods(parentInterface, methods, visited);
            }
        }
        for (ExecutableElement method : ElementFilter.methodsIn(contract.getEnclosedElements())) {
            if (method.getModifiers().contains(Modifier.ABSTRACT)
                    && !method.getModifiers().contains(Modifier.STATIC)) {
                methods.put(methodSignature(method), method);
            }
        }
    }

    private static String methodSignature(ExecutableElement method) {
        List<String> parameters = new ArrayList<>(method.getParameters().size());
        method.getParameters().forEach(parameter -> parameters.add(parameter.asType().toString()));
        return method.getSimpleName() + "(" + String.join(",", parameters) + ")";
    }

    private void writeRestConfiguration(
            String packageName,
            String generatedReader,
            TypeElement contract,
            String rootPrefix) throws IOException {
        String simpleName = contract.getSimpleName() + "__ReactorConfiguration";
        String qualifiedName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        JavaFileObject source = processingEnv.getFiler().createSourceFile(qualifiedName, contract);
        try (Writer out = source.openWriter()) {
            if (!packageName.isEmpty()) {
                out.write("package " + packageName + ";\n\n");
            }
            out.write("@com.reactor.rust.di.annotation.Configuration\n");
            out.write("public final class " + simpleName + " {\n");
            out.write("    @com.reactor.rust.di.annotation.Bean\n");
            out.write("    public " + contract.getQualifiedName() + " "
                    + beanName(contract.getSimpleName().toString())
                    + "(com.reactor.rust.cache.core.RustCache cache) {\n");
            out.write("        return " + generatedReader + ".create(cache, "
                    + "com.reactor.rust.cache.config.CacheProperties.from("
                    + "com.reactor.rust.config.PropertiesLoader.getAll()));\n");
            out.write("    }\n");
            out.write("}\n");
        }
    }

    private void writeSource(
            Writer out,
            String packageName,
            String simpleName,
            TypeElement contract,
            List<ExecutableElement> methods,
            String rootPrefix) throws IOException {
        if (!packageName.isEmpty()) out.write("package " + packageName + ";\n\n");
        out.write("public final class " + simpleName + " implements "
                + contract.getQualifiedName() + " {\n");
        out.write("    private final com.reactor.rust.cache.core.RustCache cache;\n");
        for (int index = 0; index < methods.size(); index++) {
            ExecutableElement method = methods.get(index);
            if (annotation(method, ID_READ) != null || annotation(method, META_READ) != null) {
                out.write("    private final com.reactor.rust.cache.projection.VersionedJsonProjectionReaders.BoundProjection bound"
                        + index + ";\n");
            } else if (annotation(method, INDEX_READ) != null) {
                out.write("    private final com.reactor.rust.cache.projection.VersionedJsonProjectionReaders.BoundIndex bound"
                        + index + ";\n");
            }
        }
        out.write("\n    private " + simpleName + "(com.reactor.rust.cache.core.RustCache cache, "
                + "com.reactor.rust.cache.projection.VersionedJsonProjectionReaders readers) {\n");
        out.write("        this.cache = cache;\n");
        for (int index = 0; index < methods.size(); index++) {
            ExecutableElement method = methods.get(index);
            var id = annotation(method, ID_READ);
            var meta = annotation(method, META_READ);
            var indexed = annotation(method, INDEX_READ);
            if (id != null) {
                out.write("        this.bound" + index + " = readers.bind((java.util.function.Supplier<String>) () -> \""
                        + escape(value(id, "projection")) + "\");\n");
            } else if (meta != null) {
                out.write("        this.bound" + index + " = readers.bind((java.util.function.Supplier<String>) () -> \""
                        + escape(value(meta, "projection")) + "\");\n");
            } else if (indexed != null) {
                out.write("        this.bound" + index + " = readers.bind((java.util.function.Supplier<String>) () -> \""
                        + escape(value(indexed, "projection")) + "\").bind((java.util.function.Supplier<String>) () -> \""
                        + escape(value(indexed, "index")) + "\");\n");
            }
        }
        out.write("    }\n\n");
        out.write("    public static " + contract.getQualifiedName() + " create("
                + "com.reactor.rust.cache.core.RustCache cache, "
                + "com.reactor.rust.cache.config.CacheProperties properties) {\n");
        out.write("        var settings = com.reactor.rust.cache.projection.CacheReaderProjectionSettings"
                + ".resolveAll(properties, \"" + escape(rootPrefix) + "\");\n");
        out.write("        long versionCacheMillis = properties.getLong(\""
                + escape(rootPrefix) + ".version-cache-ms\");\n");
        out.write("        var readers = com.reactor.rust.cache.projection.VersionedJsonProjectionReaders"
                + ".create(cache, settings, versionCacheMillis);\n");
        out.write("        return new " + simpleName + "(cache, readers);\n");
        out.write("    }\n\n");
        for (int index = 0; index < methods.size(); index++) {
            writeMethod(out, methods.get(index), index);
        }
        out.write("}\n");
    }

    private void writeMethod(Writer out, ExecutableElement method, int index) throws IOException {
        String returnType = method.getReturnType().toString();
        String name = method.getSimpleName().toString();
        out.write("    @Override\n    public " + returnType + " " + name + "(");
        for (int parameter = 0; parameter < method.getParameters().size(); parameter++) {
            if (parameter > 0) out.write(", ");
            var value = method.getParameters().get(parameter);
            out.write(value.asType() + " " + value.getSimpleName());
        }
        out.write(") {\n");
        if (annotation(method, ID_READ) != null) {
            out.write("        return bound" + index + ".getById("
                    + method.getParameters().get(0).getSimpleName() + ");\n");
        } else if (annotation(method, META_READ) != null) {
            out.write("        return bound" + index + ".getMeta();\n");
        } else if (annotation(method, METRICS_READ) != null) {
            out.write("        return cache.metricsJson();\n");
        } else {
            var indexed = annotation(method, INDEX_READ);
            String parameter = method.getParameters().get(0).getSimpleName().toString();
            String defaultValue = value(indexed, "defaultValue");
            boolean trim = Boolean.parseBoolean(value(indexed, "trim"));
            out.write("        String normalizedKey = " + (trim
                    ? parameter + " == null ? null : " + parameter + ".trim()"
                    : parameter) + ";\n");
            if (!defaultValue.isEmpty()) {
                out.write("        if (normalizedKey == null || normalizedKey.isBlank()) "
                        + "normalizedKey = \"" + escape(defaultValue) + "\";\n");
            }
            out.write("        return bound" + index + ".get(normalizedKey);\n");
        }
        out.write("    }\n\n");
    }

    private void validate(List<ExecutableElement> methods) {
        for (ExecutableElement method : methods) {
            int annotations = (annotation(method, ID_READ) != null ? 1 : 0)
                    + (annotation(method, INDEX_READ) != null ? 1 : 0)
                    + (annotation(method, META_READ) != null ? 1 : 0)
                    + (annotation(method, METRICS_READ) != null ? 1 : 0);
            if (annotations != 1) {
                error(method, "Projection reader method must declare exactly one cache read annotation");
                continue;
            }
            if (annotation(method, ID_READ) != null
                    && (method.getParameters().size() != 1
                    || method.getParameters().get(0).asType().getKind() != TypeKind.LONG)) {
                error(method, "@ProjectionIdRead requires one long parameter");
            }
            if (annotation(method, INDEX_READ) != null
                    && (method.getParameters().size() != 1
                    || !method.getParameters().get(0).asType().toString().equals(String.class.getName()))) {
                error(method, "@ProjectionIndexRead requires one String parameter");
            }
            if ((annotation(method, META_READ) != null || annotation(method, METRICS_READ) != null)
                    && !method.getParameters().isEmpty()) {
                error(method, "Metadata and metrics reader methods must not declare parameters");
            }
            if (!method.getReturnType().toString().equals("com.reactor.rust.cache.api.CacheReadResult")
                    && annotation(method, METRICS_READ) == null) {
                error(method, "Projection reader methods must return CacheReadResult");
            }
            if (annotation(method, METRICS_READ) != null
                    && !method.getReturnType().toString().equals(String.class.getName())) {
                error(method, "@CacheMetricsRead method must return String");
            }
        }
    }

    private javax.lang.model.element.AnnotationMirror annotation(Element element, String type) {
        return element.getAnnotationMirrors().stream()
                .filter(annotation -> annotation.getAnnotationType().toString().equals(type))
                .findFirst()
                .orElse(null);
    }

    private String value(javax.lang.model.element.AnnotationMirror annotation, String name) {
        return processingEnv.getElementUtils().getElementValuesWithDefaults(annotation).entrySet().stream()
                .filter(entry -> entry.getKey().getSimpleName().contentEquals(name))
                .map(entry -> entry.getValue().getValue().toString())
                .findFirst()
                .orElse("");
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String beanName(String simpleName) {
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }
}
