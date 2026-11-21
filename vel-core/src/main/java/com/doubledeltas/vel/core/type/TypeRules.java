package com.doubledeltas.vel.core.type;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Result algebras of VEL operations: shapes and element algebras combined (type-system.md 1, 3).
 * Resolutions are not handled here; they are decided by the concretization rules of each target
 * language (type-system.md 0.3, java.md 7).
 */
public final class TypeRules {

    private TypeRules() {
    }

    // ------------------------------------------------------------------------------------------
    // Element algebras (type-system.md 3.1, 3.2)
    // ------------------------------------------------------------------------------------------

    /**
     * Element algebra of {@code a op b}. For {@code ^} this is the rule for an exponent other than a
     * non-negative integer literal ({@code A ∨ B ∨ Real}); {@link #binary} handles the literal case.
     */
    public static ScalarAlgebra arithmetic(Arithmetic op, ScalarAlgebra a, ScalarAlgebra b) {
        if (a == ScalarAlgebra.NOT || b == ScalarAlgebra.NOT) {
            return ScalarAlgebra.NOT; // the operation is never reached
        }
        a = a.arithmetic(); // Card is computed as Int (type-system.md 1.1)
        b = b.arithmetic();
        if (a == ScalarAlgebra.BOOL || b == ScalarAlgebra.BOOL) {
            ScalarAlgebra.join(a, b); // Bool with a numeric algebra: no join
            // Bool: + is OR, * is AND, o+ is XOR (type-system.md footnote 1)
            if (op == Arithmetic.ADD || op == Arithmetic.MUL || op == Arithmetic.XOR) {
                return ScalarAlgebra.BOOL;
            }
            throw new VelTypeException("operator " + op + " is not defined for Bool");
        }
        if (op == Arithmetic.XOR) {
            throw new VelTypeException("operator o+ is defined only for Bool");
        }
        ScalarAlgebra k = ScalarAlgebra.join(a, b);
        if (k == ScalarAlgebra.COMPLEX && op.isQuotientOrRemainder()) {
            // to be defined together with the function syntax and user-defined infix operators
            throw new VelTypeException("operator " + op + " is not defined for Complex");
        }
        if (op == Arithmetic.DIV && k == ScalarAlgebra.INT) {
            return ScalarAlgebra.REAL; // true division
        }
        if (op == Arithmetic.POW) {
            return ScalarAlgebra.join(k, ScalarAlgebra.REAL);
        }
        return k;
    }

    /** Element algebra (and constant value) of {@code a op b} for the elements of a and b. */
    private static ArrayAlgebra element(Arithmetic op, ArrayAlgebra a, ArrayAlgebra b) {
        ScalarAlgebra k = op == Arithmetic.POW && isNonNegativeInteger(b.constant())
                ? arithmetic(Arithmetic.MUL, a.element(), b.element()) // repeated multiplication
                : arithmetic(op, a.element(), b.element());
        if (a.constant() != null && b.constant() != null) {
            Constant folded = Constant.fold(op, a.constant(), b.constant());
            if (folded != null && folded.algebra() == k) {
                return ArrayAlgebra.scalar(folded);
            }
        }
        return ArrayAlgebra.scalar(k);
    }

    private static boolean isNonNegativeInteger(Constant c) {
        return c instanceof Constant.Int i && i.value().signum() >= 0;
    }

    /** Element algebra of a concatenation or a common algebra: {@code ∨}. */
    public static ScalarAlgebra join(ScalarAlgebra a, ScalarAlgebra b) {
        return ScalarAlgebra.join(a, b);
    }

    // ------------------------------------------------------------------------------------------
    // Operators
    // ------------------------------------------------------------------------------------------

    /** Algebra of {@code a op b}. */
    public static ArrayAlgebra binary(BinaryOperator op, ArrayAlgebra a, ArrayAlgebra b) {
        // a /< b divides b by a
        ArrayAlgebra e = op == BinaryOperator.LEFT_DIV
                ? element(Arithmetic.DIV, b, a)
                : element(op.arithmetic(), a, b);
        if (a.isScalar() && b.isScalar()) {
            return e;
        }
        ScalarAlgebra k = e.element();
        if (op.isElementWise()) {
            return new ArrayAlgebra(Shapes.broadcast(a.dims(), b.dims()), k);
        }
        return switch (op) {
            case MUL -> a.isScalar() || b.isScalar()
                    ? new ArrayAlgebra(Shapes.broadcast(a.dims(), b.dims()), k) // scaling
                    : new ArrayAlgebra(Shapes.matmul(a, b), k);
            case DIV, RIGHT_DIV -> b.isScalar()
                    ? new ArrayAlgebra(Shapes.broadcast(a.dims(), b.dims()), k)
                    : new ArrayAlgebra(Shapes.rightDivide(a, b), k);
            case LEFT_DIV -> a.isScalar()
                    ? new ArrayAlgebra(Shapes.broadcast(a.dims(), b.dims()), k)
                    : new ArrayAlgebra(Shapes.leftDivide(a, b), k);
            case POW -> {
                if (!b.isScalar()) {
                    throw new VelTypeException("exponent of ^ must be a scalar, not " + b + " (use .^ for element-wise power)");
                }
                Shapes.requireSquare(a, "matrix power");
                yield a.withElement(k);
            }
            default -> throw new IllegalStateException(op.toString());
        };
    }

    /**
     * Algebra of {@code if c then a else b}. The condition is a Bool scalar; only the chosen branch is
     * evaluated. The result has the common algebra of the branches: the same shape (⊤ follows
     * type-system.md 1.2) and the join of the element algebras.
     */
    public static ArrayAlgebra conditional(ArrayAlgebra condition, ArrayAlgebra then, ArrayAlgebra otherwise) {
        if (!condition.isScalar() || !condition.element().isSubtypeOf(ScalarAlgebra.BOOL)) {
            throw new VelTypeException("condition of if must be a Bool scalar, not " + condition);
        }
        int rank = Math.max(then.rank(), otherwise.rank());
        List<Card> dims = new ArrayList<>(rank);
        for (int i = 0; i < rank; i++) {
            Card a = i < then.rank() ? then.dims().get(i) : Card.ONE;
            Card b = i < otherwise.rank() ? otherwise.dims().get(i) : Card.ONE;
            dims.add(Shapes.unify(a, b, "branches of if have different shapes, " + then + " and " + otherwise + ":"));
        }
        return new ArrayAlgebra(dims, join(then.element(), otherwise.element()));
    }

    /** Algebra of {@code -a} or {@code +a}. A constant stays a constant. */
    public static ArrayAlgebra unary(ArrayAlgebra a, boolean minus) {
        if (a.element() == ScalarAlgebra.BOOL) {
            throw new VelTypeException("unary " + (minus ? "-" : "+") + " is not defined for Bool");
        }
        if (minus && a.constant() != null) {
            return ArrayAlgebra.scalar(a.constant().negate());
        }
        return a;
    }

    /**
     * Algebra of {@code a'} (conjugate) or {@code a.'}: the element is unchanged and the order of the
     * dimensions is reversed (a matrix is transposed; a tensor of rank 3 or more has its indices reversed).
     */
    public static ArrayAlgebra transpose(ArrayAlgebra a) {
        return a.isScalar() ? a : a.withDims(Shapes.transpose(a));
    }

    // ------------------------------------------------------------------------------------------
    // Literals
    // ------------------------------------------------------------------------------------------

    /**
     * Algebra of a matrix literal {@code [A, B; C, D]}: each row is concatenated horizontally, then the
     * rows vertically (type-system.md 1.4). Blocks whose shape is known to be 0x0 (such as {@code []})
     * drop out first, contributing neither shape nor element algebra; if every block is 0x0 the result
     * is {@code K^(0, 0)} with {@code K} the ∨ of their element algebras.
     */
    public static ArrayAlgebra matrix(List<List<ArrayAlgebra>> rows) {
        if (rows.isEmpty() || rows.stream().anyMatch(List::isEmpty)) {
            throw new VelTypeException("empty matrix literal");
        }
        List<List<ArrayAlgebra>> kept = dropEmptyBlocks(rows);
        if (kept.isEmpty()) {
            ScalarAlgebra element = ScalarAlgebra.NOT;
            for (List<ArrayAlgebra> row : rows) {
                for (ArrayAlgebra block : row) {
                    element = join(element, block.element());
                }
            }
            return empty(element);
        }
        ArrayAlgebra result = null;
        for (List<ArrayAlgebra> row : kept) {
            ArrayAlgebra line = row.getFirst();
            for (ArrayAlgebra block : row.subList(1, row.size())) {
                line = new ArrayAlgebra(Shapes.horizontal(line, block), join(line.element(), block.element()));
            }
            result = result == null
                    ? line
                    : new ArrayAlgebra(Shapes.vertical(result, line), join(result.element(), line.element()));
        }
        return result;
    }

    /** {@code K^(0, 0)}; {@code []} is {@code empty(Not)}. */
    public static ArrayAlgebra empty(ScalarAlgebra element) {
        return ArrayAlgebra.mat(Card.of(0), Card.of(0), element);
    }

    /** Whether the shape is known to be 0x0, so the block drops out of a concatenation. */
    public static boolean isEmptyBlock(ArrayAlgebra a) {
        return a.isMatrix() && a.rows().isZero() && a.cols().isZero();
    }

    /** The rows of a matrix literal without their 0x0 blocks; rows left empty are removed. */
    public static <T> List<List<T>> dropEmptyBlocks(List<List<T>> rows, Function<T, ArrayAlgebra> alg) {
        List<List<T>> kept = new ArrayList<>();
        for (List<T> row : rows) {
            List<T> r = row.stream().filter(b -> !isEmptyBlock(alg.apply(b))).toList();
            if (!r.isEmpty()) {
                kept.add(r);
            }
        }
        return kept;
    }

    private static List<List<ArrayAlgebra>> dropEmptyBlocks(List<List<ArrayAlgebra>> rows) {
        return dropEmptyBlocks(rows, a -> a);
    }

    /**
     * Algebra of a tuple {@code (A₁, …, Aₖ)}: the items are <em>stacked</em> along a new first dimension.
     * All items have the same shape {@code 𝐝} and the result is {@code Array(k, 𝐝)}, item {@code i}
     * being {@code T[i, …]}. Scalars give {@code Vec(k)}, vectors of length M give {@code Mat(k, M)}
     * (item i is row i). The same rule maps component objects such as {@code Pair} (java.md 2.5).
     */
    public static ArrayAlgebra tuple(List<ArrayAlgebra> items) {
        if (items.isEmpty()) {
            throw new VelTypeException("empty tuple");
        }
        List<Card> shape = items.getFirst().dims();
        ScalarAlgebra element = items.getFirst().element();
        for (ArrayAlgebra item : items.subList(1, items.size())) {
            shape = unifyShapes(shape, item.dims(), "items of a tuple have different shapes:");
            element = join(element, item.element());
        }
        List<Card> dims = new ArrayList<>(shape.size() + 1);
        dims.add(Card.of(items.size()));
        dims.addAll(shape);
        return new ArrayAlgebra(dims, element);
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
     * Algebra of a range {@code start:end} = [start, end) (end-exclusive, as in Python): a row vector of
     * Int indices. Both bounds are in index position: cardinals, or Int scalars converted to cardinals.
     * The length is {@code end - start}, which is ⊥ (null) when {@code end < start}; it is known only
     * when both bounds are integer constants (and then ⊥ is a compile error), otherwise it is ⊤.
     */
    public static ArrayAlgebra range(ArrayAlgebra start, ArrayAlgebra end) {
        requireCard(start, "range bound");
        requireCard(end, "range bound");
        Card length = Card.TOP;
        if (start.constant() instanceof Constant.Int s && end.constant() instanceof Constant.Int e) {
            length = Card.of(e.value().longValueExact()).minus(Card.of(s.value().longValueExact()))
                    .requireValid("length of the range " + s + ":" + e);
        }
        return ArrayAlgebra.mat(Card.ONE, length, ScalarAlgebra.INT);
    }

    /**
     * Algebra of {@code a[s₁, …, sₖ]}.
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
    public static ArrayAlgebra index(ArrayAlgebra a, List<Subscript> subscripts) {
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
     * Algebra of the variable after {@code a[s₁, …, sₖ] = value} (copy and rewrite, type-system.md 1.5).
     * The value must fit the selected region (or broadcast into it) and have a common algebra with the
     * elements; when its algebra is larger, the whole array takes the join.
     */
    public static ArrayAlgebra indexAssign(ArrayAlgebra a, List<Subscript> subscripts, ArrayAlgebra value) {
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
    public static ArrayAlgebra destructureTuple(ArrayAlgebra value, int k) {
        String what = "cannot destructure " + value + " into a tuple pattern of " + k + ":";
        List<Card> d = value.dims();
        Shapes.unify(d.getFirst(), Card.of(k), what);
        return new ArrayAlgebra(d.subList(1, d.size()), value.element());
    }

    /**
     * Matrix pattern {@code [t₁₁, …; …] = value} of {@code rows × cols} items: the value is split along
     * its first two dimensions, which must be {@code rows × cols}; each target gets the rest,
     * {@code value[i, j, …]}. A matrix is split into its elements (scalars), a block tensor
     * {@code Array(k, l, h, w)} into its blocks {@code Mat(h, w)}.
     */
    public static ArrayAlgebra destructureMatrix(ArrayAlgebra value, int rows, int cols) {
        String what = "cannot destructure " + value + " into a " + rows + "x" + cols + " pattern:";
        List<Card> d = value.dims();
        Shapes.unify(d.get(0), Card.of(rows), what);
        Shapes.unify(d.get(1), Card.of(cols), what);
        List<Card> rest = d.subList(2, d.size());
        return rest.isEmpty() ? ArrayAlgebra.scalar(value.element()) : new ArrayAlgebra(rest, value.element());
    }

    /**
     * Exact algebras of the blocks of {@code a[s₁, s₂]}, {@code blocks.get(i).get(j)} being block
     * {@code (i, j)}. When the right-hand side of a block destructuring is written as a partitioned
     * index, the targets get these algebras instead of the common (possibly ⊤) block size of the tensor:
     * in {@code [A, B; C, D] = M[0:2:3, 0:1:3]}, {@code A} is 2x1 and {@code C} is a scalar.
     */
    public static List<List<ArrayAlgebra>> blocks(ArrayAlgebra a, List<Subscript> subscripts) {
        index(a, subscripts); // validates
        List<Selection> sel = selections(a, subscripts);
        if (sel.size() != 2) {
            throw new VelTypeException("partitioned indexing is defined for matrices, not " + a);
        }
        List<Card> rows = blockSizes(sel.get(0), rowsOrCols(a, subscripts, 0));
        List<Card> cols = blockSizes(sel.get(1), rowsOrCols(a, subscripts, 1));
        List<List<ArrayAlgebra>> result = new ArrayList<>();
        for (Card r : rows) {
            List<ArrayAlgebra> line = new ArrayList<>();
            for (Card c : cols) {
                line.add(ArrayAlgebra.mat(r, c, a.element()));
            }
            result.add(List.copyOf(line));
        }
        return List.copyOf(result);
    }

    /** The subscript applied to dimension {@code dim} of a matrix (a vector's single subscript applies to its long side). */
    private static Subscript rowsOrCols(ArrayAlgebra a, List<Subscript> subscripts, int dim) {
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
            if (p.cuts().get(i - 1).constant() instanceof Constant.Int lo
                    && p.cuts().get(i).constant() instanceof Constant.Int hi) {
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

    private static List<Selection> selections(ArrayAlgebra a, List<Subscript> subscripts) {
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
                if (at.index().constant() instanceof Constant.Int lit
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
        for (ArrayAlgebra cut : p.cuts()) {
            requireCard(cut, "cut point");
            cuts.add(cut.constant() instanceof Constant.Int lit ? lit.value() : null);
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

    /**
     * Index position: a Card scalar, or an Int scalar converted to Card (type-system.md 1.1). A negative
     * Int becomes ⊥ (null); when the value is a known constant this is a compile error.
     */
    private static void requireCard(ArrayAlgebra t, String what) {
        if (!t.isScalar() || (t.element() != ScalarAlgebra.CARD && t.element() != ScalarAlgebra.INT)) {
            throw new VelTypeException(what + " must be a Card or Int scalar, not " + t);
        }
        if (t.constant() instanceof Constant.Int lit && lit.value().signum() < 0) {
            throw new VelTypeException(what + " " + lit.value() + " is ⊥ (null)");
        }
    }
}
