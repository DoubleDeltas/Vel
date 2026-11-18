package com.doubledeltas.vel.core.codegen;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.antlr.v4.runtime.ParserRuleContext;

import com.doubledeltas.vel.core.concretization.Operand;
import com.doubledeltas.vel.core.concretization.Promotion;
import com.doubledeltas.vel.core.concretization.Resolution;
import com.doubledeltas.vel.core.concretization.ScalarType;
import com.doubledeltas.vel.core.type.Arithmetic;
import com.doubledeltas.vel.core.type.ArrayAlgebra;
import com.doubledeltas.vel.core.type.BinaryOperator;
import com.doubledeltas.vel.core.type.Card;
import com.doubledeltas.vel.core.type.Constant;
import com.doubledeltas.vel.core.type.ScalarAlgebra;
import com.doubledeltas.vel.core.type.Shapes;
import com.doubledeltas.vel.core.type.Subscript;
import com.doubledeltas.vel.core.type.TypeRules;
import com.doubledeltas.vel.core.type.VelTypeException;
import com.doubledeltas.vel.parser.VelParser;

/**
 * The Java concretizer (type-system.md 0.3, java.md 7): turns a VEL program into the body of a Java
 * method. Algebras are checked with {@link TypeRules}, resolutions are decided with {@link Promotion},
 * and arrays become loops over Java arrays laid out as in java.md 2 (VEL {@code Mat(n, m)} is Java
 * {@code a[m][n]}).
 * <p>
 * Not supported yet (a {@link VelCompileError} is reported): functions and method calls (except
 * {@code error()}), member access, Complex and {@code j}, {@code inf} as a value, resolutions without a
 * Java primitive (Z∞, Dn, D∞), matrix division and matrix power, partitions, Bool matrix products.
 */
public final class JavaCodeGenerator {

    static final String VEL_MATH = "com.doubledeltas.vel.VelMath";
    static final String VEL_EXCEPTION = "com.doubledeltas.vel.VelException";

    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "var", "yield", "record");

    /**
     * A VEL value during generation.
     *
     * @param alg      its algebra (a scalar may carry a constant value)
     * @param res      the element resolution; null for a constant scalar (decided by its partner) and for Not
     * @param code     a Java expression (a scalar) or the name of a Java array variable; null for a constant or Not
     * @param external whether the Java array is owned by Java (a parameter): it must be copied before it leaves VEL
     */
    private record Val(ArrayAlgebra alg, Resolution res, String code, boolean external) {

        boolean isScalar() {
            return alg.isScalar();
        }

        boolean isConstant() {
            return alg.constant() != null;
        }

        boolean isNot() {
            return alg.element() == ScalarAlgebra.NOT;
        }

        Operand operand() {
            return isConstant() ? Operand.of(alg.constant()) : Operand.of(res);
        }
    }

    private StringBuilder out = new StringBuilder();
    private int indent = 1;
    private int counter;
    private boolean mayGoOutOfRange;
    private final Set<String> javaNames = new HashSet<>();
    private final Map<String, Val> params = new LinkedHashMap<>();
    private final Map<String, Val> locals = new HashMap<>();
    private final Deque<String> ends = new ArrayDeque<>();
    private final ConcreteType returnType;

    private JavaCodeGenerator(Map<String, ConcreteType> parameters, ConcreteType returnType) {
        this.returnType = returnType;
        parameters.forEach((name, type) -> {
            javaNames.add(name);
            params.put(name, new Val(type.algebra(), type.element().resolution(), name, true));
        });
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
        JavaCodeGenerator g = new JavaCodeGenerator(parameters, returnType);
        g.program(program);
        String body = g.out.toString();
        if (!g.mayGoOutOfRange) {
            return body.replace("\n    ", "\n").replaceFirst("^    ", "");
        }
        return "try {\n" + body + "} catch (IndexOutOfBoundsException | NegativeArraySizeException e) {\n"
                + "    throw " + VEL_MATH + ".outOfRange(e);\n}\n";
    }

    // ------------------------------------------------------------------------------------------
    // Statements
    // ------------------------------------------------------------------------------------------

    private void program(VelParser.ProgramContext program) {
        List<VelParser.StatementContext> statements = program.statement();
        if (statements.size() == 1 && statements.getFirst() instanceof VelParser.ExprStmtContext e) {
            located(e, () -> {
                returnValue(expr(e.expr()));
                return null;
            });
            return;
        }
        for (VelParser.StatementContext s : statements) {
            located(s, () -> {
                statement(s);
                return null;
            });
        }
    }

    private void statement(VelParser.StatementContext s) {
        switch (s) {
            case VelParser.ReturnStmtContext r -> returnValue(expr(r.expr()));
            case VelParser.IfStmtContext i -> {
                String condition = conv(condition(expr(i.expr())), Resolution.BOOL);
                line("if (" + condition + ") {");
                indent++;
                statement(i.statement());
                indent--;
                line("}");
            }
            case VelParser.AssignStmtContext a -> assign(a);
            case VelParser.DestructuringStmtContext d -> destructure(d);
            case VelParser.ExprStmtContext e -> expr(e.expr()); // evaluated for its errors only
            default -> throw new VelTypeException("unsupported statement: " + s.getText());
        }
    }

    private Val condition(Val c) {
        if (!c.isScalar() || !c.alg().element().isSubtypeOf(ScalarAlgebra.BOOL)) {
            throw new VelTypeException("a condition must be a Bool scalar, not " + c.alg());
        }
        return c;
    }

    private void returnValue(Val v) {
        if (v.isNot()) {
            line("throw new " + VEL_EXCEPTION + "();");
            return;
        }
        ScalarType target = returnType.element();
        if ((target.algebra() == ScalarAlgebra.BOOL) != (v.alg().element() == ScalarAlgebra.BOOL)) {
            throw new VelTypeException("cannot return " + v.alg() + " as " + returnType);
        }
        if (returnType.isScalar()) {
            if (!v.isScalar()) {
                throw new VelTypeException("cannot return " + v.alg() + " as the scalar " + returnType);
            }
            line("return " + conv(v, target.resolution()) + ";");
            return;
        }
        checkShape(v.alg().dims(), returnType.dims(), "cannot return " + v.alg() + " as " + returnType + ":");
        Val array = v.isScalar() ? broadcastTo(v, returnType.dims()) : v;
        line("return " + arrayCode(array, target.resolution(), true) + ";");
    }

    /** Known sizes must match; ⊤ matches anything. */
    private static void checkShape(List<Card> from, List<Card> to, String what) {
        if (from.size() != to.size()) {
            throw new VelTypeException(what + " the shapes differ");
        }
        for (int i = 0; i < from.size(); i++) {
            Shapes.unify(from.get(i), to.get(i), what);
        }
    }

    private void assign(VelParser.AssignStmtContext a) {
        VelParser.TargetContext target = a.target();
        Val value = expr(a.expr());
        String op = a.op.getText();
        if (!op.equals("=")) {
            Val current = target.indexList() == null
                    ? lookup(name(target), target)
                    : index(lookup(name(target), target), target.indexList());
            value = binary(BinaryOperator.fromSymbol(op.substring(0, op.length() - 1)), current, value);
        }
        bindTarget(target, value);
    }

    private void bindTarget(VelParser.TargetContext target, Val value) {
        String name = name(target);
        if (target.indexList() == null) {
            bind(name, value);
        } else {
            bind(name, indexAssign(lookup(name, target), target.indexList(), value));
        }
    }

    private static String name(VelParser.TargetContext target) {
        return target.ID() != null ? target.ID().getText() : unquote(target.QUOTED_ID().getText());
    }

    private void bind(String name, Val value) {
        if (value.isConstant() || value.isNot()) {
            locals.put(name, value); // a constant keeps following its partners
            return;
        }
        String javaName = freshName(name);
        String type = javaType(value);
        line("final " + type + " " + javaName + " = " + value.code() + ";");
        locals.put(name, new Val(value.alg(), value.res(), javaName, value.external()));
    }

    private void destructure(VelParser.DestructuringStmtContext d) {
        Val value = expr(d.expr());
        switch (d.pattern()) {
            case VelParser.TuplePatternContext t -> {
                List<VelParser.PatternItemContext> items = t.patternItem();
                TypeRules.destructureTuple(value.alg(), items.size());
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i) instanceof VelParser.BindItemContext b) {
                        bindTarget(b.target(), slice(value, List.of(String.valueOf(i))));
                    }
                }
            }
            case VelParser.MatrixPatternContext m -> {
                List<VelParser.PatternRowContext> rows = m.patternRow();
                int cols = rows.getFirst().patternItem().size();
                TypeRules.destructureMatrix(value.alg(), rows.size(), cols);
                for (int i = 0; i < rows.size(); i++) {
                    for (int j = 0; j < cols; j++) {
                        if (rows.get(i).patternItem(j) instanceof VelParser.BindItemContext b) {
                            bindTarget(b.target(), slice(value, List.of(String.valueOf(i), String.valueOf(j))));
                        }
                    }
                }
            }
            default -> throw new IllegalStateException();
        }
    }

    /** {@code v[i₀, …, iₘ, :, …]}: the leading dimensions fixed, the rest kept. */
    private Val slice(Val v, List<String> fixed) {
        mayGoOutOfRange = true;
        List<Card> dims = v.alg().dims();
        List<Card> rest = dims.subList(fixed.size(), dims.size());
        if (rest.isEmpty() || rest.stream().allMatch(Card::isOne)) {
            List<String> idx = new ArrayList<>(fixed);
            while (idx.size() < dims.size()) {
                idx.add("0");
            }
            return scalarVal(v.alg().element(), v.res(), elementAt(v, idx));
        }
        ArrayAlgebra alg = new ArrayAlgebra(rest, v.alg().element());
        List<String> lengths = new ArrayList<>();
        for (int k = fixed.size(); k < dims.size(); k++) {
            lengths.add(lengthOf(v, k));
        }
        return tabulate(alg, v.res(), lengths, idx -> {
            List<String> src = new ArrayList<>(fixed);
            src.addAll(idx);
            return elementAt(v, src.subList(0, dims.size()));
        });
    }

    // ------------------------------------------------------------------------------------------
    // Expressions
    // ------------------------------------------------------------------------------------------

    private Val expr(VelParser.ExprContext e) {
        return located(e, () -> exprUnlocated(e));
    }

    private Val exprUnlocated(VelParser.ExprContext e) {
        return switch (e) {
            case VelParser.PrimaryExprContext p -> primary(p.primary());
            case VelParser.IndexExprContext i -> index(expr(i.expr()), i.indexList());
            case VelParser.TransposeExprContext t -> transpose(expr(t.expr()));
            case VelParser.PowExprContext p -> binary(BinaryOperator.fromSymbol(p.op.getText()), expr(p.expr(0)), expr(p.expr(1)));
            case VelParser.UnaryExprContext u -> unary(expr(u.expr()), u.op.getText().equals("-"));
            case VelParser.MulExprContext m -> binary(BinaryOperator.fromSymbol(m.op.getText()), expr(m.expr(0)), expr(m.expr(1)));
            case VelParser.AddExprContext a -> binary(BinaryOperator.fromSymbol(a.op.getText()), expr(a.expr(0)), expr(a.expr(1)));
            case VelParser.RangeExprContext r -> rangeValue(expr(r.expr(0)), expr(r.expr(1)));
            case VelParser.CompareExprContext c -> compare(c.op.getText(), expr(c.expr(0)), expr(c.expr(1)));
            case VelParser.ConditionalExprContext c -> conditional(c);
            case VelParser.MethodCallExprContext ignored -> throw unsupported("method calls");
            case VelParser.BroadcastMethodCallExprContext ignored -> throw unsupported("method calls");
            case VelParser.MethodRefExprContext ignored -> throw unsupported("method references");
            case VelParser.MemberExprContext ignored -> throw unsupported("member access");
            case VelParser.FunctionExprContext ignored -> throw unsupported("function literals");
            default -> throw new IllegalStateException(e.getClass().getSimpleName());
        };
    }

    private Val primary(VelParser.PrimaryContext p) {
        return switch (p) {
            case VelParser.NumberLiteralContext n -> constantVal(number(n.getText()));
            case VelParser.BoolLiteralContext b -> scalarVal(ScalarAlgebra.BOOL, Resolution.BOOL, b.getText());
            case VelParser.IdentifierContext i -> identifier(i.getText(), p);
            case VelParser.QuotedIdentifierContext q -> javaVariable(unquote(q.getText()), p);
            case VelParser.ParenExprContext e -> expr(e.expr());
            case VelParser.TupleLiteralContext t -> tuple(t.expr().stream().map(this::expr).toList());
            case VelParser.MatrixLiteralContext m -> matrix(m.matrixRow().stream()
                    .map(r -> r.expr().stream().map(this::expr).toList()).toList());
            case VelParser.CallExprContext c -> call(c);
            case VelParser.BroadcastCallExprContext ignored -> throw unsupported("function calls");
            case VelParser.FunctionRefContext ignored -> throw unsupported("function references");
            default -> throw new IllegalStateException(p.getClass().getSimpleName());
        };
    }

    private Val constantVal(Constant c) {
        return new Val(ArrayAlgebra.scalar(c), null, null, false);
    }

    private static Constant number(String text) {
        String digits = text.replace("_", "");
        if (digits.contains(".") || digits.contains("e") || digits.contains("E")) {
            return Constant.of(new BigDecimal(digits));
        }
        return Constant.of(new BigInteger(digits));
    }

    private Val call(VelParser.CallExprContext c) {
        String name = c.ID() != null ? c.ID().getText() : null;
        if ("error".equals(name) && c.arguments() == null && c.signature() == null && !locals.containsKey(name)) {
            return new Val(ArrayAlgebra.scalar(ScalarAlgebra.NOT), null, null, false);
        }
        throw unsupported("function calls");
    }

    /** A plain name: a VEL variable, then a predeclared name, then a Java variable (java.md 4). */
    private Val identifier(String name, ParserRuleContext at) {
        Val local = locals.get(name);
        if (local != null) {
            return local;
        }
        switch (name) {
            case "end" -> {
                if (ends.isEmpty()) {
                    throw new VelTypeException("end is only defined inside a subscript");
                }
                return scalarVal(ScalarAlgebra.CARD, Resolution.CARD, ends.peek());
            }
            case "inf" -> throw unsupported("inf as a value");
            case "j" -> throw unsupported("Complex values (j)");
            default -> {
                return javaVariable(name, at);
            }
        }
    }

    private Val javaVariable(String name, ParserRuleContext at) {
        Val v = params.get(name);
        if (v == null) {
            throw new VelTypeException("unknown name: " + name);
        }
        return v;
    }

    private Val lookup(String name, ParserRuleContext at) {
        return identifier(name, at);
    }

    private static String unquote(String quoted) {
        return quoted.substring(1, quoted.length() - 1);
    }

    // ---- operators ---------------------------------------------------------------------------

    private Val binary(BinaryOperator op, Val a, Val b) {
        ArrayAlgebra alg = TypeRules.binary(op, a.alg(), b.alg());
        if (alg.element() == ScalarAlgebra.NOT) {
            return new Val(alg, null, null, false);
        }
        Arithmetic arithmetic = op == BinaryOperator.LEFT_DIV ? Arithmetic.DIV : op.arithmetic();
        Val x = op == BinaryOperator.LEFT_DIV ? b : a; // a /< b divides b by a
        Val y = op == BinaryOperator.LEFT_DIV ? a : b;
        if (a.isScalar() && b.isScalar()) {
            return scalarArithmetic(arithmetic, x, y, alg);
        }
        boolean elementWise = op.isElementWise()
                || (op == BinaryOperator.MUL && (a.isScalar() || b.isScalar()))
                || ((op == BinaryOperator.DIV || op == BinaryOperator.RIGHT_DIV) && b.isScalar())
                || (op == BinaryOperator.LEFT_DIV && a.isScalar());
        if (elementWise) {
            Resolution res = elementResolution(arithmetic, x, y, alg.element());
            return elementWise(alg, res, List.of(x, y),
                    e -> scalarArithmetic(arithmetic, e.get(0), e.get(1), ArrayAlgebra.scalar(alg.element())).code());
        }
        return switch (op) {
            case MUL -> matmul(a, b, alg);
            case DIV, RIGHT_DIV, LEFT_DIV -> throw unsupported("matrix division");
            case POW -> throw unsupported("matrix power");
            default -> throw new IllegalStateException(op.toString());
        };
    }

    private Resolution elementResolution(Arithmetic op, Val a, Val b, ScalarAlgebra k) {
        return normalize(k, Promotion.arithmetic(op, elementOperand(a), elementOperand(b)));
    }

    private static Operand elementOperand(Val v) {
        return v.isConstant() ? Operand.of(v.alg().constant()) : Operand.of(v.res());
    }

    /** {@code a op b} for scalars (constants are folded by {@link TypeRules}). */
    private Val scalarArithmetic(Arithmetic op, Val a, Val b, ArrayAlgebra alg) {
        if (alg.constant() != null) {
            return new Val(alg, null, null, false);
        }
        ScalarAlgebra k = alg.element();
        if (k == ScalarAlgebra.NOT || a.isNot() || b.isNot()) {
            return new Val(ArrayAlgebra.scalar(ScalarAlgebra.NOT), null, null, false);
        }
        if (k == ScalarAlgebra.BOOL) {
            String x = conv(a, Resolution.BOOL);
            String y = conv(b, Resolution.BOOL);
            String code = switch (op) {
                case ADD -> "(" + x + " || " + y + ")";
                case MUL -> "(" + x + " && " + y + ")";
                case XOR -> "(" + x + " ^ " + y + ")";
                default -> throw new VelTypeException("operator " + op + " is not defined for Bool");
            };
            return scalarVal(k, Resolution.BOOL, code);
        }
        Resolution r = normalize(k, Promotion.arithmetic(op, a.operand(), b.operand()));
        boolean floating = r.isFloating();
        if (op == Arithmetic.POW) {
            if (b.isConstant() && b.alg().constant() instanceof Constant.Int n && n.value().signum() >= 0
                    && n.value().bitLength() < 31) {
                return scalarVal(k, r, VEL_MATH + ".pow(" + conv(a, r) + ", " + n.value() + ")");
            }
            return scalarVal(k, r, conv(scalarVal(ScalarAlgebra.REAL, Resolution.FLOAT64,
                    "Math.pow(" + conv(a, Resolution.FLOAT64) + ", " + conv(b, Resolution.FLOAT64) + ")"), r));
        }
        String x = conv(a, r);
        String y = conv(b, r);
        String code = switch (op) {
            case ADD -> "(" + x + " + " + y + ")";
            case SUB -> "(" + x + " - " + y + ")";
            case MUL -> "(" + x + " * " + y + ")";
            case DIV -> "(" + x + " / " + y + ")";
            case FLOOR_DIV -> (floating ? VEL_MATH + ".floorDiv(" : "Math.floorDiv(") + x + ", " + y + ")";
            case TRUNC_DIV -> floating ? VEL_MATH + ".truncDiv(" + x + ", " + y + ")" : "(" + x + " / " + y + ")";
            case FLOOR_MOD -> (floating ? VEL_MATH + ".floorMod(" : "Math.floorMod(") + x + ", " + y + ")";
            case TRUNC_MOD -> "(" + x + " % " + y + ")";
            default -> throw new VelTypeException("operator " + op + " is defined only for Bool");
        };
        return scalarVal(k, r, code);
    }

    private Val unary(Val a, boolean minus) {
        ArrayAlgebra alg = TypeRules.unary(a.alg(), minus);
        if (alg.constant() != null || a.isNot()) {
            return new Val(alg, null, null, false);
        }
        if (a.isConstant()) {
            return new Val(alg, null, null, false);
        }
        Resolution r = normalize(alg.element(), Promotion.negate(a.res(), minus));
        if (a.isScalar()) {
            return scalarVal(alg.element(), r, minus ? "(-" + conv(a, r) + ")" : conv(a, r));
        }
        return elementWise(alg, r, List.of(a), e -> minus ? "(-" + conv(e.getFirst(), r) + ")" : conv(e.getFirst(), r));
    }

    private Val compare(String op, Val a, Val b) {
        ScalarAlgebra k = ScalarAlgebra.join(a.alg().element(), b.alg().element());
        boolean ordering = !op.equals("==") && !op.equals("!=");
        if (ordering && (k == ScalarAlgebra.BOOL || k == ScalarAlgebra.COMPLEX)) {
            throw new VelTypeException("operator " + op + " is not defined for " + k);
        }
        ArrayAlgebra alg = new ArrayAlgebra(Shapes.broadcast(a.alg().dims(), b.alg().dims()), ScalarAlgebra.BOOL);
        Function<List<Val>, String> element = e -> {
            Val x = e.get(0);
            Val y = e.get(1);
            if (x.isNot() || y.isNot()) {
                return notCode(Resolution.BOOL);
            }
            Resolution r = k == ScalarAlgebra.BOOL ? Resolution.BOOL : Promotion.join(x.operand(), y.operand());
            return "(" + conv(x, r) + " " + op + " " + conv(y, r) + ")";
        };
        if (alg.isScalar()) {
            return scalarVal(ScalarAlgebra.BOOL, Resolution.BOOL, element.apply(List.of(a, b)));
        }
        return elementWise(alg, Resolution.BOOL, List.of(a, b), element);
    }

    private Val conditional(VelParser.ConditionalExprContext c) {
        Val condition = condition(expr(c.expr(0)));
        String test = conv(condition, Resolution.BOOL);
        StringBuilder outer = out;
        StringBuilder thenOut = new StringBuilder();
        StringBuilder elseOut = new StringBuilder();
        out = thenOut;
        indent++;
        Val a = expr(c.expr(1));
        out = elseOut;
        Val b = expr(c.expr(2));
        indent--;
        out = outer;
        ArrayAlgebra alg = TypeRules.conditional(condition.alg(), a.alg(), b.alg());
        if (alg.element() == ScalarAlgebra.NOT) {
            return new Val(alg, null, null, false);
        }
        Resolution r = normalize(alg.element(), a.isNot() ? resolve(b) : b.isNot() ? resolve(a)
                : Promotion.join(elementOperand(a), elementOperand(b)));
        if (alg.isScalar() && thenOut.isEmpty() && elseOut.isEmpty()) {
            return scalarVal(alg.element(), r, "(" + test + " ? " + conv(a, r) + " : " + conv(b, r) + ")");
        }
        Val probe = new Val(alg, r, null, false);
        String t = fresh("c");
        line(javaType(probe) + " " + t + ";");
        line("if (" + test + ") {");
        out.append(thenOut);
        indent++;
        String thenCode = branchCode(a, alg, r, probe);
        line(t + " = " + thenCode + ";");
        indent--;
        line("} else {");
        out.append(elseOut);
        indent++;
        String elseCode = branchCode(b, alg, r, probe);
        line(t + " = " + elseCode + ";");
        indent--;
        line("}");
        return alg.isScalar() ? scalarVal(alg.element(), r, t) : new Val(alg, r, t, false);
    }

    /** A branch of a conditional as a value of the conditional's type. */
    private String branchCode(Val branch, ArrayAlgebra alg, Resolution r, Val probe) {
        if (alg.isScalar()) {
            return conv(branch, r);
        }
        if (branch.isNot()) {
            return VEL_MATH + ".<" + javaType(probe) + ">error()";
        }
        return arrayCode(branch.isScalar() ? broadcastTo(branch, alg.dims()) : branch, r, false);
    }

    private Val transpose(Val a) {
        ArrayAlgebra alg = TypeRules.transpose(a.alg());
        if (a.isScalar()) {
            return a;
        }
        List<Card> dims = alg.dims();
        List<String> lengths = new ArrayList<>();
        for (int k = 0; k < dims.size(); k++) {
            lengths.add(lengthOf(a, a.alg().rank() - 1 - k));
        }
        return tabulate(alg, a.res(), lengths, idx -> {
            List<String> src = new ArrayList<>(idx);
            Collections.reverse(src);
            return elementAt(a, src);
        });
    }

    // ---- matrices and tuples -------------------------------------------------------------------

    private Val matmul(Val a, Val b, ArrayAlgebra alg) {
        if (alg.element() == ScalarAlgebra.BOOL) {
            throw unsupported("Bool matrix products");
        }
        Resolution r = elementResolution(Arithmetic.MUL, a, b, alg.element());
        String rows = lengthOf(a, 0);
        String inner = lengthOf(a, 1);
        String cols = lengthOf(b, 1);
        String element = JavaTypes.javaName(r);
        Function<List<String>, String> dot = idx -> {
            String sum = fresh("s");
            String p = fresh("p");
            line(element + " " + sum + " = 0;");
            line("for (int " + p + " = 0; " + p + " < " + inner + "; " + p + "++) {");
            Val x = scalarVal(a.alg().element(), a.res(), elementAt(a, List.of(idx.get(0), p)));
            Val y = scalarVal(b.alg().element(), b.res(), elementAt(b, List.of(p, idx.get(1))));
            line("    " + sum + " += " + conv(x, r) + " * " + conv(y, r) + ";");
            line("}");
            return sum;
        };
        mayGoOutOfRange = true;
        if (alg.isScalar()) {
            return scalarVal(alg.element(), r, dot.apply(List.of("0", "0")));
        }
        return tabulate(alg, r, List.of(rows, cols), dot);
    }

    private Val matrix(List<List<Val>> rows) {
        ArrayAlgebra alg = TypeRules.matrix(rows.stream().map(row -> row.stream().map(Val::alg).toList()).toList());
        if (rows.size() == 1 && rows.getFirst().size() == 1) {
            return rows.getFirst().getFirst();
        }
        List<Val> all = rows.stream().flatMap(List::stream).toList();
        Resolution r = joinAll(alg.element(), all);
        if (alg.isScalar()) {
            return scalarVal(alg.element(), r, conv(all.getFirst(), r));
        }
        List<String> heights = new ArrayList<>();
        for (List<Val> row : rows) {
            heights.add(temp("int", lengthOf(row.getFirst(), 0)));
        }
        List<List<String>> widths = new ArrayList<>();
        for (List<Val> row : rows) {
            List<String> w = new ArrayList<>();
            for (Val block : row) {
                w.add(temp("int", lengthOf(block, 1)));
            }
            widths.add(w);
        }
        String totalRows = String.join(" + ", heights);
        String totalCols = String.join(" + ", widths.getFirst());
        Val result = allocate(alg, r, List.of(totalRows, totalCols));
        String rowOffset = "0";
        for (int i = 0; i < rows.size(); i++) {
            String colOffset = "0";
            for (int j = 0; j < rows.get(i).size(); j++) {
                Val block = rows.get(i).get(j);
                String ro = rowOffset;
                String co = colOffset;
                forEach(List.of(heights.get(i), widths.get(i).get(j)), idx -> {
                    Val e = block.isScalar() ? block : scalarVal(block.alg().element(), block.res(), elementAt(block, idx));
                    List<String> at = List.of(plus(ro, idx.get(0)), plus(co, idx.get(1)));
                    line(elementAt(result, at) + " = " + conv(e, r) + ";");
                });
                colOffset = plus(colOffset, widths.get(i).get(j));
            }
            rowOffset = plus(rowOffset, heights.get(i));
        }
        return result;
    }

    private Val tuple(List<Val> items) {
        ArrayAlgebra alg = TypeRules.tuple(items.stream().map(Val::alg).toList());
        Resolution r = joinAll(alg.element(), items);
        List<String> lengths = new ArrayList<>();
        lengths.add(String.valueOf(items.size()));
        Val first = items.getFirst();
        for (int k = 0; k < alg.rank() - 1; k++) {
            lengths.add(k < first.alg().rank() ? lengthOf(first, k) : "1");
        }
        Val result = allocate(alg, r, lengths);
        for (int i = 0; i < items.size(); i++) {
            Val item = items.get(i);
            String at0 = String.valueOf(i);
            forEach(lengths.subList(1, lengths.size()), idx -> {
                Val e = item.isScalar() ? item : scalarVal(item.alg().element(), item.res(),
                        elementAt(item, idx.subList(0, Math.min(idx.size(), item.alg().rank()))));
                List<String> at = new ArrayList<>();
                at.add(at0);
                at.addAll(idx);
                line(elementAt(result, at) + " = " + conv(e, r) + ";");
            });
        }
        return result;
    }

    private Resolution joinAll(ScalarAlgebra k, List<Val> values) {
        Operand acc = null;
        for (Val v : values) {
            if (v.isNot()) {
                continue;
            }
            acc = acc == null ? elementOperand(v) : Operand.of(Promotion.join(acc, elementOperand(v)));
        }
        if (acc == null) {
            throw new VelTypeException("no value to take the resolution from");
        }
        return normalize(k, Promotion.resolve(acc));
    }

    // ---- ranges and indexing ---------------------------------------------------------------------

    private Val rangeValue(Val start, Val end) {
        ArrayAlgebra alg = TypeRules.range(start.alg(), end.alg());
        mayGoOutOfRange = true;
        String s = temp("int", conv(start, Resolution.INT32));
        String length = alg.cols().isKnown() ? alg.cols().toString() : temp("int", conv(end, Resolution.INT32) + " - " + s);
        return tabulate(alg, Resolution.INT32, List.of("1", length), idx -> "(" + s + " + " + idx.get(1) + ")");
    }

    /**
     * The subscripts of {@code a[…]} evaluated once: per dimension of {@code a}, a fixed index, or a
     * start and a length. A vector takes a single subscript along its long dimension.
     */
    private record Selection(List<Subscript> subscripts, String[] fixed, String[] start, String[] length) {
    }

    private Selection select(Val a, VelParser.IndexListContext list) {
        List<VelParser.IndexContext> written = list.index();
        int rank = a.alg().rank();
        boolean vector = written.size() == 1 && a.alg().isMatrix() && (a.alg().rows().isOne() || a.alg().cols().isOne());
        if (!vector && written.size() != rank) {
            throw new VelTypeException("expected " + rank + " subscripts for " + a.alg() + ", got " + written.size());
        }
        mayGoOutOfRange = true;
        String[] fixed = new String[rank];
        String[] start = new String[rank];
        String[] length = new String[rank];
        List<Subscript> subscripts = new ArrayList<>();
        for (int s = 0; s < written.size(); s++) {
            int k = vector ? (a.alg().cols().isOne() ? 0 : 1) : s;
            String dimLength = temp("int", lengthOf(a, k));
            ends.push(dimLength);
            try {
                VelParser.IndexContext ix = written.get(s);
                if (ix instanceof VelParser.WholeIndexContext) {
                    subscripts.add(Subscript.all());
                    length[k] = dimLength;
                } else if (((VelParser.ExprIndexContext) ix).expr() instanceof VelParser.RangeExprContext range) {
                    if (range.expr(0) instanceof VelParser.RangeExprContext) {
                        throw unsupported("partitions");
                    }
                    Val from = expr(range.expr(0));
                    Val to = expr(range.expr(1));
                    ArrayAlgebra span = TypeRules.range(from.alg(), to.alg());
                    subscripts.add(Subscript.span(span));
                    start[k] = temp("int", conv(from, Resolution.INT32));
                    length[k] = span.cols().isKnown() ? span.cols().toString()
                            : temp("int", conv(to, Resolution.INT32) + " - " + start[k]);
                } else {
                    Val at = expr(((VelParser.ExprIndexContext) ix).expr());
                    subscripts.add(Subscript.at(at.alg()));
                    fixed[k] = temp("int", conv(at, Resolution.INT32));
                }
            } finally {
                ends.pop();
            }
        }
        for (int k = 0; k < rank; k++) {
            if (fixed[k] == null && length[k] == null) {
                fixed[k] = "0"; // the other side of a vector
            }
        }
        return new Selection(subscripts, fixed, start, length);
    }

    /** The index into {@code a} for position {@code idx} of the selected region. */
    private static List<String> source(Selection s, int rank, List<String> idx) {
        List<String> src = new ArrayList<>();
        for (int k = 0; k < rank; k++) {
            String i = k < idx.size() ? idx.get(k) : "0";
            src.add(s.fixed()[k] != null ? s.fixed()[k] : s.start()[k] != null ? plus(s.start()[k], i) : i);
        }
        return src;
    }

    private Val index(Val a, VelParser.IndexListContext list) {
        Selection s = select(a, list);
        ArrayAlgebra alg = TypeRules.index(a.alg(), s.subscripts());
        int rank = a.alg().rank();
        if (alg.isScalar()) {
            return scalarVal(alg.element(), a.res(), elementAt(a, source(s, rank, List.of())));
        }
        List<String> lengths = new ArrayList<>();
        for (int k = 0; k < rank; k++) {
            lengths.add(s.fixed()[k] != null ? "1" : s.length()[k]);
        }
        return tabulate(alg, a.res(), lengths, idx -> elementAt(a, source(s, rank, idx)));
    }

    /** A copy of {@code a} with the region {@code a[subscripts]} replaced by {@code value} (type-system.md 1.5). */
    private Val indexAssign(Val a, VelParser.IndexListContext list, Val value) {
        Selection s = select(a, list);
        ArrayAlgebra alg = TypeRules.indexAssign(a.alg(), s.subscripts(), value.alg());
        Resolution r = value.isNot() ? a.res()
                : normalize(alg.element(), Promotion.join(Operand.of(a.res()), elementOperand(value)));
        int rank = a.alg().rank();
        List<String> lengths = new ArrayList<>();
        List<String> region = new ArrayList<>();
        for (int k = 0; k < rank; k++) {
            lengths.add(lengthOf(a, k));
            region.add(s.fixed()[k] != null ? "1" : s.length()[k]);
        }
        Val copy = tabulate(alg, r, lengths, idx -> conv(scalarVal(a.alg().element(), a.res(), elementAt(a, idx)), r));
        forEach(region, idx -> {
            Val e = value.isScalar() ? value : scalarVal(value.alg().element(), value.res(),
                    elementAt(value, broadcastIndex(value, idx)));
            line(elementAt(copy, source(s, rank, idx)) + " = " + conv(e, r) + ";");
        });
        return copy;
    }

    /** The index into {@code v} for a position of a larger region, dimensions of size 1 fixed at 0. */
    private static List<String> broadcastIndex(Val v, List<String> idx) {
        List<String> at = new ArrayList<>();
        for (int k = 0; k < v.alg().rank(); k++) {
            at.add(v.alg().dims().get(k).isOne() || k >= idx.size() ? "0" : idx.get(k));
        }
        return at;
    }

    // ------------------------------------------------------------------------------------------
    // Arrays
    // ------------------------------------------------------------------------------------------

    /** The elements of {@code operands} (scalars broadcast, dimensions of size 1 expand) combined by {@code element}. */
    private Val elementWise(ArrayAlgebra alg, Resolution res, List<Val> operands, Function<List<Val>, String> element) {
        List<Val> ops = new ArrayList<>();
        for (Val v : operands) {
            ops.add(v.isScalar() ? materialize(v) : v);
        }
        List<Card> dims = alg.dims();
        List<String> lengths = new ArrayList<>();
        for (int k = 0; k < dims.size(); k++) {
            if (dims.get(k).isKnown()) {
                lengths.add(dims.get(k).toString());
                continue;
            }
            List<String> candidates = new ArrayList<>();
            for (Val v : ops) {
                if (!v.isScalar() && k < v.alg().rank() && !v.alg().dims().get(k).isOne()) {
                    candidates.add(lengthOf(v, k));
                }
            }
            String length = candidates.getFirst();
            for (String c : candidates.subList(1, candidates.size())) {
                length = "Math.max(" + length + ", " + c + ")";
            }
            lengths.add(temp("int", length));
        }
        mayGoOutOfRange = true;
        return tabulate(alg, res, lengths, idx -> {
            List<Val> elements = new ArrayList<>();
            for (Val v : ops) {
                if (v.isScalar()) {
                    elements.add(v);
                } else {
                    List<String> at = new ArrayList<>();
                    for (int k = 0; k < v.alg().rank(); k++) {
                        at.add(v.alg().dims().get(k).isOne() ? "0" : idx.get(k));
                    }
                    elements.add(scalarVal(v.alg().element(), v.res(), elementAt(v, at)));
                }
            }
            return element.apply(elements);
        });
    }

    /** A new array of the given algebra whose element at each index is {@code element.apply(index)}. */
    private Val tabulate(ArrayAlgebra alg, Resolution res, List<String> lengths, Function<List<String>, String> element) {
        Val result = allocate(alg, res, lengths);
        forEach(lengths, idx -> line(elementAt(result, idx) + " = " + element.apply(idx) + ";"));
        return result;
    }

    private Val allocate(ArrayAlgebra alg, Resolution res, List<String> lengths) {
        Val result = new Val(alg, res, fresh("t"), false);
        int r = JavaTypes.javaRank(alg.dims());
        StringBuilder sizes = new StringBuilder();
        for (int k = r - 1; k >= 0; k--) {
            sizes.append('[').append(lengths.get(k)).append(']');
        }
        line("final " + javaType(result) + " " + result.code() + " = new " + JavaTypes.javaName(res) + sizes + ";");
        return result;
    }

    /** Nested loops over the given lengths, outermost over the last dimension (the outer Java array). */
    private void forEach(List<String> lengths, java.util.function.Consumer<List<String>> body) {
        int r = JavaTypes.javaRank(lengths.stream().map(l -> l.equals("1") ? Card.ONE : Card.TOP).toList());
        String[] idx = new String[lengths.size()];
        java.util.Arrays.fill(idx, "0");
        int opened = 0;
        for (int k = r - 1; k >= 0; k--) {
            if (lengths.get(k).equals("1")) {
                continue;
            }
            idx[k] = fresh("i");
            line("for (int " + idx[k] + " = 0; " + idx[k] + " < " + lengths.get(k) + "; " + idx[k] + "++) {");
            indent++;
            opened++;
        }
        body.accept(List.of(idx));
        for (int i = 0; i < opened; i++) {
            indent--;
            line("}");
        }
    }

    /** The Java expression of an element of an array value at a VEL index (one entry per VEL dimension). */
    private static String elementAt(Val v, List<String> idx) {
        int r = JavaTypes.javaRank(v.alg().dims());
        StringBuilder s = new StringBuilder(v.code());
        for (int k = r - 1; k >= 0; k--) {
            s.append('[').append(idx.get(k)).append(']');
        }
        return s.toString();
    }

    /** The length of VEL dimension {@code k} of an array value. */
    private static String lengthOf(Val v, int k) {
        List<Card> dims = v.alg().dims();
        if (k >= dims.size()) {
            return "1";
        }
        if (dims.get(k).isKnown()) {
            return dims.get(k).toString();
        }
        int r = JavaTypes.javaRank(dims);
        return v.code() + "[0]".repeat(r - 1 - k) + ".length";
    }

    /** A scalar broadcast into an array of the given shape (sizes must be known or come from elsewhere). */
    private Val broadcastTo(Val scalar, List<Card> dims) {
        if (dims.stream().anyMatch(d -> !d.isKnown())) {
            throw new VelTypeException("cannot fill an array of unknown size " + dims + " with a scalar");
        }
        Val s = materialize(scalar);
        Resolution r = s.isConstant() ? Promotion.defaultOf(s.alg().constant()) : s.res();
        return tabulate(new ArrayAlgebra(dims, s.alg().element()), r,
                dims.stream().map(Card::toString).toList(), idx -> conv(s, r));
    }

    /** An array expression with the given element resolution; copied when converted, or when owned by Java and {@code leaving}. */
    private String arrayCode(Val v, Resolution target, boolean leaving) {
        if (JavaTypes.javaName(v.res()).equals(JavaTypes.javaName(target)) && !(leaving && v.external())) {
            return v.code();
        }
        List<String> lengths = new ArrayList<>();
        for (int k = 0; k < v.alg().rank(); k++) {
            lengths.add(lengthOf(v, k));
        }
        mayGoOutOfRange = true;
        return tabulate(new ArrayAlgebra(v.alg().dims(), v.alg().element()), target, lengths,
                idx -> conv(scalarVal(v.alg().element(), v.res(), elementAt(v, idx)), target)).code();
    }

    // ------------------------------------------------------------------------------------------
    // Scalars and Java code
    // ------------------------------------------------------------------------------------------

    private static Val scalarVal(ScalarAlgebra k, Resolution r, String code) {
        return new Val(ArrayAlgebra.scalar(k), r, code, false);
    }

    /** Stores a scalar expression in a local when it is not already a name or a literal. */
    private Val materialize(Val v) {
        if (v.isConstant() || v.isNot() || !v.isScalar() || v.code().matches("[\\w$.]+")) {
            return v;
        }
        return scalarVal(v.alg().element(), v.res(), temp(JavaTypes.javaName(v.res()), v.code()));
    }

    /** A scalar value as a Java expression of the primitive for {@code target}. */
    private String conv(Val v, Resolution target) {
        String type = JavaTypes.javaName(target);
        if (v.isNot()) {
            return notCode(target);
        }
        if (!v.isScalar()) {
            throw new VelTypeException("expected a scalar, not " + v.alg());
        }
        if (v.isConstant()) {
            return literal(v.alg().constant(), type);
        }
        String from = JavaTypes.javaName(v.res());
        if (from.equals(type)) {
            return v.code();
        }
        if (from.equals("boolean") || type.equals("boolean")) {
            throw new VelTypeException("cannot convert " + v.alg() + " to " + type);
        }
        return "((" + type + ") " + v.code() + ")";
    }

    private static String notCode(Resolution target) {
        return VEL_MATH + ".<" + JavaTypes.boxed(JavaTypes.javaName(target)) + ">error()";
    }

    /** A Java literal of the given primitive type for an exact constant. */
    private static String literal(Constant c, String type) {
        if (type.equals("boolean")) {
            throw new VelTypeException("cannot use the number " + c + " as a Bool");
        }
        String text;
        if (c instanceof Constant.Int i) {
            text = switch (type) {
                case "long" -> i.value() + "L";
                case "float" -> i.value() + "f";
                case "double" -> i.value() + "d";
                case "int" -> i.value().toString();
                default -> "((" + type + ") " + i.value() + ")";
            };
        } else {
            BigDecimal d = c instanceof Constant.Decimal dec ? dec.value()
                    : new BigDecimal(((Constant.Rational) c).numerator())
                            .divide(new BigDecimal(((Constant.Rational) c).denominator()), MathContext.DECIMAL128);
            String s = d.toString();
            text = switch (type) {
                case "float" -> s + "f";
                case "double" -> s + "d";
                default -> "((" + type + ") " + s + "d)";
            };
        }
        return text.startsWith("-") ? "(" + text + ")" : text;
    }

    /** A resolution consistent with the algebra (java.md 7.3, "대수와 해상도 맞추기"). */
    private static Resolution normalize(ScalarAlgebra k, Resolution r) {
        return switch (k) {
            case NOT -> null;
            case BOOL -> Resolution.BOOL;
            case CARD -> Resolution.CARD;
            case COMPLEX -> throw new VelTypeException("Complex values are not supported by the Java code generator yet");
            default -> ScalarType.of(k, r).resolution();
        };
    }

    private static Resolution resolve(Val v) {
        return v.isConstant() ? Promotion.defaultOf(v.alg().constant()) : v.res();
    }

    private static String javaType(Val v) {
        return JavaTypes.javaName(v.res()) + "[]".repeat(JavaTypes.javaRank(v.alg().dims()));
    }

    private static String plus(String a, String b) {
        if (a.equals("0")) {
            return b;
        }
        if (b.equals("0")) {
            return a;
        }
        return a + " + " + b;
    }

    private String temp(String type, String code) {
        if (code.matches("\\d+|[\\w$]+")) {
            return code;
        }
        String name = fresh("n");
        line("final " + type + " " + name + " = " + code + ";");
        return name;
    }

    private String fresh(String base) {
        return base + "$" + counter++;
    }

    private String freshName(String velName) {
        String name = JAVA_KEYWORDS.contains(velName) ? velName + "$" : velName;
        while (!javaNames.add(name)) {
            name = velName + "$" + counter++;
        }
        return name;
    }

    private void line(String s) {
        out.append("    ".repeat(indent)).append(s).append('\n');
    }

    private VelTypeException unsupported(String what) {
        return new VelTypeException(what + " are not supported by the Java code generator yet");
    }

    private <T> T located(ParserRuleContext ctx, Supplier<T> body) {
        try {
            return body.get();
        } catch (VelCompileError e) {
            throw e;
        } catch (VelTypeException e) {
            throw new VelCompileError(ctx.getStart().getLine(), ctx.getStart().getCharPositionInLine(), e.getMessage());
        }
    }
}
