package com.doubledeltas.vel.core.type;

import static com.doubledeltas.vel.core.type.BinaryOperator.ADD;
import static com.doubledeltas.vel.core.type.BinaryOperator.DIV;
import static com.doubledeltas.vel.core.type.BinaryOperator.EW_MUL;
import static com.doubledeltas.vel.core.type.BinaryOperator.EW_POW;
import static com.doubledeltas.vel.core.type.BinaryOperator.FLOOR_DIV;
import static com.doubledeltas.vel.core.type.BinaryOperator.FLOOR_MOD;
import static com.doubledeltas.vel.core.type.BinaryOperator.LEFT_DIV;
import static com.doubledeltas.vel.core.type.BinaryOperator.MUL;
import static com.doubledeltas.vel.core.type.BinaryOperator.POW;
import static com.doubledeltas.vel.core.type.BinaryOperator.RIGHT_DIV;
import static com.doubledeltas.vel.core.type.BinaryOperator.SUB;
import static com.doubledeltas.vel.core.type.BinaryOperator.TRUNC_DIV;
import static com.doubledeltas.vel.core.type.BinaryOperator.TRUNC_MOD;
import static com.doubledeltas.vel.core.type.BinaryOperator.XOR;
import static com.doubledeltas.vel.core.type.Card.TOP;
import static com.doubledeltas.vel.core.type.ScalarAlgebra.BOOL;
import static com.doubledeltas.vel.core.type.ScalarAlgebra.COMPLEX;
import static com.doubledeltas.vel.core.type.ScalarAlgebra.INT;
import static com.doubledeltas.vel.core.type.ScalarAlgebra.REAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.Test;

class TypeRulesTest {

    private static Card n(long v) {
        return Card.of(v);
    }

    private static ArrayAlgebra mat(Card r, Card c) {
        return ArrayAlgebra.mat(r, c, REAL);
    }

    private static ArrayAlgebra scalar(ScalarAlgebra k) {
        return ArrayAlgebra.scalar(k);
    }

    private static ArrayAlgebra lit(long v) {
        return ArrayAlgebra.scalar(Constant.of(v));
    }

    private static ArrayAlgebra lit(String v) {
        return ArrayAlgebra.scalar(Constant.of(new BigDecimal(v)));
    }

    // ---- canonical shapes -----------------------------------------------------------------------

    @Test
    void definitionsHoldByEquality() {
        // Vec(n) = Mat(n, 1), scalar = Mat(1, 1), Array(n) = Vec(n), trailing 1s dropped
        assertEquals(ArrayAlgebra.vec(n(3), REAL), mat(n(3), n(1)));
        assertEquals(scalar(REAL), mat(n(1), n(1)));
        assertEquals(ArrayAlgebra.vec(n(3), REAL), ArrayAlgebra.of(REAL, n(3)));
        assertEquals(mat(n(3), n(4)), ArrayAlgebra.of(REAL, n(3), n(4), n(1), n(1)));
        assertEquals(3, ArrayAlgebra.of(REAL, n(2), n(3), n(4)).rank());
        assertTrue(scalar(REAL).isScalar());
        assertFalse(ArrayAlgebra.vec(TOP, REAL).isScalar()); // ⊤ is never assumed to be 1
    }

    @Test
    void cardinalArithmetic() {
        assertEquals(n(5), n(2).plus(n(3)));
        assertEquals(TOP, n(2).plus(TOP));
        assertEquals(TOP, n(2).max(TOP));
        assertEquals(n(3), n(2).max(n(3)));
    }

    @Test
    void onlyScalarsCarryConstants() {
        assertEquals(INT, lit(1).element());
        assertEquals(REAL, lit("0.5").element());
        assertThrows(IllegalArgumentException.class,
                () -> new ArrayAlgebra(List.of(n(2), n(1)), INT, Constant.of(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ArrayAlgebra(List.of(n(1), n(1)), REAL, Constant.of(1)));
    }

    // ---- 1.2 / 1.3 broadcasting -----------------------------------------------------------------

    @Test
    void broadcastingExamplesFromTheDocument() {
        // Vec(3) + Mat(1, 4) : Mat(3, 4)
        assertEquals(mat(n(3), n(4)), TypeRules.binary(ADD, ArrayAlgebra.vec(n(3), REAL), mat(n(1), n(4))));
        // Mat(3, 4) + [K] : Mat(3, 4)
        assertEquals(mat(n(3), n(4)), TypeRules.binary(ADD, mat(n(3), n(4)), scalar(REAL)));
    }

    @Test
    void topDimensions() {
        // ⊤ with n (or ⊤) gives ⊤; known mismatches are compile errors
        assertEquals(ArrayAlgebra.vec(TOP, REAL), TypeRules.binary(ADD, ArrayAlgebra.vec(TOP, REAL), ArrayAlgebra.vec(n(3), REAL)));
        assertEquals(ArrayAlgebra.vec(TOP, REAL), TypeRules.binary(ADD, ArrayAlgebra.vec(TOP, REAL), ArrayAlgebra.vec(TOP, REAL)));
        assertThrows(VelTypeException.class,
                () -> TypeRules.binary(ADD, ArrayAlgebra.vec(n(3), REAL), ArrayAlgebra.vec(n(4), REAL)));
        // a known 1 expands to ⊤, but ⊤ never expands
        assertEquals(mat(TOP, n(4)), TypeRules.binary(ADD, ArrayAlgebra.vec(TOP, REAL), mat(n(1), n(4))));
        assertEquals(mat(TOP, n(4)), TypeRules.binary(ADD, mat(TOP, n(1)), mat(TOP, n(4))));
    }

    @Test
    void elementWiseOperatorsBroadcastAndJoin() {
        // Vec(3, Int) .* Mat(1, 2, Real) : Mat(3, 2, Real)
        ArrayAlgebra r = TypeRules.binary(EW_MUL, ArrayAlgebra.vec(n(3), INT), mat(n(1), n(2)));
        assertEquals(mat(n(3), n(2)), r);
        // .^ with a non-negative integer literal exponent keeps the base algebra
        assertEquals(ArrayAlgebra.vec(n(3), INT), TypeRules.binary(EW_POW, ArrayAlgebra.vec(n(3), INT), lit(2)));
        assertEquals(ArrayAlgebra.vec(n(3), REAL), TypeRules.binary(EW_POW, ArrayAlgebra.vec(n(3), INT), lit(-1)));
    }

    // ---- 3.2 operators on algebras -----------------------------------------------------------------

    @Test
    void operatorAlgebras() {
        // Int / Int is true division: Real
        assertEquals(scalar(REAL), TypeRules.binary(DIV, scalar(INT), scalar(INT)));
        // quotients and remainders keep Int
        for (BinaryOperator op : List.of(FLOOR_DIV, TRUNC_DIV, FLOOR_MOD, TRUNC_MOD)) {
            assertEquals(scalar(INT), TypeRules.binary(op, scalar(INT), scalar(INT)), op::toString);
        }
        assertEquals(scalar(REAL), TypeRules.binary(FLOOR_DIV, scalar(INT), scalar(REAL)));
        // A ^ non-negative integer literal: A; any other exponent: A ∨ B ∨ Real
        assertEquals(scalar(INT), TypeRules.binary(POW, scalar(INT), lit(3)));
        assertEquals(scalar(REAL), TypeRules.binary(POW, scalar(INT), scalar(INT)));
        assertEquals(scalar(REAL), TypeRules.binary(POW, scalar(INT), lit(-1)));
        assertEquals(scalar(COMPLEX), TypeRules.binary(POW, scalar(COMPLEX), scalar(REAL)));
        // Int ∨ Real = Real, Real ∨ Complex = Complex
        assertEquals(scalar(REAL), TypeRules.binary(ADD, scalar(INT), scalar(REAL)));
        assertEquals(COMPLEX, TypeRules.binary(ADD, scalar(COMPLEX), lit(1)).element());
    }

    @Test
    void scalarAlgebraErrors() {
        ArrayAlgebra b = scalar(BOOL);
        ArrayAlgebra c = scalar(COMPLEX);
        assertThrows(VelTypeException.class, () -> TypeRules.binary(ADD, b, lit(1)));       // Bool vs Int
        assertThrows(VelTypeException.class, () -> TypeRules.binary(FLOOR_MOD, c, lit(2)));  // no %% on Complex
        assertThrows(VelTypeException.class, () -> TypeRules.binary(SUB, b, b));            // Bool: + * o+ only
        assertThrows(VelTypeException.class, () -> TypeRules.binary(POW, b, lit(2)));
        assertThrows(VelTypeException.class, () -> TypeRules.unary(b, true));
        // Bool: + is OR, * is AND
        assertEquals(b, TypeRules.binary(ADD, b, b));
        assertEquals(b, TypeRules.binary(MUL, b, b));
    }

    @Test
    void boolXorIsElementWise() {
        ArrayAlgebra bools = ArrayAlgebra.vec(n(3), BOOL);
        assertEquals(bools, TypeRules.binary(XOR, bools, scalar(BOOL)));
        assertThrows(VelTypeException.class, () -> TypeRules.binary(XOR, mat(n(2), n(2)), mat(n(2), n(2))));
        assertThrows(VelTypeException.class, () -> TypeRules.binary(XOR, lit(1), lit(0)));
    }

    // ---- 3.3 constants ------------------------------------------------------------------------------

    @Test
    void literalArithmeticIsFoldedExactly() {
        assertEquals(lit(3), TypeRules.binary(ADD, lit(1), lit(2)));
        assertEquals(lit("0.5"), TypeRules.binary(DIV, lit(1), lit(2)));       // true division
        assertEquals(lit("1.0"), TypeRules.binary(MUL, lit("0.5"), lit(2)));   // decimal stays decimal
        assertEquals(ArrayAlgebra.scalar(Constant.of(BigInteger.TWO.pow(100))), TypeRules.binary(POW, lit(2), lit(100)));
        assertEquals(lit("0.25"), TypeRules.binary(POW, lit(2), lit(-2)));
        // 1/3 is not a finite decimal: kept as an exact fraction; (1/3) * 3 is exactly 1
        ArrayAlgebra third = TypeRules.binary(DIV, lit(1), lit(3));
        assertEquals(new Constant.Rational(BigInteger.ONE, BigInteger.valueOf(3)), third.constant());
        assertEquals(lit("1"), TypeRules.binary(MUL, third, lit(3)));
        // quotient / remainder pairs on integer constants
        assertEquals(lit(-4), TypeRules.binary(FLOOR_DIV, lit(-7), lit(2)));
        assertEquals(lit(1), TypeRules.binary(FLOOR_MOD, lit(-7), lit(2)));
        assertEquals(lit(-3), TypeRules.binary(TRUNC_DIV, lit(-7), lit(2)));
        assertEquals(lit(-1), TypeRules.binary(TRUNC_MOD, lit(-7), lit(2)));
        assertEquals(lit(-1), TypeRules.binary(FLOOR_MOD, lit(7), lit(-2)));
        // literal operands of /<: 2 /< 6 = 6 / 2 = 3
        assertEquals(lit("3"), TypeRules.binary(LEFT_DIV, lit(2), lit(6)));
        // unary minus keeps a constant a constant
        assertEquals(lit(-1), TypeRules.unary(lit(1), true));
        // what cannot be folded exactly is not a constant
        assertNull(TypeRules.binary(DIV, lit(1), lit(0)).constant());
        assertNull(TypeRules.binary(ADD, lit(1), scalar(INT)).constant());
    }

    // ---- matrix operators -------------------------------------------------------------------------

    @Test
    void matrixProduct() {
        assertEquals(mat(n(2), n(4)), TypeRules.binary(MUL, mat(n(2), n(3)), mat(n(3), n(4))));
        assertEquals(mat(n(2), n(4)), TypeRules.binary(MUL, mat(n(2), TOP), mat(n(3), n(4))));
        assertThrows(VelTypeException.class, () -> TypeRules.binary(MUL, mat(n(2), n(3)), mat(n(2), n(3))));
        // row · column = 1x1
        assertTrue(TypeRules.binary(MUL, mat(n(1), n(3)), ArrayAlgebra.vec(n(3), REAL)).isScalar());
        // scalar * matrix is scaling, not a product
        assertEquals(mat(n(2), n(3)), TypeRules.binary(MUL, lit(2), mat(n(2), n(3))));
    }

    @Test
    void matrixDivisionAndPower() {
        ArrayAlgebra intMat = ArrayAlgebra.mat(n(2), n(2), INT);
        // A / B = A · B⁻¹: Int becomes Real
        assertEquals(mat(n(3), n(2)), TypeRules.binary(DIV, ArrayAlgebra.mat(n(3), n(2), INT), intMat));
        assertThrows(VelTypeException.class, () -> TypeRules.binary(DIV, mat(n(3), n(2)), mat(n(2), n(3))));
        // matrix / scalar is element-wise; Int / Int is true division
        assertEquals(mat(n(2), n(2)), TypeRules.binary(DIV, intMat, lit(2)));
        // A ^ k: square only; a non-negative integer literal exponent keeps the algebra
        assertEquals(intMat, TypeRules.binary(POW, intMat, lit(3)));
        assertEquals(mat(n(2), n(2)), TypeRules.binary(POW, intMat, lit(-1)));
        assertThrows(VelTypeException.class, () -> TypeRules.binary(POW, mat(n(2), n(3)), lit(2)));
        assertThrows(VelTypeException.class, () -> TypeRules.binary(POW, intMat, intMat));
    }

    @Test
    void transpose() {
        assertEquals(mat(n(1), n(3)), TypeRules.transpose(ArrayAlgebra.vec(n(3), REAL)));
        assertEquals(mat(TOP, n(2)), TypeRules.transpose(mat(n(2), TOP)));
        // rank 3 or more: the order of the indices is reversed
        assertEquals(ArrayAlgebra.of(REAL, n(4), n(3), n(2)), TypeRules.transpose(ArrayAlgebra.of(REAL, n(2), n(3), n(4))));
    }

    @Test
    void leftAndRightDivision() {
        ArrayAlgebra a = mat(n(3), n(3));
        ArrayAlgebra b = mat(n(3), n(2));
        // A /< B = A⁻¹ B : Mat(3, 2)
        assertEquals(mat(n(3), n(2)), TypeRules.binary(LEFT_DIV, a, b));
        assertThrows(VelTypeException.class, () -> TypeRules.binary(LEFT_DIV, b, a));          // A not square
        assertThrows(VelTypeException.class, () -> TypeRules.binary(LEFT_DIV, a, mat(n(2), n(2)))); // rows differ
        // B' /> A = B' A⁻¹, same as /
        ArrayAlgebra bt = TypeRules.transpose(b);
        assertEquals(mat(n(2), n(3)), TypeRules.binary(RIGHT_DIV, bt, a));
        assertEquals(TypeRules.binary(DIV, bt, a), TypeRules.binary(RIGHT_DIV, bt, a));
        // scalar /< B divides every element of B
        assertEquals(b, TypeRules.binary(LEFT_DIV, lit(2), b));
    }

    // ---- 1.4 concatenation, tuples, ranges --------------------------------------------------------

    @Test
    void blockMatrix() {
        // A: a×c, B: a×d, C: b×c, D: b×d -> (a+b)×(c+d)
        ArrayAlgebra a = mat(n(2), n(3));
        ArrayAlgebra b = mat(n(2), n(1));
        ArrayAlgebra c = mat(n(4), n(3));
        ArrayAlgebra d = mat(n(4), n(1));
        assertEquals(mat(n(6), n(4)), TypeRules.matrix(List.of(List.of(a, b), List.of(c, d))));
        assertThrows(VelTypeException.class, () -> TypeRules.matrix(List.of(List.of(a, c)))); // rows differ
        // ⊤ makes the sum ⊤
        assertEquals(mat(n(2), TOP), TypeRules.matrix(List.of(List.of(a, mat(n(2), TOP)))));
    }

    @Test
    void scalarLiteralsFormMatricesAndTuplesFormColumns() {
        // [1, 2; 3, 4] : Mat(2, 2, Int)
        assertEquals(ArrayAlgebra.mat(n(2), n(2), INT),
                TypeRules.matrix(List.of(List.of(lit(1), lit(2)), List.of(lit(3), lit(4)))));
        // [1, x] with x: Real -> Real
        assertEquals(ArrayAlgebra.mat(n(1), n(2), REAL), TypeRules.matrix(List.of(List.of(lit(1), scalar(REAL)))));
        // (x, y, z) = [x; y; z] : Vec(3)
        ArrayAlgebra x = scalar(REAL);
        assertEquals(ArrayAlgebra.vec(n(3), REAL), TypeRules.tuple(List.of(x, x, x)));
        // a tuple of matrices of different shapes cannot be stacked (it is not a concatenation)
        assertThrows(VelTypeException.class, () -> TypeRules.tuple(List.of(mat(n(2), n(3)), mat(n(3), n(3)))));
    }

    @Test
    void rangesHaveCardinalBounds() {
        // [0, 4): end-exclusive
        assertEquals(ArrayAlgebra.mat(n(1), n(4), INT), TypeRules.range(lit(0), lit(4)));
        assertEquals(ArrayAlgebra.mat(n(1), n(0), INT), TypeRules.range(lit(3), lit(1)));      // empty
        // a non-constant bound (a variable, or end) gives length ⊤
        assertEquals(ArrayAlgebra.mat(n(1), TOP, INT), TypeRules.range(lit(0), scalar(INT)));
        // bounds must be non-negative Int scalars
        assertThrows(VelTypeException.class, () -> TypeRules.range(lit(0), lit("0.1")));
        assertThrows(VelTypeException.class, () -> TypeRules.range(lit(-1), lit(3)));
        assertThrows(VelTypeException.class, () -> TypeRules.range(lit(0), scalar(REAL)));
        assertThrows(VelTypeException.class, () -> TypeRules.range(lit(0), ArrayAlgebra.vec(n(2), INT)));
    }

    // ---- indexing ---------------------------------------------------------------------------------

    @Test
    void indexing() {
        ArrayAlgebra m = mat(n(3), n(4));
        Subscript row1 = Subscript.at(lit(1));
        Subscript cols = Subscript.span(TypeRules.range(lit(0), lit(2)));   // [0, 2)
        // m[i, j] is a scalar, m[1, :] a row, m[:, 0:1] a 3x2 block
        assertTrue(TypeRules.index(m, List.of(row1, Subscript.at(lit(2)))).isScalar());
        assertEquals(mat(n(1), n(4)), TypeRules.index(m, List.of(row1, Subscript.all())));
        assertEquals(mat(n(3), n(2)), TypeRules.index(m, List.of(Subscript.all(), cols)));
        // a vector takes one subscript and keeps its orientation
        ArrayAlgebra v = ArrayAlgebra.vec(n(5), REAL);
        assertTrue(TypeRules.index(v, List.of(Subscript.at(lit(4)))).isScalar());
        assertEquals(ArrayAlgebra.vec(n(2), REAL), TypeRules.index(v, List.of(cols)));
        assertEquals(mat(n(1), n(2)), TypeRules.index(TypeRules.transpose(v), List.of(cols)));
        // a range up to end has length ⊤
        Subscript toEnd = Subscript.span(TypeRules.range(lit(1), scalar(INT)));
        assertEquals(ArrayAlgebra.vec(TOP, REAL), TypeRules.index(v, List.of(toEnd)));
        // an Int element of another array is a valid index: a[b[i]]
        ArrayAlgebra bi = TypeRules.index(ArrayAlgebra.vec(TOP, INT), List.of(Subscript.at(scalar(INT))));
        assertTrue(TypeRules.index(v, List.of(Subscript.at(bi))).isScalar());
        // errors: subscript count, index algebra, known out of bounds
        assertThrows(VelTypeException.class, () -> TypeRules.index(m, List.of(row1)));
        assertThrows(VelTypeException.class, () -> TypeRules.index(v, List.of(Subscript.at(scalar(REAL)))));
        assertThrows(VelTypeException.class, () -> TypeRules.index(v, List.of(Subscript.at(lit(5)))));
        assertThrows(VelTypeException.class, () -> TypeRules.index(v, List.of(Subscript.at(lit(-1)))));
    }

    @Test
    void indexAssignmentWidensTheVariable() {
        ArrayAlgebra ints = ArrayAlgebra.mat(n(2), n(3), INT);
        Subscript first = Subscript.at(lit(0));
        // m[0, 0] = 5 keeps Int; m[0, 0] = 0.5 makes the whole array Real
        assertEquals(ints, TypeRules.indexAssign(ints, List.of(first, first), lit(5)));
        assertEquals(ArrayAlgebra.mat(n(2), n(3), REAL), TypeRules.indexAssign(ints, List.of(first, first), lit("0.5")));
        // a row region takes a matching row or a broadcast scalar
        assertEquals(ints, TypeRules.indexAssign(ints, List.of(first, Subscript.all()), ArrayAlgebra.mat(n(1), n(3), INT)));
        assertEquals(ints, TypeRules.indexAssign(ints, List.of(first, Subscript.all()), lit(0)));
        assertThrows(VelTypeException.class,
                () -> TypeRules.indexAssign(ints, List.of(first, Subscript.all()), ArrayAlgebra.mat(n(1), n(2), INT)));
        // the value must have a common algebra with the elements
        assertThrows(VelTypeException.class, () -> TypeRules.indexAssign(ints, List.of(first, first), scalar(BOOL)));
    }

    @Test
    void destructuring() {
        // (x, y, z) = (1, 2, 3): each target is an Int scalar
        ArrayAlgebra value = TypeRules.tuple(List.of(lit(1), lit(2), lit(3)));
        assertEquals(scalar(INT), TypeRules.destructureTuple(value, 3));
        // [a, b; c, d] = M with M: Mat(2, 2, Real): elements
        assertEquals(scalar(REAL), TypeRules.destructureMatrix(mat(n(2), n(2)), 2, 2));
        // (u, v) = M with M: Mat(2, 3): the rows, as Vec(3) (undoes the stacking of a tuple)
        assertEquals(ArrayAlgebra.vec(n(3), REAL), TypeRules.destructureTuple(mat(n(2), n(3)), 2));
        // ⊤ sizes are accepted (checked at runtime as usual), known mismatches are errors
        assertEquals(scalar(REAL), TypeRules.destructureTuple(ArrayAlgebra.vec(TOP, REAL), 3));
        assertThrows(VelTypeException.class, () -> TypeRules.destructureTuple(ArrayAlgebra.vec(n(2), REAL), 3));
        assertThrows(VelTypeException.class, () -> TypeRules.destructureMatrix(mat(n(2), n(2)), 4, 1));
    }

    @Test
    void tuplesStackTheirItems() {
        ArrayAlgebra u = ArrayAlgebra.vec(n(3), REAL);
        // (u, v) of Vec(3): Mat(2, 3), item i is row i
        assertEquals(mat(n(2), n(3)), TypeRules.tuple(List.of(u, u)));
        // (A, B) of Mat(2, 3): Array(2, 2, 3)
        assertEquals(ArrayAlgebra.of(REAL, n(2), n(2), n(3)), TypeRules.tuple(List.of(mat(n(2), n(3)), mat(n(2), n(3)))));
        // items must have the same shape; ⊤ follows the usual rule; algebras join
        assertThrows(VelTypeException.class, () -> TypeRules.tuple(List.of(u, ArrayAlgebra.vec(n(4), REAL))));
        assertThrows(VelTypeException.class, () -> TypeRules.tuple(List.of(u, lit(1))));
        assertEquals(mat(n(2), TOP), TypeRules.tuple(List.of(u, ArrayAlgebra.vec(TOP, REAL))));
        assertEquals(ArrayAlgebra.vec(n(2), REAL), TypeRules.tuple(List.of(lit(1), scalar(REAL))));
        // stacking then destructuring gives the items back
        assertEquals(u, TypeRules.destructureTuple(TypeRules.tuple(List.of(u, u)), 2));
    }

    @Test
    void partitionedIndexingGivesABlockTensor() {
        ArrayAlgebra m = ArrayAlgebra.mat(n(3), n(3), INT);
        Subscript rows023 = Subscript.partition(List.of(lit(0), lit(2), lit(3)));   // [0,2), [2,3)
        Subscript cols013 = Subscript.partition(List.of(lit(0), lit(1), lit(3)));   // [0,1), [1,3)
        // 2x2 blocks of different sizes: block indices first, block sizes ⊤
        ArrayAlgebra t = TypeRules.index(m, List.of(rows023, cols013));
        assertEquals(ArrayAlgebra.of(INT, n(2), n(2), TOP, TOP), t);
        // equal blocks keep their size: M6x6[0:2:4:6, 0:3:6] -> Array(3, 2, 2, 3)
        ArrayAlgebra m6 = ArrayAlgebra.mat(n(6), n(6), INT);
        ArrayAlgebra even = TypeRules.index(m6, List.of(
                Subscript.partition(List.of(lit(0), lit(2), lit(4), lit(6))),
                Subscript.partition(List.of(lit(0), lit(3), lit(6)))));
        assertEquals(ArrayAlgebra.of(INT, n(3), n(2), n(2), n(3)), even);
        // only the rows partitioned: Array(k, h, w), a stack of row blocks
        ArrayAlgebra rowsOnly = TypeRules.index(m6, List.of(
                Subscript.partition(List.of(lit(0), lit(2), lit(4), lit(6))), Subscript.all()));
        assertEquals(ArrayAlgebra.of(INT, n(3), n(2), n(6)), rowsOnly);
        // a vector partition is a stack of column vectors: Mat(k, h)
        ArrayAlgebra v = ArrayAlgebra.vec(n(6), INT);
        assertEquals(ArrayAlgebra.mat(n(3), n(2), INT), TypeRules.index(v, List.of(
                Subscript.partition(List.of(lit(0), lit(2), lit(4), lit(6))))));
        // cut points: strictly increasing, within the size, non-negative integers
        assertThrows(VelTypeException.class, () -> TypeRules.index(m, List.of(
                Subscript.partition(List.of(lit(0), lit(2), lit(2))), Subscript.all())));
        assertThrows(VelTypeException.class, () -> TypeRules.index(m, List.of(
                Subscript.partition(List.of(lit(0), lit(2), lit(4))), Subscript.all())));
        // assignment through a partition is not allowed
        assertThrows(VelTypeException.class, () -> TypeRules.indexAssign(m, List.of(rows023, cols013), lit(0)));
    }

    @Test
    void blockDestructuring() {
        // [A, B; C, D] = M[0:2:3, 0:1:3]: 2x2 pattern over the block dimensions, each target a block
        ArrayAlgebra m = ArrayAlgebra.mat(n(3), n(3), INT);
        ArrayAlgebra t = TypeRules.index(m, List.of(
                Subscript.partition(List.of(lit(0), lit(2), lit(3))),
                Subscript.partition(List.of(lit(0), lit(1), lit(3)))));
        assertEquals(ArrayAlgebra.mat(TOP, TOP, INT), TypeRules.destructureMatrix(t, 2, 2));
        assertThrows(VelTypeException.class, () -> TypeRules.destructureMatrix(t, 3, 1));
        // with the partition at hand each block gets its exact algebra:
        // A = [1; 4] (2x1), B = [2, 3; 5, 6] (2x2), C = 7 (scalar), D = [8, 9] (1x2)
        List<List<ArrayAlgebra>> exact = TypeRules.blocks(m, List.of(
                Subscript.partition(List.of(lit(0), lit(2), lit(3))),
                Subscript.partition(List.of(lit(0), lit(1), lit(3)))));
        assertEquals(ArrayAlgebra.vec(n(2), INT), exact.get(0).get(0));
        assertEquals(ArrayAlgebra.mat(n(2), n(2), INT), exact.get(0).get(1));
        assertTrue(exact.get(1).get(0).isScalar());
        assertEquals(ArrayAlgebra.mat(n(1), n(2), INT), exact.get(1).get(1));
        // (Top, Bottom) = M6[0:3:6, :]: tuple pattern over the stack of row blocks
        ArrayAlgebra m6 = ArrayAlgebra.mat(n(6), n(4), REAL);
        ArrayAlgebra halves = TypeRules.index(m6, List.of(Subscript.partition(List.of(lit(0), lit(3), lit(6))), Subscript.all()));
        assertEquals(ArrayAlgebra.mat(n(3), n(4), REAL), TypeRules.destructureTuple(halves, 2));
    }

    @Test
    void conditional() {
        ArrayAlgebra cond = scalar(BOOL);
        // branches join: Int and Real -> Real
        assertEquals(scalar(REAL), TypeRules.conditional(cond, scalar(INT), scalar(REAL)));
        assertEquals(scalar(INT), TypeRules.conditional(cond, lit(1), lit(2)));
        // shapes must agree; ⊤ follows the usual rule
        assertEquals(ArrayAlgebra.vec(n(3), REAL), TypeRules.conditional(cond, ArrayAlgebra.vec(n(3), REAL), ArrayAlgebra.vec(n(3), REAL)));
        assertEquals(ArrayAlgebra.vec(TOP, REAL), TypeRules.conditional(cond, ArrayAlgebra.vec(TOP, REAL), ArrayAlgebra.vec(n(3), REAL)));
        assertThrows(VelTypeException.class,
                () -> TypeRules.conditional(cond, ArrayAlgebra.vec(n(3), REAL), ArrayAlgebra.vec(n(4), REAL)));
        // the condition is a Bool scalar; Bool and numeric branches don't mix
        assertThrows(VelTypeException.class, () -> TypeRules.conditional(lit(1), lit(1), lit(2)));
        assertThrows(VelTypeException.class,
                () -> TypeRules.conditional(ArrayAlgebra.vec(n(2), BOOL), lit(1), lit(2)));
        assertThrows(VelTypeException.class, () -> TypeRules.conditional(cond, cond, lit(2)));
    }

    // ---- scalar algebras ----------------------------------------------------------------------------

    @Test
    void intRealComplexHierarchy() {
        assertTrue(INT.isSubtypeOf(REAL));
        assertTrue(INT.isSubtypeOf(COMPLEX));
        assertTrue(REAL.isSubtypeOf(COMPLEX));
        assertFalse(REAL.isSubtypeOf(INT));
        // Bool is unrelated to the numeric algebras
        assertFalse(BOOL.isSubtypeOf(INT));
        assertFalse(INT.isSubtypeOf(BOOL));
        assertThrows(VelTypeException.class, () -> ScalarAlgebra.join(BOOL, INT));
        assertEquals(REAL, ScalarAlgebra.join(INT, REAL));
    }

    // ---- @Dim -------------------------------------------------------------------------------------

    @Test
    void dimAnnotation() {
        int[] none = {};
        assertEquals(List.of(n(3), n(4)), DimSpec.fromAnnotation(new int[] {3, 4}, none, none));   // value = vel
        assertEquals(List.of(n(3), n(4)), DimSpec.fromAnnotation(none, none, new int[] {3, 4}));
        assertEquals(List.of(n(3), n(4)), DimSpec.fromAnnotation(none, new int[] {4, 3}, none));   // java reversed
        assertEquals(List.of(n(3), TOP), DimSpec.fromAnnotation(none, new int[] {-1, 3}, none));
        assertThrows(VelTypeException.class, () -> DimSpec.fromAnnotation(none, new int[] {4, 3}, new int[] {3, 4}));
        assertThrows(VelTypeException.class, () -> DimSpec.fromAnnotation(new int[] {3}, none, new int[] {3}));
        assertThrows(VelTypeException.class, () -> DimSpec.fromAnnotation(none, none, none));
        assertThrows(VelTypeException.class, () -> DimSpec.fromAnnotation(new int[] {-2}, none, none));
    }

    // ---- assignability ----------------------------------------------------------------------------

    @Test
    void assignability() {
        assertTrue(ArrayAlgebra.vec(n(3), INT).isAssignableTo(ArrayAlgebra.vec(n(3), REAL)));
        assertFalse(ArrayAlgebra.vec(n(3), REAL).isAssignableTo(ArrayAlgebra.vec(n(3), INT)));
        assertTrue(ArrayAlgebra.vec(n(3), REAL).isAssignableTo(ArrayAlgebra.vec(TOP, REAL)));
        assertFalse(ArrayAlgebra.vec(n(3), REAL).isAssignableTo(ArrayAlgebra.vec(n(4), REAL)));
        assertTrue(scalar(REAL).isAssignableTo(scalar(COMPLEX)));
        assertFalse(scalar(COMPLEX).isAssignableTo(scalar(REAL)));
    }
}
