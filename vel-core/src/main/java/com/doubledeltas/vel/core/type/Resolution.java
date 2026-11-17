package com.doubledeltas.vel.core.type;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Resolution: the range and precision used to represent scalar values (type-system.md 2).
 * <p>
 * Besides the concrete resolutions, literals carry an <em>undetermined</em> resolution together with
 * their value; they take the resolution of the operand they are used with (type-system.md 3.4).
 */
public sealed interface Resolution {

    Resolution BOOL = new Bool();
    Resolution INT32 = new FixedInt(32, true);
    Resolution INT64 = new FixedInt(64, true);
    Resolution BIG_INT = new BigInt();
    Resolution FLOAT32 = new BinaryFloat(32);
    Resolution FLOAT64 = new BinaryFloat(64);
    Resolution BIG_DECIMAL = new BigDecimalRes();

    static Resolution signed(int bits) {
        return new FixedInt(bits, true);
    }

    static Resolution unsigned(int bits) {
        return new FixedInt(bits, false);
    }

    static Resolution binaryFloat(int bits) {
        return new BinaryFloat(bits);
    }

    static Resolution decimalFloat(int bits) {
        return new DecimalFloat(bits);
    }

    static Resolution literal(BigInteger value) {
        return new IntLiteral(value);
    }

    static Resolution literal(BigDecimal value) {
        return new DecimalLiteral(value);
    }

    /** ⟨Bool⟩ */
    record Bool() implements Resolution {
        @Override
        public String toString() {
            return "Bool";
        }
    }

    /** Z(n) when signed, Z+(n) when unsigned. Overflow wraps around. */
    record FixedInt(int bits, boolean signed) implements Resolution {
        public FixedInt {
            if (bits <= 0) {
                throw new IllegalArgumentException("bits must be positive: " + bits);
            }
        }

        @Override
        public String toString() {
            return (signed ? "Z(" : "Z+(") + bits + ")";
        }
    }

    /** Z(∞) */
    record BigInt() implements Resolution {
        @Override
        public String toString() {
            return "Z(∞)";
        }
    }

    /** F(n): IEEE 754 binary floating point. */
    record BinaryFloat(int bits) implements Resolution {
        public BinaryFloat {
            significandBits(bits); // validates
        }

        /** p(n): significand bits + 1 (the implicit bit). */
        public int precision() {
            return significandBits(bits);
        }

        private static int significandBits(int bits) {
            return switch (bits) {
                case 16 -> 11;
                case 32 -> 24;
                case 64 -> 53;
                case 128 -> 113;
                default -> throw new IllegalArgumentException("unsupported binary float width: " + bits);
            };
        }

        @Override
        public String toString() {
            return "F(" + bits + ")";
        }
    }

    /** D(n): IEEE 754 decimal floating point. */
    record DecimalFloat(int bits) implements Resolution {
        public DecimalFloat {
            decimalDigits(bits); // validates
        }

        /** q(n): significant decimal digits. */
        public int digits() {
            return decimalDigits(bits);
        }

        private static int decimalDigits(int bits) {
            return switch (bits) {
                case 32 -> 7;
                case 64 -> 16;
                case 128 -> 34;
                default -> throw new IllegalArgumentException("unsupported decimal float width: " + bits);
            };
        }

        @Override
        public String toString() {
            return "D(" + bits + ")";
        }
    }

    /** D(∞) */
    record BigDecimalRes() implements Resolution {
        @Override
        public String toString() {
            return "D(∞)";
        }
    }

    /** An integer literal whose resolution is not yet determined. */
    record IntLiteral(BigInteger value) implements Resolution {
        @Override
        public String toString() {
            return "lit(" + value + ")";
        }
    }

    /** A decimal literal whose resolution is not yet determined. */
    record DecimalLiteral(BigDecimal value) implements Resolution {
        /** Kept without trailing zeros so that equal values are equal literals ({@code 1.0 == 1}). */
        public DecimalLiteral {
            value = value.stripTrailingZeros();
        }

        @Override
        public String toString() {
            return "lit(" + value.toPlainString() + ")";
        }
    }

    /**
     * An exact constant that is not a finite decimal, such as {@code 1/3}, produced by folding
     * literal arithmetic. Behaves like a decimal literal; only D(∞) (kept as a fraction) holds it exactly.
     *
     * @param numerator   any integer
     * @param denominator greater than 1, coprime with the numerator, with a prime factor other than 2 and 5
     */
    record RationalLiteral(BigInteger numerator, BigInteger denominator) implements Resolution {
        @Override
        public String toString() {
            return "lit(" + numerator + "/" + denominator + ")";
        }
    }

    default boolean isLiteral() {
        return this instanceof IntLiteral || this instanceof DecimalLiteral || this instanceof RationalLiteral;
    }

    /** Z(n), Z+(n) or Z(∞) (literals excluded). */
    default boolean isInteger() {
        return this instanceof FixedInt || this instanceof BigInt;
    }

    /** F(n), D(n) or D(∞). */
    default boolean isFloating() {
        return this instanceof BinaryFloat || this instanceof DecimalFloat || this instanceof BigDecimalRes;
    }
}
