package com.doubledeltas.vel.core.type;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Array algebra {@code Array(d₁, …, dₙ, K)} (type-system.md 1): the dimension sizes together with the
 * element {@link ScalarAlgebra} {@code K}. It carries no resolution (type-system.md 0.3).
 * <p>
 * A scalar may also carry its exact {@link Constant} value when it is a literal or folded from
 * literals (type-system.md 3.3); the constant decides literal indices, range lengths and the
 * {@code ^} rule, and lets the concretization give it the resolution of its partner.
 * <p>
 * Dimensions are kept in a canonical form so that the definitions in the type system hold by
 * equality: the rank is at least 2 (a rank-1 {@code Array(n)} is {@code Mat(n, 1)} = {@code Vec(n)}),
 * and trailing dimensions beyond the second that are known to be 1 are dropped. Hence
 * {@code Vec(n) = Mat(n, 1)} and a scalar is {@code Mat(1, 1)}.
 *
 * @param constant the exact value of a constant scalar, or null
 */
public record ArrayAlgebra(List<Card> dims, ScalarAlgebra element, Constant constant) implements VelAlgebra {

    public ArrayAlgebra {
        dims = normalize(dims);
        if (constant != null) {
            if (!isScalar(dims)) {
                throw new IllegalArgumentException("only a scalar has a constant value: " + dims);
            }
            if (constant.algebra() != element) {
                throw new IllegalArgumentException("constant " + constant + " is not " + element);
            }
        }
    }

    public ArrayAlgebra(List<Card> dims, ScalarAlgebra element) {
        this(dims, element, null);
    }

    public static ArrayAlgebra scalar(ScalarAlgebra element) {
        return new ArrayAlgebra(List.of(Card.ONE, Card.ONE), element);
    }

    /** A constant scalar, e.g. a literal. */
    public static ArrayAlgebra scalar(Constant constant) {
        return new ArrayAlgebra(List.of(Card.ONE, Card.ONE), constant.algebra(), constant);
    }

    public static ArrayAlgebra vec(Card n, ScalarAlgebra element) {
        return new ArrayAlgebra(List.of(n, Card.ONE), element);
    }

    public static ArrayAlgebra mat(Card rows, Card cols, ScalarAlgebra element) {
        return new ArrayAlgebra(List.of(rows, cols), element);
    }

    public static ArrayAlgebra of(ScalarAlgebra element, Card... dims) {
        return new ArrayAlgebra(List.of(dims), element);
    }

    private static List<Card> normalize(List<Card> dims) {
        if (dims.isEmpty()) {
            throw new IllegalArgumentException("an array has at least one dimension");
        }
        for (Card c : dims) {
            c.requireValid("dimension size");
        }
        List<Card> d = new ArrayList<>(dims);
        while (d.size() < 2) {
            d.add(Card.ONE);
        }
        while (d.size() > 2 && d.getLast().isOne()) {
            d.removeLast();
        }
        return List.copyOf(d);
    }

    private static boolean isScalar(List<Card> dims) {
        return dims.size() == 2 && dims.get(0).isOne() && dims.get(1).isOne();
    }

    public int rank() {
        return dims.size();
    }

    /** {@code [K] = Mat(1, 1, K)}, known at compile time. */
    public boolean isScalar() {
        return isScalar(dims);
    }

    public boolean isMatrix() {
        return rank() == 2;
    }

    public Card rows() {
        return dims.get(0);
    }

    public Card cols() {
        return dims.get(1);
    }

    /** The same shape with another element algebra (the constant value, if any, is dropped). */
    public ArrayAlgebra withElement(ScalarAlgebra newElement) {
        return new ArrayAlgebra(dims, newElement);
    }

    /** Another shape with the same element algebra (the constant value, if any, is dropped). */
    public ArrayAlgebra withDims(List<Card> newDims) {
        return new ArrayAlgebra(newDims, element);
    }

    /** Assignability: same shape (⊤ matches any size) and a subtype element algebra. */
    public boolean isAssignableTo(ArrayAlgebra target) {
        if (rank() != target.rank()) {
            return false;
        }
        for (int i = 0; i < rank(); i++) {
            Card a = dims.get(i);
            Card b = target.dims.get(i);
            if (a.isKnown() && b.isKnown() && !a.equals(b)) {
                return false;
            }
        }
        return element.isSubtypeOf(target.element);
    }

    @Override
    public String toString() {
        if (isScalar()) {
            return constant == null ? element.toString() : element + "(" + constant + ")";
        }
        String sizes = dims.stream().map(Card::toString).collect(Collectors.joining(", "));
        return (isMatrix() ? "Mat(" : "Array(") + sizes + ", " + element + ")";
    }
}
