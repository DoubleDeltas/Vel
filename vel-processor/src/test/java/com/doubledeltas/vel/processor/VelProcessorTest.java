package com.doubledeltas.vel.processor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VelProcessorTest {

    @TempDir
    Path out;

    @Test
    void generatesTheImplementingClass() throws Exception {
        var errors = compile("""
                import com.doubledeltas.vel.Vel;
                import com.doubledeltas.vel.Dim;
                public interface Physics {
                    @Vel(\"""
                        # constant-acceleration step
                        return p + v * dt + 0.5 * a * dt^2
                        \""")
                    double position(double p, double v, double a, double dt);

                    @Vel("p + v * dt")
                    @Dim(3) double[] step(@Dim(3) double[] p, @Dim(3) double[] v, double dt);

                    default double kineticEnergy(double m, double v) {
                        return 0.5 * m * v * v;
                    }
                }
                """);
        assertTrue(errors.isEmpty(), errors::toString);
        String generated = Files.readString(out.resolve("PhysicsVel.java"));
        assertTrue(generated.contains("public final class PhysicsVel implements Physics"), generated);
        try (var loader = new URLClassLoader(new java.net.URL[] {out.toUri().toURL()}, getClass().getClassLoader())) {
            Object physics = loader.loadClass("PhysicsVel").getConstructor().newInstance();
            Class<?> type = physics.getClass();
            assertEquals(1 + 2 * 3 + 0.5 * 4 * 9.0,
                    type.getMethod("position", double.class, double.class, double.class, double.class)
                            .invoke(physics, 1.0, 2.0, 4.0, 3.0));
            assertArrayEquals(new double[] {1.5, 2, 2.5},
                    (double[]) type.getMethod("step", double[].class, double[].class, double.class)
                            .invoke(physics, new double[] {1, 1, 1}, new double[] {1, 2, 3}, 0.5));
        }
    }

    @Test
    void abstractClassWithOtherAbstractMethodsGivesAnAbstractClass() throws Exception {
        var errors = compile("""
                package demo;
                import com.doubledeltas.vel.Vel;
                public abstract class Shapes {
                    @Vel("w * h")
                    protected abstract int area(int w, int h);
                    public abstract String name();
                }
                """);
        assertTrue(errors.isEmpty(), errors::toString);
        String generated = Files.readString(out.resolve("demo/ShapesVel.java"));
        assertTrue(generated.contains("public abstract class ShapesVel extends demo.Shapes"), generated);
        assertTrue(generated.contains("protected int area(int w, int h)"), generated);
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
    void typeErrorIsReportedWithItsVelPosition() {
        var errors = compile("""
                import com.doubledeltas.vel.Vel;
                interface Logic {
                    @Vel(\"""
                        x = 1
                        return x + b
                        \""")
                    double f(boolean b);
                }
                """);
        assertEquals(1, errors.size(), errors::toString);
        assertTrue(errors.getFirst().getMessage(null).startsWith("VEL 2:"), errors.getFirst().getMessage(null));
    }

    @Test
    void unsupportedJavaTypeIsReported() {
        var errors = compile("""
                import com.doubledeltas.vel.Vel;
                interface Text {
                    @Vel("s")
                    String f(String s);
                }
                """);
        assertEquals(1, errors.size(), errors::toString);
        assertTrue(errors.getFirst().getMessage(null).contains("not supported"), errors.getFirst().getMessage(null));
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

    private List<Diagnostic<? extends JavaFileObject>> compile(String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        String name = source.contains("class Shapes") ? "Shapes" : source.contains("interface Logic") ? "Logic"
                : source.contains("interface Text") ? "Text" : "Physics";
        var file = new SimpleJavaFileObject(URI.create("string:///" + name + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        var task = compiler.getTask(null, null, diagnostics,
                List.of("-classpath", System.getProperty("java.class.path"), "-d", out.toString(), "-s", out.toString()),
                null, List.of(file));
        task.setProcessors(List.of(new VelProcessor()));
        task.call();
        return diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .toList();
    }
}
