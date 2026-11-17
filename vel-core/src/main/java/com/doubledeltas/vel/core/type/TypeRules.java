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
     * Type of a range {@code start:end} = [start, end) (end-exclusive, as in Python): a row vector of
     * indices. Both bounds are cardinals (non-negative integer scalars). The length is known only when
     * both bounds are integer literals ({@code max(0, end - start)}); otherwise it is ⊤.
     */
    public static ArrayType range(ArrayType start, ArrayType end) {
        requireCard(start, "range bound");
        requireCard(end, "range bound");
        ScalarType element = join(start.element(), end.element());
        element = new ScalarType(element.field(), Promotion.defaultOf(element.resolution()));
        Card length = Card.TOP;
        if (start.element().resolution() instanceof IntLiteral s && end.element().resolution() instanceof IntLiteral e) {
            BigInteger n = e.value().subtract(s.value());
            length = Card.of(n.signum() > 0 ? n.longValueExact() : 0);
        }
        return ArrayType.mat(Card.ONE, length, element);
    }

    /**
     * Type of {@code a[s₁, …, sₖ]}.
     * <p>
     * Without partitions it is the selected region. With a partition {@code a₀:…:aₖ} on a matrix it is
     * the block tensor {@code Array(h, w, k, l)}: block {@code (i, j)} is {@code T[:, :, i, j]}, {@code k}
     * and {@code l} are the numbers of row and column blocks, and {@code h}, {@code w} the block sizes
     * (⊤ when the blocks differ in size; the missing positions of smaller blocks are null).
     */
    public static ArrayType index(ArrayType a, List<Subscript> subscripts) {
        List<Selection> sel = selections(a, subscripts);
        if (subscripts.stream().noneMatch(s -> s instanceof Subscript.Partition)) {
            return a.withDims(sel.stream().map(Selection::size).toList());
        }
        if (sel.size() != 2) {
            throw new VelTypeException("partitioned indexing is defined for matrices, not " + a);
        }
        return a.withDims(List.of(sel.get(0).size(), sel.get(1).size(), sel.get(0).blocks(), sel.get(1).blocks()));
    }

    /**
     * Type of the variable after {@code a[s₁, …, sₖ] = value} (copy and rewrite, type-system.md 1.5).
     * The value must fit the selected region (or broadcast into it) and have a common type with the
     * elements; when its resolution is larger, the whole array takes the larger resolution.
     */
    public static ArrayType indexAssign(ArrayType a, List<Subscript> subscripts, ArrayType value) {
        if (subscripts.stream().anyMatch(s -> s instanceof Subscript.Partition)) {
            throw new VelTypeException("cannot assign to a partitioned index");
        }
        List<Card> region = selections(a, subscripts).stream().map(Selection::size).toList();
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

    /**
     * Destructuring assignment {@code [t₁₁, …; …] = value} with a pattern of {@code rows × cols} items
     * (a tuple pattern of k items is k × 1). Returns the type each target receives.
     * <ul>
     *   <li>A matrix value is split into elements: it must be {@code rows × cols}, each target gets a scalar.</li>
     *   <li>A block tensor {@code Array(h, w, k, l)} (rank 3 or 4, see {@link #index}) is split into
     *       blocks: {@code k × l} must be {@code rows × cols}, each target gets a {@code Mat(h, w)}.</li>
     * </ul>
     * ⊤ sizes follow type-system.md 1.2.
     */
    public static ArrayType destructure(ArrayType value, int rows, int cols) {
        String what = "cannot destructure " + value + " into a " + rows + "x" + cols + " pattern:";
        List<Card> d = value.dims();
        if (value.isMatrix()) {
            Shapes.unify(d.get(0), Card.of(rows), what);
            Shapes.unify(d.get(1), Card.of(cols), what);
            return ArrayType.scalar(value.element());
        }
        if (value.rank() <= 4) {
            Shapes.unify(d.get(2), Card.of(rows), what);
            Shapes.unify(value.rank() == 4 ? d.get(3) : Card.ONE, Card.of(cols), what);
            return ArrayType.mat(d.get(0), d.get(1), value.element());
        }
        throw new VelTypeException(what + " rank " + value.rank() + " is too high");
    }

    /**
     * Exact types of the blocks of {@code a[s₁, s₂]}, {@code blocks.get(i).get(j)} being block
     * {@code (i, j)}. When the right-hand side of a block destructuring is written as a partitioned
     * index, the targets get these types instead of the common (possibly ⊤) block size of the tensor:
     * in {@code [A, B; C, D] = M[0:2:3, 0:1:3]}, {@code A} is 2x1 and {@code C} is a scalar.
     */
    public static List<List<ArrayType>> blocks(ArrayType a, List<Subscript> subscripts) {
        index(a, subscripts); // validates
        List<Selection> sel = selections(a, subscripts);
        if (sel.size() != 2) {
            throw new VelTypeException("partitioned indexing is defined for matrices, not " + a);
        }
        List<Card> rows = blockSizes(sel.get(0), rowsOrCols(a, subscripts, 0));
        List<Card> cols = blockSizes(sel.get(1), rowsOrCols(a, subscripts, 1));
        List<List<ArrayType>> result = new ArrayList<>();
        for (Card r : rows) {
            List<ArrayType> line = new ArrayList<>();
            for (Card c : cols) {
                line.add(ArrayType.mat(r, c, a.element()));
            }
            result.add(List.copyOf(line));
        }
        return List.copyOf(result);
    }

    /** The subscript applied to dimension {@code dim} of a matrix (a vector's single subscript applies to its long side). */
    private static Subscript rowsOrCols(ArrayType a, List<Subscript> subscripts, int dim) {
        if (subscripts.size() == 2) {
            return subscripts.get(dim);
        }
        boolean column = a.cols().isOne();
        return (dim == 0) == column ? subscripts.getFirst() : Subscript.all();
    }

    private static List<Card> blockSizes(Selection selection, Subscript subscript) {
        if (!(subscript instanceof Subscript.Partition p)) {
            return List.of(selection.size());
        }
        List<Card> sizes = new ArrayList<>();
        for (int i = 1; i < p.cuts().size(); i++) {
            if (p.cuts().get(i - 1).element().resolution() instanceof IntLiteral lo
                    && p.cuts().get(i).element().resolution() instanceof IntLiteral hi) {
                sizes.add(Card.of(hi.value().subtract(lo.value()).longValueExact()));
            } else {
                sizes.add(Card.TOP);
            }
        }
        return sizes;
    }

    /** What a subscript selects along one dimension: the (block) size and the number of blocks. */
    private record Selection(Card size, Card blocks) {
        static final Selection ONE = new Selection(Card.ONE, Card.ONE);
    }

    private static List<Selection> selections(ArrayType a, List<Subscript> subscripts) {
        if (subscripts.size() == a.rank()) {
            List<Selection> result = new ArrayList<>(a.rank());
            for (int i = 0; i < a.rank(); i++) {
                result.add(select(a.dims().get(i), subscripts.get(i)));
            }
            return result;
        }
        if (subscripts.size() == 1 && a.isMatrix() && (a.rows().isOne() || a.cols().isOne())) {
            // a vector takes a single subscript along its long dimension and keeps its orientation
            boolean column = a.cols().isOne();
            Selection selected = select(column ? a.rows() : a.cols(), subscripts.getFirst());
            return column ? List.of(selected, Selection.ONE) : List.of(Selection.ONE, selected);
        }
        throw new VelTypeException("expected " + a.rank() + " subscripts for " + a + ", got " + subscripts.size());
    }

    private static Selection select(Card size, Subscript subscript) {
        return switch (subscript) {
            case Subscript.At at -> {
                requireCard(at.index(), "index");
                if (at.index().element().resolution() instanceof IntLiteral lit
                        && size instanceof Card.Finite f && lit.value().compareTo(BigInteger.valueOf(f.size())) >= 0) {
                    throw new VelTypeException("index " + lit.value() + " out of bounds for size " + f.size());
                }
                yield Selection.ONE;
            }
            case Subscript.Span span -> new Selection(span.range().cols(), Card.ONE);
            case Subscript.All ignored -> new Selection(size, Card.ONE);
            case Subscript.Partition p -> partition(size, p);
        };
    }

    /** Block size (⊤ unless all blocks are known and equal) and block count of a partition. */
    private static Selection partition(Card size, Subscript.Partition p) {
        List<BigInteger> cuts = new ArrayList<>();
        for (ArrayType cut : p.cuts()) {
            requireCard(cut, "cut point");
            cuts.add(cut.element().resolution() instanceof IntLiteral lit ? lit.value() : null);
        }
        Card blocks = Card.of(cuts.size() - 1);
        if (cuts.contains(null)) {
            return new Selection(Card.TOP, blocks);
        }
        for (int i = 1; i < cuts.size(); i++) {
            if (cuts.get(i).compareTo(cuts.get(i - 1)) <= 0) {
                throw new VelTypeException("cut points must be strictly increasing: " + cuts);
            }
        }
        if (size instanceof Card.Finite f && cuts.getLast().compareTo(BigInteger.valueOf(f.size())) > 0) {
            throw new VelTypeException("last cut point " + cuts.getLast() + " exceeds size " + f.size());
        }
        BigInteger first = cuts.get(1).subtract(cuts.get(0));
        for (int i = 2; i < cuts.size(); i++) {
            if (!cuts.get(i).subtract(cuts.get(i - 1)).equals(first)) {
                return new Selection(Card.TOP, blocks); // blocks of different sizes
            }
        }
        return new Selection(Card.of(first.longValueExact()), blocks);
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
