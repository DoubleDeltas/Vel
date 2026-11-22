package com.doubledeltas.vel.core.codegen;

import java.util.List;

/**
 * Target-neutral code produced by {@link CodeGenerator} and turned into Java source
 * ({@link JavaPrinter}), Kotlin source ({@link KotlinPrinter}) or Kotlin IR (vel-kotlinc).
 * <p>
 * The model is a small imperative language over JVM primitives and arrays of them, laid out as in
 * java.md 2: an array of rank {@code r} is {@code r} nested arrays, and {@link Get} goes one level down.
 */
public final class Code {

    private Code() {
    }

    /**
     * A JVM primitive ({@code boolean byte short char int long float double}), an unsigned integer
     * stored in the bits of one ({@code ubyte ushort uint ulong}, see {@link JavaTypes#javaName} and
     * {@link ConcreteType#representation}), or an array of it.
     * Arithmetic on unsigned values wraps like the signed one; division, remainder, ordering and
     * conversions are unsigned.
     *
     * @param primitive the element primitive
     * @param rank      the number of array levels; 0 for a scalar
     */
    public record Type(String primitive, int rank) {

        public static final Type INT = new Type("int", 0);
        public static final Type BOOLEAN = new Type("boolean", 0);

        public static Type scalar(String primitive) {
            return new Type(primitive, 0);
        }

        public boolean isArray() {
            return rank > 0;
        }

        /** The type one level down: the element of an array. */
        public Type component() {
            return new Type(primitive, rank - 1);
        }

        public boolean isFloating() {
            return primitive.equals("float") || primitive.equals("double");
        }

        public boolean isUnsigned() {
            return JavaTypes.isUnsigned(primitive);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Expressions
    // ------------------------------------------------------------------------------------------

    public sealed interface Expr permits Lit, Ref, Binary, Neg, Convert, Call, Invoke, Choose, Fail, Get, Length, NewArray {
        Type type();
    }

    /**
     * A literal of a scalar type.
     *
     * @param value a {@link java.math.BigInteger} for integral types (including {@code char}), a
     *              {@link java.math.BigDecimal} for {@code float} and {@code double}, a {@link Boolean}
     */
    public record Lit(Type type, Object value) implements Expr {
    }

    /** A parameter or local variable. */
    public record Ref(String name, Type type) implements Expr {
    }

    public enum Op {
        ADD, SUB, MUL, DIV, REM, AND, OR, XOR, LT, LE, GT, GE, EQ, NE;

        public boolean isComparison() {
            return ordinal() >= LT.ordinal();
        }
    }

    /**
     * A binary operation on two operands of the same type ({@code type} for arithmetic; comparisons
     * give {@code boolean}). {@code DIV} and {@code REM} on integers truncate, as in Java and Kotlin.
     */
    public record Binary(Op op, Expr left, Expr right, Type type) implements Expr {
    }

    public record Neg(Expr operand, Type type) implements Expr {
    }

    /** A primitive conversion (Java cast semantics). */
    public record Convert(Expr operand, Type type) implements Expr {
    }

    /** Static methods of the JDK and of {@code com.doubledeltas.vel.VelMath} called by generated code. */
    public enum Helper {
        FLOOR_DIV("java.lang.Math", "floorDiv"),
        FLOOR_MOD("java.lang.Math", "floorMod"),
        POW("java.lang.Math", "pow"),
        MAX("java.lang.Math", "max"),
        INT_POW("com.doubledeltas.vel.VelMath", "pow"),
        REAL_FLOOR_DIV("com.doubledeltas.vel.VelMath", "floorDiv"),
        REAL_TRUNC_DIV("com.doubledeltas.vel.VelMath", "truncDiv"),
        REAL_FLOOR_MOD("com.doubledeltas.vel.VelMath", "floorMod");

        private final String owner;
        private final String method;

        Helper(String owner, String method) {
            this.owner = owner;
            this.method = method;
        }

        public String owner() {
            return owner;
        }

        public String method() {
            return method;
        }
    }

    public record Call(Helper helper, List<Expr> args, Type type) implements Expr {
    }

    /**
     * A call to a static method of a user-visible class ({@code java.lang.Math.abs(x)}, java.md 3).
     *
     * @param owner  the qualified class name
     * @param params the parameter types, to tell overloads apart; the arguments have exactly these types
     */
    public record Invoke(String owner, String method, List<Type> params, List<Expr> args, Type type) implements Expr {
    }

    /** {@code c ? a : b}: only the chosen operand is evaluated. */
    public record Choose(Expr condition, Expr ifTrue, Expr ifFalse, Type type) implements Expr {
    }

    /** VEL {@code error()} where a value of {@code type} is expected: throws {@code VelException}. */
    public record Fail(Type type) implements Expr {
    }

    /** {@code array[index]}, one level down. */
    public record Get(Expr array, Expr index, Type type) implements Expr {
    }

    /** The length of the outermost level of an array. */
    public record Length(Expr array) implements Expr {
        @Override
        public Type type() {
            return Type.INT;
        }
    }

    /**
     * A new zero-filled array of the given type.
     *
     * @param lengths the lengths of the levels, outermost first ({@code type.rank()} of them)
     */
    public record NewArray(Type type, List<Expr> lengths) implements Expr {
    }

    // ------------------------------------------------------------------------------------------
    // Statements
    // ------------------------------------------------------------------------------------------

    public sealed interface Stmt permits Let, Var, Declare, Assign, Store, For, If, CheckSize, Return, Throw {
    }

    /** An immutable local initialized once. */
    public record Let(String name, Type type, Expr init) implements Stmt {
    }

    /** A mutable local. */
    public record Var(String name, Type type, Expr init) implements Stmt {
    }

    /** An immutable local assigned exactly once on each path afterwards. */
    public record Declare(String name, Type type) implements Stmt {
    }

    public record Assign(String name, Expr value) implements Stmt {
    }

    /** {@code array[index] = value}. */
    public record Store(Expr array, Expr index, Expr value) implements Stmt {
    }

    /** {@code for (int index = 0; index < length; index++) body}. */
    public record For(String index, Expr length, List<Stmt> body) implements Stmt {
    }

    public record If(Expr condition, List<Stmt> then, List<Stmt> otherwise) implements Stmt {
    }

    /** A runtime dimension equality assertion; a mismatch becomes a VEL null-element error (NPE). */
    public record CheckSize(Expr left, Expr right) implements Stmt {
    }

    /** Ends the computation with its result (a method's {@code return}, an inline expression's value). */
    public record Return(Expr value) implements Stmt {
    }

    /** VEL {@code error()} as a statement: throws {@code VelException}. */
    public record Throw() implements Stmt {
    }

    /**
     * Generated code for a VEL program or expression.
     *
     * @param guarded whether out-of-range indices and negative lengths can occur; the statements must
     *                then run inside a handler that reports them as {@code NullPointerException}
     *                ({@code VelMath.outOfRange}, java.md 2.2)
     * @param used    the variables of the target language the code reads, by their VEL names (only these
     *                need converting at the boundary)
     */
    public record Body(List<Stmt> statements, boolean guarded, java.util.Set<String> used) {
    }
}
