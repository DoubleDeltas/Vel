package com.doubledeltas.vel.core.type;

import java.util.ArrayList;
import java.util.List;

/** Shape rules: ⊤ dimensions, broadcasting, matrix product, transposition, concatenation (type-system.md 1.2–1.4). */
public final class Shapes {

    private Shapes() {
    }

    /**
     * Two sizes that must agree (no broadcasting). Equal sizes stay; ⊤ on either side gives ⊤
     * (a mismatch shows up at runtime as a NullPointerException); known different sizes are an error.
     */
    public static Card unify(Card a, Card b, String what) {
        if (a.equals(b)) {
            return a;
        }
        if (!a.isKnown() || !b.isKnown()) {
            return Card.TOP;
        }
        throw new VelTypeException(what + " mismatch: " + a + " vs " + b);
    }

    /** One dimension of an element-wise operation: a size known to be 1 expands to the other size. */
    public static Card broadcast(Card a, Card b) {
        if (a.isOne()) {
            return b;
        }
        if (b.isOne()) {
            return a;
        }
        return unify(a, b, "dimension size");
    }

    /** Shape of an element-wise operation (MATLAB implicit expansion). */
    public static List<Card> broadcast(List<Card> a, List<Card> b) {
        int rank = Math.max(a.size(), b.size());
        List<Card> result = new ArrayList<>(rank);
        try {
            for (int i = 0; i < rank; i++) {
                result.add(broadcast(dim(a, i), dim(b, i)));
            }
        } catch (VelTypeException e) {
            throw new VelTypeException("cannot broadcast " + a + " and " + b + ": " + e.getMessage());
        }
        return result;
    }

    private static Card dim(List<Card> dims, int i) {
        return i < dims.size() ? dims.get(i) : Card.ONE;
    }

    /** {@code Mat(m, k) · Mat(k, n) : Mat(m, n)}. */
    public static List<Card> matmul(ArrayAlgebra a, ArrayAlgebra b) {
        requireMatrix(a, "matrix product");
        requireMatrix(b, "matrix product");
        unify(a.cols(), b.rows(), "inner dimension of matrix product");
        return List.of(a.rows(), b.cols());
    }

    /** {@code A / B = A · B⁻¹}: B is square and A has as many columns as B. */
    public static List<Card> rightDivide(ArrayAlgebra a, ArrayAlgebra b) {
        requireMatrix(a, "matrix division");
        requireMatrix(b, "matrix division");
        Card n = unify(b.rows(), b.cols(), "divisor must be square:");
        unify(a.cols(), n, "columns of matrix division");
        return List.of(a.rows(), b.cols());
    }

    /** {@code A /< B = A⁻¹ · B}: A is square and has as many rows as B. */
    public static List<Card> leftDivide(ArrayAlgebra a, ArrayAlgebra b) {
        requireMatrix(a, "matrix division");
        requireMatrix(b, "matrix division");
        Card n = unify(a.rows(), a.cols(), "divisor must be square:");
        unify(n, b.rows(), "rows of matrix division");
        return List.of(a.cols(), b.cols());
    }

    /** A square matrix (for matrix power). */
    public static void requireSquare(ArrayAlgebra a, String operation) {
        requireMatrix(a, operation);
        unify(a.rows(), a.cols(), operation + " needs a square matrix:");
    }

    /** Reverses the order of the dimensions: a matrix is transposed, a tensor of rank 3 or more is reversed. */
    public static List<Card> transpose(ArrayAlgebra a) {
        return a.dims().reversed();
    }

    /** {@code [A, B]}: same number of rows, columns add up. */
    public static List<Card> horizontal(ArrayAlgebra a, ArrayAlgebra b) {
        requireMatrix(a, "concatenation");
        requireMatrix(b, "concatenation");
        return List.of(unify(a.rows(), b.rows(), "rows of horizontal concatenation"), a.cols().plus(b.cols()));
    }

    /** {@code [A; B]}: same number of columns, rows add up. */
    public static List<Card> vertical(ArrayAlgebra a, ArrayAlgebra b) {
        requireMatrix(a, "concatenation");
        requireMatrix(b, "concatenation");
        return List.of(a.rows().plus(b.rows()), unify(a.cols(), b.cols(), "columns of vertical concatenation"));
    }

    private static void requireMatrix(ArrayAlgebra a, String operation) {
        if (!a.isMatrix()) {
            throw new VelTypeException(operation + " is defined for matrices, not " + a);
        }
    }
}
