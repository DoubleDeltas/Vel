package com.doubledeltas.vel.core.type;

import com.doubledeltas.vel.core.type.Promotion.Arithmetic;

/** Binary operators of VEL and the element-level arithmetic each one uses. */
public enum BinaryOperator {
    ADD("+", Arithmetic.ADD, true),
    SUB("-", Arithmetic.SUB, true),
    /** Matrix product; element-wise when an operand is a scalar. */
    MUL("*", Arithmetic.MUL, false),
    /** Right division {@code A·B⁻¹}; true division for scalars. Same as {@link #RIGHT_DIV}. */
    DIV("/", Arithmetic.DIV, false),
    /** Right division {@code A·B⁻¹}, written {@code />}. */
    RIGHT_DIV("/>", Arithmetic.DIV, false),
    /** Left division {@code A⁻¹·B}, written {@code /<}. */
    LEFT_DIV("/<", Arithmetic.DIV, false),
    /** Matrix power; ordinary power for scalars. */
    POW("^", Arithmetic.POW, false),
    FLOOR_DIV("//", Arithmetic.FLOOR_DIV, true),
    TRUNC_DIV("///", Arithmetic.TRUNC_DIV, true),
    FLOOR_MOD("%%", Arithmetic.FLOOR_MOD, true),
    TRUNC_MOD("%%%", Arithmetic.TRUNC_MOD, true),
    /** Bool exclusive or (⊕). */
    XOR("o+", Arithmetic.XOR, true),
    EW_ADD(".+", Arithmetic.ADD, true),
    EW_SUB(".-", Arithmetic.SUB, true),
    EW_MUL(".*", Arithmetic.MUL, true),
    EW_DIV("./", Arithmetic.DIV, true),
    EW_POW(".^", Arithmetic.POW, true);

    private final String symbol;
    private final Arithmetic arithmetic;
    private final boolean elementWise;

    BinaryOperator(String symbol, Arithmetic arithmetic, boolean elementWise) {
        this.symbol = symbol;
        this.arithmetic = arithmetic;
        this.elementWise = elementWise;
    }

    public String symbol() {
        return symbol;
    }

    public Arithmetic arithmetic() {
        return arithmetic;
    }

    /** Whether the operator always works element by element (and therefore broadcasts). */
    public boolean isElementWise() {
        return elementWise;
    }

    public static BinaryOperator fromSymbol(String symbol) {
        for (BinaryOperator op : values()) {
            if (op.symbol.equals(symbol)) {
                return op;
            }
        }
        throw new IllegalArgumentException("unknown operator: " + symbol);
    }
}
