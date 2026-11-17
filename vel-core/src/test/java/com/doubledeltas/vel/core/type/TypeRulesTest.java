package com.doubledeltas.vel.core.type;

import static com.doubledeltas.vel.core.type.BinaryOperator.ADD;
import static com.doubledeltas.vel.core.type.BinaryOperator.DIV;
import static com.doubledeltas.vel.core.type.BinaryOperator.EW_MUL;
import static com.doubledeltas.vel.core.type.BinaryOperator.EW_POW;
import static com.doubledeltas.vel.core.type.BinaryOperator.FLOOR_MOD;
import static com.doubledeltas.vel.core.type.BinaryOperator.LEFT_DIV;
import static com.doubledeltas.vel.core.type.BinaryOperator.MUL;
import static com.doubledeltas.vel.core.type.BinaryOperator.POW;
import static com.doubledeltas.vel.core.type.BinaryOperator.RIGHT_DIV;
import static com.doubledeltas.vel.core.type.BinaryOperator.XOR;
import static com.doubledeltas.vel.core.type.Card.TOP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.Test;

class TypeRulesTest {

    private static final ScalarType F64 = ScalarType.real(Resolution.FLOAT64);
    private static final ScalarType F32 = ScalarType.real(Resolution.FLOAT32);
    private static final ScalarType I32 = ScalarType.real(Resolution.INT32);

    private static Card n(long v) {
        return Card.of(v);
    }

    private static ArrayType mat(Card r, Card c) {
        return ArrayType.mat(r, c, F64);
    }

    private static ArrayType lit(long v) {
        return ArrayType.scalar(ScalarType.literal(BigInteger.valueOf(v)));
    }

    // ---- canonical shapes -----------------------------------------------------------------------

    @Test
    void definitionsHoldByEquality() {
        // Vec(n) = Mat(n, 1), scalar = Mat(1, 1), Array(n) = Vec(n), trailing 1s dropped
        assertEquals(ArrayType.vec(n(3), F64), mat(n(3), n(1)));
        assertEquals(ArrayType.scalar(F64), mat(n(1), n(1)));
        assertEquals(ArrayType.vec(n(3), F64), ArrayType.of(F64, n(3)));
        assertEquals(mat(n(3), n(4)), ArrayType.of(F64, n(3), n(4), n(1), n(1)));
        assertEquals(3, ArrayType.of(F64, n(2), n(3), n(4)).rank());
        assertTrue(ArrayType.scalar(F64).isScalar());
        assertFalse(ArrayType.vec(TOP, F64).isScalar()); // ⊤ is never assumed to be 1
    }

    @Test
    void cardinalArithmetic() {
        assertEquals(n(5), n(2).plus(n(3)));
        assertEquals(TOP, n(2).plus(TOP));
        assertEquals(TOP, n(2).max(TOP));
        assertEquals(n(3), n(2).max(n(3)));
    }

    // ---- 1.2 / 1.3 broadcasting -----------------------------------------------------------------

    @Test
    void broadcastingExamplesFromTheDocument() {
        // Vec(3) + Mat(1, 4) : Mat(3, 4)
        assertEquals(mat(n(3), n(4)), TypeRules.binary(ADD, ArrayType.vec(n(3), F64), mat(n(1), n(4))));
        // Mat(3, 4) + [F] : Mat(3, 4)
        assertEquals(mat(n(3), n(4)), TypeRules.binary(ADD, mat(n(3), n(4)), ArrayType.scalar(F64)));
    }

    @Test
    void topDimensions() {
        // ⊤ with n (or ⊤) gives ⊤; known mismatches are compile errors
        assertEquals(ArrayType.vec(TOP, F64), TypeRules.binary(ADD, ArrayType.vec(TOP, F64), ArrayType.vec(n(3), F64)));
        assertEquals(ArrayType.vec(TOP, F64), TypeRules.binary(ADD, ArrayType.vec(TOP, F64), ArrayType.vec(TOP, F64)));
        assertThrows(VelTypeException.class,
                () -> TypeRules.binary(ADD, ArrayType.vec(n(3), F64), ArrayType.vec(n(4), F64)));
        // a known 1 expands to ⊤, but ⊤ never expands
        assertEquals(mat(TOP, n(4)), TypeRules.binary(ADD, ArrayType.vec(TOP, F64), mat(n(1), n(4))));
        assertEquals(mat(TOP, n(4)), TypeRules.binary(ADD, mat(TOP, n(1)), mat(TOP, n(4))));
    }

    @Test
    void elementWiseOperatorsBroadcastAndJoin() {
        // Vec(3, F(32)) .* Mat(1, 2, F(64)) : Mat(3, 2, F(64))
        ArrayType r = TypeRules.binary(EW_MUL, ArrayType.vec(n(3), F32), mat(n(1), n(2)));
        assertEquals(ArrayType.mat(n(3), n(2), F64), r);
        // .^ with an integer literal exponent keeps the base resolution
        assertEquals(ArrayType.vec(n(3), I32), TypeRules.binary(EW_POW, ArrayType.vec(n(3), I32), lit(2)));
    }

    // ---- matrix operators -------------------------------------------------------------------------

    @Test
    void matrixProduct() {
        assertEquals(mat(n(2), n(4)), TypeRules.binary(MUL, mat(n(2), n(3)), mat(n(3), n(4))));
        assertEquals(mat(n(2), n(4)), TypeRules.binary(MUL, mat(n(2), TOP), mat(n(3), n(4))));
        assertThrows(VelTypeException.class, () -> TypeRules.binary(MUL, mat(n(2), n(3)), mat(n(2), n(3))));
        // row · column = 1x1
        assertTrue(TypeRules.binary(MUL, mat(n(1), n(3)), ArrayType.vec(n(3), F64)).isScalar());
        // scalar * matrix is scaling, not a product
        assertEquals(mat(n(2), n(3)), TypeRules.binary(MUL, lit(2), mat(n(2), n(3))));
    }

    @Test
    void matrixDivisionAndPower() {
        ArrayType intMat = ArrayType.mat(n(2), n(2), I32);
        // A / B = A · B⁻¹: integers become F(64)
        assertEquals(ArrayType.mat(n(3), n(2), F64), TypeRules.binary(DIV, ArrayType.mat(n(3), n(2), I32), intMat));
        assertThrows(VelTypeException.class, () -> TypeRules.binary(DIV, mat(n(3), n(2)), mat(n(2), n(3))));
        // matrix / scalar is element-wise; integer / integer is true division
        assertEquals(ArrayType.mat(n(2), n(2), F64), TypeRules.binary(DIV, intMat, lit(2)));
        // A ^ k: square only; integer literal exponent keeps the resolution
        assertEquals(intMat, TypeRules.binary(POW, intMat, lit(3)));
        assertEquals(ArrayType.mat(n(2), n(2), F64), TypeRules.binary(POW, intMat, lit(-1)));
        assertThrows(VelTypeException.class, () -> TypeRules.binary(POW, mat(n(2), n(3)), lit(2)));
        assertThrows(VelTypeException.class, () -> TypeRules.binary(POW, intMat, intMat));
    }

    @Test
    void transpose() {
        assertEquals(mat(n(1), n(3)), TypeRules.transpose(ArrayType.vec(n(3), F64)));
        assertEquals(mat(TOP, n(2)), TypeRules.transpose(mat(n(2), TOP)));
        // rank 3 or more: the order of the indices is reversed
        assertEquals(ArrayType.of(F64, n(4), n(3), n(2)), TypeRules.transpose(ArrayType.of(F64, n(2), n(3), n(4))));
    }

    @Test
    void leftAndRightDivision() {
        ArrayType a = mat(n(3), n(3));
        ArrayType b = mat(n(3), n(2));
        // A /< B = A⁻¹ B : Mat(3, 2)
        assertEquals(mat(n(3), n(2)), TypeRules.binary(LEFT_DIV, a, b));
        assertThrows(VelTypeException.class, () -> TypeRules.binary(LEFT_DIV, b, a));          // A not square
        assertThrows(VelTypeException.class, () -> TypeRules.binary(LEFT_DIV, a, mat(n(2), n(2)))); // rows differ
        // B' /> A = B' A⁻¹, same as /
        ArrayType bt = TypeRules.transpose(b);
        assertEquals(mat(n(2), n(3)), TypeRules.binary(RIGHT_DIV, bt, a));
        assertEquals(TypeRules.binary(DIV, bt, a), TypeRules.binary(RIGHT_DIV, bt, a));
        // scalar /< B divides every element of B
        assertEquals(b, TypeRules.binary(LEFT_DIV, lit(2), b));
        // literal operands: 2 /< 6 = 6 / 2 = 3
        assertEquals(ScalarType.real(Resolution.literal(new BigDecimal("3"))),
                TypeRules.binary(LEFT_DIV, lit(2), lit(6)).element());
    }

    @Test
    void boolXorIsElementWise() {
        ArrayType bools = ArrayType.vec(n(3), ScalarType.BOOL);
        assertEquals(bools, TypeRules.binary(XOR, bools, ArrayType.scalar(ScalarType.BOOL)));
        assertThrows(VelTypeException.class, () -> TypeRules.binary(XOR, mat(n(2), n(2)), mat(n(2), n(2))));
    }

    @Test
    void scalarTypeErrors() {
        ArrayType b = ArrayType.scalar(ScalarType.BOOL);
        ArrayType c = ArrayType.scalar(ScalarType.complex(Resolution.FLOAT64));
        assertThrows(VelTypeException.class, () -> TypeRules.binary(ADD, b, lit(1)));      // Bool vs Real
        assertThrows(VelTypeException.class, () -> TypeRules.binary(FLOOR_MOD, c, lit(2))); // no %% on Complex
        // Real ∨ Complex = Complex
        assertEquals(Field.COMPLEX, TypeRules.binary(ADD, c, lit(1)).element().field());
        // 2 * j follows a partner: j's parts are integer literals
        assertEquals(ScalarType.complex(Resolution.FLOAT32),
                TypeRules.binary(MUL, ArrayType.scalar(F32), ArrayType.scalar(ScalarType.IMAGINARY_UNIT)).element());
    }

    // ---- 1.4 concatenation, tuples, ranges --------------------------------------------------------

    @Test
    void blockMatrix() {
        // A: a×c, B: a×d, C: b×c, D: b×d -> (a+b)×(c+d)
        ArrayType a = mat(n(2), n(3));
        ArrayType b = mat(n(2), n(1));
        ArrayType c = mat(n(4), n(3));
        ArrayType d = mat(n(4), n(1));
        assertEquals(mat(n(6), n(4)), TypeRules.matrix(List.of(List.of(a, b), List.of(c, d))));
        assertThrows(VelTypeException.class, () -> TypeRules.matrix(List.of(List.of(a, c)))); // rows differ
        // ⊤ makes the sum ⊤
        assertEquals(mat(n(2), TOP), TypeRules.matrix(List.of(List.of(a, mat(n(2), TOP)))));
    }

    @Test
    void scalarLiteralsFormMatricesAndTuplesFormColumns() {
        // [1, 2; 3, 4] : Mat(2, 2, Z(32))
        assertEquals(ArrayType.mat(n(2), n(2), I32),
                TypeRules.matrix(List.of(List.of(lit(1), lit(2)), List.of(lit(3), lit(4)))));
        // [1, x] with x: F(32) -> F(32)
        assertEquals(ArrayType.mat(n(1), n(2), F32),
                TypeRules.matrix(List.of(List.of(lit(1), ArrayType.scalar(F32)))));
        // (x, y, z) = [x; y; z] : Vec(3)
        ArrayType x = ArrayType.scalar(F64);
        assertEquals(ArrayType.vec(n(3), F64), TypeRules.tuple(List.of(x, x, x)));
        // a tuple of matrices of different shapes cannot be stacked (it is not a concatenation)
        assertThrows(VelTypeException.class, () -> TypeRules.tuple(List.of(mat(n(2), n(3)), mat(n(3), n(3)))));
    }

    @Test
    void rangesHaveCardinalBounds() {
        // [0, 4): end-exclusive
        assertEquals(ArrayType.mat(n(1), n(4), I32), TypeRules.range(lit(0), lit(4)));
        assertEquals(ArrayType.mat(n(1), n(0), I32), TypeRules.range(lit(3), lit(1)));      // empty
        // a non-literal bound (a variable, or end) gives length ⊤
        assertEquals(ArrayType.mat(n(1), TOP, I32), TypeRules.range(lit(0), ArrayType.scalar(I32)));
        // bounds must be non-negative integer scalars
        ArrayType tenth = ArrayType.scalar(ScalarType.literal(new BigDecimal("0.1")));
        assertThrows(VelTypeException.class, () -> TypeRules.range(lit(0), tenth));
        assertThrows(VelTypeException.class, () -> TypeRules.range(lit(-1), lit(3)));
        assertThrows(VelTypeException.class, () -> TypeRules.range(lit(0), ArrayType.scalar(F64)));
        assertThrows(VelTypeException.class, () -> TypeRules.range(lit(0), ArrayType.vec(n(2), I32)));
    }

    // ---- indexing ---------------------------------------------------------------------------------

    @Test
    void indexing() {
        ArrayType m = mat(n(3), n(4));
        Subscript row1 = Subscript.at(lit(1));
        Subscript cols = Subscript.span(TypeRules.range(lit(0), lit(2)));   // [0, 2)
        // m[i, j] is a scalar, m[1, :] a row, m[:, 0:1] a 3x2 block
        assertTrue(TypeRules.index(m, List.of(row1, Subscript.at(lit(2)))).isScalar());
        assertEquals(mat(n(1), n(4)), TypeRules.index(m, List.of(row1, Subscript.all())));
        assertEquals(mat(n(3), n(2)), TypeRules.index(m, List.of(Subscript.all(), cols)));
        // a vector takes one subscript and keeps its orientation
        ArrayType v = ArrayType.vec(n(5), F64);
        assertTrue(TypeRules.index(v, List.of(Subscript.at(lit(4)))).isScalar());
        assertEquals(ArrayType.vec(n(2), F64), TypeRules.index(v, List.of(cols)));
        assertEquals(mat(n(1), n(2)), TypeRules.index(TypeRules.transpose(v), List.of(cols)));
        // a range up to end has length ⊤
        Subscript toEnd = Subscript.span(TypeRules.range(lit(1), ArrayType.scalar(I32)));
        assertEquals(ArrayType.vec(TOP, F64), TypeRules.index(v, List.of(toEnd)));
        // errors: subscript count, index type, known out of bounds
        assertThrows(VelTypeException.class, () -> TypeRules.index(m, List.of(row1)));
        assertThrows(VelTypeException.class, () -> TypeRules.index(v, List.of(Subscript.at(ArrayType.scalar(F64)))));
        assertThrows(VelTypeException.class, () -> TypeRules.index(v, List.of(Subscript.at(lit(5)))));
        assertThrows(VelTypeException.class, () -> TypeRules.index(v, List.of(Subscript.at(lit(-1)))));
    }

    @Test
    void indexAssignmentWidensTheVariable() {
        ArrayType ints = ArrayType.mat(n(2), n(3), I32);
        Subscript first = Subscript.at(lit(0));
        // m[0, 0] = 5 keeps Z(32); m[0, 0] = 0.5 widens the whole array to F(64)
        assertEquals(ints, TypeRules.indexAssign(ints, List.of(first, first), lit(5)));
        ArrayType half = ArrayType.scalar(ScalarType.literal(new BigDecimal("0.5")));
        assertEquals(ArrayType.mat(n(2), n(3), F64), TypeRules.indexAssign(ints, List.of(first, first), half));
        // a row region takes a matching row or a broadcast scalar
        assertEquals(ints, TypeRules.indexAssign(ints, List.of(first, Subscript.all()), ArrayType.mat(n(1), n(3), I32)));
        assertEquals(ints, TypeRules.indexAssign(ints, List.of(first, Subscript.all()), lit(0)));
        assertThrows(VelTypeException.class,
                () -> TypeRules.indexAssign(ints, List.of(first, Subscript.all()), ArrayType.mat(n(1), n(2), I32)));
        // the value must have a common type with the elements
        assertThrows(VelTypeException.class,
                () -> TypeRules.indexAssign(ints, List.of(first, first), ArrayType.scalar(ScalarType.BOOL)));
    }

    @Test
    void destructuring() {
        // (x, y, z) = (1, 2, 3): each target is a Z(32) scalar
        ArrayType value = TypeRules.tuple(List.of(lit(1), lit(2), lit(3)));
        assertEquals(ArrayType.scalar(I32), TypeRules.destructureTuple(value, 3));
        // [a, b; c, d] = M with M: Mat(2, 2, F(64)): elements
        assertEquals(ArrayType.scalar(F64), TypeRules.destructureMatrix(mat(n(2), n(2)), 2, 2));
        // (u, v) = M with M: Mat(2, 3): the rows, as Vec(3) (undoes the stacking of a tuple)
        assertEquals(ArrayType.vec(n(3), F64), TypeRules.destructureTuple(mat(n(2), n(3)), 2));
        // ⊤ sizes are accepted (checked at runtime as usual), known mismatches are errors
        assertEquals(ArrayType.scalar(F64), TypeRules.destructureTuple(ArrayType.vec(TOP, F64), 3));
        assertThrows(VelTypeException.class, () -> TypeRules.destructureTuple(ArrayType.vec(n(2), F64), 3));
        assertThrows(VelTypeException.class, () -> TypeRules.destructureMatrix(mat(n(2), n(2)), 4, 1));
    }

    @Test
    void tuplesStackTheirItems() {
        ArrayType u = ArrayType.vec(n(3), F64);
        // (u, v) of Vec(3): Mat(2, 3), item i is row i
        assertEquals(mat(n(2), n(3)), TypeRules.tuple(List.of(u, u)));
        // (A, B) of Mat(2, 3): Array(2, 2, 3)
        assertEquals(ArrayType.of(F64, n(2), n(2), n(3)), TypeRules.tuple(List.of(mat(n(2), n(3)), mat(n(2), n(3)))));
        // items must have the same shape; ⊤ follows the usual rule; resolutions join
        assertThrows(VelTypeException.class, () -> TypeRules.tuple(List.of(u, ArrayType.vec(n(4), F64))));
        assertThrows(VelTypeException.class, () -> TypeRules.tuple(List.of(u, lit(1))));
        assertEquals(mat(n(2), TOP), TypeRules.tuple(List.of(u, ArrayType.vec(TOP, F64))));
        assertEquals(ArrayType.vec(n(2), F64), TypeRules.tuple(List.of(lit(1), ArrayType.scalar(F64))));
        // stacking then destructuring gives the items back
        assertEquals(u, TypeRules.destructureTuple(TypeRules.tuple(List.of(u, u)), 2));
    }

    @Test
    void partitionedIndexingGivesABlockTensor() {
        ArrayType m = ArrayType.mat(n(3), n(3), I32);
        Subscript rows023 = Subscript.partition(List.of(lit(0), lit(2), lit(3)));   // [0,2), [2,3)
        Subscript cols013 = Subscript.partition(List.of(lit(0), lit(1), lit(3)));   // [0,1), [1,3)
        // 2x2 blocks of different sizes: block indices first, block sizes ⊤
        ArrayType t = TypeRules.index(m, List.of(rows023, cols013));
        assertEquals(ArrayType.of(I32, n(2), n(2), TOP, TOP), t);
        // equal blocks keep their size: M6x6[0:2:4:6, 0:3:6] -> Array(3, 2, 2, 3)
        ArrayType m6 = ArrayType.mat(n(6), n(6), I32);
        ArrayType even = TypeRules.index(m6, List.of(
                Subscript.partition(List.of(lit(0), lit(2), lit(4), lit(6))),
                Subscript.partition(List.of(lit(0), lit(3), lit(6)))));
        assertEquals(ArrayType.of(I32, n(3), n(2), n(2), n(3)), even);
        // only the rows partitioned: Array(k, h, w), a stack of row blocks
        ArrayType rowsOnly = TypeRules.index(m6, List.of(
                Subscript.partition(List.of(lit(0), lit(2), lit(4), lit(6))), Subscript.all()));
        assertEquals(ArrayType.of(I32, n(3), n(2), n(6)), rowsOnly);
        // a vector partition is a stack of column vectors: Mat(k, h)
        ArrayType v = ArrayType.vec(n(6), I32);
        assertEquals(ArrayType.mat(n(3), n(2), I32), TypeRules.index(v, List.of(
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
        ArrayType m = ArrayType.mat(n(3), n(3), I32);
        ArrayType t = TypeRules.index(m, List.of(
                Subscript.partition(List.of(lit(0), lit(2), lit(3))),
                Subscript.partition(List.of(lit(0), lit(1), lit(3)))));
        assertEquals(ArrayType.mat(TOP, TOP, I32), TypeRules.destructureMatrix(t, 2, 2));
        assertThrows(VelTypeException.class, () -> TypeRules.destructureMatrix(t, 3, 1));
        // with the partition at hand each block gets its exact type:
        // A = [1; 4] (2x1), B = [2, 3; 5, 6] (2x2), C = 7 (scalar), D = [8, 9] (1x2)
        List<List<ArrayType>> exact = TypeRules.blocks(m, List.of(
                Subscript.partition(List.of(lit(0), lit(2), lit(3))),
                Subscript.partition(List.of(lit(0), lit(1), lit(3)))));
        assertEquals(ArrayType.vec(n(2), I32), exact.get(0).get(0));
        assertEquals(ArrayType.mat(n(2), n(2), I32), exact.get(0).get(1));
        assertTrue(exact.get(1).get(0).isScalar());
        assertEquals(ArrayType.mat(n(1), n(2), I32), exact.get(1).get(1));
        // (Top, Bottom) = M6[0:3:6, :]: tuple pattern over the stack of row blocks
        ArrayType m6 = ArrayType.mat(n(6), n(4), F64);
        ArrayType halves = TypeRules.index(m6, List.of(Subscript.partition(List.of(lit(0), lit(3), lit(6))), Subscript.all()));
        assertEquals(ArrayType.mat(n(3), n(4), F64), TypeRules.destructureTuple(halves, 2));
    }

    @Test
    void conditional() {
        ArrayType cond = ArrayType.scalar(ScalarType.BOOL);
        // branches join: Z(32) and F(32) -> F(32); literals follow their partner
        assertEquals(ArrayType.scalar(F32), TypeRules.conditional(cond, ArrayType.scalar(I32), ArrayType.scalar(F32)));
        assertEquals(ArrayType.scalar(F32), TypeRules.conditional(cond, lit(1), ArrayType.scalar(F32)));
        // shapes must agree; ⊤ follows the usual rule
        assertEquals(ArrayType.vec(n(3), F64), TypeRules.conditional(cond, ArrayType.vec(n(3), F64), ArrayType.vec(n(3), F64)));
        assertEquals(ArrayType.vec(TOP, F64), TypeRules.conditional(cond, ArrayType.vec(TOP, F64), ArrayType.vec(n(3), F64)));
        assertThrows(VelTypeException.class,
                () -> TypeRules.conditional(cond, ArrayType.vec(n(3), F64), ArrayType.vec(n(4), F64)));
        // the condition is a Bool scalar; Bool and numeric branches don't mix
        assertThrows(VelTypeException.class, () -> TypeRules.conditional(lit(1), lit(1), lit(2)));
        assertThrows(VelTypeException.class,
                () -> TypeRules.conditional(ArrayType.vec(n(2), ScalarType.BOOL), lit(1), lit(2)));
        assertThrows(VelTypeException.class, () -> TypeRules.conditional(cond, cond, lit(2)));
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
        assertTrue(ArrayType.vec(n(3), I32).isAssignableTo(ArrayType.vec(n(3), F64)));
        assertFalse(ArrayType.vec(n(3), F64).isAssignableTo(ArrayType.vec(n(3), I32)));
        assertTrue(ArrayType.vec(n(3), F64).isAssignableTo(ArrayType.vec(TOP, F64)));
        assertFalse(ArrayType.vec(n(3), F64).isAssignableTo(ArrayType.vec(n(4), F64)));
        assertTrue(ScalarType.real(Resolution.FLOAT64).isAssignableTo(ScalarType.complex(Resolution.FLOAT64)));
        assertFalse(ScalarType.complex(Resolution.FLOAT64).isAssignableTo(ScalarType.real(Resolution.FLOAT64)));
    }
}
