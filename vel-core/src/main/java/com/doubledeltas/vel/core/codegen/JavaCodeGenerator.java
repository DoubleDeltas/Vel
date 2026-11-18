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
}
