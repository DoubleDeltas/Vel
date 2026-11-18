package com.doubledeltas.vel.core.codegen;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import com.doubledeltas.vel.core.codegen.Code.Assign;
import com.doubledeltas.vel.core.codegen.Code.Binary;
import com.doubledeltas.vel.core.codegen.Code.Body;
import com.doubledeltas.vel.core.codegen.Code.Call;
import com.doubledeltas.vel.core.codegen.Code.Choose;
import com.doubledeltas.vel.core.codegen.Code.Convert;
import com.doubledeltas.vel.core.codegen.Code.Declare;
import com.doubledeltas.vel.core.codegen.Code.Expr;
import com.doubledeltas.vel.core.codegen.Code.Fail;
import com.doubledeltas.vel.core.codegen.Code.For;
import com.doubledeltas.vel.core.codegen.Code.Get;
import com.doubledeltas.vel.core.codegen.Code.If;
import com.doubledeltas.vel.core.codegen.Code.Length;
import com.doubledeltas.vel.core.codegen.Code.Let;
import com.doubledeltas.vel.core.codegen.Code.Lit;
import com.doubledeltas.vel.core.codegen.Code.Neg;
import com.doubledeltas.vel.core.codegen.Code.NewArray;
import com.doubledeltas.vel.core.codegen.Code.Ref;
import com.doubledeltas.vel.core.codegen.Code.Return;
import com.doubledeltas.vel.core.codegen.Code.Stmt;
import com.doubledeltas.vel.core.codegen.Code.Store;
import com.doubledeltas.vel.core.codegen.Code.Throw;
import com.doubledeltas.vel.core.codegen.Code.Type;
import com.doubledeltas.vel.core.codegen.Code.Var;

/** Prints {@link Code} as Java source. */
public final class JavaPrinter {

    static final String VEL_MATH = "com.doubledeltas.vel.VelMath";
    static final String VEL_EXCEPTION = "com.doubledeltas.vel.VelException";

    private final StringBuilder out = new StringBuilder();
    private final String returnKeyword;
    private int indent;

    private JavaPrinter(String returnKeyword) {
        this.returnKeyword = returnKeyword;
    }

    /** A method body (without braces): the result is returned. */
    public static String methodBody(Body body) {
        JavaPrinter p = new JavaPrinter("return");
        p.body(body);
        return p.out.toString();
    }

    /**
     * An expression computing the result: a switch expression whose block holds the statements,
     * {@code switch (0) { default -> { …; yield result; } }}, so that loops can stand where an
     * expression is expected.
     */
    public static String expression(Body body) {
        JavaPrinter p = new JavaPrinter("yield");
        p.indent = 1;
        p.body(body);
        return "switch (0) { default -> {\n" + p.out + "} }";
    }

    private void body(Body body) {
        if (!body.guarded()) {
            statements(body.statements());
            return;
        }
        line("try {");
        indent++;
        statements(body.statements());
        indent--;
        line("} catch (IndexOutOfBoundsException | NegativeArraySizeException e) {");
        line("    throw " + VEL_MATH + ".outOfRange(e);");
        line("}");
    }

    private void statements(List<Stmt> statements) {
        for (Stmt s : statements) {
            statement(s);
        }
    }

    private void statement(Stmt s) {
        switch (s) {
            case Let l -> line("final " + type(l.type()) + " " + l.name() + " = " + expr(l.init()) + ";");
            case Var v -> line(type(v.type()) + " " + v.name() + " = " + expr(v.init()) + ";");
            case Declare d -> line("final " + type(d.type()) + " " + d.name() + ";");
            case Assign a -> line(a.name() + " = " + expr(a.value()) + ";");
            case Store st -> line(expr(st.array()) + "[" + expr(st.index()) + "] = " + expr(st.value()) + ";");
            case For f -> {
                line("for (int " + f.index() + " = 0; " + f.index() + " < " + expr(f.length()) + "; " + f.index() + "++) {");
                block(f.body());
            }
            case If i -> {
                line("if (" + expr(i.condition()) + ") {");
                if (i.otherwise().isEmpty()) {
                    block(i.then());
                } else {
                    indent++;
                    statements(i.then());
                    indent--;
                    line("} else {");
                    block(i.otherwise());
                }
            }
            case Return r -> line(returnKeyword + " " + expr(r.value()) + ";");
            case Throw ignored -> line("throw new " + VEL_EXCEPTION + "();");
        }
    }

    /** The statements, indented, then the closing brace. */
    private void block(List<Stmt> statements) {
        indent++;
        statements(statements);
        indent--;
        line("}");
    }

    static String type(Type t) {
        return JavaTypes.storage(t.primitive()) + "[]".repeat(t.rank());
    }

    private static String expr(Expr e) {
        return switch (e) {
            case Lit l -> literal(l);
            case Ref r -> r.name();
            case Binary b -> binary(b);
            case Neg n -> "(-" + expr(n.operand()) + ")";
            case Convert c -> convert(expr(c.operand()), c.operand().type().primitive(), c.type().primitive());
            case Call c -> call(c);
            case Choose c -> "(" + expr(c.condition()) + " ? " + expr(c.ifTrue()) + " : " + expr(c.ifFalse()) + ")";
            case Fail f -> VEL_MATH + ".<" + boxed(f.type()) + ">error()";
            case Get g -> expr(g.array()) + "[" + expr(g.index()) + "]";
            case Length l -> expr(l.array()) + ".length";
            case NewArray n -> "new " + JavaTypes.storage(n.type().primitive())
                    + n.lengths().stream().map(l -> "[" + expr(l) + "]").collect(Collectors.joining());
        };
    }

    private static String binary(Binary b) {
        String l = expr(b.left());
        String r = expr(b.right());
        String operand = b.left().type().primitive();
        if (JavaTypes.isUnsigned(operand)) {
            String box = operand.equals("ulong") ? "Long" : "Integer";
            if (operand.equals("ubyte")) {
                l = "Byte.toUnsignedInt(" + l + ")";
                r = "Byte.toUnsignedInt(" + r + ")";
            } else if (b.op() == Code.Op.DIV) {
                return box + ".divideUnsigned(" + l + ", " + r + ")";
            } else if (b.op() == Code.Op.REM) {
                return box + ".remainderUnsigned(" + l + ", " + r + ")";
            } else if (b.op().isComparison() && b.op() != Code.Op.EQ && b.op() != Code.Op.NE) {
                return "(" + box + ".compareUnsigned(" + l + ", " + r + ") " + operator(b.op()) + " 0)";
            }
        }
        return "(" + l + " " + operator(b.op()) + " " + r + ")";
    }

    private static String call(Call c) {
        List<String> args = c.args().stream().map(JavaPrinter::expr).toList();
        String operand = c.args().getFirst().type().primitive();
        if (JavaTypes.isUnsigned(operand) && (c.helper() == Code.Helper.FLOOR_DIV || c.helper() == Code.Helper.FLOOR_MOD)) {
            // unsigned values are not negative: floor and truncation agree
            String box = operand.equals("ulong") ? "Long" : "Integer";
            return box + (c.helper() == Code.Helper.FLOOR_DIV ? ".divideUnsigned(" : ".remainderUnsigned(")
                    + String.join(", ", args) + ")";
        }
        return c.helper().owner().replace("java.lang.", "") + "." + c.helper().method() + "(" + String.join(", ", args) + ")";
    }

    /** A primitive conversion; values of unsigned types are read as unsigned. */
    private static String convert(String code, String from, String to) {
        if (from.equals(to)) {
            return code;
        }
        String target = JavaTypes.storage(to);
        boolean wide = target.equals("long") || target.equals("float") || target.equals("double");
        switch (from) {
            case "uint" -> {
                if (wide) {
                    code = "Integer.toUnsignedLong(" + code + ")";
                    from = "long";
                }
            }
            case "ubyte" -> {
                code = "Byte.toUnsignedInt(" + code + ")";
                from = "int";
            }
            case "ulong" -> {
                if (target.equals("float") || target.equals("double")) {
                    code = VEL_MATH + ".unsignedToDouble(" + code + ")";
                    from = "double";
                }
            }
            default -> {
            }
        }
        if (JavaTypes.storage(from).equals(target)) {
            return code;
        }
        if (JavaTypes.isUnsigned(to) && (from.equals("float") || from.equals("double"))) {
            return "((" + target + ") (long) " + code + ")";
        }
        return "((" + target + ") " + code + ")";
    }

    private static String operator(Code.Op op) {
        return switch (op) {
            case ADD -> "+";
            case SUB -> "-";
            case MUL -> "*";
            case DIV -> "/";
            case REM -> "%";
            case AND -> "&&";
            case OR -> "||";
            case XOR -> "^";
            case LT -> "<";
            case LE -> "<=";
            case GT -> ">";
            case GE -> ">=";
            case EQ -> "==";
            case NE -> "!=";
        };
    }

    private static String literal(Lit l) {
        String p = l.type().primitive();
        if (l.value() instanceof Boolean b) {
            return b.toString();
        }
        String text;
        if (l.value() instanceof BigDecimal d) {
            text = d.toString() + (p.equals("float") ? "f" : "d");
        } else {
            java.math.BigInteger big = (java.math.BigInteger) l.value();
            String v = big.toString();
            text = switch (p) {
                case "int" -> v;
                case "long" -> v + "L";
                case "uint" -> String.valueOf(big.intValue());
                case "ulong" -> big.longValue() + "L";
                case "ubyte" -> "((byte) " + big.byteValue() + ")";
                default -> "((" + p + ") " + v + ")";
            };
        }
        return text.startsWith("-") ? "(" + text + ")" : text;
    }

    /** The class for a generic type argument: the wrapper of a primitive, or the array type. */
    private static String boxed(Type t) {
        return t.isArray() ? type(t) : JavaTypes.boxed(t.primitive());
    }

    private void line(String s) {
        out.append("    ".repeat(indent)).append(s).append('\n');
    }
}
