package com.doubledeltas.vel.core.type;

/** Element-level arithmetic, after mapping element-wise and matrix operators to these. */
public enum Arithmetic {
    ADD, SUB, MUL, DIV, FLOOR_DIV, TRUNC_DIV, FLOOR_MOD, TRUNC_MOD, POW,
    /** Bool exclusive or (⊕). */
    XOR;

    /** {@code //}, {@code ///}, {@code %%}, {@code %%%}. */
    public boolean isQuotientOrRemainder() {
        return this == FLOOR_DIV || this == TRUNC_DIV || this == FLOOR_MOD || this == TRUNC_MOD;
    }
}
