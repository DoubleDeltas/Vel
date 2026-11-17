package com.doubledeltas.vel.core.type;

/**
 * Scalar algebra (type-system.md 0.2, 1.1): the atomic algebra that decides what the arithmetic means.
 * A scalar algebra has no resolution; resolutions belong to concretization
 * ({@link com.doubledeltas.vel.core.concretization.ScalarType}).
 * {@code Int <: Real <: Complex}; {@code Bool} is unrelated to the others.
 */
public enum ScalarAlgebra {
    BOOL("Bool"),
    INT("Int"),
    REAL("Real"),
    COMPLEX("Complex");

    private final String symbol;

    ScalarAlgebra(String symbol) {
        this.symbol = symbol;
    }

    public boolean isSubtypeOf(ScalarAlgebra other) {
        if (this == other) {
            return true;
        }
        return this != BOOL && other != BOOL && ordinal() < other.ordinal();
    }

    /** {@code a ∨ b} (type-system.md 3.1). Bool has no join with a numeric algebra. */
    public static ScalarAlgebra join(ScalarAlgebra a, ScalarAlgebra b) {
        if (a.isSubtypeOf(b)) {
            return b;
        }
        if (b.isSubtypeOf(a)) {
            return a;
        }
        throw new VelTypeException("no common scalar algebra for " + a + " and " + b);
    }

    @Override
    public String toString() {
        return symbol;
    }
}
