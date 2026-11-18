package com.doubledeltas.vel.core.codegen;

import java.util.Map;

import com.doubledeltas.vel.parser.VelParser;

/** Java source for VEL: {@link CodeGenerator} followed by {@link JavaPrinter}. */
public final class JavaCodeGenerator {

    private JavaCodeGenerator() {
    }

    /**
     * The body of a Java method implementing a VEL program (style B), without the surrounding braces.
     *
     * @param parameters the method parameters in order, by name
     * @param returnType the method's return type
     * @throws VelCompileError when the program is not well typed or uses what is not supported yet
     */
    public static String methodBody(VelParser.ProgramContext program, Map<String, ConcreteType> parameters,
                                    ConcreteType returnType) {
        return JavaPrinter.methodBody(CodeGenerator.program(program, parameters, returnType));
    }

    /**
     * A Java expression computing an inline VEL expression (style C), a switch expression holding the
     * generated statements (see {@link JavaPrinter#expression}).
     *
     * @param variables the Java variables visible at the call, by name
     * @param target    the type the context expects, or null to keep the value's own type
     * @throws VelCompileError when the expression is not well typed or uses what is not supported yet
     */
    public static String inlineExpression(VelParser.SingleExpressionContext expression,
                                          Map<String, ConcreteType> variables, ConcreteType target) {
        return JavaPrinter.expression(CodeGenerator.expression(expression, variables, target));
    }

    /**
     * The body of a Java method implementing a VEL program (style B) whose parameters and result may need
     * converting at the boundary ({@link Boundary}): wrappers, lists, arrays of non-primitives, component
     * objects. Only the parameters the program reads are converted.
     */
    public static String methodBody(VelParser.ProgramContext program, Map<String, Boundary> parameters, Boundary result) {
        Map<String, ConcreteType> types = new java.util.LinkedHashMap<>();
        Map<String, String> codeNames = new java.util.HashMap<>();
        parameters.forEach((name, b) -> {
            types.put(name, b.type());
            if (!b.isDirect()) {
                codeNames.put(name, name + "__v");
            }
        });
        Code.Body body = CodeGenerator.program(program, types, codeNames, result.type());
        return JavaPrinter.methodBody(body, prologue(parameters, codeNames, body), writer(result));
    }

    /** Like {@link #methodBody(VelParser.ProgramContext, Map, Boundary)} for an inline expression (style C); {@code target} may be null. */
    public static String inlineExpressionAt(VelParser.SingleExpressionContext expression, Map<String, Boundary> variables,
                                            Boundary target) {
        Map<String, ConcreteType> types = new java.util.LinkedHashMap<>();
        Map<String, String> codeNames = new java.util.HashMap<>();
        variables.forEach((name, b) -> {
            types.put(name, b.type());
            if (!b.isDirect()) {
                codeNames.put(name, name + "__v");
            }
        });
        Code.Body body = CodeGenerator.expression(expression, types, codeNames, target == null ? null : target.type());
        return JavaPrinter.expression(body, prologue(variables, codeNames, body), target == null ? null : writer(target));
    }

    private static String prologue(Map<String, Boundary> values, Map<String, String> codeNames, Code.Body body) {
        StringBuilder s = new StringBuilder();
        for (String name : body.used()) {
            String code = codeNames.get(name);
            if (code != null) {
                s.append(JavaBoundary.read(values.get(name), name, code));
            }
        }
        return s.toString();
    }

    private static JavaPrinter.ResultWriter writer(Boundary result) {
        return result.isDirect() ? null : (value, keyword) -> JavaBoundary.write(result, value, keyword, "result");
    }
}
