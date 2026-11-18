package com.doubledeltas.vel.core.codegen;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.antlr.v4.runtime.ParserRuleContext;

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
import com.doubledeltas.vel.core.codegen.Code.Helper;
import com.doubledeltas.vel.core.codegen.Code.If;
import com.doubledeltas.vel.core.codegen.Code.Length;
import com.doubledeltas.vel.core.codegen.Code.Let;
import com.doubledeltas.vel.core.codegen.Code.Lit;
import com.doubledeltas.vel.core.codegen.Code.Neg;
import com.doubledeltas.vel.core.codegen.Code.NewArray;
import com.doubledeltas.vel.core.codegen.Code.Op;
import com.doubledeltas.vel.core.codegen.Code.Ref;
import com.doubledeltas.vel.core.codegen.Code.Return;
import com.doubledeltas.vel.core.codegen.Code.Stmt;
import com.doubledeltas.vel.core.codegen.Code.Store;
import com.doubledeltas.vel.core.codegen.Code.Throw;
import com.doubledeltas.vel.core.codegen.Code.Type;
import com.doubledeltas.vel.core.codegen.Code.Var;
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
 * The concretizer (type-system.md 0.3, java.md 7): turns VEL into target-neutral {@link Code}.
 * Algebras are checked with {@link TypeRules}, resolutions are decided with {@link Promotion}, and
 * arrays become loops over JVM arrays laid out as in java.md 2 (VEL {@code Mat(n, m)} is {@code a[m][n]}).
 * <p>
 * Not supported yet (a {@link VelCompileError} is reported): functions and method calls (except
 * {@code error()}), member access, Complex and {@code j}, {@code inf} as a value, resolutions without a
 * JVM primitive (Z∞, Dn, D∞), matrix division and matrix power, partitions, Bool matrix products.
 */
public final class CodeGenerator {

    /**
     * Names VEL variables must not take in generated code: Java and Kotlin keywords. Temporaries use
     * a {@code __} suffix and a number, which is valid in both languages.
     */
    private static final Set<String> RESERVED = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "var", "yield", "record",
            "as", "fun", "in", "is", "object", "typealias", "typeof", "val", "when");

    /**
     * A VEL value during generation.
     *
     * @param alg      its algebra (a scalar may carry a constant value)
     * @param res      the element resolution; null for a constant scalar (decided by its partner) and for Not
     * @param code     the value (a scalar) or a reference to the array; null for a constant or Not
     * @param external whether the array is owned by the caller (a parameter): copied before it leaves VEL
     */
    private record Val(ArrayAlgebra alg, Resolution res, Expr code, boolean external) {

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

    private List<Stmt> out = new ArrayList<>();
    private int counter;
    private boolean guarded;
    private final Set<String> names = new HashSet<>();
    private final Map<String, Val> params = new LinkedHashMap<>();
    private final Map<String, Val> locals = new HashMap<>();
    private final Deque<Expr> ends = new ArrayDeque<>();
    /** The type the result is concretized to; null to keep the value's own type. */
    private final ConcreteType returnType;

    private CodeGenerator(Map<String, ConcreteType> parameters, ConcreteType returnType) {
        this.returnType = returnType;
        parameters.forEach((name, type) -> {
            names.add(name);
            Type t = new Type(JavaTypes.javaName(type.element().resolution()), type.javaRank());
            params.put(name, new Val(type.algebra(), type.element().resolution(), new Ref(name, t), true));
        });
    }

    /**
     * The code of a VEL program (style B): statements ending with {@link Return}s of the result.
     *
     * @param parameters the parameters in order, by name
     * @param returnType the return type
     * @throws VelCompileError when the program is not well typed or uses what is not supported yet
     */
    public static Body program(VelParser.ProgramContext program, Map<String, ConcreteType> parameters,
                               ConcreteType returnType) {
        CodeGenerator g = new CodeGenerator(parameters, returnType);
        g.program(program);
        return new Body(List.copyOf(g.out), g.guarded);
    }

    /**
     * The code of an inline VEL expression (style C): statements ending with one {@link Return} of the
     * value. No other {@code Return} occurs.
     *
     * @param variables the target-language variables visible at the expression, by name
     * @param target    the type the context expects, or null to keep the value's own type
     */
    public static Body expression(VelParser.SingleExpressionContext expression, Map<String, ConcreteType> variables,
                                  ConcreteType target) {
        CodeGenerator g = new CodeGenerator(variables, target);
        g.located(expression.expr(), () -> {
            g.returnValue(g.expr(expression.expr()));
            return null;
        });
        return new Body(List.copyOf(g.out), g.guarded);
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
                Expr condition = conv(condition(expr(i.expr())), Resolution.BOOL);
                List<Stmt> then = capture(() -> statement(i.statement()));
                out.add(new If(condition, then, List.of()));
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
            out.add(new Throw());
            return;
        }
        if (returnType == null) {
            // no expected type: the value keeps its own resolution (a constant takes its default)
            out.add(new Return(v.isScalar() ? conv(v, resolve(v)) : arrayCode(v, v.res(), true)));
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
            out.add(new Return(conv(v, target.resolution())));
            return;
        }
        checkShape(v.alg().dims(), returnType.dims(), "cannot return " + v.alg() + " as " + returnType + ":");
        Val array = v.isScalar() ? broadcastTo(v, returnType.dims()) : v;
        out.add(new Return(arrayCode(array, target.resolution(), true)));
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
                    ? identifier(name(target))
                    : index(identifier(name(target)), target.indexList());
            value = binary(BinaryOperator.fromSymbol(op.substring(0, op.length() - 1)), current, value);
        }
        bindTarget(target, value);
    }

    private void bindTarget(VelParser.TargetContext target, Val value) {
        String name = name(target);
        if (target.indexList() == null) {
            bind(name, value);
        } else {
            bind(name, indexAssign(identifier(name), target.indexList(), value));
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
        String local = freshName(name);
        Type type = typeOf(value);
        out.add(new Let(local, type, value.code()));
        locals.put(name, new Val(value.alg(), value.res(), new Ref(local, type), value.external()));
    }

    private void destructure(VelParser.DestructuringStmtContext d) {
        Val value = expr(d.expr());
        switch (d.pattern()) {
            case VelParser.TuplePatternContext t -> {
                List<VelParser.PatternItemContext> items = t.patternItem();
                TypeRules.destructureTuple(value.alg(), items.size());
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i) instanceof VelParser.BindItemContext b) {
                        bindTarget(b.target(), slice(value, List.of(intLit(i))));
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
                            bindTarget(b.target(), slice(value, List.of(intLit(i), intLit(j))));
                        }
                    }
                }
            }
            default -> throw new IllegalStateException();
        }
    }

    /** {@code v[i₀, …, iₘ, :, …]}: the leading dimensions fixed, the rest kept. */
    private Val slice(Val v, List<Expr> fixed) {
        guarded = true;
        List<Card> dims = v.alg().dims();
        List<Card> rest = dims.subList(fixed.size(), dims.size());
        if (rest.isEmpty() || rest.stream().allMatch(Card::isOne)) {
            List<Expr> idx = new ArrayList<>(fixed);
            while (idx.size() < dims.size()) {
                idx.add(intLit(0));
            }
            return scalarVal(v.alg().element(), v.res(), elementAt(v, idx));
        }
        ArrayAlgebra alg = new ArrayAlgebra(rest, v.alg().element());
        List<Expr> lengths = new ArrayList<>();
        for (int k = fixed.size(); k < dims.size(); k++) {
            lengths.add(lengthOf(v, k));
        }
        return tabulate(alg, v.res(), lengths, idx -> {
            List<Expr> src = new ArrayList<>(fixed);
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
            case VelParser.BoolLiteralContext b -> scalarVal(ScalarAlgebra.BOOL, Resolution.BOOL,
                    new Lit(Type.BOOLEAN, Boolean.valueOf(b.getText())));
            case VelParser.IdentifierContext i -> identifier(i.getText());
            case VelParser.QuotedIdentifierContext q -> variable(unquote(q.getText()));
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

    private static Val constantVal(Constant c) {
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

    /** A plain name: a VEL variable, then a predeclared name, then a target-language variable (java.md 4). */
    private Val identifier(String name) {
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
                return variable(name);
            }
        }
    }

    private Val variable(String name) {
        Val v = params.get(name);
        if (v == null) {
            throw new VelTypeException("unknown name: " + name);
        }
        return v;
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
            Expr x = conv(a, Resolution.BOOL);
            Expr y = conv(b, Resolution.BOOL);
            Op o = switch (op) {
                case ADD -> Op.OR;
                case MUL -> Op.AND;
                case XOR -> Op.XOR;
                default -> throw new VelTypeException("operator " + op + " is not defined for Bool");
            };
            return scalarVal(k, Resolution.BOOL, new Binary(o, x, y, Type.BOOLEAN));
        }
        Resolution r = normalize(k, Promotion.arithmetic(op, a.operand(), b.operand()));
        Type t = Type.scalar(JavaTypes.javaName(r));
        boolean floating = r.isFloating();
        if (op == Arithmetic.POW) {
            if (b.isConstant() && b.alg().constant() instanceof Constant.Int n && n.value().signum() >= 0
                    && n.value().bitLength() < 31) {
                return scalarVal(k, r, new Call(Helper.INT_POW, List.of(conv(a, r), new Lit(Type.INT, n.value())), t));
            }
            Type f64 = Type.scalar("double");
            Expr pow = new Call(Helper.POW, List.of(conv(a, Resolution.FLOAT64), conv(b, Resolution.FLOAT64)), f64);
            return scalarVal(k, r, conv(scalarVal(ScalarAlgebra.REAL, Resolution.FLOAT64, pow), r));
        }
        Expr x = conv(a, r);
        Expr y = conv(b, r);
        Expr code = switch (op) {
            case ADD -> new Binary(Op.ADD, x, y, t);
            case SUB -> new Binary(Op.SUB, x, y, t);
            case MUL -> new Binary(Op.MUL, x, y, t);
            case DIV -> new Binary(Op.DIV, x, y, t);
            case FLOOR_DIV -> new Call(floating ? Helper.REAL_FLOOR_DIV : Helper.FLOOR_DIV, List.of(x, y), t);
            case TRUNC_DIV -> floating ? new Call(Helper.REAL_TRUNC_DIV, List.of(x, y), t) : new Binary(Op.DIV, x, y, t);
            case FLOOR_MOD -> new Call(floating ? Helper.REAL_FLOOR_MOD : Helper.FLOOR_MOD, List.of(x, y), t);
            case TRUNC_MOD -> new Binary(Op.REM, x, y, t);
            default -> throw new VelTypeException("operator " + op + " is defined only for Bool");
        };
        return scalarVal(k, r, code);
    }

    private Val unary(Val a, boolean minus) {
        ArrayAlgebra alg = TypeRules.unary(a.alg(), minus);
        if (alg.constant() != null || a.isNot() || a.isConstant()) {
            return new Val(alg, null, null, false);
        }
        Resolution r = normalize(alg.element(), Promotion.negate(a.res(), minus));
        Type t = Type.scalar(JavaTypes.javaName(r));
        if (a.isScalar()) {
            return scalarVal(alg.element(), r, minus ? new Neg(conv(a, r), t) : conv(a, r));
        }
        return elementWise(alg, r, List.of(a), e -> minus ? new Neg(conv(e.getFirst(), r), t) : conv(e.getFirst(), r));
    }

    private Val compare(String symbol, Val a, Val b) {
        ScalarAlgebra k = ScalarAlgebra.join(a.alg().element(), b.alg().element());
        Op op = switch (symbol) {
            case "==" -> Op.EQ;
            case "!=" -> Op.NE;
            case "<" -> Op.LT;
            case "<=" -> Op.LE;
            case ">" -> Op.GT;
            case ">=" -> Op.GE;
            default -> throw new IllegalStateException(symbol);
        };
        boolean ordering = op != Op.EQ && op != Op.NE;
        if (ordering && (k == ScalarAlgebra.BOOL || k == ScalarAlgebra.COMPLEX)) {
            throw new VelTypeException("operator " + symbol + " is not defined for " + k);
        }
        ArrayAlgebra alg = new ArrayAlgebra(Shapes.broadcast(a.alg().dims(), b.alg().dims()), ScalarAlgebra.BOOL);
        Function<List<Val>, Expr> element = e -> {
            Val x = e.get(0);
            Val y = e.get(1);
            if (x.isNot() || y.isNot()) {
                return new Fail(Type.BOOLEAN);
            }
            Resolution r = k == ScalarAlgebra.BOOL ? Resolution.BOOL : Promotion.join(x.operand(), y.operand());
            return new Binary(op, conv(x, r), conv(y, r), Type.BOOLEAN);
        };
        if (alg.isScalar()) {
            return scalarVal(ScalarAlgebra.BOOL, Resolution.BOOL, element.apply(List.of(a, b)));
        }
        return elementWise(alg, Resolution.BOOL, List.of(a, b), element);
    }

    private Val conditional(VelParser.ConditionalExprContext c) {
        Val condition = condition(expr(c.expr(0)));
        Expr test = conv(condition, Resolution.BOOL);
        Val[] branch = new Val[2];
        List<Stmt> thenOut = capture(() -> branch[0] = expr(c.expr(1)));
        List<Stmt> elseOut = capture(() -> branch[1] = expr(c.expr(2)));
        Val a = branch[0];
        Val b = branch[1];
        ArrayAlgebra alg = TypeRules.conditional(condition.alg(), a.alg(), b.alg());
        if (alg.element() == ScalarAlgebra.NOT) {
            return new Val(alg, null, null, false);
        }
        Resolution r = normalize(alg.element(), a.isNot() ? resolve(b) : b.isNot() ? resolve(a)
                : Promotion.join(elementOperand(a), elementOperand(b)));
        Type t = new Type(JavaTypes.javaName(r), JavaTypes.javaRank(alg.dims()));
        if (alg.isScalar() && thenOut.isEmpty() && elseOut.isEmpty()) {
            return scalarVal(alg.element(), r, new Choose(test, conv(a, r), conv(b, r), t));
        }
        String name = fresh("c");
        out.add(new Declare(name, t));
        List<Stmt> thenBlock = new ArrayList<>(thenOut);
        List<Stmt> elseBlock = new ArrayList<>(elseOut);
        thenBlock.addAll(capture(() -> out.add(new Assign(name, branchCode(a, alg, r, t)))));
        elseBlock.addAll(capture(() -> out.add(new Assign(name, branchCode(b, alg, r, t)))));
        out.add(new If(test, thenBlock, elseBlock));
        return new Val(alg, r, new Ref(name, t), false);
    }

    /** A branch of a conditional as a value of the conditional's type. */
    private Expr branchCode(Val branch, ArrayAlgebra alg, Resolution r, Type t) {
        if (alg.isScalar()) {
            return conv(branch, r);
        }
        if (branch.isNot()) {
            return new Fail(t);
        }
        return arrayCode(branch.isScalar() ? broadcastTo(branch, alg.dims()) : branch, r, false);
    }

    private Val transpose(Val a) {
        ArrayAlgebra alg = TypeRules.transpose(a.alg());
        if (a.isScalar()) {
            return a;
        }
        List<Card> dims = alg.dims();
        List<Expr> lengths = new ArrayList<>();
        for (int k = 0; k < dims.size(); k++) {
            lengths.add(lengthOf(a, a.alg().rank() - 1 - k));
        }
        return tabulate(alg, a.res(), lengths, idx -> {
            List<Expr> src = new ArrayList<>(idx);
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
        Type t = Type.scalar(JavaTypes.javaName(r));
        Expr rows = lengthOf(a, 0);
        Expr inner = lengthOf(a, 1);
        Expr cols = lengthOf(b, 1);
        Function<List<Expr>, Expr> dot = idx -> {
            String sum = fresh("s");
            String p = fresh("p");
            out.add(new Var(sum, t, zero(t)));
            Ref pi = new Ref(p, Type.INT);
            Val x = scalarVal(a.alg().element(), a.res(), elementAt(a, List.of(idx.get(0), pi)));
            Val y = scalarVal(b.alg().element(), b.res(), elementAt(b, List.of(pi, idx.get(1))));
            Ref s = new Ref(sum, t);
            out.add(new For(p, inner, List.of(new Assign(sum,
                    new Binary(Op.ADD, s, new Binary(Op.MUL, conv(x, r), conv(y, r), t), t)))));
            return s;
        };
        guarded = true;
        if (alg.isScalar()) {
            return scalarVal(alg.element(), r, dot.apply(List.of(intLit(0), intLit(0))));
        }
        return tabulate(alg, r, List.of(rows, cols), dot);
    }

    private static Expr zero(Type t) {
        return t.isFloating() ? new Lit(t, BigDecimal.ZERO) : new Lit(t, BigInteger.ZERO);
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
        List<Expr> heights = new ArrayList<>();
        for (List<Val> row : rows) {
            heights.add(temp(lengthOf(row.getFirst(), 0)));
        }
        List<List<Expr>> widths = new ArrayList<>();
        for (List<Val> row : rows) {
            List<Expr> w = new ArrayList<>();
            for (Val block : row) {
                w.add(temp(lengthOf(block, 1)));
            }
            widths.add(w);
        }
        Expr totalRows = heights.stream().reduce(CodeGenerator::plus).orElseThrow();
        Expr totalCols = widths.getFirst().stream().reduce(CodeGenerator::plus).orElseThrow();
        Val result = allocate(alg, r, List.of(totalRows, totalCols));
        Expr rowOffset = intLit(0);
        for (int i = 0; i < rows.size(); i++) {
            Expr colOffset = intLit(0);
            for (int j = 0; j < rows.get(i).size(); j++) {
                Val block = rows.get(i).get(j);
                Expr ro = rowOffset;
                Expr co = colOffset;
                forEach(List.of(heights.get(i), widths.get(i).get(j)), idx -> {
                    Val e = block.isScalar() ? block : scalarVal(block.alg().element(), block.res(), elementAt(block, idx));
                    store(result, List.of(plus(ro, idx.get(0)), plus(co, idx.get(1))), conv(e, r));
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
        List<Expr> lengths = new ArrayList<>();
        lengths.add(intLit(items.size()));
        Val first = items.getFirst();
        for (int k = 0; k < alg.rank() - 1; k++) {
            lengths.add(k < first.alg().rank() ? lengthOf(first, k) : intLit(1));
        }
        Val result = allocate(alg, r, lengths);
        for (int i = 0; i < items.size(); i++) {
            Val item = items.get(i);
            Expr at0 = intLit(i);
            forEach(lengths.subList(1, lengths.size()), idx -> {
                Val e = item.isScalar() ? item : scalarVal(item.alg().element(), item.res(),
                        elementAt(item, idx.subList(0, Math.min(idx.size(), item.alg().rank()))));
                List<Expr> at = new ArrayList<>();
                at.add(at0);
                at.addAll(idx);
                store(result, at, conv(e, r));
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
        guarded = true;
        Expr s = temp(conv(start, Resolution.INT32));
        Expr length = alg.cols().isKnown() ? intLit(((Card.Finite) alg.cols()).size())
                : temp(new Binary(Op.SUB, conv(end, Resolution.INT32), s, Type.INT));
        return tabulate(alg, Resolution.INT32, List.of(intLit(1), length), idx -> new Binary(Op.ADD, s, idx.get(1), Type.INT));
    }

    /**
     * The subscripts of {@code a[…]} evaluated once: per dimension of {@code a}, a fixed index, or a
     * start and a length. A vector takes a single subscript along its long dimension.
     */
    private record Selection(List<Subscript> subscripts, Expr[] fixed, Expr[] start, Expr[] length) {
    }

    private Selection select(Val a, VelParser.IndexListContext list) {
        List<VelParser.IndexContext> written = list.index();
        int rank = a.alg().rank();
        boolean vector = written.size() == 1 && a.alg().isMatrix() && (a.alg().rows().isOne() || a.alg().cols().isOne());
        if (!vector && written.size() != rank) {
            throw new VelTypeException("expected " + rank + " subscripts for " + a.alg() + ", got " + written.size());
        }
        guarded = true;
        Expr[] fixed = new Expr[rank];
        Expr[] start = new Expr[rank];
        Expr[] length = new Expr[rank];
        List<Subscript> subscripts = new ArrayList<>();
        for (int s = 0; s < written.size(); s++) {
            int k = vector ? (a.alg().cols().isOne() ? 0 : 1) : s;
            Expr dimLength = temp(lengthOf(a, k));
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
                    start[k] = temp(conv(from, Resolution.INT32));
                    length[k] = span.cols().isKnown() ? intLit(((Card.Finite) span.cols()).size())
                            : temp(new Binary(Op.SUB, conv(to, Resolution.INT32), start[k], Type.INT));
                } else {
                    Val at = expr(((VelParser.ExprIndexContext) ix).expr());
                    subscripts.add(Subscript.at(at.alg()));
                    fixed[k] = temp(conv(at, Resolution.INT32));
                }
            } finally {
                ends.pop();
            }
        }
        for (int k = 0; k < rank; k++) {
            if (fixed[k] == null && length[k] == null) {
                fixed[k] = intLit(0); // the other side of a vector
            }
        }
        return new Selection(subscripts, fixed, start, length);
    }

    /** The index into {@code a} for position {@code idx} of the selected region. */
    private static List<Expr> source(Selection s, int rank, List<Expr> idx) {
        List<Expr> src = new ArrayList<>();
        for (int k = 0; k < rank; k++) {
            Expr i = k < idx.size() ? idx.get(k) : intLit(0);
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
        List<Expr> lengths = new ArrayList<>();
        for (int k = 0; k < rank; k++) {
            lengths.add(s.fixed()[k] != null ? intLit(1) : s.length()[k]);
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
        List<Expr> lengths = new ArrayList<>();
        List<Expr> region = new ArrayList<>();
        for (int k = 0; k < rank; k++) {
            lengths.add(lengthOf(a, k));
            region.add(s.fixed()[k] != null ? intLit(1) : s.length()[k]);
        }
        Val copy = tabulate(alg, r, lengths, idx -> conv(scalarVal(a.alg().element(), a.res(), elementAt(a, idx)), r));
        forEach(region, idx -> {
            Val e = value.isScalar() ? value : scalarVal(value.alg().element(), value.res(),
                    elementAt(value, broadcastIndex(value, idx)));
            store(copy, source(s, rank, idx), conv(e, r));
        });
        return copy;
    }

    /** The index into {@code v} for a position of a larger region, dimensions of size 1 fixed at 0. */
    private static List<Expr> broadcastIndex(Val v, List<Expr> idx) {
        List<Expr> at = new ArrayList<>();
        for (int k = 0; k < v.alg().rank(); k++) {
            at.add(v.alg().dims().get(k).isOne() || k >= idx.size() ? intLit(0) : idx.get(k));
        }
        return at;
    }

    // ------------------------------------------------------------------------------------------
    // Arrays
    // ------------------------------------------------------------------------------------------

    /** The elements of {@code operands} (scalars broadcast, dimensions of size 1 expand) combined by {@code element}. */
    private Val elementWise(ArrayAlgebra alg, Resolution res, List<Val> operands, Function<List<Val>, Expr> element) {
        List<Val> ops = new ArrayList<>();
        for (Val v : operands) {
            ops.add(v.isScalar() ? materialize(v) : v);
        }
        List<Card> dims = alg.dims();
        List<Expr> lengths = new ArrayList<>();
        for (int k = 0; k < dims.size(); k++) {
            if (dims.get(k) instanceof Card.Finite f) {
                lengths.add(intLit(f.size()));
                continue;
            }
            List<Expr> candidates = new ArrayList<>();
            for (Val v : ops) {
                if (!v.isScalar() && k < v.alg().rank() && !v.alg().dims().get(k).isOne()) {
                    candidates.add(lengthOf(v, k));
                }
            }
            Expr length = candidates.getFirst();
            for (Expr c : candidates.subList(1, candidates.size())) {
                length = new Call(Helper.MAX, List.of(length, c), Type.INT);
            }
            lengths.add(temp(length));
        }
        guarded = true;
        return tabulate(alg, res, lengths, idx -> {
            List<Val> elements = new ArrayList<>();
            for (Val v : ops) {
                if (v.isScalar()) {
                    elements.add(v);
                } else {
                    List<Expr> at = new ArrayList<>();
                    for (int k = 0; k < v.alg().rank(); k++) {
                        at.add(v.alg().dims().get(k).isOne() ? intLit(0) : idx.get(k));
                    }
                    elements.add(scalarVal(v.alg().element(), v.res(), elementAt(v, at)));
                }
            }
            return element.apply(elements);
        });
    }

    /** A new array of the given algebra whose element at each index is {@code element.apply(index)}. */
    private Val tabulate(ArrayAlgebra alg, Resolution res, List<Expr> lengths, Function<List<Expr>, Expr> element) {
        Val result = allocate(alg, res, lengths);
        forEach(lengths, idx -> store(result, idx, element.apply(idx)));
        return result;
    }

    private Val allocate(ArrayAlgebra alg, Resolution res, List<Expr> lengths) {
        int r = JavaTypes.javaRank(alg.dims());
        Type t = new Type(JavaTypes.javaName(res), r);
        List<Expr> outerFirst = new ArrayList<>();
        for (int k = r - 1; k >= 0; k--) {
            outerFirst.add(lengths.get(k));
        }
        String name = fresh("t");
        out.add(new Let(name, t, new NewArray(t, outerFirst)));
        return new Val(alg, res, new Ref(name, t), false);
    }

    /** Nested loops over the given lengths, outermost over the last dimension (the outer array). */
    private void forEach(List<Expr> lengths, Consumer<List<Expr>> body) {
        int r = 0;
        for (int k = lengths.size() - 1; k >= 0; k--) {
            if (!isOne(lengths.get(k))) {
                r = k + 1;
                break;
            }
        }
        Expr[] idx = new Expr[lengths.size()];
        java.util.Arrays.fill(idx, intLit(0));
        List<Integer> loops = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int k = r - 1; k >= 0; k--) {
            if (isOne(lengths.get(k))) {
                continue;
            }
            String i = fresh("i");
            idx[k] = new Ref(i, Type.INT);
            loops.add(k);
            names.add(i);
        }
        List<Stmt> inner = capture(() -> body.accept(List.of(idx)));
        for (int n = loops.size() - 1; n >= 0; n--) {
            inner = List.of(new For(names.get(n), lengths.get(loops.get(n)), inner));
        }
        out.addAll(inner);
    }

    private static boolean isOne(Expr e) {
        return e instanceof Lit lit && lit.value() instanceof BigInteger v && v.equals(BigInteger.ONE);
    }

    /** An element of an array value at a VEL index (one entry per VEL dimension). */
    private static Expr elementAt(Val v, List<Expr> idx) {
        int r = JavaTypes.javaRank(v.alg().dims());
        Expr e = v.code();
        for (int k = r - 1; k >= 0; k--) {
            e = new Get(e, idx.get(k), e.type().component());
        }
        return e;
    }

    private void store(Val array, List<Expr> idx, Expr value) {
        int r = JavaTypes.javaRank(array.alg().dims());
        Expr e = array.code();
        for (int k = r - 1; k >= 1; k--) {
            e = new Get(e, idx.get(k), e.type().component());
        }
        out.add(new Store(e, idx.getFirst(), value));
    }

    /** The length of VEL dimension {@code k} of an array value. */
    private static Expr lengthOf(Val v, int k) {
        List<Card> dims = v.alg().dims();
        if (k >= dims.size()) {
            return intLit(1);
        }
        if (dims.get(k) instanceof Card.Finite f) {
            return intLit(f.size());
        }
        int r = JavaTypes.javaRank(dims);
        Expr e = v.code();
        for (int i = 0; i < r - 1 - k; i++) {
            e = new Get(e, intLit(0), e.type().component());
        }
        return new Length(e);
    }

    /** A scalar broadcast into an array of the given known shape. */
    private Val broadcastTo(Val scalar, List<Card> dims) {
        if (dims.stream().anyMatch(d -> !d.isKnown())) {
            throw new VelTypeException("cannot fill an array of unknown size " + dims + " with a scalar");
        }
        Val s = materialize(scalar);
        Resolution r = s.isConstant() ? Promotion.defaultOf(s.alg().constant()) : s.res();
        return tabulate(new ArrayAlgebra(dims, s.alg().element()), r,
                dims.stream().map(d -> (Expr) intLit(((Card.Finite) d).size())).toList(), idx -> conv(s, r));
    }

    /** An array with the given element resolution; copied when converted, or when owned by the caller and {@code leaving}. */
    private Expr arrayCode(Val v, Resolution target, boolean leaving) {
        if (JavaTypes.javaName(v.res()).equals(JavaTypes.javaName(target)) && !(leaving && v.external())) {
            return v.code();
        }
        List<Expr> lengths = new ArrayList<>();
        for (int k = 0; k < v.alg().rank(); k++) {
            lengths.add(lengthOf(v, k));
        }
        guarded = true;
        return tabulate(new ArrayAlgebra(v.alg().dims(), v.alg().element()), target, lengths,
                idx -> conv(scalarVal(v.alg().element(), v.res(), elementAt(v, idx)), target)).code();
    }

    // ------------------------------------------------------------------------------------------
    // Scalars
    // ------------------------------------------------------------------------------------------

    private static Val scalarVal(ScalarAlgebra k, Resolution r, Expr code) {
        return new Val(ArrayAlgebra.scalar(k), r, code, false);
    }

    /** Stores a scalar expression in a local when it is not already a reference or a literal. */
    private Val materialize(Val v) {
        if (v.isConstant() || v.isNot() || !v.isScalar() || v.code() instanceof Ref || v.code() instanceof Lit) {
            return v;
        }
        return scalarVal(v.alg().element(), v.res(), temp(v.code()));
    }

    /** A scalar value as an expression of the primitive for {@code target}. */
    private Expr conv(Val v, Resolution target) {
        Type type = Type.scalar(JavaTypes.javaName(target));
        if (v.isNot()) {
            return new Fail(type);
        }
        if (!v.isScalar()) {
            throw new VelTypeException("expected a scalar, not " + v.alg());
        }
        if (v.isConstant()) {
            return literal(v.alg().constant(), type);
        }
        String from = JavaTypes.javaName(v.res());
        if (from.equals(type.primitive())) {
            return v.code();
        }
        if (from.equals("boolean") || type.primitive().equals("boolean")) {
            throw new VelTypeException("cannot convert " + v.alg() + " to " + type.primitive());
        }
        return new Convert(v.code(), type);
    }

    /** A literal of the given scalar type for an exact constant. */
    private static Expr literal(Constant c, Type type) {
        if (type.primitive().equals("boolean")) {
            throw new VelTypeException("cannot use the number " + c + " as a Bool");
        }
        if (c instanceof Constant.Int i) {
            return type.isFloating() ? new Lit(type, new BigDecimal(i.value())) : new Lit(type, i.value());
        }
        BigDecimal d = c instanceof Constant.Decimal dec ? dec.value()
                : new BigDecimal(((Constant.Rational) c).numerator())
                        .divide(new BigDecimal(((Constant.Rational) c).denominator()), MathContext.DECIMAL128);
        if (type.isFloating()) {
            return new Lit(type, d);
        }
        return new Convert(new Lit(Type.scalar("double"), d), type);
    }

    private static Lit intLit(long v) {
        return new Lit(Type.INT, BigInteger.valueOf(v));
    }

    /** A resolution consistent with the algebra (java.md 7.3, "대수와 해상도 맞추기"). */
    private static Resolution normalize(ScalarAlgebra k, Resolution r) {
        return switch (k) {
            case NOT -> null;
            case BOOL -> Resolution.BOOL;
            case CARD -> Resolution.CARD;
            case COMPLEX -> throw new VelTypeException("Complex values are not supported by the code generator yet");
            default -> ScalarType.of(k, r).resolution();
        };
    }

    private static Resolution resolve(Val v) {
        return v.isConstant() ? Promotion.defaultOf(v.alg().constant()) : v.res();
    }

    private static Type typeOf(Val v) {
        return new Type(JavaTypes.javaName(v.res()), JavaTypes.javaRank(v.alg().dims()));
    }

    private static Expr plus(Expr a, Expr b) {
        if (a instanceof Lit l && l.value() instanceof BigInteger v && v.signum() == 0) {
            return b;
        }
        if (b instanceof Lit l && l.value() instanceof BigInteger v && v.signum() == 0) {
            return a;
        }
        return new Binary(Op.ADD, a, b, Type.INT);
    }

    /** The expression itself when it is a reference or a literal, otherwise a new local holding it. */
    private Expr temp(Expr e) {
        if (e instanceof Ref || e instanceof Lit) {
            return e;
        }
        String name = fresh("n");
        out.add(new Let(name, e.type(), e));
        return new Ref(name, e.type());
    }

    /** The statements emitted by {@code body}, collected apart from the current ones. */
    private List<Stmt> capture(Runnable body) {
        List<Stmt> saved = out;
        out = new ArrayList<>();
        try {
            body.run();
            return out;
        } finally {
            out = saved;
        }
    }

    private String fresh(String base) {
        return base + "__" + counter++;
    }

    private String freshName(String velName) {
        String name = RESERVED.contains(velName) ? velName + "__" : velName;
        while (!names.add(name)) {
            name = velName + "__" + counter++;
        }
        return name;
    }

    private static VelTypeException unsupported(String what) {
        return new VelTypeException(what + " are not supported by the code generator yet");
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
