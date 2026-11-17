package com.doubledeltas.vel.core.type;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Scalar type {@code ⟨K⟩_R}: a scalar field {@code K} with a resolution {@code R} (type-system.md 0.2),
 * e.g. {@code ⟨Real⟩_F64} for Java {@code double}. It is also the element type of an {@link ArrayType}.
 * {@code Bool} always has the {@code Bool} resolution, and numeric fields never do.
 */
public record ScalarType(Field field, Resolution resolution) {

    public static final ScalarType BOOL = new ScalarType(Field.BOOL, Resolution.BOOL);

    /** The predeclared imaginary unit {@code j = complex(0, 1)}: its parts are untyped integer literals. */
    public static final ScalarType IMAGINARY_UNIT = new ScalarType(Field.COMPLEX, Resolution.literal(BigInteger.ONE));

    public ScalarType {
        boolean boolRes = resolution instanceof Resolution.Bool;
        if ((field == Field.BOOL) != boolRes) {
            throw new IllegalArgumentException("invalid scalar type: " + field + " with " + resolution);
        }
    }

    public static ScalarType real(Resolution resolution) {
        return new ScalarType(Field.REAL, resolution);
    }

    public static ScalarType complex(Resolution resolution) {
        return new ScalarType(Field.COMPLEX, resolution);
    }

    public static ScalarType literal(BigInteger value) {
        return real(Resolution.literal(value));
    }

    public static ScalarType literal(BigDecimal value) {
        return real(Resolution.literal(value));
    }

    /** {@code a ⊑ b} for element types: the field is a subtype and the resolution is representable. */
    public boolean isAssignableTo(ScalarType target) {
        return field.isSubtypeOf(target.field) && Promotion.isRepresentable(resolution, target.resolution);
    }

    @Override
    public String toString() {
        return "<" + field + ">" + resolution;
    }
}
