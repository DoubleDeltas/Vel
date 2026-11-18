package com.doubledeltas.vel.javac;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLClassLoader;
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

class VelJavacPluginTest {

    @TempDir
    Path out;

    // ---- rewriting and running ---------------------------------------------------------------

    @Test
    void inlineExpressionIsReplacedByGeneratedCode() throws Exception {
        String source = """
                import static com.doubledeltas.vel.VelInline.vel;
                public class Energy {
                    public static double ke(double m, double v) {
                        double half = 0.5;
                        return vel("half * m * v^2");
                    }
                }
                """;
        assertNoErrors(compile(source));
        assertEquals(0.5 * 2 * 9, call("Energy", "ke", 2.0, 3.0));
    }

    @Test
    void arraysFieldsAndTargets() throws Exception {
        String source = """
                import static com.doubledeltas.vel.VelInline.vel;
                import com.doubledeltas.vel.Dim;
                public class Kinematics {
                    static double gravity = 9.8;
                    public static double[] step(@Dim(3) double[] p, @Dim(3) double[] v, double dt) {
                        @Dim(3) double[] next = vel("p + v * dt");
                        return next;
                    }
                    public static float fall(float t) {
                        float h = vel("gravity * t^2 / 2");   // computed in F64, assigned as float
                        return h;
                    }
                    public static double dot(double[] u, double[] v) {
                        return vel("u' * v");
                    }
                    public static int last(int[] a) {
                        int x = 0;
                        x = vel("a[end - 1]");
                        return x;
                    }
                    public static double inLambda(double y) {
                        java.util.function.DoubleUnaryOperator f = (double x) -> vel("x * y");
                        return f.applyAsDouble(2);
                    }
                }
                """;
        assertNoErrors(compile(source));
        assertArrayEquals(new double[] {1.5, 2, 2.5}, (double[]) call("Kinematics", "step",
                new double[] {1, 1, 1}, new double[] {1, 2, 3}, 0.5));
        assertEquals((float) (9.8 * 4 / 2), call("Kinematics", "fall", 2f));
        assertEquals(32.0, call("Kinematics", "dot", new double[] {1, 2, 3}, new double[] {4, 5, 6}));
        assertEquals(30, call("Kinematics", "last", (Object) new int[] {10, 20, 30}));
        assertEquals(6.0, call("Kinematics", "inLambda", 3.0));
    }

    @Test
    void qualifiedCallIsRewrittenToo() throws Exception {
        String source = """
                public class Q {
                    public static int twice(int n) { return com.doubledeltas.vel.VelInline.vel("2 * n"); }
                }
                """;
        assertNoErrors(compile(source));
        assertEquals(14, call("Q", "twice", 7));
    }

    // ---- errors -------------------------------------------------------------------------------

    @Test
    void syntaxErrorIsReportedAtLiteral() {
        var errors = compile("""
                import static com.doubledeltas.vel.VelInline.vel;
                class Energy {
                    double ke(double m, double v) {
                        return vel("0.5 * (m * v");
                    }
                }
                """);
        assertEquals(1, errors.size(), errors::toString);
        assertTrue(errors.getFirst().getMessage(null).startsWith("VEL 1:"));
        assertEquals(4, errors.getFirst().getLineNumber());
    }

    @Test
    void typeErrorIsReportedAtLiteral() {
        var errors = compile("""
                import static com.doubledeltas.vel.VelInline.vel;
                class Logic {
                    double f(double x, boolean b) {
                        return vel("x + b");
                    }
                }
                """);
        assertEquals(1, errors.size(), errors::toString);
        assertTrue(errors.getFirst().getMessage(null).startsWith("VEL 1:"), errors.getFirst().getMessage(null));
        assertEquals(4, errors.getFirst().getLineNumber());
    }

    @Test
    void variablesOfOtherTypesAreNotUsable() {
        var errors = compile("""
                import static com.doubledeltas.vel.VelInline.vel;
                class Names {
                    double f() {
                        var x = 1.0;
                        return vel("x * 2");
                    }
                }
                """);
        assertEquals(1, errors.size(), errors::toString);
        assertTrue(errors.getFirst().getMessage(null).contains("unknown name: x"), errors.getFirst().getMessage(null));
    }

    @Test
    void nonLiteralArgumentIsRejected() {
        var errors = compile("""
                import static com.doubledeltas.vel.VelInline.vel;
                class Energy {
                    double ke(String src) { return vel(src); }
                }
                """);
        assertTrue(errors.stream().anyMatch(e -> e.getMessage(null).contains("string literal")), errors::toString);
    }

    @Test
    void unrelatedMethodNamedVelIsIgnored() {
        var errors = compile("""
                class Other {
                    static double vel(String s) { return 0; }
                    double x() { return vel("((("); }
                }
                """);
        assertTrue(errors.isEmpty(), errors::toString);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static void assertNoErrors(List<Diagnostic<? extends JavaFileObject>> errors) {
        assertTrue(errors.isEmpty(), errors::toString);
    }

    private Object call(String className, String method, Object... args) throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[] {out.toUri().toURL()}, getClass().getClassLoader())) {
            for (Method m : loader.loadClass(className).getMethods()) {
                if (m.getName().equals(method)) {
                    try {
                        return m.invoke(null, args);
                    } catch (InvocationTargetException e) {
                        throw (Exception) e.getCause();
                    }
                }
            }
        }
        throw new NoSuchMethodException(method);
    }

    private List<Diagnostic<? extends JavaFileObject>> compile(String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        String name = source.replaceAll("(?s).*?class (\\w+).*", "$1");
        var file = new SimpleJavaFileObject(URI.create("string:///" + name + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        String cp = System.getProperty("java.class.path");
        var task = compiler.getTask(null, null, diagnostics,
                List.of("-Xplugin:Vel", "-proc:none", "-classpath", cp, "-processorpath", cp,
                        "-d", out.toString()),
                null, List.of(file));
        task.call();
        return diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .toList();
    }
}
