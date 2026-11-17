package com.doubledeltas.vel.core.concretization;

import com.doubledeltas.vel.core.type.Constant;

/**
 * An operand as seen by the concretization: either a value whose resolution is already decided (an
 * input, or an intermediate result) or a constant whose resolution is decided by its partner
 * (java.md 7.6).
 */
public sealed interface Operand {

    static Operand of(Resolution resolution) {
        return new Fixed(resolution);
    }

    static Operand of(Constant constant) {
        return new Literal(constant);
    }

    /** A value with a decided resolution. */
    record Fixed(Resolution resolution) implements Operand {
        @Override
        public String toString() {
            return resolution.toString();
        }
    }

    /** A constant (type-system.md 3.3) whose resolution follows its partner. */
    record Literal(Constant value) implements Operand {
        @Override
        public String toString() {
            return "lit(" + value + ")";
        }
    }
}
