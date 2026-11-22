package com.doubledeltas.vel.core.type;

import java.util.ArrayList;
import java.util.List;

/**
 * Interprets the elements of a {@code @Dim} annotation as dimension sizes in VEL order.
 * <p>
 * {@code value} and {@code vel} are in VEL order (rows first), {@code java} in Java declaration order
 * (outermost array first) and is reversed. Exactly one of them may be given; {@code -1}
 * ({@code Dim.VAR}) is a dimension of unknown length.
 */
public final class DimSpec {

    /** {@code Dim.VAR}. */
    public static final int VAR = -1;

    private DimSpec() {
    }

    public static List<Card> fromAnnotation(int[] value, int[] java, int[] vel) {
        int given = (value.length > 0 ? 1 : 0) + (java.length > 0 ? 1 : 0) + (vel.length > 0 ? 1 : 0);
        if (given == 0) {
            throw new VelTypeException("@Dim needs sizes: @Dim(...), @Dim(vel = ...) or @Dim(java = ...)");
        }
        if (given > 1) {
            throw new VelTypeException("@Dim takes only one of value, vel and java");
        }
        List<Card> dims = new ArrayList<>();
        if (java.length > 0) {
            for (int i = java.length - 1; i >= 0; i--) {
                dims.add(card(java[i]));
            }
        } else {
            for (int size : value.length > 0 ? value : vel) {
                dims.add(card(size));
            }
        }
        return dims;
    }

    private static Card card(int size) {
        if (size == VAR) {
            return Card.fresh();
        }
        if (size < 0) {
            throw new VelTypeException("@Dim size must be non-negative or Dim.VAR, got " + size);
        }
        return Card.of(size);
    }
}
