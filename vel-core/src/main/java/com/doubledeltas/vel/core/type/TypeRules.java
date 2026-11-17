package com.doubledeltas.vel.core.type;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import com.doubledeltas.vel.core.type.Promotion.Arithmetic;
import com.doubledeltas.vel.core.type.Resolution.IntLiteral;

/** Result types of VEL operations: shapes and element types combined. */
public final class TypeRules {

    private TypeRules() {
    }

    // ------------------------------------------------------------------------------------------
    // Element types
    // ------------------------------------------------------------------------------------------

    /** Element type of {@code a op b}. */
    public static ScalarType arithmetic(Arithmetic op, ScalarType a, ScalarType b) {
        Field field = Field.join(a.field(), b.field());
        if (field == Field.COMPLEX && isQuotientOrRemainder(op)) {
            // to be defined together with the function syntax and user-defined infix operators
            throw new VelTypeException("operator " + op + " is not defined for Complex");
        }
        return new ScalarType(field, Promotion.arithmetic(op, a.resolution(), b.resolution()));
    }

    private static boolean isQuotientOrRemainder(Arithmetic op) {
        return op == Arithmetic.FLOOR_DIV || op == Arithmetic.TRUNC_DIV
                || op == Arithmetic.FLOOR_MOD || op == Arithmetic.TRUNC_MOD;
    }

    /** Element type of a concatenation or a common type: field ∨ and resolution ⊔ (no minimum width). */
    public static ScalarType join(ScalarType a, ScalarType b) {
        return new ScalarType(Field.join(a.field(), b.field()), Promotion.join(a.resolution(), b.resolution()));
    }

    // ------------------------------------------------------------------------------------------
    // Operators
    // ------------------------------------------------------------------------------------------

    /** Type of {@code a op b}. */
    public static ArrayType binary(BinaryOperator op, ArrayType a, ArrayType b) {
        // a /< b divides b by a
        ScalarType element = op == BinaryOperator.LEFT_DIV
                ? arithmetic(Arithmetic.DIV, b.element(), a.element())
                : arithmetic(op.arithmetic(), a.element(), b.element());

        if (op.isElementWise() || (a.isScalar() && b.isScalar())) {
            return new ArrayType(Shapes.broadcast(a.dims(), b.dims()), element);
        }
        return switch (op) {
            case MUL -> a.isScalar() || b.isScalar()
                    ? new ArrayType(Shapes.broadcast(a.dims(), b.dims()), element) // scaling
                    : new ArrayType(Shapes.matmul(a, b), element);
            case DIV, RIGHT_DIV -> b.isScalar()
                    ? new ArrayType(Shapes.broadcast(a.dims(), b.dims()), element)
                    : new ArrayType(Shapes.rightDivide(a, b), element);
            case LEFT_DIV -> a.isScalar()
                    ? new ArrayType(Shapes.broadcast(a.dims(), b.dims()), element)
                    : new ArrayType(Shapes.leftDivide(a, b), element);
            case POW -> {
                if (!b.isScalar()) {
                    throw new VelTypeException("exponent of ^ must be a scalar, not " + b + " (use .^ for element-wise power)");
                }
                Shapes.requireSquare(a, "matrix power");
                yield a.withElement(element);
            }
            default -> throw new IllegalStateException(op.toString());
        };
    }

    /** Type of {@code -a} or {@code +a}. */
    public static ArrayType unary(ArrayType a, boolean minus) {
        ScalarType e = a.element();
        return a.withElement(new ScalarType(e.field(), Promotion.negate(e.resolution(), minus)));
    }

    /**
     * Type of {@code a'} (conjugate) or {@code a.'}: the element is unchanged and the order of the
     * dimensions is reversed (a matrix is transposed; a tensor of rank 3 or more has its indices reversed).
     */
    public static ArrayType transpose(ArrayType a) {
        return a.withDims(Shapes.transpose(a));
    }

    // ------------------------------------------------------------------------------------------
    // Literals
    // ------------------------------------------------------------------------------------------

    /**
     * Type of a matrix literal {@code [A, B; C, D]}: each row is concatenated horizontally, then the
     * rows vertically (type-system.md 1.4).
     */
    public static ArrayType matrix(List<List<ArrayType>> rows) {
        if (rows.isEmpty() || rows.stream().anyMatch(List::isEmpty)) {
            throw new VelTypeException("empty matrix literal");
        }
        ArrayType result = null;
        for (List<ArrayType> row : rows) {
            ArrayType line = row.getFirst();
            for (ArrayType block : row.subList(1, row.size())) {
                line = new ArrayType(Shapes.horizontal(line, block), join(line.element(), block.element()));
            }
            result = result == null
                    ? line
                    : new ArrayType(Shapes.vertical(result, line), join(result.element(), line.element()));
        }
        return result;
    }

    /** Type of a tuple {@code (A₁, …, Aₖ)} = {@code [A₁; …; Aₖ]}. */
    public static ArrayType tuple(List<ArrayType> items) {
        return matrix(items.stream().map(List::of).toList());
    }

    // ------------------------------------------------------------------------------------------
    // Ranges and indexing
    // ------------------------------------------------------------------------------------------

    /**
     * Type of a range {@code start:end} (inclusive): a row vector of indices. Both bounds are cardinals
     * (non-negative integer scalars; {@code end} for the last index). The length is known only when
     * both bounds are integer literals; otherwise it is ⊤.
     */
    public static ArrayType range(ArrayType start, ArrayType end) {
        requireCard(start, "range bound");
        requireCard(end, "range bound");
        ScalarType element = join(start.element(), end.element());
        element = new ScalarType(element.field(), Promotion.defaultOf(element.resolution()));
        Card length = Card.TOP;
        if (start.element().resolution() instanceof IntLiteral s && end.element().resolution() instanceof IntLiteral e) {
            BigInteger n = e.value().subtract(s.value()).add(BigInteger.ONE);
            length = Card.of(n.signum() > 0 ? n.longValueExact() : 0);
        }
        return ArrayType.mat(Card.ONE, length, element);
    }

    /** Type of {@code a[s₁, …, sₖ]}. */
    public static ArrayType index(ArrayType a, List<Subscript> subscripts) {
        return a.withDims(region(a, subscripts));
    }

    /**
     * Type of the variable after {@code a[s₁, …, sₖ] = value} (copy and rewrite, type-system.md 1.5).
     * The value must fit the selected region (or broadcast into it) and have a common type with the
     * elements; when its resolution is larger, the whole array takes the larger resolution.
     */
    public static ArrayType indexAssign(ArrayType a, List<Subscript> subscripts, ArrayType value) {
        List<Card> region = region(a, subscripts);
        int rank = Math.max(region.size(), value.rank());
        for (int i = 0; i < rank; i++) {
            Card v = i < value.rank() ? value.dims().get(i) : Card.ONE;
            Card r = i < region.size() ? region.get(i) : Card.ONE;
            if (!v.isOne()) {
                Shapes.unify(r, v, "cannot assign " + value + " to a region of size " + region + ":");
            }
        }
        return a.withElement(join(a.element(), value.element()));
    }

    /** Sizes of the region selected by the subscripts. */
    private static List<Card> region(ArrayType a, List<Subscript> subscripts) {
        if (subscripts.size() == a.rank()) {
            List<Card> dims = new ArrayList<>(a.rank());
            for (int i = 0; i < a.rank(); i++) {
                dims.add(select(a.dims().get(i), subscripts.get(i)));
            }
            return dims;
        }
        if (subscripts.size() == 1 && a.isMatrix() && (a.rows().isOne() || a.cols().isOne())) {
            // a vector takes a single subscript along its long dimension and keeps its orientation
            boolean column = a.cols().isOne();
            Card selected = select(column ? a.rows() : a.cols(), subscripts.getFirst());
            return column ? List.of(selected, Card.ONE) : List.of(Card.ONE, selected);
        }
        throw new VelTypeException("expected " + a.rank() + " subscripts for " + a + ", got " + subscripts.size());
    }

    private static Card select(Card size, Subscript subscript) {
        return switch (subscript) {
            case Subscript.At at -> {
                requireCard(at.index(), "index");
                if (at.index().element().resolution() instanceof IntLiteral lit
                        && size instanceof Card.Finite f && lit.value().compareTo(BigInteger.valueOf(f.size())) >= 0) {
                    throw new VelTypeException("index " + lit.value() + " out of bounds for size " + f.size());
                }
                yield Card.ONE;
            }
            case Subscript.Span span -> span.range().cols();
            case Subscript.All ignored -> size;
        };
    }

    /** A cardinal: a non-negative integer scalar. */
    private static void requireCard(ArrayType t, String what) {
        Resolution r = t.element().resolution();
        boolean integer = r.isInteger() || r instanceof IntLiteral;
        if (!t.isScalar() || t.element().field() != Field.REAL || !integer) {
            throw new VelTypeException(what + " must be a non-negative integer scalar, not " + t);
        }
        if (r instanceof IntLiteral lit && lit.value().signum() < 0) {
            throw new VelTypeException(what + " must not be negative: " + lit.value());
        }
    }
}
