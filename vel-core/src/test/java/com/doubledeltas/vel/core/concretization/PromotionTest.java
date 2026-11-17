package com.doubledeltas.vel.core.concretization;

import static com.doubledeltas.vel.core.concretization.Promotion.arithmetic;
import static com.doubledeltas.vel.core.concretization.Promotion.isRepresentable;
import static com.doubledeltas.vel.core.concretization.Promotion.join;
import static com.doubledeltas.vel.core.concretization.Resolution.BIG_DECIMAL;
import static com.doubledeltas.vel.core.concretization.Resolution.BIG_INT;
import static com.doubledeltas.vel.core.concretization.Resolution.BOOL;
import static com.doubledeltas.vel.core.concretization.Resolution.FLOAT32;
import static com.doubledeltas.vel.core.concretization.Resolution.FLOAT64;
import static com.doubledeltas.vel.core.concretization.Resolution.INT32;
import static com.doubledeltas.vel.core.concretization.Resolution.INT64;
import static com.doubledeltas.vel.core.concretization.Resolution.decimalFloat;
import static com.doubledeltas.vel.core.concretization.Resolution.signed;
import static com.doubledeltas.vel.core.concretization.Resolution.unsigned;
import static com.doubledeltas.vel.core.type.Arithmetic.ADD;
import static com.doubledeltas.vel.core.type.Arithmetic.DIV;
import static com.doubledeltas.vel.core.type.Arithmetic.FLOOR_MOD;
import static com.doubledeltas.vel.core.type.Arithmetic.MUL;
import static com.doubledeltas.vel.core.type.Arithmetic.POW;
import static com.doubledeltas.vel.core.type.Arithmetic.SUB;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import com.doubledeltas.vel.core.type.Constant;
import com.doubledeltas.vel.core.type.ScalarAlgebra;
import com.doubledeltas.vel.core.type.VelTypeException;

/** The Java concretization rules of java.md 7, row by row. */
class PromotionTest {

    private static Operand r(Resolution r) {
        return Operand.of(r);
    }

    private static Constant c(long v) {
        return Constant.of(v);
    }

    private static Constant c(String v) {
        return Constant.of(new BigDecimal(v));
    }

    private static Operand lit(long v) {
        return Operand.of(c(v));
    }

    private static Operand lit(String v) {
        return Operand.of(c(v));
    }

    // ---- 7.2 ⊑ --------------------------------------------------------------------------------

    @Test
    void representabilityBetweenIntegers() {
        assertTrue(isRepresentable(signed(8), signed(16)));
        assertFalse(isRepresentable(signed(16), signed(8)));
        assertTrue(isRepresentable(unsigned(8), unsigned(16)));
        assertTrue(isRepresentable(unsigned(8), signed(16)));   // a < b
        assertFalse(isRepresentable(unsigned(16), signed(16)));
        assertFalse(isRepresentable(signed(8), unsigned(16)));  // never Z → Z+
        assertTrue(isRepresentable(INT64, BIG_INT));
        assertTrue(isRepresentable(unsigned(64), BIG_INT));
    }

    @Test
    void representabilityIntoFloats() {
        assertTrue(isRepresentable(FLOAT32, FLOAT64));
        assertFalse(isRepresentable(FLOAT64, FLOAT32));
        assertTrue(isRepresentable(signed(16), FLOAT32));   // 15 <= 24
        assertTrue(isRepresentable(signed(25), FLOAT32));   // 24 <= 24
        assertFalse(isRepresentable(INT32, FLOAT32));       // 31 > 24
        assertTrue(isRepresentable(INT32, FLOAT64));        // 31 <= 53
        assertFalse(isRepresentable(INT64, FLOAT64));
        assertTrue(isRepresentable(unsigned(24), FLOAT32));
        assertFalse(isRepresentable(unsigned(25), FLOAT32));
    }

    @Test
    void representabilityIntoDecimals() {
        assertTrue(isRepresentable(decimalFloat(32), decimalFloat(64)));
        assertTrue(isRepresentable(signed(16), decimalFloat(32)));   // 2^15 <= 10^7
        assertFalse(isRepresentable(INT32, decimalFloat(32)));       // 2^31 > 10^7
        assertTrue(isRepresentable(INT32, decimalFloat(64)));
        assertTrue(isRepresentable(INT64, decimalFloat(128)));       // 2^63 <= 10^34
        assertFalse(isRepresentable(FLOAT32, decimalFloat(128)));    // binary and decimal floats don't mix
        assertFalse(isRepresentable(decimalFloat(32), FLOAT64));
        for (Resolution x : new Resolution[] {BIG_INT, FLOAT64, decimalFloat(64), INT32, unsigned(8)}) {
            assertTrue(isRepresentable(x, BIG_DECIMAL), x::toString);
        }
        assertFalse(isRepresentable(BIG_INT, FLOAT64));
        assertFalse(isRepresentable(BOOL, INT32));
    }

    @Test
    void constantRepresentabilityIsByValue() {
        assertTrue(isRepresentable(c(127), signed(8)));
        assertFalse(isRepresentable(c(128), signed(8)));
        assertTrue(isRepresentable(c(-128), signed(8)));
        assertFalse(isRepresentable(c(-1), unsigned(8)));
        assertTrue(isRepresentable(c(255), unsigned(8)));
        assertTrue(isRepresentable(c(16_777_216), FLOAT32));
        assertFalse(isRepresentable(c(16_777_217), FLOAT32));
        assertTrue(isRepresentable(c("0.5"), FLOAT32));
        assertFalse(isRepresentable(c("0.1"), FLOAT64));          // not exact in binary
        assertTrue(isRepresentable(c("0.1"), decimalFloat(32)));
        assertFalse(isRepresentable(c("2.0"), INT32));            // decimal constants never become integers
        Constant third = new Constant.Rational(BigInteger.ONE, BigInteger.valueOf(3));
        assertTrue(isRepresentable(third, BIG_DECIMAL));
        assertFalse(isRepresentable(third, FLOAT64));
    }

    // ---- 7.3 ⊔ --------------------------------------------------------------------------------

    @Test
    void joinTableRows() {
        assertEquals(BIG_DECIMAL, join(BIG_DECIMAL, INT32));                       // 1
        assertEquals(BIG_DECIMAL, join(BIG_INT, FLOAT32));                         // 2
        assertEquals(BIG_DECIMAL, join(decimalFloat(32), BIG_INT));                // 2
        assertEquals(BIG_INT, join(BIG_INT, INT64));                               // 3
        assertEquals(decimalFloat(64), join(decimalFloat(32), FLOAT64));           // 4: D32 ⊔ F64 = D64
        assertEquals(decimalFloat(32), join(decimalFloat(32), INT64));             // 4: integer ignored
        assertEquals(FLOAT32, join(INT64, FLOAT32));                               // 5: Z64 ⊔ F32 = F32
        assertEquals(FLOAT64, join(FLOAT32, FLOAT64));                             // 5
        assertEquals(INT64, join(INT32, INT64));                                   // 6
        assertEquals(unsigned(16), join(unsigned(8), unsigned(16)));               // 7
        assertEquals(signed(17), join(signed(8), unsigned(16)));                   // 8: max(a, b + 1)
        assertEquals(INT32, join(INT32, unsigned(16)));                            // 8
        assertEquals(BOOL, join(BOOL, BOOL));
        assertThrows(VelTypeException.class, () -> join(BOOL, INT32));
    }

    // ---- minimum width and operator exceptions ------------------------------------------------

    @Test
    void integerArithmeticIsAtLeast32Bits() {
        assertEquals(INT32, arithmetic(ADD, r(signed(8)), r(signed(8))));
        assertEquals(unsigned(32), arithmetic(MUL, r(unsigned(8)), r(unsigned(16))));
        assertEquals(INT64, arithmetic(ADD, r(INT64), r(signed(8))));
        // join alone (e.g. for concatenation) does not widen
        assertEquals(signed(8), join(signed(8), signed(8)));
    }

    @Test
    void operatorExceptions() {
        // unary minus on Z+n -> Z[n+1], widened
        assertEquals(INT32, Promotion.negate(unsigned(16), true));
        assertEquals(signed(33), Promotion.negate(unsigned(32), true));
        // Z+ - Z+ -> Z[max + 1]
        assertEquals(INT32, arithmetic(SUB, r(unsigned(16)), r(unsigned(16))));     // char - char
        assertEquals(signed(65), arithmetic(SUB, r(unsigned(64)), r(unsigned(8))));
        // Int / Int is true division
        assertEquals(FLOAT64, arithmetic(DIV, r(INT32), r(INT32)));
        assertEquals(BIG_DECIMAL, arithmetic(DIV, r(BIG_INT), r(INT32)));
        assertEquals(FLOAT32, arithmetic(DIV, r(FLOAT32), r(INT32)));
        // quotient / remainder follow ⊔
        assertEquals(INT64, arithmetic(FLOOR_MOD, r(INT32), r(INT64)));
        // ^ with a non-negative integer literal keeps the base; anything else joins F64
        assertEquals(INT32, arithmetic(POW, r(signed(8)), lit(2)));
        assertEquals(FLOAT64, arithmetic(POW, r(INT32), lit(-1)));
        assertEquals(FLOAT64, arithmetic(POW, r(FLOAT32), r(FLOAT32)));
        assertEquals(BIG_DECIMAL, arithmetic(POW, r(BIG_INT), r(INT32)));
        // any other exponent: A ⊔ B ⊔ F64, so the exponent's resolution counts
        assertEquals(decimalFloat(64), arithmetic(POW, r(INT32), r(decimalFloat(64))));
        assertEquals(BIG_DECIMAL, arithmetic(POW, r(FLOAT64), r(BIG_DECIMAL)));
        assertEquals(BIG_DECIMAL, arithmetic(POW, r(INT32), r(BIG_INT)));
        assertEquals(FLOAT64, arithmetic(POW, r(FLOAT32), lit("0.5")));
        assertEquals(FLOAT64, arithmetic(POW, lit(2), r(INT32)));
    }

    @Test
    void boolArithmetic() {
        assertEquals(BOOL, arithmetic(ADD, r(BOOL), r(BOOL))); // OR
        assertEquals(BOOL, arithmetic(MUL, r(BOOL), r(BOOL))); // AND
    }

    // ---- 7.6 literals -------------------------------------------------------------------------

    @Test
    void constantsFollowTheirPartner() {
        // 0.5 * x with x: F32 stays F32
        assertEquals(FLOAT32, arithmetic(MUL, lit("0.5"), r(FLOAT32)));
        // an integer constant follows an integer partner when it fits
        assertEquals(INT32, arithmetic(ADD, r(signed(8)), lit(1)));          // widened from Z8
        assertEquals(INT64, arithmetic(ADD, r(INT64), lit(1)));
        // ... and uses its default when it doesn't
        assertEquals(INT64, arithmetic(ADD, r(signed(8)), lit(1L << 40)));
        // a decimal constant only follows floating partners
        assertEquals(FLOAT64, arithmetic(ADD, r(INT32), lit("0.5")));
        assertEquals(decimalFloat(64), arithmetic(ADD, r(decimalFloat(64)), lit("0.1")));
        assertEquals(BIG_DECIMAL, arithmetic(ADD, r(BIG_DECIMAL), lit("0.1")));
        // x * (1/3): F64 with x: F64, exact with x: D∞
        Operand third = Operand.of(new Constant.Rational(BigInteger.ONE, BigInteger.valueOf(3)));
        assertEquals(FLOAT64, arithmetic(MUL, r(FLOAT64), third));
        assertEquals(BIG_DECIMAL, arithmetic(MUL, r(BIG_DECIMAL), third));
        // x * -1 still follows x (the constant -1 was folded in VEL)
        assertEquals(INT64, arithmetic(MUL, r(INT64), lit(-1)));
    }

    @Test
    void constantDefaults() {
        assertEquals(INT32, Promotion.defaultOf(c(Integer.MAX_VALUE)));
        assertEquals(INT64, Promotion.defaultOf(c(Integer.MAX_VALUE + 1L)));
        assertEquals(BIG_INT, Promotion.defaultOf(Constant.of(BigInteger.TWO.pow(63))));
        assertEquals(FLOAT64, Promotion.defaultOf(c("1.5")));
        assertEquals(FLOAT64, Promotion.defaultOf(new Constant.Rational(BigInteger.ONE, BigInteger.valueOf(3))));
        // a constant that could not be folded (1/0) uses the defaults of its parts
        assertEquals(FLOAT64, arithmetic(DIV, lit(1), lit(0)));
    }

    // ---- 7.3 matching the algebra and the resolution -------------------------------------------

    @Test
    void scalarTypesKeepAlgebraAndResolutionConsistent() {
        // Zn, Z+n, Z∞ only with Int; Int only with them
        assertThrows(IllegalArgumentException.class, () -> ScalarType.real(INT32));
        assertThrows(IllegalArgumentException.class, () -> ScalarType.complex(signed(16)));
        assertThrows(IllegalArgumentException.class, () -> ScalarType.integer(FLOAT64));
        assertThrows(IllegalArgumentException.class, () -> ScalarType.integer(BIG_DECIMAL));
        // j * x with x: int cannot be ⟨Complex⟩_Z32: the resolution becomes F64
        assertEquals(ScalarType.complex(FLOAT64), ScalarType.of(ScalarAlgebra.COMPLEX, INT32));
        // Z∞ becomes D∞
        assertEquals(ScalarType.complex(BIG_DECIMAL), ScalarType.of(ScalarAlgebra.COMPLEX, BIG_INT));
        // Int with a floating resolution becomes Real
        assertEquals(ScalarType.real(FLOAT64), ScalarType.of(ScalarAlgebra.INT, FLOAT64));
        assertEquals(ScalarType.integer(INT32), ScalarType.of(ScalarAlgebra.INT, INT32));
    }

    @Test
    void scalarTypeAssignability() {
        assertTrue(ScalarType.integer(INT32).isAssignableTo(ScalarType.real(FLOAT64)));
        assertFalse(ScalarType.real(FLOAT64).isAssignableTo(ScalarType.integer(INT32)));
        assertTrue(ScalarType.real(FLOAT64).isAssignableTo(ScalarType.complex(FLOAT64)));
        assertFalse(ScalarType.complex(FLOAT64).isAssignableTo(ScalarType.real(FLOAT64)));
    }
}
