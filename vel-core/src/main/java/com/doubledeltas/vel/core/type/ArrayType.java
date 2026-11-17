package com.doubledeltas.vel.core.type;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Array type {@code ⟨Array(d₁, …, dₙ, K)⟩_R}: the array field {@code Array(d₁, …, dₙ, K)} (type-system.md 1)
 * with a resolution, i.e. the dimension sizes together with the element {@link ScalarType} {@code ⟨K⟩_R}.
 * For example Java {@code double[]} is {@code ⟨Vec(⊤, Real)⟩_F64}.
 * <p>
 * Dimensions are kept in a canonical form so that the definitions in the type system hold by
 * equality: the rank is at least 2 (a rank-1 {@code Array(n)} is {@code Mat(n, 1)} = {@code Vec(n)}),
 * and trailing dimensions beyond the second that are known to be 1 are dropped. Hence
 * {@code Vec(n) = Mat(n, 1)} and a scalar is {@code Mat(1, 1)}.
 */
public record ArrayType(List<Card> dims, ScalarType element) implements VelType {

    public ArrayType {
        dims = normalize(dims);
    }

    public static ArrayType scalar(ScalarType element) {
        return new ArrayType(List.of(Card.ONE, Card.ONE), element);
    }

    public static ArrayType vec(Card n, ScalarType element) {
        return new ArrayType(List.of(n, Card.ONE), element);
    }

    public static ArrayType mat(Card rows, Card cols, ScalarType element) {
        return new ArrayType(List.of(rows, cols), element);
    }

    public static ArrayType of(ScalarType element, Card... dims) {
        return new ArrayType(List.of(dims), element);
    }

    private static List<Card> normalize(List<Card> dims) {
        if (dims.isEmpty()) {
            throw new IllegalArgumentException("an array has at least one dimension");
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

    public int rank() {
        return dims.size();
    }

    /** {@code [F] = Mat(1, 1, F)}, known at compile time. */
    public boolean isScalar() {
        return rank() == 2 && dims.get(0).isOne() && dims.get(1).isOne();
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

    public ArrayType withElement(ScalarType newElement) {
        return new ArrayType(dims, newElement);
    }

    public ArrayType withDims(List<Card> newDims) {
        return new ArrayType(newDims, element);
    }

    /** Assignability: same shape (⊤ matches any size) and an assignable element type. */
    public boolean isAssignableTo(ArrayType target) {
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
        return element.isAssignableTo(target.element);
    }

    @Override
    public String toString() {
        if (isScalar()) {
            return element.toString();
        }
        String sizes = dims.stream().map(Card::toString).collect(Collectors.joining(", "));
        return (isMatrix() ? "Mat(" : "Array(") + sizes + ", " + element + ")";
    }
}
