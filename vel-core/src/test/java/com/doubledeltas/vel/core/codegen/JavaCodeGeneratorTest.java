package com.doubledeltas.vel.core.codegen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.doubledeltas.vel.VelException;
import com.doubledeltas.vel.core.VelFrontend;
import com.doubledeltas.vel.core.type.Card;

/** Generates Java from VEL, compiles it and runs it. */
class JavaCodeGeneratorTest {

    @TempDir
    Path dir;

    private int classes;

    /** A Java type written as {@code double}, {@code int[]}, or {@code double[][]@3,4} (VEL sizes after @, ⊤ as ?). */
    private static ConcreteType type(String spec) {
        String[] parts = spec.split("@");
        String java = parts[0];
        int rank = 0;
        while (java.endsWith("[]")) {
            java = java.substring(0, java.length() - 2);
            rank++;
        }
        var element = JavaTypes.ofPrimitive(java);
        if (rank == 0) {
            return ConcreteType.scalar(element);
        }
        List<Card> dims = null;
        if (parts.length > 1) {
            dims = new ArrayList<>();
            for (String d : parts[1].split(",")) {
                dims.add(d.equals("?") ? Card.TOP : Card.of(Long.parseLong(d)));
            }
        }
        return ConcreteType.javaArray(element, rank, dims);
    }

    /** {@code signature} is "ret(name type, name type, …)". */
    private Object run(String vel, String signature, Object... args) throws Exception {
        String ret = signature.substring(0, signature.indexOf('('));
        Map<String, ConcreteType> params = new LinkedHashMap<>();
        StringBuilder javaParams = new StringBuilder();
        String inside = signature.substring(signature.indexOf('(') + 1, signature.length() - 1);
        if (!inside.isBlank()) {
            for (String p : inside.split(";")) {
                String[] nt = p.trim().split(" ");
                ConcreteType t = type(nt[1]);
                params.put(nt[0], t);
                if (!javaParams.isEmpty()) {
                    javaParams.append(", ");
                }
                javaParams.append(t.javaName()).append(' ').append(nt[0]);
            }
        }
        var parsed = VelFrontend.parseProgram(vel);
        assertTrue(parsed.diagnostics().isEmpty(), parsed.diagnostics()::toString);
        ConcreteType returnType = type(ret);
        String body = JavaCodeGenerator.methodBody(parsed.tree(), params, returnType);
        String name = "Gen" + classes++;
        String source = "public class " + name + " {\n public static " + returnType.javaName() + " f(" + javaParams
                + ") {\n" + body + "}\n}\n";
        Path file = dir.resolve(name + ".java");
        Files.writeString(file, source);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int status = compiler.run(null, null, null, "-classpath", System.getProperty("java.class.path"),
                "-d", dir.toString(), file.toString());
        assertEquals(0, status, () -> "generated code does not compile:\n" + source);
        try (var loader = new URLClassLoader(new java.net.URL[] {dir.toUri().toURL()}, getClass().getClassLoader())) {
            Method f = null;
            for (Method m : loader.loadClass(name).getMethods()) {
                if (m.getName().equals("f")) {
                    f = m;
                }
            }
            try {
                return f.invoke(null, args);
            } catch (InvocationTargetException e) {
                throw (Exception) e.getCause();
            }
        }
    }

    // ---- scalars ----------------------------------------------------------------------------------

    @Test
    void physicsPosition() throws Exception {
        assertEquals(1 + 2 * 3 + 0.5 * 4 * 9.0, run("""
                # constant-acceleration step
                return p + v * dt + 0.5 * a * dt^2
                """, "double(p double; v double; a double; dt double)", 1.0, 2.0, 4.0, 3.0));
    }

    @Test
    void integerArithmeticAndTrueDivision() throws Exception {
        assertEquals(3.5, run("a / b", "double(a int; b int)", 7, 2));
        assertEquals(-4, run("a // b", "int(a int; b int)", -7, 2));
        assertEquals(-3, run("a /// b", "int(a int; b int)", -7, 2));
        assertEquals(1, run("a %% b", "int(a int; b int)", -7, 2));
        assertEquals(-1, run("a %%% b", "int(a int; b int)", -7, 2));
        assertEquals(27L, run("a ^ 3", "long(a long)", 3L));
        // char - char is Z32 (java.md 7.3)
        assertEquals(-1, run("a - b", "int(a char; b char)", 'a', 'b'));
        // char + char is Z+32, an unsigned int in the bits of a Java int (java.md 7.3)
        assertEquals(195, run("a + b", "int(a char; b char)", 'a', 'b'));
        assertEquals(195L, run("a + b", "long(a char; b char)", 'a', 'b'));
        assertEquals(97.5, run("(a + b) / 2", "double(a char; b char)", 'a', 'b'));
        // a long result follows the long operand
        assertEquals(1L << 40, run("x * 2", "long(x long)", 1L << 39));
    }

    @Test
    void literalsFollowTheirPartner() throws Exception {
        // 0.1 * x with x: float is computed in float
        assertEquals(0.1f * 3f, run("0.1 * x", "float(x float)", 3f));
        // constants are folded exactly: 1/3 * 3 = 1
        assertEquals(1.0, run("1/3 * 3 * x", "double(x double)", 1.0));
        // a variable bound to a constant stays a constant
        assertEquals(6f, run("""
                k = 2
                return k * x
                """, "float(x float)", 3f));
    }

    @Test
    void conditionalComparisonAndEarlyReturn() throws Exception {
        assertEquals(5.0, run("if x < 0 then -x else x", "double(x double)", -5.0));
        assertEquals(0, run("""
                if n == 0 then return 0
                m = n * 2
                return m + 1
                """, "int(n int)", 0));
        assertEquals(7, run("""
                if n == 0 then return 0
                m = n * 2
                return m + 1
                """, "int(n int)", 3));
        assertEquals(true, run("a + b", "boolean(a boolean; b boolean)", false, true)); // + is OR
        assertEquals(false, run("a o+ b", "boolean(a boolean; b boolean)", true, true));
    }

    @Test
    void errorThrowsVelException() {
        assertThrows(VelException.class, () -> run("if x > 0 then x else error()", "double(x double)", -1.0));
        assertThrows(VelException.class, () -> run("error()", "int()"));
    }

    @Test
    void returnConcretizesLossily() throws Exception {
        // the result is computed in F64 and cast to the return type
        assertEquals(2, run("x * 1.5", "int(x double)", 1.5));
    }

    // ---- arrays -----------------------------------------------------------------------------------

    @Test
    void elementWiseWithBroadcasting() throws Exception {
        assertArrayEquals(new double[] {5, 7, 9}, (double[]) run("u + v", "double[](u double[]; v double[])",
                new double[] {1, 2, 3}, new double[] {4, 5, 6}));
        assertArrayEquals(new double[] {2, 4, 6}, (double[]) run("2 * u", "double[](u double[])", new double[] {1, 2, 3}));
        assertArrayEquals(new double[] {1, 4, 9}, (double[]) run("u .^ 2", "double[](u double[])", new double[] {1, 2, 3}));
        // int elements widen to double
        assertArrayEquals(new double[] {1.5, 2.5}, (double[]) run("a + 0.5", "double[](a int[])", new int[] {1, 2}));
        // Vec(3) + Mat(1, 2) : Mat(3, 2); Java a[2][3]
        double[][] m = (double[][]) run("u + w", "double[][](u double[]@3; w double[][]@1,2)",
                new double[] {1, 2, 3}, new double[][] {{10}, {20}});
        assertArrayEquals(new double[][] {{11, 12, 13}, {21, 22, 23}}, m);
    }

    @Test
    void lengthMismatchIsNull() {
        // ⊤ sizes: the shorter operand runs out of elements, which are null (java.md 2.2)
        assertThrows(NullPointerException.class, () -> run("u + v", "double[](u double[]; v double[])",
                new double[] {1, 2, 3}, new double[] {1, 2}));
    }

    @Test
    void matrixLiteralsTuplesAndProducts() throws Exception {
        // [1, 2; 3, 4] is Mat(2, 2); Java a[col][row]
        assertArrayEquals(new int[][] {{1, 3}, {2, 4}}, (int[][]) run("[1, 2; 3, 4]", "int[][]()"));
        // (x, y, z) is the column [x; y; z]
        assertArrayEquals(new double[] {1, 2, 3}, (double[]) run("(x, 2, 3)", "double[](x double)", 1.0));
        // [A, b] block matrix
        assertArrayEquals(new double[][] {{1, 3}, {2, 4}, {5, 6}},
                (double[][]) run("[A, b]", "double[][](A double[][]@2,2; b double[]@2)",
                        new double[][] {{1, 3}, {2, 4}}, new double[] {5, 6}));
        // A * v: Mat(2, 2) * Vec(2)
        double[][] a = {{1, 3}, {2, 4}}; // [1, 2; 3, 4]
        assertArrayEquals(new double[] {1 * 5 + 2 * 6, 3 * 5 + 4 * 6},
                (double[]) run("A * v", "double[](A double[][]@2,2; v double[]@2)", a, new double[] {5, 6}));
        // u' * v is a scalar (dot product)
        assertEquals(32.0, run("u' * v", "double(u double[]@3; v double[]@3)", new double[] {1, 2, 3}, new double[] {4, 5, 6}));
        // transpose of a matrix
        assertArrayEquals(new double[][] {{1, 2}, {3, 4}}, (double[][]) run("A'", "double[][](A double[][]@2,2)", (Object) a));
    }

    @Test
    void indexing() throws Exception {
        double[] v = {10, 20, 30, 40};
        assertEquals(30.0, run("v[2]", "double(v double[])", (Object) v));
        assertEquals(40.0, run("v[end - 1]", "double(v double[])", (Object) v));
        assertArrayEquals(new double[] {20, 30}, (double[]) run("v[1:3]", "double[](v double[])", (Object) v));
        assertArrayEquals(new double[] {30, 40}, (double[]) run("v[2:end]", "double[](v double[])", (Object) v));
        double[][] m = {{1, 3}, {2, 4}}; // [1, 2; 3, 4]
        assertEquals(2.0, run("m[0, 1]", "double(m double[][])", (Object) m));
        // m[1, :] is the row [3, 4]: Mat(1, 2), Java a[2][1]
        assertArrayEquals(new double[][] {{3}, {4}}, (double[][]) run("m[1, :]", "double[][](m double[][])", (Object) m));
        // m[:, 0] is the column [1; 3]
        assertArrayEquals(new double[] {1, 3}, (double[]) run("m[:, 0]", "double[](m double[][])", (Object) m));
        // a[b[i]]
        assertEquals(20.0, run("v[b[0]]", "double(v double[]; b int[])", v, new int[] {1}));
        // out of range is null
        assertThrows(NullPointerException.class, () -> run("v[4]", "double(v double[])", (Object) v));
        assertThrows(NullPointerException.class, () -> run("v[i]", "double(v double[]; i int)", v, -1));
    }

    @Test
    void indexAssignmentCopies() throws Exception {
        double[] v = {1, 2, 3};
        assertArrayEquals(new double[] {1, 9, 3}, (double[]) run("""
                w = v
                w[1] = 9
                return w
                """, "double[](v double[])", (Object) v));
        assertArrayEquals(new double[] {1, 2, 3}, v); // the Java array is not modified
        // m[0, :] = 0 and a widening assignment
        assertArrayEquals(new double[] {0.5, 2}, (double[]) run("""
                a[0] = 0.5
                return a
                """, "double[](a int[])", (Object) new int[] {1, 2}));
    }

    @Test
    void destructuring() throws Exception {
        assertEquals(5.0, run("""
                (x, y) = v
                return x + y
                """, "double(v double[]@2)", (Object) new double[] {2, 3}));
        assertEquals(10.0, run("""
                [a, b; c, d] = m
                return a + 2*b + 3*c - d
                """, "double(m double[][]@2,2)", (Object) new double[][] {{1, 3}, {2, 4}})); // 1 + 4 + 9 - 4
    }

    @Test
    void returnedJavaArraysAreCopies() throws Exception {
        double[] v = {1, 2};
        Object r = run("v", "double[](v double[])", (Object) v);
        assertArrayEquals(v, (double[]) r);
        assertTrue(r != v);
    }

    @Test
    void conditionalOnArraysEvaluatesOnlyTheChosenBranch() throws Exception {
        assertArrayEquals(new double[] {2, 4}, (double[]) run("if c then 2 * v else error()",
                "double[](c boolean; v double[])", true, new double[] {1, 2}));
    }

    // ---- errors -----------------------------------------------------------------------------------

    @Test
    void typeErrorsAreLocated() {
        VelCompileError e = assertThrows(VelCompileError.class, () -> run("""
                x = 1
                return x + b
                """, "double(b boolean)", true));
        assertEquals(2, e.line());
        assertInstanceOf(VelCompileError.class, assertThrows(VelCompileError.class,
                () -> run("sqrt(x)", "double(x double)", 1.0)));
        assertThrows(VelCompileError.class, () -> run("u + v", "double[](u double[]@2; v double[]@3)",
                new double[2], new double[3]));
    }
}
