package com.doubledeltas.vel.core.codegen;

import java.util.List;

import com.doubledeltas.vel.core.concretization.ScalarType;
import com.doubledeltas.vel.core.type.ArrayAlgebra;
import com.doubledeltas.vel.core.type.Card;

/**
 * A type at the boundary of VEL (type-system.md 0.2): {@code ⟨Array(𝐝, K)⟩_R}, the dimension sizes with
 * the element scalar type. A scalar has the dimensions {@code (1, 1)}.
 * <p>
 * Its Java representation follows java.md 2: a Java array {@code a[m][n]} is the VEL matrix
 * {@code Mat(n, m)}, so VEL dimension {@code k} is the {@code k}-th array level counted from the
 * innermost one, and trailing dimensions of size 1 are not represented.
 */
public record ConcreteType(List<Card> dims, ScalarType element) {

    public ConcreteType {
        dims = new ArrayAlgebra(dims, element.algebra()).dims(); // canonical form
    }

    public static ConcreteType scalar(ScalarType element) {
        return new ConcreteType(List.of(Card.ONE, Card.ONE), element);
    }

    /**
     * A Java array type of {@code javaRank} levels with the sizes given in VEL order (from {@code @Dim};
     * null for all ⊤).
     */
    public static ConcreteType javaArray(ScalarType element, int javaRank, List<Card> velDims) {
        List<Card> dims = velDims;
        if (dims == null) {
            dims = java.util.Collections.nCopies(javaRank, Card.TOP);
        }
        if (dims.size() != javaRank) {
            throw new IllegalArgumentException("@Dim gives " + dims.size() + " sizes for an array of " + javaRank + " levels");
        }
        return new ConcreteType(javaRank == 1 ? List.of(dims.getFirst(), Card.ONE) : dims, element);
    }

    public ArrayAlgebra algebra() {
        return new ArrayAlgebra(dims, element.algebra());
    }

    public boolean isScalar() {
        return algebra().isScalar();
    }

    /** Number of Java array levels: the VEL dimensions up to the last one not known to be 1. */
    public int javaRank() {
        return JavaTypes.javaRank(dims);
    }

    /** The Java type, e.g. {@code double}, {@code int[][]}. */
    public String javaName() {
        return JavaTypes.storage(JavaTypes.javaName(element.resolution())) + "[]".repeat(javaRank());
    }

    @Override
    public String toString() {
        return "<" + algebra() + ">" + element.resolution();
    }
}
