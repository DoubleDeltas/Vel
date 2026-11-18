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
        return t.primitive() + "[]".repeat(t.rank());
    }

    private static String expr(Expr e) {
        return switch (e) {
            case Lit l -> literal(l);
            case Ref r -> r.name();
            case Binary b -> "(" + expr(b.left()) + " " + operator(b.op()) + " " + expr(b.right()) + ")";
            case Neg n -> "(-" + expr(n.operand()) + ")";
            case Convert c -> "((" + c.type().primitive() + ") " + expr(c.operand()) + ")";
            case Call c -> c.helper().owner().replace("java.lang.", "") + "." + c.helper().method() + "("
                    + c.args().stream().map(JavaPrinter::expr).collect(Collectors.joining(", ")) + ")";
            case Choose c -> "(" + expr(c.condition()) + " ? " + expr(c.ifTrue()) + " : " + expr(c.ifFalse()) + ")";
            case Fail f -> VEL_MATH + ".<" + boxed(f.type()) + ">error()";
            case Get g -> expr(g.array()) + "[" + expr(g.index()) + "]";
            case Length l -> expr(l.array()) + ".length";
            case NewArray n -> "new " + n.type().primitive()
                    + n.lengths().stream().map(l -> "[" + expr(l) + "]").collect(Collectors.joining());
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
            String v = l.value().toString();
            text = switch (p) {
                case "int" -> v;
                case "long" -> v + "L";
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
