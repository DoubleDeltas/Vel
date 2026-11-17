package com.doubledeltas.vel.core.concretization;

import java.math.BigDecimal;
import java.math.BigInteger;

import com.doubledeltas.vel.core.concretization.Operand.Fixed;
import com.doubledeltas.vel.core.concretization.Operand.Literal;
import com.doubledeltas.vel.core.concretization.Resolution.BigDecimalRes;
import com.doubledeltas.vel.core.concretization.Resolution.BigInt;
import com.doubledeltas.vel.core.concretization.Resolution.BinaryFloat;
import com.doubledeltas.vel.core.concretization.Resolution.Bool;
import com.doubledeltas.vel.core.concretization.Resolution.DecimalFloat;
import com.doubledeltas.vel.core.concretization.Resolution.FixedInt;
import com.doubledeltas.vel.core.type.Arithmetic;
import com.doubledeltas.vel.core.type.Constant;
import com.doubledeltas.vel.core.type.VelTypeException;

/**
 * Resolutions of intermediate computations in the Java concretization (java.md 7). Kotlin follows
 * the same rules. VEL itself only handles algebras (type-system.md 0.3).
 */
public final class Promotion {

    /** Integer results of arithmetic are at least this wide (java.md 7.3, "최소 연산 폭"). */
    public static final int MIN_ARITHMETIC_WIDTH = 32;

    private Promotion() {
    }

    // ------------------------------------------------------------------------------------------
    // ⊑ (7.2)
    // ------------------------------------------------------------------------------------------

    /** {@code a ⊑ b}: every value of {@code a} is exactly representable in {@code b}. */
    public static boolean isRepresentable(Resolution a, Resolution b) {
        a = arithmetic(a);
        b = arithmetic(b);
        if (a.equals(b)) {
            return true;
        }
        if (a instanceof Bool || b instanceof Bool) {
            return false;
        }
        if (b instanceof BigDecimalRes) {
            return true; // Zn, Z+n, Z∞, Fn, Dn ⊑ D∞
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

    /** Whether the constant is exactly representable in {@code r}, judged by its value. */
    public static boolean isRepresentable(Constant c, Resolution r) {
        return switch (c) {
            case Constant.Int i -> fits(i.value(), r);
            case Constant.Decimal d -> fits(d.value(), r);
            case Constant.Rational ignored -> r instanceof BigDecimalRes; // D∞ keeps fractions exactly
        };
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
            default -> false; // a decimal constant never takes an integer resolution
        };
    }

    // ------------------------------------------------------------------------------------------
    // Literals (7.6)
    // ------------------------------------------------------------------------------------------

    /** Default resolution of a constant used without a decided partner. */
    public static Resolution defaultOf(Constant c) {
        if (c instanceof Constant.Int i) {
            BigInteger v = i.value();
            if (v.bitLength() < 32) {
                return Resolution.INT32;
            }
            return v.bitLength() < 64 ? Resolution.INT64 : Resolution.BIG_INT;
        }
        return Resolution.FLOAT64;
    }

    /** The resolution a constant takes next to a {@code partner} of decided resolution. */
    public static Resolution adopt(Constant c, Resolution partner) {
        if (c instanceof Constant.Int && isRepresentable(c, partner)) {
            return partner;
        }
        if (!(c instanceof Constant.Int) && partner.isFloating()) {
            return partner;
        }
        return defaultOf(c);
    }

    /** The resolution of a single operand: a constant without a partner takes its default. */
    public static Resolution resolve(Operand a) {
        return switch (a) {
            case Fixed f -> f.resolution();
            case Literal l -> defaultOf(l.value());
        };
    }

    /** Decides both resolutions: a constant follows its partner, two constants use their defaults. */
    static Resolution[] resolve(Operand a, Operand b) {
        if (a instanceof Literal x && b instanceof Fixed y) {
            return new Resolution[] {adopt(x.value(), y.resolution()), y.resolution()};
        }
        if (a instanceof Fixed x && b instanceof Literal y) {
            return new Resolution[] {x.resolution(), adopt(y.value(), x.resolution())};
        }
        return new Resolution[] {resolve(a), resolve(b)};
    }

    // ------------------------------------------------------------------------------------------
    // ⊔ (7.3)
    // ------------------------------------------------------------------------------------------

    /**
     * {@code a ⊔ b}, the first matching row of the table in java.md 7.3. Constants are resolved first.
     * The minimum arithmetic width is <em>not</em> applied (see {@link #arithmetic}).
     */
    public static Resolution join(Operand a, Operand b) {
        Resolution[] c = resolve(a, b);
        return join(c[0], c[1]);
    }

    public static Resolution join(Resolution x, Resolution y) {
        x = arithmetic(x);
        y = arithmetic(y);
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
    // Arithmetic (7.3, "연산자별 예외", "최소 연산 폭")
    // ------------------------------------------------------------------------------------------

    /**
     * Resolution of {@code a op b}. The operation itself has already been checked on algebras
     * (type-system.md 3); constants that could be folded have been folded there.
     */
    public static Resolution arithmetic(Arithmetic op, Operand a, Operand b) {
        if (op == Arithmetic.POW) {
            return power(a, b);
        }
        Resolution[] c = resolve(a, b);
        Resolution x = arithmetic(c[0]);
        Resolution y = arithmetic(c[1]);
        if (x instanceof Bool || y instanceof Bool) {
            return join(x, y); // + is OR, * is AND, o+ is XOR
        }
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

    /**
     * {@code A ^ B}: a non-negative integer literal exponent is repeated multiplication and keeps
     * {@code A}; any other exponent can make the result fractional, so the result is
     * {@code A ⊔ B ⊔ F64} (the exponent's resolution counts too, like any mixed operation).
     */
    private static Resolution power(Operand base, Operand exponent) {
        if (exponent instanceof Literal l && l.value() instanceof Constant.Int i && i.value().signum() >= 0) {
            return widen(resolve(base)); // repeated multiplication
        }
        return join(join(base, exponent), Resolution.FLOAT64);
    }

    /** Resolution of a unary {@code -a} or {@code +a}. */
    public static Resolution negate(Resolution a, boolean minus) {
        if (minus && a instanceof FixedInt f && !f.signed()) {
            return widen(new FixedInt(f.bits() + 1, true));
        }
        return widen(a);
    }

    /** ⟨Card⟩ is computed as Z32 in Java (java.md 7.1). */
    private static Resolution arithmetic(Resolution r) {
        return r instanceof Resolution.CardRes ? Resolution.INT32 : r;
    }

    /** Applies the minimum arithmetic width to fixed-width integers. */
    static Resolution widen(Resolution r) {
        if (r instanceof FixedInt f && f.bits() < MIN_ARITHMETIC_WIDTH) {
            return new FixedInt(MIN_ARITHMETIC_WIDTH, f.signed());
        }
        return r;
    }
}
