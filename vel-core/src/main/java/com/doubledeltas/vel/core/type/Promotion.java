package com.doubledeltas.vel.core.type;

import java.math.BigDecimal;
import java.math.BigInteger;

import com.doubledeltas.vel.core.type.Resolution.BigDecimalRes;
import com.doubledeltas.vel.core.type.Resolution.BigInt;
import com.doubledeltas.vel.core.type.Resolution.BinaryFloat;
import com.doubledeltas.vel.core.type.Resolution.Bool;
import com.doubledeltas.vel.core.type.Resolution.DecimalFloat;
import com.doubledeltas.vel.core.type.Resolution.DecimalLiteral;
import com.doubledeltas.vel.core.type.Resolution.FixedInt;
import com.doubledeltas.vel.core.type.Resolution.IntLiteral;
import com.doubledeltas.vel.core.type.Resolution.RationalLiteral;

/** Resolution promotion rules (type-system.md 3). */
public final class Promotion {

    /** Integer results of arithmetic are at least this wide (type-system.md 3.3, "최소 연산 폭"). */
    public static final int MIN_ARITHMETIC_WIDTH = 32;

    /** Literal powers are folded exactly only up to this exponent, to keep constants small. */
    static final int MAX_FOLDED_EXPONENT = 1024;

    private Promotion() {
    }

    // ------------------------------------------------------------------------------------------
    // ⊑ (3.2)
    // ------------------------------------------------------------------------------------------

    /**
     * {@code a ⊑ b}: every value of {@code a} is exactly representable in {@code b}, i.e. the
     * implicit conversion from {@code a} to {@code b} is allowed inside VEL. A literal is checked by
     * its value.
     */
    public static boolean isRepresentable(Resolution a, Resolution b) {
        if (b.isLiteral()) {
            throw new IllegalArgumentException("target resolution must be concrete: " + b);
        }
        switch (a) {
            case IntLiteral lit -> {
                return fits(lit.value(), b);
            }
            case DecimalLiteral lit -> {
                return fits(lit.value(), b);
            }
            case RationalLiteral ignored -> {
                return b instanceof BigDecimalRes; // D(∞) keeps fractions exactly
            }
            default -> {
            }
        }
        if (a.equals(b)) {
            return true;
        }
        if (a instanceof Bool || b instanceof Bool) {
            return false;
        }
        if (b instanceof BigDecimalRes) {
            return true; // Z(n), Z+(n), Z(∞), F(n), D(n) ⊑ D(∞)
        }
        if (a instanceof FixedInt x) {
            return switch (b) {
                case FixedInt y -> y.signed()
                        ? (x.signed() ? x.bits() <= y.bits() : x.bits() < y.bits())
                        : !x.signed() && x.bits() <= y.bits();
                case BigInt ignored -> true;
                case BinaryFloat y -> x.signed() ? x.bits() - 1 <= y.precision() : x.bits() <= y.precision();
                case DecimalFloat y -> BigInteger.TWO.pow(x.signed() ? x.bits() - 1 : x.bits())
                        .compareTo(BigInteger.TEN.pow(y.digits())) <= 0;
                default -> false;
            };
        }
        if (a instanceof BinaryFloat x && b instanceof BinaryFloat y) {
            return x.bits() <= y.bits();
        }
        if (a instanceof DecimalFloat x && b instanceof DecimalFloat y) {
            return x.bits() <= y.bits();
        }
        return false;
    }

    private static boolean fits(BigInteger v, Resolution r) {
        return switch (r) {
            case FixedInt f -> f.signed()
                    ? v.bitLength() < f.bits()
                    : v.signum() >= 0 && v.bitLength() <= f.bits();
            case BigInt ignored -> true;
            case BinaryFloat f -> fits(new BigDecimal(v), f);
            case DecimalFloat f -> fits(new BigDecimal(v), f);
            case BigDecimalRes ignored -> true;
            default -> false;
        };
    }

    private static boolean fits(BigDecimal v, Resolution r) {
        return switch (r) {
            case BinaryFloat f -> switch (f.bits()) {
                case 32 -> Float.isFinite(v.floatValue()) && new BigDecimal(v.floatValue()).compareTo(v) == 0;
                case 64 -> Double.isFinite(v.doubleValue()) && new BigDecimal(v.doubleValue()).compareTo(v) == 0;
                default -> v.stripTrailingZeros().unscaledValue().bitLength() <= f.precision();
            };
            case DecimalFloat f -> v.stripTrailingZeros().precision() <= f.digits();
            case BigDecimalRes ignored -> true;
            default -> false; // a decimal literal never converts implicitly to an integer resolution
        };
    }

    // ------------------------------------------------------------------------------------------
    // Literals (3.4)
    // ------------------------------------------------------------------------------------------

    /** Default resolution of a literal used without a concrete partner. */
    public static Resolution defaultOf(Resolution r) {
        if (r instanceof IntLiteral lit) {
            BigInteger v = lit.value();
            if (v.bitLength() < 32) {
                return Resolution.INT32;
            }
            return v.bitLength() < 64 ? Resolution.INT64 : Resolution.BIG_INT;
        }
        if (r instanceof DecimalLiteral || r instanceof RationalLiteral) {
            return Resolution.FLOAT64;
        }
        return r;
    }

    /** The resolution a literal takes next to a concrete {@code partner}. */
    public static Resolution adopt(Resolution literal, Resolution partner) {
        if (!literal.isLiteral() || partner.isLiteral()) {
            throw new IllegalArgumentException("adopt(literal, concrete), got " + literal + ", " + partner);
        }
        if (literal instanceof IntLiteral && isRepresentable(literal, partner)) {
            return partner;
        }
        if (!(literal instanceof IntLiteral) && partner.isFloating()) {
            return partner;
        }
        return defaultOf(literal);
    }

    /** Makes both resolutions concrete: a literal follows its partner, two literals use their defaults. */
    static Resolution[] concretize(Resolution a, Resolution b) {
        if (a.isLiteral() && b.isLiteral()) {
            return new Resolution[] {defaultOf(a), defaultOf(b)};
        }
        if (a.isLiteral()) {
            return new Resolution[] {adopt(a, b), b};
        }
        if (b.isLiteral()) {
            return new Resolution[] {a, adopt(b, a)};
        }
        return new Resolution[] {a, b};
    }

    // ------------------------------------------------------------------------------------------
    // ⊔ (3.3)
    // ------------------------------------------------------------------------------------------

    /**
     * {@code a ⊔ b}, the first matching row of the table in type-system.md 3.3. Literals are made
     * concrete first. The minimum arithmetic width is <em>not</em> applied (see {@link #arithmetic}).
     */
    public static Resolution join(Resolution a, Resolution b) {
        Resolution[] c = concretize(a, b);
        Resolution x = c[0];
        Resolution y = c[1];

        if (x instanceof Bool || y instanceof Bool) {
            if (x instanceof Bool && y instanceof Bool) {
                return x;
            }
            throw new VelTypeException("no common resolution for " + x + " and " + y);
        }
        // 1
        if (x instanceof BigDecimalRes || y instanceof BigDecimalRes) {
            return Resolution.BIG_DECIMAL;
        }
        // 2, 3
        if (x instanceof BigInt || y instanceof BigInt) {
            Resolution other = x instanceof BigInt ? y : x;
            return other instanceof BinaryFloat || other instanceof DecimalFloat
                    ? Resolution.BIG_DECIMAL
                    : Resolution.BIG_INT;
        }
        // 4
        if (x instanceof DecimalFloat || y instanceof DecimalFloat) {
            return new DecimalFloat(Math.max(floatWidth(x), floatWidth(y)));
        }
        // 5
        if (x instanceof BinaryFloat || y instanceof BinaryFloat) {
            return new BinaryFloat(Math.max(floatWidth(x), floatWidth(y)));
        }
        // 6, 7, 8
        FixedInt p = (FixedInt) x;
        FixedInt q = (FixedInt) y;
        if (p.signed() == q.signed()) {
            return new FixedInt(Math.max(p.bits(), q.bits()), p.signed());
        }
        FixedInt s = p.signed() ? p : q;
        FixedInt u = p.signed() ? q : p;
        return new FixedInt(Math.max(s.bits(), u.bits() + 1), true);
    }

    /** Width of a floating resolution; 0 for integers so they are ignored by max(). */
    private static int floatWidth(Resolution r) {
        return switch (r) {
            case BinaryFloat f -> f.bits();
            case DecimalFloat d -> d.bits();
            default -> 0;
        };
    }

    // ------------------------------------------------------------------------------------------
    // Arithmetic (3.3, "연산자별 예외", "최소 연산 폭")
    // ------------------------------------------------------------------------------------------

    /** Element-level arithmetic, after mapping element-wise and matrix operators to these. */
    public enum Arithmetic {
        ADD, SUB, MUL, DIV, FLOOR_DIV, TRUNC_DIV, FLOOR_MOD, TRUNC_MOD, POW,
        /** Bool exclusive or (⊕). */
        XOR
    }

    /**
     * Result resolution of {@code a op b}. Arithmetic on literals alone is folded exactly and stays a
     * literal, so intermediate constants keep the highest precision until they meet a concrete operand.
     */
    public static Resolution arithmetic(Arithmetic op, Resolution a, Resolution b) {
        if (a instanceof Bool || b instanceof Bool) {
            // Bool: + is OR, * is AND, o+ is XOR (type-system.md footnote 1)
            if (op == Arithmetic.ADD || op == Arithmetic.MUL || op == Arithmetic.XOR) {
                return join(a, b);
            }
            throw new VelTypeException("operator " + op + " is not defined for Bool");
        }
        if (op == Arithmetic.XOR) {
            throw new VelTypeException("operator o+ is defined only for Bool");
        }
        if (a.isLiteral() && b.isLiteral()) {
            Resolution folded = Constants.fold(op, a, b);
            if (folded != null) {
                return folded;
            }
        }
        if (op == Arithmetic.POW) {
            return power(a, b);
        }
        Resolution[] c = concretize(a, b);
        Resolution x = c[0];
        Resolution y = c[1];

        if (op == Arithmetic.DIV && x.isInteger() && y.isInteger()) {
            // true division
            return x instanceof BigInt || y instanceof BigInt ? Resolution.BIG_DECIMAL : Resolution.FLOAT64;
        }
        if (op == Arithmetic.SUB && x instanceof FixedInt p && !p.signed()
                && y instanceof FixedInt q && !q.signed()) {
            return widen(new FixedInt(Math.max(p.bits(), q.bits()) + 1, true));
        }
        return widen(join(x, y));
    }

    private static Resolution power(Resolution base, Resolution exponent) {
        Resolution b = base.isLiteral() && !exponent.isLiteral() ? adopt(base, exponent) : defaultOf(base);
        if (exponent instanceof IntLiteral lit && lit.value().signum() >= 0) {
            return widen(b); // repeated multiplication
        }
        return join(b, Resolution.FLOAT64);
    }

    /** Result resolution of a unary {@code -a} or {@code +a}. A literal stays a literal. */
    public static Resolution negate(Resolution a, boolean minus) {
        switch (a) {
            case Bool ignored -> throw new VelTypeException("unary " + (minus ? "-" : "+") + " is not defined for Bool");
            case IntLiteral lit -> {
                return minus ? new IntLiteral(lit.value().negate()) : lit;
            }
            case DecimalLiteral lit -> {
                return minus ? new DecimalLiteral(lit.value().negate()) : lit;
            }
            case RationalLiteral lit -> {
                return minus ? new RationalLiteral(lit.numerator().negate(), lit.denominator()) : lit;
            }
            case FixedInt f when minus && !f.signed() -> {
                return widen(new FixedInt(f.bits() + 1, true));
            }
            default -> {
                return widen(a);
            }
        }
    }

    /** Applies the minimum arithmetic width to fixed-width integers. */
    static Resolution widen(Resolution r) {
        if (r instanceof FixedInt f && f.bits() < MIN_ARITHMETIC_WIDTH) {
            return new FixedInt(MIN_ARITHMETIC_WIDTH, f.signed());
        }
        return r;
    }

    // ------------------------------------------------------------------------------------------
    // Exact folding of literal arithmetic
    // ------------------------------------------------------------------------------------------

    /** Exact rational arithmetic on literal constants. */
    static final class Constants {

        private Constants() {
        }

        /** Folds {@code a op b} for two literals, or returns null when it cannot be done exactly. */
        static Resolution fold(Arithmetic op, Resolution a, Resolution b) {
            boolean integers = a instanceof IntLiteral && b instanceof IntLiteral;
            BigInteger[] x = rational(a);
            BigInteger[] y = rational(b);
            return switch (op) {
                case ADD -> make(x[0].multiply(y[1]).add(y[0].multiply(x[1])), x[1].multiply(y[1]), integers);
                case SUB -> make(x[0].multiply(y[1]).subtract(y[0].multiply(x[1])), x[1].multiply(y[1]), integers);
                case MUL -> make(x[0].multiply(y[0]), x[1].multiply(y[1]), integers);
                case DIV -> y[0].signum() == 0 ? null
                        : make(x[0].multiply(y[1]), x[1].multiply(y[0]), false); // true division
                case FLOOR_DIV, TRUNC_DIV, FLOOR_MOD, TRUNC_MOD -> integers && y[0].signum() != 0
                        ? new IntLiteral(integerOp(op, x[0], y[0]))
                        : null;
                case POW -> power(x, b, a instanceof IntLiteral);
                case XOR -> null;
            };
        }

        private static BigInteger integerOp(Arithmetic op, BigInteger a, BigInteger b) {
            BigInteger[] qr = a.divideAndRemainder(b); // truncating
            boolean adjust = qr[1].signum() != 0 && qr[1].signum() != b.signum();
            return switch (op) {
                case TRUNC_DIV -> qr[0];
                case TRUNC_MOD -> qr[1];
                case FLOOR_DIV -> adjust ? qr[0].subtract(BigInteger.ONE) : qr[0];
                case FLOOR_MOD -> adjust ? qr[1].add(b) : qr[1];
                default -> throw new IllegalArgumentException(op.toString());
            };
        }

        private static Resolution power(BigInteger[] base, Resolution exponent, boolean integerBase) {
            if (!(exponent instanceof IntLiteral e) || e.value().abs().compareTo(BigInteger.valueOf(MAX_FOLDED_EXPONENT)) > 0) {
                return null;
            }
            int n = e.value().intValueExact();
            if (n >= 0) {
                return make(base[0].pow(n), base[1].pow(n), integerBase);
            }
            if (base[0].signum() == 0) {
                return null;
            }
            return make(base[1].pow(-n), base[0].pow(-n), false);
        }

        /** {@code {numerator, denominator}} of a literal, denominator > 0. */
        private static BigInteger[] rational(Resolution r) {
            return switch (r) {
                case IntLiteral i -> new BigInteger[] {i.value(), BigInteger.ONE};
                case DecimalLiteral d -> d.value().scale() <= 0
                        ? new BigInteger[] {d.value().toBigIntegerExact(), BigInteger.ONE}
                        : new BigInteger[] {d.value().unscaledValue(), BigInteger.TEN.pow(d.value().scale())};
                case RationalLiteral q -> new BigInteger[] {q.numerator(), q.denominator()};
                default -> throw new IllegalArgumentException("not a literal: " + r);
            };
        }

        /**
         * A literal for {@code num/den}: an integer literal if both inputs were integers and the
         * operation keeps integers, otherwise a decimal literal when finite and a fraction when not.
         */
        private static Resolution make(BigInteger num, BigInteger den, boolean integers) {
            if (den.signum() < 0) {
                num = num.negate();
                den = den.negate();
            }
            BigInteger g = num.gcd(den);
            if (g.signum() != 0 && !g.equals(BigInteger.ONE)) {
                num = num.divide(g);
                den = den.divide(g);
            }
            if (integers && den.equals(BigInteger.ONE)) {
                return new IntLiteral(num);
            }
            BigInteger rest = den;
            int twos = 0;
            int fives = 0;
            while (!rest.testBit(0) && rest.signum() != 0) {
                rest = rest.shiftRight(1);
                twos++;
            }
            BigInteger five = BigInteger.valueOf(5);
            while (rest.mod(five).signum() == 0) {
                rest = rest.divide(five);
                fives++;
            }
            if (!rest.equals(BigInteger.ONE)) {
                return new RationalLiteral(num, den);
            }
            int scale = Math.max(twos, fives);
            BigInteger unscaled = num.multiply(BigInteger.TEN.pow(scale)).divide(den);
            return new DecimalLiteral(new BigDecimal(unscaled, scale));
        }
    }
}
