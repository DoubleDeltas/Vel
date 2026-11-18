package com.doubledeltas.vel.processor;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;

import com.doubledeltas.vel.core.VelDiagnostic;
import com.doubledeltas.vel.core.VelFrontend;
import com.doubledeltas.vel.core.codegen.Boundary;
import com.doubledeltas.vel.core.codegen.ModelBoundaries;
import com.doubledeltas.vel.core.codegen.JavaCodeGenerator;
import com.doubledeltas.vel.core.codegen.JavaTypes;
import com.doubledeltas.vel.core.codegen.VelCompileError;
import com.doubledeltas.vel.core.concretization.ScalarType;
import com.doubledeltas.vel.core.type.Card;
import com.doubledeltas.vel.core.type.DimSpec;
import com.doubledeltas.vel.core.type.VelTypeException;

/**
 * Handles {@code @Vel} methods (style B): checks each VEL body against the method's Java types and
 * generates {@code <Owner>Vel}, a class implementing every {@code @Vel} method of the owner.
 * <p>
 * For an interface the class implements it; for an abstract class it extends it. When the owner has
 * other abstract methods, the generated class is abstract too. Supported Java types: primitives and
 * their arrays, wrappers, {@code List}s and arrays of those, records and classes with
 * {@code componentN()} (java.md 1, 2), sized by {@code @Dim}.
 */
@SupportedAnnotationTypes(VelProcessor.VEL_ANNOTATION)
public final class VelProcessor extends AbstractProcessor {

    static final String VEL_ANNOTATION = "com.doubledeltas.vel.Vel";
    static final String DIM_ANNOTATION = "com.doubledeltas.vel.Dim";

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Map<TypeElement, List<String>> generated = new LinkedHashMap<>();
        Map<TypeElement, Boolean> failed = new LinkedHashMap<>();
        for (TypeElement annotation : annotations) {
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                ExecutableElement method = (ExecutableElement) element; // @Target(METHOD) guarantees this
                String code = processMethod(method);
                Element owner = method.getEnclosingElement();
                if (owner instanceof TypeElement type) {
                    generated.computeIfAbsent(type, t -> new ArrayList<>());
                    if (code == null) {
                        failed.put(type, true);
                    } else {
                        generated.get(type).add(code);
                    }
                }
            }
        }
        generated.forEach((owner, methods) -> {
            if (!failed.containsKey(owner)) {
                writeClass(owner, methods);
            }
        });
        return true;
    }

    /** The Java source of the implementing method, or null after reporting an error. */
    private String processMethod(ExecutableElement method) {
        Messager messager = processingEnv.getMessager();
        AnnotationMirror mirror = findMirror(method, VEL_ANNOTATION);
        AnnotationValue value = findValue(mirror, "value");

        if (!method.getModifiers().contains(Modifier.ABSTRACT)) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "@Vel method must be abstract (its body is generated from VEL)", method, mirror);
            return null;
        }
        Element owner = method.getEnclosingElement();
        if (owner.getKind() != ElementKind.INTERFACE && owner.getKind() != ElementKind.CLASS) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "@Vel method must be declared in an interface or abstract class", method, mirror);
            return null;
        }
        if (!method.getTypeParameters().isEmpty() || !((TypeElement) owner).getTypeParameters().isEmpty()) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "generic @Vel methods and owners are not supported yet", method, mirror);
            return null;
        }

        String source = (String) value.getValue();
        var result = VelFrontend.parseProgram(source);
        for (VelDiagnostic d : result.diagnostics()) {
            messager.printMessage(toKind(d), d.format(), method, mirror, value);
        }
        if (result.hasErrors()) {
            return null;
        }

        Map<String, Boundary> params = new LinkedHashMap<>();
        ModelBoundaries boundaries = new ModelBoundaries(processingEnv.getTypeUtils());
        StringBuilder signature = new StringBuilder();
        try {
            for (VariableElement p : method.getParameters()) {
                Boundary type = boundaries.of(p.asType(), p);
                params.put(p.getSimpleName().toString(), type);
                if (!signature.isEmpty()) {
                    signature.append(", ");
                }
                signature.append(p.asType()).append(' ').append(p.getSimpleName());
            }
            Boundary returnType = boundaries.of(method.getReturnType(), method);
            String body = JavaCodeGenerator.methodBody(result.tree(), params, returnType);

            StringBuilder code = new StringBuilder();
            code.append("    @Override\n    ");
            for (Modifier m : method.getModifiers()) {
                if (m != Modifier.ABSTRACT && m != Modifier.DEFAULT) {
                    code.append(m).append(' ');
                }
            }
            if (owner.getKind() == ElementKind.INTERFACE) {
                code.append("public ");
            }
            code.append(method.getReturnType()).append(' ').append(method.getSimpleName())
                    .append('(').append(signature).append(") {\n");
            for (String line : body.split("\n")) {
                code.append(line.isEmpty() ? "" : "        " + line).append('\n');
            }
            code.append("    }\n");
            return code.toString().replace("public public ", "public ");
        } catch (VelCompileError e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    VelDiagnostic.error(e.line(), e.column(), e.getMessage()).format(), method, mirror, value);
            return null;
        } catch (VelTypeException e) {
            messager.printMessage(Diagnostic.Kind.ERROR, "VEL: " + e.getMessage(), method, mirror);
            return null;
        }
    }

    private void writeClass(TypeElement owner, List<String> methods) {
        PackageElement pkg = processingEnv.getElementUtils().getPackageOf(owner);
        String simple = generatedName(owner);
        boolean allImplemented = ElementFilter.methodsIn(owner.getEnclosedElements()).stream()
                .filter(m -> m.getModifiers().contains(Modifier.ABSTRACT))
                .allMatch(m -> findOptionalMirror(m, VEL_ANNOTATION) != null);
        StringBuilder src = new StringBuilder();
        if (!pkg.isUnnamed()) {
            src.append("package ").append(pkg.getQualifiedName()).append(";\n\n");
        }
        src.append("@javax.annotation.processing.Generated(\"").append(VelProcessor.class.getName()).append("\")\n");
        src.append("public ").append(allImplemented ? "final " : "abstract ").append("class ").append(simple)
                .append(owner.getKind() == ElementKind.INTERFACE ? " implements " : " extends ")
                .append(owner.getQualifiedName()).append(" {\n");
        for (String m : methods) {
            src.append('\n').append(m);
        }
        src.append("}\n");
        String name = pkg.isUnnamed() ? simple : pkg.getQualifiedName() + "." + simple;
        try (Writer w = processingEnv.getFiler().createSourceFile(name, owner).openWriter()) {
            w.write(src.toString());
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "cannot write " + name + ": " + e.getMessage(), owner);
        }
    }

    /** {@code PhysicsVel}; for a nested owner {@code Outer_InnerVel}. */
    private static String generatedName(TypeElement owner) {
        StringBuilder name = new StringBuilder(owner.getSimpleName());
        Element e = owner.getEnclosingElement();
        while (e instanceof TypeElement t) {
            name.insert(0, t.getSimpleName() + "_");
            e = t.getEnclosingElement();
        }
        return name + "Vel";
    }

    private static AnnotationMirror findMirror(Element element, String annotation) {
        AnnotationMirror m = findOptionalMirror(element, annotation);
        if (m == null) {
            throw new IllegalStateException(annotation + " not found on " + element);
        }
        return m;
    }

    private static AnnotationMirror findOptionalMirror(Element element, String annotation) {
        for (AnnotationMirror m : element.getAnnotationMirrors()) {
            TypeElement type = (TypeElement) m.getAnnotationType().asElement();
            if (type.getQualifiedName().contentEquals(annotation)) {
                return m;
            }
        }
        return null;
    }

    private static AnnotationValue findValue(AnnotationMirror mirror, String name) {
        return mirror.getElementValues().entrySet().stream()
                .filter(e -> e.getKey().getSimpleName().contentEquals(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
    }

    private static Diagnostic.Kind toKind(VelDiagnostic d) {
        return d.severity() == VelDiagnostic.Severity.ERROR ? Diagnostic.Kind.ERROR : Diagnostic.Kind.WARNING;
    }
}
