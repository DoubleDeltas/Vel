package com.doubledeltas.vel.core.type;

import java.math.BigInteger;
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
        ScalarType element = arithmetic(op.arithmetic(), a.element(), b.element());

        if (op.isElementWise() || bothScalar(a, b)) {
            return new ArrayType(Shapes.broadcast(a.dims(), b.dims()), element);
        }
        return switch (op) {
            case MUL -> a.isScalar() || b.isScalar()
                    ? new ArrayType(Shapes.broadcast(a.dims(), b.dims()), element) // scaling
                    : new ArrayType(Shapes.matmul(a, b), element);
            case DIV -> b.isScalar()
                    ? new ArrayType(Shapes.broadcast(a.dims(), b.dims()), element)
                    : new ArrayType(Shapes.rightDivide(a, b), element);
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

    private static boolean bothScalar(ArrayType a, ArrayType b) {
        return a.isScalar() && b.isScalar();
    }

    /** Type of {@code -a} or {@code +a}. */
    public static ArrayType unary(ArrayType a, boolean minus) {
        ScalarType e = a.element();
        return a.withElement(new ScalarType(e.field(), Promotion.negate(e.resolution(), minus)));
    }

    /** Type of {@code a'} (conjugate) or {@code a.'}: shape transposed, element unchanged. */
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

    /**
     * Type of a range {@code start:end} or {@code start:step:end}: a row vector. The length is known
     * only when all bounds are integer literals; otherwise it is ⊤.
     *
     * @param step {@code null} for {@code start:end}
     */
    public static ArrayType range(ArrayType start, ArrayType step, ArrayType end) {
        List<ArrayType> parts = step == null ? List.of(start, end) : List.of(start, step, end);
        ScalarType element = null;
        for (ArrayType p : parts) {
            if (!p.isScalar() || p.element().field() != Field.REAL) {
                throw new VelTypeException("range bounds must be real scalars, not " + p);
            }
            element = element == null ? p.element() : join(element, p.element());
        }
        element = new ScalarType(element.field(), Promotion.defaultOf(element.resolution()));
        return ArrayType.mat(Card.ONE, rangeLength(start, step, end), element);
    }

    private static Card rangeLength(ArrayType start, ArrayType step, ArrayType end) {
        if (start.element().resolution() instanceof IntLiteral s
                && end.element().resolution() instanceof IntLiteral e
                && (step == null || step.element().resolution() instanceof IntLiteral)) {
            BigInteger d = step == null ? BigInteger.ONE : ((IntLiteral) step.element().resolution()).value();
            if (d.signum() == 0) {
                throw new VelTypeException("range step must not be 0");
            }
            BigInteger n = e.value().subtract(s.value()).divide(d).add(BigInteger.ONE);
            boolean empty = e.value().subtract(s.value()).signum() * d.signum() < 0;
            return Card.of(empty ? 0 : n.longValueExact());
        }
        return Card.TOP;
    }
}
