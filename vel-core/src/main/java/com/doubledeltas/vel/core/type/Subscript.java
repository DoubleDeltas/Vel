package com.doubledeltas.vel.core.type;

import java.util.List;

/** One subscript of an index expression {@code m[s₁, s₂, …]}. */
public sealed interface Subscript permits Subscript.At, Subscript.Span, Subscript.Partition, Subscript.All {

    /** A single index: an integer scalar (a {@code Card}). Selects one position; the dimension becomes 1. */
    record At(ArrayType index) implements Subscript {
    }

    /** A range {@code a:b} = [a, b) (its type from {@link TypeRules#range}). Selects as many positions as its length. */
    record Span(ArrayType range) implements Subscript {
    }

    /**
     * Cut points {@code a₀:a₁:…:aₖ} (three or more) splitting the dimension into the blocks
     * [a₀, a₁), …, [aₖ₋₁, aₖ). An index with a partition yields a block tensor.
     */
    record Partition(List<ArrayType> cuts) implements Subscript {
        public Partition {
            cuts = List.copyOf(cuts);
            if (cuts.size() < 3) {
                throw new IllegalArgumentException("a partition has at least 3 cut points; a:b is a Span");
            }
        }
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

    static Subscript partition(List<ArrayType> cuts) {
        return new Partition(cuts);
    }

    static Subscript all() {
        return All.INSTANCE;
    }
}
