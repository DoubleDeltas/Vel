package com.doubledeltas.vel.core.type;

/** One subscript of an index expression {@code m[s₁, s₂, …]}. */
public sealed interface Subscript permits Subscript.At, Subscript.Span, Subscript.All {

    /** A single index: an integer scalar (a {@code Card}). Selects one position; the dimension becomes 1. */
    record At(ArrayType index) implements Subscript {
    }

    /** A range {@code a:b} (its type from {@link TypeRules#range}). Selects as many positions as its length. */
    record Span(ArrayType range) implements Subscript {
    }

    /** {@code :}, the whole dimension. */
    enum All implements Subscript {
        INSTANCE
    }

    static Subscript at(ArrayType index) {
        return new At(index);
    }

    static Subscript span(ArrayType range) {
        return new Span(range);
    }

    static Subscript all() {
        return All.INSTANCE;
    }
}
