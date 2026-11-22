package com.doubledeltas.vel.core.type;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cardinal: an element of the scalar algebra {@code Card} (type-system.md 0.1, 1.1, 1.2), used as an
 * array dimension size or an index.
 * <p>
 * A cardinal is one of
 * <ul>
 *   <li>{@link Finite}: a size {@code n >= 0} known at compile time;</li>
 *   <li>{@link Expr}: a <b>size expression</b>, a finite size known only at runtime, written as a linear
 *       expression {@code c₀ + c₁ℓ₁ + … + cₖℓₖ} of <b>length variables</b> {@link Var} with integer
 *       coefficients (type-system.md 1.2). A length variable stands for a finite length decided at
 *       runtime, such as the length of a Java {@code double[]};</li>
 *   <li>{@link #TOP} (⊤, {@code inf}): the <b>infinite</b> length ℵ₀;</li>
 *   <li>{@link #BOTTOM} (⊥): the invalid cardinal.</li>
 * </ul>
 * {@code 0 < 1 < 2 < ... < ⊤}; ⊥ is outside this order. ⊥ plays the role of null: an operation whose
 * result cannot be a cardinal gives ⊥, and using ⊥ (as a size, an index, or in an operation) is an
 * error, a compile error when known at compile time.
 * <p>
 * Size expressions are kept in a normal form (no zero coefficients, the variables in order, and an
 * expression without variables is a {@link Finite}), so that two sizes are equal by {@link #equals}
 * exactly when they are the same expression: {@code ℓ + 1 + ℓ' = ℓ' + ℓ + 1}, {@code (ℓ + 2) - 2 = ℓ}.
 */
public sealed interface Card permits Card.Finite, Card.Expr, Card.Top, Card.Bottom {

    Card ONE = new Finite(1);
    Card ZERO = new Finite(0);
    Card TOP = Top.INSTANCE;
    Card BOTTOM = Bottom.INSTANCE;

    /** The cardinal {@code size}, or ⊥ when it is negative. */
    static Card of(long size) {
        if (size < 0) {
            return BOTTOM;
        }
        return size == 1 ? ONE : size == 0 ? ZERO : new Finite(size);
    }

    /** A fresh length variable, different from every other: a finite length known only at runtime. */
    static Card fresh() {
        return Expr.of(0, Map.of(Var.fresh(), 1L));
    }

    /** A size known at compile time. */
    record Finite(long size) implements Card {
        public Finite {
            if (size < 0) {
                throw new IllegalArgumentException("negative size: " + size);
            }
        }

        @Override
        public String toString() {
            return Long.toString(size);
        }
    }

    /**
     * A length variable ℓ: a finite length decided at runtime. Equal only to itself; variables are
     * ordered by creation, which fixes the order of the terms of a size expression.
     */
    record Var(long id) implements Comparable<Var> {
        private static final AtomicLong COUNTER = new AtomicLong();

        static Var fresh() {
            return new Var(COUNTER.incrementAndGet());
        }

        @Override
        public int compareTo(Var o) {
            return Long.compare(id, o.id);
        }

        @Override
        public String toString() {
            return "ℓ" + id;
        }
    }

    /**
     * A size expression {@code constant + Σ coefficient·variable} with at least one variable, in normal
     * form. Its value must be {@code >= 0} at runtime; a negative value is the invalid cardinal, found at
     * runtime (type-system.md 1.1).
     *
     * @param terms the coefficients by variable, all nonzero, sorted by variable
     */
    record Expr(long constant, Map<Var, Long> terms) implements Card {
        public Expr {
            TreeMap<Var, Long> t = new TreeMap<>();
            terms.forEach((v, c) -> {
                if (c != 0) {
                    t.put(v, c);
                }
            });
            if (t.isEmpty()) {
                throw new IllegalArgumentException("a size expression has a variable; use Card.of for " + constant);
            }
            terms = java.util.Collections.unmodifiableSortedMap(t);
        }

        /** The normal form of {@code constant + terms}: a {@link Finite} (or ⊥) without variables. */
        static Card of(long constant, Map<Var, Long> terms) {
            if (terms.values().stream().allMatch(c -> c == 0)) {
                return Card.of(constant);
            }
            return new Expr(constant, terms);
        }

        @Override
        public String toString() {
            StringBuilder s = new StringBuilder();
            terms.forEach((v, c) -> {
                if (!s.isEmpty()) {
                    s.append(c < 0 ? " - " : " + ");
                } else if (c < 0) {
                    s.append("-");
                }
                long a = Math.abs(c);
                s.append(a == 1 ? "" : a).append(v);
            });
            if (constant != 0) {
                s.append(constant < 0 ? " - " : " + ").append(Math.abs(constant));
            }
            return s.toString();
        }
    }

    /** ⊤: the infinite length ℵ₀ ({@code inf}). */
    enum Top implements Card {
        INSTANCE;

        @Override
        public String toString() {
            return "⊤";
        }
    }

    /** ⊥: the invalid cardinal (null). */
    enum Bottom implements Card {
        INSTANCE;

        @Override
        public String toString() {
            return "⊥";
        }
    }

    /** Whether the size is known at compile time (a natural number). */
    default boolean isKnown() {
        return this instanceof Finite;
    }

    /** Whether the size is finite: a natural number or a size expression (not ⊤, not ⊥). */
    default boolean isFinite() {
        return this instanceof Finite || this instanceof Expr;
    }

    /** Whether the size is ⊤, the infinite length. */
    default boolean isInfinite() {
        return this == TOP;
    }

    default boolean isBottom() {
        return this == BOTTOM;
    }

    /** Whether the size is known at compile time to be 0. */
    default boolean isZero() {
        return this instanceof Finite f && f.size() == 0;
    }

    /** Whether the size is known at compile time to be 1 (only such dimensions are broadcast). */
    default boolean isOne() {
        return this instanceof Finite f && f.size() == 1;
    }

    /** The number of length variables in the expression (0 for a natural number, ⊤ and ⊥). */
    default int variables() {
        return this instanceof Expr e ? e.terms().size() : 0;
    }

    /** Fails with a compile error when this is ⊥, which cannot be used (type-system.md 1.1). */
    default Card requireValid(String what) {
        if (isBottom()) {
            throw new VelTypeException(what + " is ⊥ (null)");
        }
        return this;
    }

    /** {@code n + m}, adding size expressions; {@code d + ⊤ = ⊤}. Using ⊥ is an error. */
    default Card plus(Card other) {
        requireValid("operand of +");
        other.requireValid("operand of +");
        return linear(this, other, 1);
    }

    /**
     * {@code n - m}, a size expression; for natural numbers it is ⊥ when {@code m > n}. A size
     * expression that may be negative is kept: its value is checked at runtime. {@code ⊤ - d} is ⊤ and
     * {@code d - ⊤} is ⊥ for a finite {@code d} (to be refined with infinite arrays). Using ⊥ is an error.
     */
    default Card minus(Card other) {
        requireValid("operand of -");
        other.requireValid("operand of -");
        if (other.isInfinite()) {
            return isInfinite() ? TOP : BOTTOM;
        }
        return linear(this, other, -1);
    }

    /**
     * {@code max(n, m)}; {@code max(d, ⊤) = ⊤}. Not linear: unless both are known or equal, the result
     * is a fresh length variable. Using ⊥ is an error.
     */
    default Card max(Card other) {
        requireValid("operand of max");
        other.requireValid("operand of max");
        if (isInfinite() || other.isInfinite()) {
            return TOP;
        }
        if (this instanceof Finite a && other instanceof Finite b) {
            return of(Math.max(a.size(), b.size()));
        }
        return equals(other) ? this : fresh();
    }

    private static Card linear(Card a, Card b, long sign) {
        if (a.isInfinite() || b.isInfinite()) {
            return TOP;
        }
        TreeMap<Var, Long> terms = new TreeMap<>();
        long constant = Math.addExact(constantOf(a), Math.multiplyExact(sign, constantOf(b)));
        termsOf(a).forEach((v, c) -> terms.merge(v, c, Math::addExact));
        termsOf(b).forEach((v, c) -> terms.merge(v, Math.multiplyExact(sign, c), Math::addExact));
        return Expr.of(constant, terms);
    }

    private static long constantOf(Card c) {
        return switch (c) {
            case Finite f -> f.size();
            case Expr e -> e.constant();
            default -> throw new IllegalStateException(c.toString());
        };
    }

    private static Map<Var, Long> termsOf(Card c) {
        return c instanceof Expr e ? e.terms() : Map.of();
    }
}
