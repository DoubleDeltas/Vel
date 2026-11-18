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
    void wrappersListsAndComponentObjects() throws Exception {
        var errors = compile("""
                import com.doubledeltas.vel.Vel;
                import com.doubledeltas.vel.Dim;
                import java.util.List;
                public interface Shapes {
                    record Point(double x, double y) {}
                    record Mixed(int a, double b) {}
                    record Segment(@Dim(2) double[] from, @Dim(2) double[] to) {}
                    final class Pair2 {   // like Kotlin's Pair seen from Java
                        private final Double a, b;
                        public Pair2(Double a, Double b) { this.a = a; this.b = b; }
                        public Double component1() { return a; }
                        public Double component2() { return b; }
                    }

                    @Vel("x * 2")
                    Double twice(Double x);
                    @Vel("u + v")
                    List<Double> add(List<Double> u, List<Double> v);
                    @Vel("2 * a")
                    Integer[] doubled(Integer[] a);
                    @Vel("m'")
                    List<List<Double>> transposed(List<List<Double>> m);
                    @Vel("(-p[1], p[0])")
                    Point rotate(Point p);
                    @Vel("q[0] + q[1]")
                    double sum(Mixed q);
                    @Vel("(s[1, :] - s[0, :])'")
                    double[] direction(Segment s);
                    @Vel("(s[1, 0] - s[0, 0], s[1, 1] - s[0, 1])")
                    Point delta(Segment s);
                    @Vel("(p[1], p[0])")
                    Pair2 swap(Pair2 p);
                    @Vel("x")
                    double unboxed(Double x);
                }
                """);
        assertTrue(errors.isEmpty(), errors::toString);
        try (var loader = new URLClassLoader(new java.net.URL[] {out.toUri().toURL()}, getClass().getClassLoader())) {
            Object shapes = loader.loadClass("ShapesVel").getConstructor().newInstance();
            Class<?> type = shapes.getClass();
            java.util.function.BiFunction<String, Object[], Object> call = (name, args) -> {
                for (var m : type.getMethods()) {
                    if (m.getName().equals(name)) {
                        try {
                            return m.invoke(shapes, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw new RuntimeException(e.getCause());
                        } catch (IllegalAccessException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
                throw new AssertionError(name);
            };
            assertEquals(6.0, call.apply("twice", new Object[] {3.0}));
            assertEquals(List.of(5.0, 7.0), call.apply("add", new Object[] {List.of(1.0, 2.0), List.of(4.0, 5.0)}));
            assertArrayEquals(new Integer[] {2, 4}, (Integer[]) call.apply("doubled", new Object[] {new Integer[] {1, 2}}));
            // a List<List<Double>> m is Mat(n, m) with m.get(j).get(i) = M[i, j]; transposing swaps the lists
            assertEquals(List.of(List.of(1.0, 3.0), List.of(2.0, 4.0)),
                    call.apply("transposed", new Object[] {List.of(List.of(1.0, 2.0), List.of(3.0, 4.0))}));
            Class<?> point = loader.loadClass("Shapes$Point");
            Object p = point.getConstructor(double.class, double.class).newInstance(1.0, 2.0);
            Object rotated = call.apply("rotate", new Object[] {p});
            assertEquals(point.getConstructor(double.class, double.class).newInstance(-2.0, 1.0), rotated);
            Object mixed = loader.loadClass("Shapes$Mixed").getConstructor(int.class, double.class).newInstance(1, 0.5);
            assertEquals(1.5, call.apply("sum", new Object[] {mixed}));
            Object segment = loader.loadClass("Shapes$Segment").getConstructor(double[].class, double[].class)
                    .newInstance(new double[] {1, 1}, new double[] {4, 5});
            assertArrayEquals(new double[] {3, 4}, (double[]) call.apply("direction", new Object[] {segment}));
            assertEquals(point.getConstructor(double.class, double.class).newInstance(3.0, 4.0),
                    call.apply("delta", new Object[] {segment}));
            Class<?> pair = loader.loadClass("Shapes$Pair2");
            Object swapped = call.apply("swap", new Object[] {pair.getConstructor(Double.class, Double.class).newInstance(1.0, 2.0)});
            assertEquals(2.0, pair.getMethod("component1").invoke(swapped));
            assertEquals(1.0, pair.getMethod("component2").invoke(swapped));
            // null is null: NullPointerException (java.md 2.2)
            RuntimeException e = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                    () -> call.apply("unboxed", new Object[] {null}));
            assertTrue(e.getCause() instanceof NullPointerException, e.getCause().toString());
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
        String name = source.contains("abstract class Shapes") ? "Shapes" : source.contains("interface Shapes") ? "Shapes" : source.contains("interface Logic") ? "Logic"
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
