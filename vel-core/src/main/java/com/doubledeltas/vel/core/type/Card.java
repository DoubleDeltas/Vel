package com.doubledeltas.vel.core.type;

/**
 * Cardinal: the size of an array dimension (type-system.md 0.1, 1.2).
 * <p>
 * Either a known size {@code n >= 0} or {@link #TOP} (⊤), a size unknown at compile time.
 * {@code 0 < 1 < 2 < ... < ⊤}.
 */
public sealed interface Card permits Card.Finite, Card.Top {

    Card ONE = new Finite(1);
    Card TOP = Top.INSTANCE;

    static Card of(long size) {
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

    default boolean isKnown() {
        return this instanceof Finite;
    }

    /** Whether the size is known at compile time to be 1 (only such dimensions are broadcast). */
    default boolean isOne() {
        return this instanceof Finite f && f.size() == 1;
    }

    /** {@code n + m}; {@code n + ⊤ = ⊤}. */
    default Card plus(Card other) {
        if (this instanceof Finite a && other instanceof Finite b) {
            return of(Math.addExact(a.size(), b.size()));
        }
        return TOP;
    }

    /** {@code max(n, ⊤) = ⊤}. */
    default Card max(Card other) {
        if (this instanceof Finite a && other instanceof Finite b) {
            return of(Math.max(a.size(), b.size()));
        }
        return TOP;
    }
}
