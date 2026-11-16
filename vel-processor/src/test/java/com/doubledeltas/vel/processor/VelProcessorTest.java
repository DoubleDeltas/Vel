package com.doubledeltas.vel.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;

class VelProcessorTest {

    @Test
    void validVelCompiles() {
        var errors = compile("""
                import com.doubledeltas.vel.Vel;
                interface Physics {
                    @Vel(\"""
                        return p + v * dt + 0.5 * a * dt^2
                        \""")
                    double position(double p, double v, double a, double dt);
                }
                """);
        assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void syntaxErrorIsReportedAtAnnotationValue() {
        var errors = compile("""
                import com.doubledeltas.vel.Vel;
                interface Physics {
                    @Vel("return p + * v")
                    double f(double p, double v);
                }
                """);
        assertEquals(1, errors.size(), errors::toString);
        var d = errors.getFirst();
        assertTrue(d.getMessage(null).startsWith("VEL 1:"), d.getMessage(null));
        assertEquals(3, d.getLineNumber()); // the @Vel line, not the method line
    }

    @Test
    void concreteMethodIsRejected() {
        var errors = compile("""
                import com.doubledeltas.vel.Vel;
                class Physics {
                    @Vel("return 1")
                    double f() { return 0; }
                }
                """);
        assertEquals(1, errors.size(), errors::toString);
        assertTrue(errors.getFirst().getMessage(null).contains("must be abstract"));
    }

    private static List<Diagnostic<? extends JavaFileObject>> compile(String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var file = new SimpleJavaFileObject(URI.create("string:///Physics.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        var task = compiler.getTask(null, null, diagnostics,
                List.of("-proc:only", "-classpath", System.getProperty("java.class.path")),
                null, List.of(file));
        task.setProcessors(List.of(new VelProcessor()));
        task.call();
        return diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .toList();
    }
}
