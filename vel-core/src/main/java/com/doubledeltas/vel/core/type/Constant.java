package com.doubledeltas.vel.core.type;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * The exact value of a literal or of arithmetic on literals (type-system.md 3.3). Inside VEL a literal
 * is an exact number; its resolution is decided only when it is concretized.
 */
public sealed interface Constant {

    /** Literal powers are folded exactly only up to this exponent, to keep constants small. */
    int MAX_FOLDED_EXPONENT = 1024;

    static Constant of(BigInteger value) {
        return new Int(value);
    }

    static Constant of(BigDecimal value) {
        return new Decimal(value);
    }

    static Constant of(long value) {
        return new Int(BigInteger.valueOf(value));
    }

    /** An integer literal: algebra {@code Int}. */
    record Int(BigInteger value) implements Constant {
        @Override
        public String toString() {
            return value.toString();
        }
    }

    /** A decimal literal: algebra {@code Real}. */
    record Decimal(BigDecimal value) implements Constant {
        /** Kept without trailing zeros so that equal values are equal constants ({@code 1.0 == 1}). */
        public Decimal {
            value = value.stripTrailingZeros();
        }

        @Override
        public String toString() {
            return value.toPlainString();
        }
    }

    /**
     * An exact constant that is not a finite decimal, such as {@code 1/3}, produced by folding literal
     * arithmetic. Algebra {@code Real}.
     *
     * @param numerator   any integer
     * @param denominator greater than 1, coprime with the numerator, with a prime factor other than 2 and 5
     */
    record Rational(BigInteger numerator, BigInteger denominator) implements Constant {
        @Override
        public String toString() {
            return numerator + "/" + denominator;
        }
    }

    default ScalarAlgebra algebra() {
        return this instanceof Int ? ScalarAlgebra.INT : ScalarAlgebra.REAL;
    }

    /** {@code -c}. */
    default Constant negate() {
        return switch (this) {
            case Int i -> new Int(i.value().negate());
            case Decimal d -> new Decimal(d.value().negate());
            case Rational q -> new Rational(q.numerator().negate(), q.denominator());
        };
    }

    /** {@code {numerator, denominator}}, denominator > 0. */
    default BigInteger[] rational() {
        return switch (this) {
            case Int i -> new BigInteger[] {i.value(), BigInteger.ONE};
            case Decimal d -> d.value().scale() <= 0
                    ? new BigInteger[] {d.value().toBigIntegerExact(), BigInteger.ONE}
                    : new BigInteger[] {d.value().unscaledValue(), BigInteger.TEN.pow(d.value().scale())};
            case Rational q -> new BigInteger[] {q.numerator(), q.denominator()};
        };
    }

    /**
     * {@code a op b} computed exactly (constant folding), or null when it cannot be done exactly
     * (division by zero, a huge or non-integer exponent, {@code o+}).
     */
    static Constant fold(Arithmetic op, Constant a, Constant b) {
        boolean integers = a instanceof Int && b instanceof Int;
        BigInteger[] x = a.rational();
        BigInteger[] y = b.rational();
        return switch (op) {
            case ADD -> make(x[0].multiply(y[1]).add(y[0].multiply(x[1])), x[1].multiply(y[1]), integers);
            case SUB -> make(x[0].multiply(y[1]).subtract(y[0].multiply(x[1])), x[1].multiply(y[1]), integers);
            case MUL -> make(x[0].multiply(y[0]), x[1].multiply(y[1]), integers);
            case DIV -> y[0].signum() == 0 ? null
                    : make(x[0].multiply(y[1]), x[1].multiply(y[0]), false); // true division
            case FLOOR_DIV, TRUNC_DIV, FLOOR_MOD, TRUNC_MOD -> integers && y[0].signum() != 0
                    ? new Int(integerOp(op, x[0], y[0]))
                    : null;
            case POW -> power(x, b, a instanceof Int);
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

    private static Constant power(BigInteger[] base, Constant exponent, boolean integerBase) {
        if (!(exponent instanceof Int e) || e.value().abs().compareTo(BigInteger.valueOf(MAX_FOLDED_EXPONENT)) > 0) {
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

    /**
     * A constant for {@code num/den}: an integer if both inputs were integers and the operation keeps
     * integers, otherwise a decimal when finite and a fraction when not.
     */
    private static Constant make(BigInteger num, BigInteger den, boolean integers) {
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
            return new Int(num);
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
            return new Rational(num, den);
        }
        int scale = Math.max(twos, fives);
        BigInteger unscaled = num.multiply(BigInteger.TEN.pow(scale)).divide(den);
        return new Decimal(new BigDecimal(unscaled, scale));
    }
}
