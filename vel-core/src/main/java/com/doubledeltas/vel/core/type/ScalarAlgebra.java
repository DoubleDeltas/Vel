package com.doubledeltas.vel.core.type;

/**
 * Scalar algebra (type-system.md 0.2, 1.1): the atomic algebra that decides what the arithmetic means.
 * A scalar algebra has no resolution; resolutions belong to concretization
 * ({@link com.doubledeltas.vel.core.concretization.ScalarType}).
 * {@code Int <: Real <: Complex}; {@code Bool} is unrelated to the others. {@code Card} (cardinals,
 * type-system.md 1.1) is not a subtype of {@code Int}, but converts to {@code Int} in arithmetic and
 * from {@code Int} in index positions.
 */
public enum ScalarAlgebra {
    BOOL("Bool"),
    INT("Int"),
    REAL("Real"),
    COMPLEX("Complex"),
    CARD("Card");

    private final String symbol;

    ScalarAlgebra(String symbol) {
        this.symbol = symbol;
    }

    public boolean isSubtypeOf(ScalarAlgebra other) {
        if (this == other) {
            return true;
        }
        return isNumber() && other.isNumber() && ordinal() < other.ordinal();
    }

    /** {@code Int}, {@code Real} or {@code Complex}. */
    public boolean isNumber() {
        return this == INT || this == REAL || this == COMPLEX;
    }

    /** The algebra used in arithmetic: {@code Card} is computed as {@code Int} (type-system.md 1.1). */
    public ScalarAlgebra arithmetic() {
        return this == CARD ? INT : this;
    }

    /**
     * {@code a ∨ b} (type-system.md 3.1). Bool has no join with a numeric algebra. {@code Card ∨ Card}
     * is {@code Card}; with a number, {@code Card} converts to {@code Int}.
     */
    public static ScalarAlgebra join(ScalarAlgebra a, ScalarAlgebra b) {
        if (a == CARD && b == CARD) {
            return CARD;
        }
        a = a.arithmetic();
        b = b.arithmetic();
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
