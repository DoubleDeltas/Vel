package com.doubledeltas.vel.processor;

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
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

import com.doubledeltas.vel.core.VelDiagnostic;
import com.doubledeltas.vel.core.VelFrontend;

/**
 * Handles {@code @Vel} methods (style B).
 * <p>
 * Current stage: validates placement and VEL syntax, reporting errors at the annotation
 * value. Next stage: type-check against parameter types and generate the implementing class.
 */
@SupportedAnnotationTypes(VelProcessor.VEL_ANNOTATION)
public final class VelProcessor extends AbstractProcessor {

    static final String VEL_ANNOTATION = "com.doubledeltas.vel.Vel";

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (TypeElement annotation : annotations) {
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                processMethod((ExecutableElement) element); // @Target(METHOD) guarantees this
            }
        }
        return true;
    }

    private void processMethod(ExecutableElement method) {
        Messager messager = processingEnv.getMessager();
        AnnotationMirror mirror = findVelMirror(method);
        AnnotationValue value = findValue(mirror);

        if (!method.getModifiers().contains(Modifier.ABSTRACT)) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "@Vel method must be abstract (its body is generated from VEL)", method, mirror);
            return;
        }
        Element owner = method.getEnclosingElement();
        if (owner.getKind() != ElementKind.INTERFACE && owner.getKind() != ElementKind.CLASS) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "@Vel method must be declared in an interface or abstract class", method, mirror);
            return;
        }

        String source = (String) value.getValue();
        var result = VelFrontend.parseProgram(source);
        for (VelDiagnostic d : result.diagnostics()) {
            messager.printMessage(toKind(d), d.format(), method, mirror, value);
        }
        // TODO: type check + code generation into <Owner>Vel via processingEnv.getFiler()
    }

    private static AnnotationMirror findVelMirror(ExecutableElement method) {
        for (AnnotationMirror m : method.getAnnotationMirrors()) {
            TypeElement type = (TypeElement) m.getAnnotationType().asElement();
            if (type.getQualifiedName().contentEquals(VEL_ANNOTATION)) {
                return m;
            }
        }
        throw new IllegalStateException("@Vel not found on " + method);
    }

    private static AnnotationValue findValue(AnnotationMirror mirror) {
        return mirror.getElementValues().entrySet().stream()
                .filter(e -> e.getKey().getSimpleName().contentEquals("value"))
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
    }

    private static Diagnostic.Kind toKind(VelDiagnostic d) {
        return d.severity() == VelDiagnostic.Severity.ERROR ? Diagnostic.Kind.ERROR : Diagnostic.Kind.WARNING;
    }
}
