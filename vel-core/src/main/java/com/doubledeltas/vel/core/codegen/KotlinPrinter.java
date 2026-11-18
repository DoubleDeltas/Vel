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

/**
 * Prints {@link Code} as Kotlin source. Arrays follow kotlin.md 2: a JVM {@code double[]} is
 * {@code DoubleArray}, {@code double[][]} is {@code Array<DoubleArray>}; {@code char} is {@code Char},
 * converted through {@code code} and {@code toChar()}; the unsigned {@code ubyte uint ulong} are
 * {@code UByte UInt ULong}, whose operators are already unsigned.
 */
public final class KotlinPrinter {

    static final String VEL_MATH = "com.doubledeltas.vel.VelMath";
    static final String VEL_EXCEPTION = "com.doubledeltas.vel.VelException";

    private final StringBuilder out = new StringBuilder();
    private final java.util.function.UnaryOperator<String> returned;
    private int indent;

    private KotlinPrinter(java.util.function.UnaryOperator<String> returned) {
        this.returned = returned;
    }

    /** A function body (without braces): the result is returned. */
    public static String functionBody(Body body) {
        return functionBody(body, java.util.function.UnaryOperator.identity());
    }

    /**
     * A function body (without braces): the result is returned after {@code returned} adapts it to the
     * declared return type (e.g. {@code Char} to {@code UShort}).
     */
    public static String functionBody(Body body, java.util.function.UnaryOperator<String> returned) {
        KotlinPrinter p = new KotlinPrinter(returned);
        if (!body.guarded()) {
            p.statements(body.statements());
        } else {
            p.line("try {");
            p.indent++;
            p.statements(body.statements());
            p.indent--;
            p.line("} catch (e: IndexOutOfBoundsException) {");
            p.line("    throw " + VEL_MATH + ".outOfRange(e)");
            p.line("} catch (e: java.lang.NegativeArraySizeException) {");
            p.line("    throw " + VEL_MATH + ".outOfRange(e)");
            p.line("}");
        }
        return p.out.toString();
    }

    /** The Kotlin type, e.g. {@code Double}, {@code DoubleArray}, {@code Array<DoubleArray>}. */
    public static String type(Type t) {
        String scalar = scalarType(t.primitive());
        if (t.rank() == 0) {
            return scalar;
        }
        String result = scalar + "Array";
        for (int i = 1; i < t.rank(); i++) {
            result = "Array<" + result + ">";
        }
        return result;
    }

    private static String scalarType(String primitive) {
        return switch (primitive) {
            case "ubyte" -> "UByte";
            case "uint" -> "UInt";
            case "ulong" -> "ULong";
            case "boolean" -> "Boolean";
            case "byte" -> "Byte";
            case "short" -> "Short";
            case "char" -> "Char";
            case "int" -> "Int";
            case "long" -> "Long";
            case "float" -> "Float";
            case "double" -> "Double";
            default -> throw new IllegalArgumentException(primitive);
        };
    }

    private void statements(List<Stmt> statements) {
        for (Stmt s : statements) {
            statement(s);
        }
    }

    private void statement(Stmt s) {
        switch (s) {
            case Let l -> line("val " + l.name() + ": " + type(l.type()) + " = " + expr(l.init()));
            case Var v -> line("var " + v.name() + ": " + type(v.type()) + " = " + expr(v.init()));
            case Declare d -> line("val " + d.name() + ": " + type(d.type()));
            case Assign a -> line(a.name() + " = " + expr(a.value()));
            case Store st -> line(expr(st.array()) + "[" + expr(st.index()) + "] = " + expr(st.value()));
            case For f -> {
                line("for (" + f.index() + " in 0 until " + expr(f.length()) + ") {");
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
            case Return r -> line("return " + returned.apply(expr(r.value())));
            case Throw ignored -> line("throw " + VEL_EXCEPTION + "()");
        }
    }

    private void block(List<Stmt> statements) {
        indent++;
        statements(statements);
        indent--;
        line("}");
    }

    private static String expr(Expr e) {
        return switch (e) {
            case Lit l -> literal(l);
            case Ref r -> r.name();
            case Binary b -> "(" + expr(b.left()) + " " + operator(b.op()) + " " + expr(b.right()) + ")";
            case Neg n -> "(-" + expr(n.operand()) + ")";
            case Convert c -> convert(expr(c.operand()), c.operand().type().primitive(), c.type().primitive());
            case Call c -> call(c);
            case Choose c -> "(if (" + expr(c.condition()) + ") " + expr(c.ifTrue()) + " else " + expr(c.ifFalse()) + ")";
            case Fail f -> VEL_MATH + ".error<" + type(f.type()) + ">()";
            case Get g -> expr(g.array()) + "[" + expr(g.index()) + "]";
            case Length l -> expr(l.array()) + ".size";
            case NewArray n -> newArray(n.type(), n.lengths());
        };
    }

    private static String call(Call c) {
        String operand = c.args().getFirst().type().primitive();
        List<String> args = c.args().stream().map(KotlinPrinter::expr).toList();
        if (JavaTypes.isUnsigned(operand)) {
            switch (c.helper()) {
                case FLOOR_DIV -> {
                    return "(" + args.get(0) + " / " + args.get(1) + ")"; // unsigned: floor = truncation
                }
                case FLOOR_MOD -> {
                    return "(" + args.get(0) + " % " + args.get(1) + ")";
                }
                case INT_POW -> {
                    // repeated multiplication on the stored bits wraps like the unsigned type
                    String bits = JavaTypes.storage(operand);
                    String kt = scalarType(bits);
                    return VEL_MATH + ".pow((" + args.get(0) + ").to" + kt + "(), " + args.get(1) + ").to" + scalarType(operand) + "()";
                }
                default -> {
                }
            }
        }
        return c.helper().owner() + "." + c.helper().method() + "(" + String.join(", ", args) + ")";
    }

    private static String newArray(Type t, List<Expr> lengths) {
        if (t.rank() == 1) {
            return type(t) + "(" + expr(lengths.getFirst()) + ")";
        }
        return "Array(" + expr(lengths.getFirst()) + ") { " + newArray(t.component(), lengths.subList(1, lengths.size())) + " }";
    }

    /** A primitive conversion with Java cast semantics, through {@code Int} where Kotlin has no direct one. */
    private static String convert(String code, String from, String to) {
        if (from.equals(to)) {
            return code;
        }
        String value = "(" + code + ")";
        if (from.equals("char")) {
            value = value + ".code";
            from = "int";
            if (to.equals("int")) {
                return value;
            }
        }
        boolean floating = from.equals("float") || from.equals("double");
        return switch (to) {
            case "char" -> (from.equals("int") ? value : value + ".toInt()") + ".toChar()";
            case "byte", "short" -> (floating ? value + ".toInt()" : value) + ".to" + scalarType(to) + "()";
            default -> value + ".to" + scalarType(to) + "()";
        };
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
            case XOR -> "xor";
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
            String s = d.toString();
            if (!s.contains(".") && !s.contains("E") && !s.contains("e")) {
                s = s + ".0";
            }
            text = p.equals("float") ? s + "f" : s;
        } else {
            String v = l.value().toString();
            text = switch (p) {
                case "int" -> v;
                case "long" -> v + "L";
                case "uint" -> v + "u";
                case "ulong" -> v + "uL";
                case "ubyte" -> "(" + v + "u).toUByte()";
                case "char" -> "(" + v + ").toChar()";
                default -> "(" + v + ").to" + scalarType(p) + "()";
            };
        }
        return text.startsWith("-") ? "(" + text + ")" : text;
    }

    private void line(String s) {
        out.append("    ".repeat(indent)).append(s).append('\n');
    }
}
