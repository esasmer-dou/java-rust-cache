package com.reactor.rust.cache.codegen;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Build-time projection registry generator. Kept out of the runtime JAR. */
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class ProjectionRegistryProcessor extends AbstractProcessor {

    private static final String ANNOTATION =
            "com.reactor.rust.cache.projection.GenerateProjectionRegistry";
    private final Set<String> generatedTypes = new HashSet<>();

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(ANNOTATION);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        TypeElement marker = processingEnv.getElementUtils().getTypeElement(ANNOTATION);
        if (marker == null) {
            return false;
        }
        for (Element element : roundEnv.getElementsAnnotatedWith(marker)) {
            if (!(element instanceof TypeElement owner)) {
                error(element, "@GenerateProjectionRegistry can only target a class");
                continue;
            }
            generate(owner);
        }
        return true;
    }

    private void generate(TypeElement owner) {
        TypeElement projectionEnum = projectionEnum(owner);
        if (projectionEnum == null) {
            return;
        }
        if (projectionEnum.getKind() != ElementKind.ENUM) {
            error(owner, "@GenerateProjectionRegistry value must be an enum");
            return;
        }

        List<String> constants = projectionEnum.getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.ENUM_CONSTANT)
                .map(element -> element.getSimpleName().toString())
                .toList();
        if (constants.isEmpty()) {
            error(owner, "Projection enum must declare at least one constant");
            return;
        }

        List<String> methodNames = new ArrayList<>(constants.size());
        for (String constant : constants) {
            String methodName = "write" + upperCamel(constant);
            if (!hasWriterMethod(owner, methodName)) {
                error(owner, "Missing projection writer method " + methodName
                        + "(VersionedJsonProjectionMaterializer.ProjectionTarget)");
                return;
            }
            methodNames.add(methodName);
        }

        String packageName = processingEnv.getElementUtils().getPackageOf(owner)
                .getQualifiedName().toString();
        String ownerName = owner.getQualifiedName().toString();
        String simpleName = owner.getSimpleName() + "ProjectionRegistry";
        String generatedName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        if (!generatedTypes.add(generatedName)) {
            return;
        }

        try {
            JavaFileObject source = processingEnv.getFiler().createSourceFile(generatedName, owner);
            try (Writer writer = source.openWriter()) {
                if (!packageName.isEmpty()) {
                    writer.write("package " + packageName + ";\n\n");
                }
                writer.write("public final class " + simpleName + " {\n");
                writer.write("    private " + simpleName + "() {}\n\n");
                writer.write("    public static com.reactor.rust.cache.projection.VersionedJsonProjectionMaterializer create(\n");
                writer.write("            " + ownerName + " owner,\n");
                writer.write("            com.reactor.rust.cache.core.RustCache cache,\n");
                writer.write("            java.util.List<com.reactor.rust.cache.projection.CacheWriterProjectionSettings> settings,\n");
                writer.write("            int batchSize) {\n");
                writer.write("        var builder = com.reactor.rust.cache.projection.VersionedJsonProjectionMaterializer"
                        + ".builder(cache, settings, batchSize);\n");
                for (int index = 0; index < constants.size(); index++) {
                    writer.write("        builder.projection(" + projectionEnum.getQualifiedName() + "."
                            + constants.get(index) + ", owner::" + methodNames.get(index) + ");\n");
                }
                writer.write("        return builder.build();\n");
                writer.write("    }\n");
                writer.write("}\n");
            }
        } catch (IOException failure) {
            error(owner, "Failed to generate projection registry: " + failure.getMessage());
        }
    }

    private TypeElement projectionEnum(TypeElement owner) {
        for (AnnotationMirror mirror : owner.getAnnotationMirrors()) {
            if (!mirror.getAnnotationType().toString().equals(ANNOTATION)) {
                continue;
            }
            Map<? extends ExecutableElement, ? extends AnnotationValue> values =
                    processingEnv.getElementUtils().getElementValuesWithDefaults(mirror);
            for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : values.entrySet()) {
                if (entry.getKey().getSimpleName().contentEquals("value")) {
                    Object value = entry.getValue().getValue();
                    if (value instanceof TypeMirror typeMirror
                            && processingEnv.getTypeUtils().asElement(typeMirror) instanceof TypeElement type) {
                        return type;
                    }
                }
            }
        }
        error(owner, "Projection enum could not be resolved");
        return null;
    }

    private static boolean hasWriterMethod(TypeElement owner, String methodName) {
        return owner.getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.METHOD)
                .map(ExecutableElement.class::cast)
                .anyMatch(method -> method.getSimpleName().contentEquals(methodName)
                        && method.getParameters().size() == 1);
    }

    private static String upperCamel(String constant) {
        StringBuilder value = new StringBuilder(constant.length());
        boolean uppercase = true;
        for (int index = 0; index < constant.length(); index++) {
            char current = constant.charAt(index);
            if (current == '_') {
                uppercase = true;
            } else if (uppercase) {
                value.append(Character.toUpperCase(current));
                uppercase = false;
            } else {
                value.append(Character.toLowerCase(current));
            }
        }
        return value.toString();
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
