package com.doubledeltas.vel.core.type;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Scalar type {@code ⟨K⟩_R}: a scalar algebra {@code K} with a resolution {@code R} (type-system.md 0.2),
 * e.g. {@code ⟨Real⟩_F64} for Java {@code double}. It is also the element type of an {@link ArrayType}.
 * {@code Bool} always has the {@code Bool} resolution, and numeric algebras never do.
 */
public record ScalarType(Algebra algebra, Resolution resolution) {

    public static final ScalarType BOOL = new ScalarType(Algebra.BOOL, Resolution.BOOL);

    /** The predeclared imaginary unit {@code j = complex(0, 1)}: its parts are untyped integer literals. */
    public static final ScalarType IMAGINARY_UNIT = new ScalarType(Algebra.COMPLEX, Resolution.literal(BigInteger.ONE));

    /**
     * Valid combinations (type-system.md 2): {@code Bool} only with the Bool resolution; integer
     * resolutions ({@code Zn}, {@code Z+n}, {@code Z∞}) only with {@code Int}; {@code Int} only with
     * those; {@code Real} and {@code Complex} with {@code Fn}, {@code Dn}, {@code D∞}. Untyped literals
     * are allowed with any numeric algebra.
     */
    public ScalarType {
        if (!isValid(algebra, resolution)) {
            throw new IllegalArgumentException("invalid scalar type: " + algebra + " with " + resolution);
        }
    }

    private static boolean isValid(Algebra algebra, Resolution r) {
        if (algebra == Algebra.BOOL || r instanceof Resolution.Bool) {
            return algebra == Algebra.BOOL && r instanceof Resolution.Bool;
        }
        if (r.isLiteral()) {
            return algebra != Algebra.INT || r instanceof Resolution.IntLiteral;
        }
        return algebra == Algebra.INT ? r.isInteger() : r.isFloating();
    }

    /**
     * The scalar type for a algebra and a resolution computed independently, made consistent:
     * an integer resolution in {@code Real}/{@code Complex} becomes real ({@code Zn → F64},
     * {@code Z∞ → D∞}); a non-integer resolution in {@code Int} makes the algebra {@code Real}.
     */
    public static ScalarType of(Algebra algebra, Resolution resolution) {
        if (algebra == Algebra.INT && (resolution.isFloating()
                || resolution instanceof Resolution.DecimalLiteral || resolution instanceof Resolution.RationalLiteral)) {
            algebra = Algebra.REAL;
        }
        if ((algebra == Algebra.REAL || algebra == Algebra.COMPLEX) && resolution.isInteger()) {
            resolution = resolution instanceof Resolution.BigInt ? Resolution.BIG_DECIMAL : Resolution.FLOAT64;
        }
        return new ScalarType(algebra, resolution);
    }

    public static ScalarType integer(Resolution resolution) {
        return new ScalarType(Algebra.INT, resolution);
    }

    public static ScalarType real(Resolution resolution) {
        return new ScalarType(Algebra.REAL, resolution);
    }

    public static ScalarType complex(Resolution resolution) {
        return new ScalarType(Algebra.COMPLEX, resolution);
    }

    /** An integer literal: algebra {@code Int}, resolution not yet determined. */
    public static ScalarType literal(BigInteger value) {
        return integer(Resolution.literal(value));
    }

    public static ScalarType literal(BigDecimal value) {
        return real(Resolution.literal(value));
    }

    /** {@code a ⊑ b} for element types: the algebra is a subtype and the resolution is representable. */
    public boolean isAssignableTo(ScalarType target) {
        return algebra.isSubtypeOf(target.algebra) && Promotion.isRepresentable(resolution, target.resolution);
    }

    @Override
    public String toString() {
        return "<" + algebra + ">" + resolution;
    }
}
