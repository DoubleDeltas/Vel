package com.doubledeltas.vel.core.concretization;

/**
 * Resolution: the range and precision used to represent scalar values (type-system.md 2). There are no
 * conversion rules between resolutions inside VEL; a resolution is a symbol for the range, overflow,
 * casting and significant digits that a concretization follows (type-system.md 0.3, java.md 7).
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

    /** ⟨Bool⟩ */
    record Bool() implements Resolution {
        @Override
        public String toString() {
            return "Bool";
        }
    }

    /** Zn when signed, Z+n when unsigned. Overflow wraps around. */
    record FixedInt(int bits, boolean signed) implements Resolution {
        public FixedInt {
            if (bits <= 0) {
                throw new IllegalArgumentException("bits must be positive: " + bits);
            }
        }

        @Override
        public String toString() {
            return (signed ? "Z" : "Z+") + bits;
        }
    }

    /** Z∞ */
    record BigInt() implements Resolution {
        @Override
        public String toString() {
            return "Z∞";
        }
    }

    /** Fn: IEEE 754 binary floating point. */
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
            return "F" + bits;
        }
    }

    /** Dn: IEEE 754 decimal floating point. */
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
            return "D" + bits;
        }
    }

    /** D∞ */
    record BigDecimalRes() implements Resolution {
        @Override
        public String toString() {
            return "D∞";
        }
    }

    /** Zn, Z+n or Z∞. */
    default boolean isInteger() {
        return this instanceof FixedInt || this instanceof BigInt;
    }

    /** Fn, Dn or D∞. */
    default boolean isFloating() {
        return this instanceof BinaryFloat || this instanceof DecimalFloat || this instanceof BigDecimalRes;
    }
}
