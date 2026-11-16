package com.doubledeltas.vel.javac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
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

    @Test
    void validInlineExpressionCompiles() {
        var errors = compile("""
                import static com.doubledeltas.vel.VelInline.vel;
                class Energy {
                    double ke(double m, double v) { return vel("0.5 * m * v^2"); }
                }
                """);
        assertTrue(errors.isEmpty(), errors::toString);
    }

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
    void nonLiteralArgumentIsRejected() {
        var errors = compile("""
                import static com.doubledeltas.vel.VelInline.vel;
                class Energy {
                    double ke(String src) { return vel(src); }
                }
                """);
        assertEquals(1, errors.size(), errors::toString);
        assertTrue(errors.getFirst().getMessage(null).contains("string literal"));
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

    private List<Diagnostic<? extends JavaFileObject>> compile(String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var file = new SimpleJavaFileObject(URI.create("string:///Test.java"), JavaFileObject.Kind.SOURCE) {
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
