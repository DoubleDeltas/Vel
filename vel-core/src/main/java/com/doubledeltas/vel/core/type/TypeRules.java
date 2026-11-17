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
        Algebra algebra = Algebra.join(a.algebra(), b.algebra());
        if (algebra == Algebra.COMPLEX && isQuotientOrRemainder(op)) {
            // to be defined together with the function syntax and user-defined infix operators
            throw new VelTypeException("operator " + op + " is not defined for Complex");
        }
        return ScalarType.of(algebra, Promotion.arithmetic(op, a.resolution(), b.resolution()));
    }

    private static boolean isQuotientOrRemainder(Arithmetic op) {
        return op == Arithmetic.FLOOR_DIV || op == Arithmetic.TRUNC_DIV
                || op == Arithmetic.FLOOR_MOD || op == Arithmetic.TRUNC_MOD;
    }

    /** Element type of a concatenation or a common type: algebra ∨ and resolution ⊔ (no minimum width). */
    public static ScalarType join(ScalarType a, ScalarType b) {
        return ScalarType.of(Algebra.join(a.algebra(), b.algebra()), Promotion.join(a.resolution(), b.resolution()));
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

    /**
     * Type of {@code if c then a else b}. The condition is a Bool scalar; only the chosen branch is
     * evaluated. The result has the common type of the branches: the same shape (⊤ follows
     * type-system.md 1.2), algebra ∨ and resolution ⊔ of the elements.
     */
    public static ArrayType conditional(ArrayType condition, ArrayType then, ArrayType otherwise) {
        if (!condition.isScalar() || condition.element().algebra() != Algebra.BOOL) {
            throw new VelTypeException("condition of if must be a Bool scalar, not " + condition);
        }
        int rank = Math.max(then.rank(), otherwise.rank());
        List<Card> dims = new ArrayList<>(rank);
        for (int i = 0; i < rank; i++) {
            Card a = i < then.rank() ? then.dims().get(i) : Card.ONE;
            Card b = i < otherwise.rank() ? otherwise.dims().get(i) : Card.ONE;
            dims.add(Shapes.unify(a, b, "branches of if have different shapes, " + then + " and " + otherwise + ":"));
        }
        return new ArrayType(dims, join(then.element(), otherwise.element()));
    }

    /** Type of {@code -a} or {@code +a}. */
    public static ArrayType unary(ArrayType a, boolean minus) {
        ScalarType e = a.element();
        return a.withElement(new ScalarType(e.algebra(), Promotion.negate(e.resolution(), minus)));
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

    /**
     * Type of a tuple {@code (A₁, …, Aₖ)}: the items are <em>stacked</em> along a new first dimension.
     * All items have the same shape {@code 𝐝} and the result is {@code Array(k, 𝐝)}, item {@code i}
     * being {@code T[i, …]}. Scalars give {@code Vec(k)}, vectors of length M give {@code Mat(k, M)}
     * (item i is row i). The same rule maps component objects such as {@code Pair} (java.md 2.5).
     */
    public static ArrayType tuple(List<ArrayType> items) {
        if (items.isEmpty()) {
            throw new VelTypeException("empty tuple");
        }
        List<Card> shape = items.getFirst().dims();
        ScalarType element = items.getFirst().element();
        for (ArrayType item : items.subList(1, items.size())) {
            shape = unifyShapes(shape, item.dims(), "items of a tuple have different shapes:");
            element = join(element, item.element());
        }
        List<Card> dims = new ArrayList<>(shape.size() + 1);
        dims.add(Card.of(items.size()));
        dims.addAll(shape);
        return new ArrayType(dims, element);
    }

    /** Two shapes that must agree dimension by dimension (missing trailing dimensions are 1). */
    private static List<Card> unifyShapes(List<Card> a, List<Card> b, String what) {
        int rank = Math.max(a.size(), b.size());
        List<Card> dims = new ArrayList<>(rank);
        for (int i = 0; i < rank; i++) {
            Card x = i < a.size() ? a.get(i) : Card.ONE;
            Card y = i < b.size() ? b.get(i) : Card.ONE;
            dims.add(Shapes.unify(x, y, what + " " + a + " vs " + b + ":"));
        }
        return dims;
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
        element = new ScalarType(element.algebra(), Promotion.defaultOf(element.resolution()));
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
     * a block tensor: the blocks are stacked like the items of a tuple, the block indices first.
     * <ul>
     *   <li>rows and columns partitioned: {@code Array(k, l, h, w)}, block {@code (i, j)} is {@code T[i, j, :, :]}</li>
     *   <li>only the rows (or only the columns) partitioned: {@code Array(k, h, w)}, block {@code i} is {@code T[i, :, :]}</li>
     * </ul>
     * {@code k}, {@code l} are the numbers of row and column blocks and {@code h}, {@code w} the block
     * sizes (⊤ when the blocks differ in size; the missing positions of smaller blocks are null).
     */
    public static ArrayType index(ArrayType a, List<Subscript> subscripts) {
        List<Selection> sel = selections(a, subscripts);
        if (subscripts.stream().noneMatch(s -> s instanceof Subscript.Partition)) {
            return a.withDims(sel.stream().map(Selection::size).toList());
        }
        if (sel.size() != 2) {
            throw new VelTypeException("partitioned indexing is defined for matrices, not " + a);
        }
        List<Card> dims = new ArrayList<>(4);
        for (Selection s : sel) {
            if (!s.blocks().isOne()) {
                dims.add(s.blocks());
            }
        }
        dims.add(sel.get(0).size());
        dims.add(sel.get(1).size());
        return a.withDims(dims);
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
     * Tuple pattern {@code (t₁, …, tₖ) = value}: the value is split along its first dimension, which
     * must be {@code k}; each target gets the rest, {@code value[i, …]}. This undoes a tuple or a
     * component object: {@code (x, y) = (1, 2)} gives scalars, {@code (u, v) = M} with
     * {@code M : Mat(2, n)} gives {@code Vec(n)} rows, {@code (Top, Bottom) = M[0:3:6, :]} gives blocks.
     * ⊤ sizes follow type-system.md 1.2.
     */
    public static ArrayType destructureTuple(ArrayType value, int k) {
        String what = "cannot destructure " + value + " into a tuple pattern of " + k + ":";
        List<Card> d = value.dims();
        Shapes.unify(d.getFirst(), Card.of(k), what);
        return new ArrayType(d.subList(1, d.size()), value.element());
    }

    /**
     * Matrix pattern {@code [t₁₁, …; …] = value} of {@code rows × cols} items: the value is split along
     * its first two dimensions, which must be {@code rows × cols}; each target gets the rest,
     * {@code value[i, j, …]}. A matrix is split into its elements (scalars), a block tensor
     * {@code Array(k, l, h, w)} into its blocks {@code Mat(h, w)}.
     */
    public static ArrayType destructureMatrix(ArrayType value, int rows, int cols) {
        String what = "cannot destructure " + value + " into a " + rows + "x" + cols + " pattern:";
        List<Card> d = value.dims();
        Shapes.unify(d.get(0), Card.of(rows), what);
        Shapes.unify(d.get(1), Card.of(cols), what);
        List<Card> rest = d.subList(2, d.size());
        return rest.isEmpty() ? ArrayType.scalar(value.element()) : new ArrayType(rest, value.element());
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
        if (!t.isScalar() || t.element().algebra() != Algebra.INT) {
            throw new VelTypeException(what + " must be a non-negative integer scalar, not " + t);
        }
        if (r instanceof IntLiteral lit && lit.value().signum() < 0) {
            throw new VelTypeException(what + " must not be negative: " + lit.value());
        }
    }
}
