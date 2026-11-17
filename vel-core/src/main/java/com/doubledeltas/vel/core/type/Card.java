package com.doubledeltas.vel.core.type;

/**
 * Cardinal: an element of the scalar algebra {@code Card} (type-system.md 0.1, 1.1, 1.2), used as an
 * array dimension size or an index.
 * <p>
 * Either a known size {@code n >= 0}, {@link #TOP} (⊤), a size unknown at compile time, or
 * {@link #BOTTOM} (⊥), the invalid cardinal. {@code 0 < 1 < 2 < ... < ⊤}; ⊥ is outside this order.
 * ⊥ plays the role of null: an operation whose result cannot be a cardinal gives ⊥, and using ⊥
 * (as a size, an index, or in an operation) is an error, a compile error when known at compile time.
 */
public sealed interface Card permits Card.Finite, Card.Top, Card.Bottom {

    Card ONE = new Finite(1);
    Card TOP = Top.INSTANCE;
    Card BOTTOM = Bottom.INSTANCE;

    /** The cardinal {@code size}, or ⊥ when it is negative. */
    static Card of(long size) {
        if (size < 0) {
            return BOTTOM;
        }
        return size == 1 ? ONE : new Finite(size);
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

    /** ⊤: a size known only at runtime. */
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

    default boolean isKnown() {
        return this instanceof Finite;
    }

    default boolean isBottom() {
        return this == BOTTOM;
    }

    /** Whether the size is known at compile time to be 1 (only such dimensions are broadcast). */
    default boolean isOne() {
        return this instanceof Finite f && f.size() == 1;
    }

    /** Fails with a compile error when this is ⊥, which cannot be used (type-system.md 1.1). */
    default Card requireValid(String what) {
        if (isBottom()) {
            throw new VelTypeException(what + " is ⊥ (null)");
        }
        return this;
    }

    /** {@code n + m}; {@code n + ⊤ = ⊤}. Using ⊥ is an error. */
    default Card plus(Card other) {
        requireValid("operand of +");
        other.requireValid("operand of +");
        if (this instanceof Finite a && other instanceof Finite b) {
            return of(Math.addExact(a.size(), b.size()));
        }
        return TOP;
    }

    /** {@code n - m}, which is ⊥ when {@code m > n}; ⊤ when either is ⊤. Using ⊥ is an error. */
    default Card minus(Card other) {
        requireValid("operand of -");
        other.requireValid("operand of -");
        if (this instanceof Finite a && other instanceof Finite b) {
            return of(a.size() - b.size());
        }
        return TOP;
    }

    /** {@code max(n, ⊤) = ⊤}. Using ⊥ is an error. */
    default Card max(Card other) {
        requireValid("operand of max");
        other.requireValid("operand of max");
        if (this instanceof Finite a && other instanceof Finite b) {
            return of(Math.max(a.size(), b.size()));
        }
        return TOP;
    }
}
