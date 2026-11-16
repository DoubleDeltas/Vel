package com.doubledeltas.vel.core.type;

/**
 * Scalar type (the "field" of type-system.md 1.1): decides what the arithmetic means.
 * {@code Real <: Complex}; {@code Bool} is unrelated to both.
 */
public enum Field {
    BOOL("Bool"),
    REAL("Real"),
    COMPLEX("Complex");

    private final String symbol;

    Field(String symbol) {
        this.symbol = symbol;
    }

    public boolean isSubtypeOf(Field other) {
        return this == other || (this == REAL && other == COMPLEX);
    }

    /** {@code a ∨ b} (type-system.md 3.1). Bool has no join with a numeric field. */
    public static Field join(Field a, Field b) {
        if (a.isSubtypeOf(b)) {
            return b;
        }
        if (b.isSubtypeOf(a)) {
            return a;
        }
        throw new VelTypeException("no common scalar type for " + a + " and " + b);
    }

    @Override
    public String toString() {
        return symbol;
    }
}
