package com.doubledeltas.vel.core.type;

import static com.doubledeltas.vel.core.type.BinaryOperator.ADD;
import static com.doubledeltas.vel.core.type.BinaryOperator.DIV;
import static com.doubledeltas.vel.core.type.BinaryOperator.EW_MUL;
import static com.doubledeltas.vel.core.type.BinaryOperator.EW_POW;
import static com.doubledeltas.vel.core.type.BinaryOperator.FLOOR_MOD;
import static com.doubledeltas.vel.core.type.BinaryOperator.MUL;
import static com.doubledeltas.vel.core.type.BinaryOperator.POW;
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
        assertThrows(VelTypeException.class, () -> TypeRules.transpose(ArrayType.of(F64, n(2), n(3), n(4))));
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
        // block tuple: (A, B) = [A; B]
        assertEquals(mat(n(5), n(3)), TypeRules.tuple(List.of(mat(n(2), n(3)), mat(n(3), n(3)))));
    }

    @Test
    void ranges() {
        assertEquals(ArrayType.mat(n(1), n(5), I32), TypeRules.range(lit(0), null, lit(4)));
        assertEquals(ArrayType.mat(n(1), n(3), I32), TypeRules.range(lit(0), lit(2), lit(5)));    // 0, 2, 4
        assertEquals(ArrayType.mat(n(1), n(3), I32), TypeRules.range(lit(4), lit(-2), lit(0)));   // 4, 2, 0
        assertEquals(ArrayType.mat(n(1), n(0), I32), TypeRules.range(lit(3), null, lit(1)));      // empty
        ArrayType tenth = ArrayType.scalar(ScalarType.literal(new BigDecimal("0.1")));
        assertEquals(ArrayType.mat(n(1), TOP, F64), TypeRules.range(lit(0), tenth, lit(1)));
        assertEquals(ArrayType.mat(n(1), TOP, I32), TypeRules.range(lit(0), null, ArrayType.scalar(I32)));
        assertThrows(VelTypeException.class, () -> TypeRules.range(lit(0), lit(0), lit(1)));
        assertThrows(VelTypeException.class, () -> TypeRules.range(lit(0), null, ArrayType.vec(n(2), F64)));
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
