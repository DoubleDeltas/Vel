package com.doubledeltas.vel.core.concretization;

import com.doubledeltas.vel.core.type.ScalarAlgebra;

/**
 * Scalar type {@code ⟨K⟩_R}: a scalar algebra {@code K} with a resolution {@code R} (type-system.md 0.2),
 * e.g. {@code ⟨Real⟩_F64} for Java {@code double}. Types exist only at the boundary of VEL: a Java
 * value is abstracted from its type into an algebra, and a VEL result is concretized into a type
 * (type-system.md 0.3).
 * {@code Bool} always has the {@code Bool} resolution, and numeric algebras never do.
 */
public record ScalarType(ScalarAlgebra algebra, Resolution resolution) {

    public static final ScalarType BOOL = new ScalarType(ScalarAlgebra.BOOL, Resolution.BOOL);
    public static final ScalarType CARD = new ScalarType(ScalarAlgebra.CARD, Resolution.CARD);

    /**
     * Valid combinations (type-system.md 2): {@code Bool} only with the Bool resolution; integer
     * resolutions ({@code Zn}, {@code Z+n}, {@code Z∞}) only with {@code Int}; {@code Int} only with
     * those; {@code Real} and {@code Complex} with {@code Fn}, {@code Dn}, {@code D∞}; {@code Card}
     * only with the Card resolution.
     */
    public ScalarType {
        if (!isValid(algebra, resolution)) {
            throw new IllegalArgumentException("invalid scalar type: " + algebra + " with " + resolution);
        }
    }

    private static boolean isValid(ScalarAlgebra algebra, Resolution r) {
        if (algebra == ScalarAlgebra.BOOL || r instanceof Resolution.Bool) {
            return algebra == ScalarAlgebra.BOOL && r instanceof Resolution.Bool;
        }
        if (algebra == ScalarAlgebra.CARD || r instanceof Resolution.CardRes) {
            return algebra == ScalarAlgebra.CARD && r instanceof Resolution.CardRes;
        }
        return algebra == ScalarAlgebra.INT ? r.isInteger() : r.isFloating();
    }

    /**
     * The scalar type for an algebra and a resolution computed independently, made consistent
     * (java.md 7.3, "대수와 해상도 맞추기"): an integer resolution in {@code Real}/{@code Complex} becomes
     * real ({@code Zn → F64}, {@code Z∞ → D∞}); a non-integer resolution in {@code Int} makes the
     * algebra {@code Real}.
     */
    public static ScalarType of(ScalarAlgebra algebra, Resolution resolution) {
        if (algebra == ScalarAlgebra.INT && resolution.isFloating()) {
            algebra = ScalarAlgebra.REAL;
        }
        if ((algebra == ScalarAlgebra.REAL || algebra == ScalarAlgebra.COMPLEX) && resolution.isInteger()) {
            resolution = resolution instanceof Resolution.BigInt ? Resolution.BIG_DECIMAL : Resolution.FLOAT64;
        }
        return new ScalarType(algebra, resolution);
    }

    public static ScalarType integer(Resolution resolution) {
        return new ScalarType(ScalarAlgebra.INT, resolution);
    }

    public static ScalarType real(Resolution resolution) {
        return new ScalarType(ScalarAlgebra.REAL, resolution);
    }

    public static ScalarType complex(Resolution resolution) {
        return new ScalarType(ScalarAlgebra.COMPLEX, resolution);
    }

    /** {@code a ⊑ b} for scalar types: the algebra is a subtype and the resolution is representable. */
    public boolean isAssignableTo(ScalarType target) {
        return algebra.isSubtypeOf(target.algebra) && Promotion.isRepresentable(resolution, target.resolution);
    }

    @Override
    public String toString() {
        return "<" + algebra + ">" + resolution;
    }
}
