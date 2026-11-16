package com.doubledeltas.vel.core.type;

import static com.doubledeltas.vel.core.type.Promotion.Arithmetic.ADD;
import static com.doubledeltas.vel.core.type.Promotion.Arithmetic.DIV;
import static com.doubledeltas.vel.core.type.Promotion.Arithmetic.FLOOR_MOD;
import static com.doubledeltas.vel.core.type.Promotion.Arithmetic.MUL;
import static com.doubledeltas.vel.core.type.Promotion.Arithmetic.POW;
import static com.doubledeltas.vel.core.type.Promotion.Arithmetic.SUB;
import static com.doubledeltas.vel.core.type.Promotion.arithmetic;
import static com.doubledeltas.vel.core.type.Promotion.isRepresentable;
import static com.doubledeltas.vel.core.type.Promotion.join;
import static com.doubledeltas.vel.core.type.Resolution.BIG_DECIMAL;
import static com.doubledeltas.vel.core.type.Resolution.BIG_INT;
import static com.doubledeltas.vel.core.type.Resolution.BOOL;
import static com.doubledeltas.vel.core.type.Resolution.FLOAT32;
import static com.doubledeltas.vel.core.type.Resolution.FLOAT64;
import static com.doubledeltas.vel.core.type.Resolution.INT32;
import static com.doubledeltas.vel.core.type.Resolution.INT64;
import static com.doubledeltas.vel.core.type.Resolution.decimalFloat;
import static com.doubledeltas.vel.core.type.Resolution.signed;
import static com.doubledeltas.vel.core.type.Resolution.unsigned;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.junit.jupiter.api.Test;

/** The tables of type-system.md 3, row by row. */
class PromotionTest {

    private static Resolution lit(long v) {
        return Resolution.literal(BigInteger.valueOf(v));
    }

    private static Resolution lit(String v) {
        return Resolution.literal(new BigDecimal(v));
    }

    // ---- 3.2 ⊑ --------------------------------------------------------------------------------

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
        for (Resolution r : new Resolution[] {BIG_INT, FLOAT64, decimalFloat(64), INT32, unsigned(8)}) {
            assertTrue(isRepresentable(r, BIG_DECIMAL), r::toString);
        }
        assertFalse(isRepresentable(BIG_INT, FLOAT64));
        assertFalse(isRepresentable(BOOL, INT32));
    }

    @Test
    void literalRepresentabilityIsByValue() {
        assertTrue(isRepresentable(lit(127), signed(8)));
        assertFalse(isRepresentable(lit(128), signed(8)));
        assertTrue(isRepresentable(lit(-128), signed(8)));
        assertFalse(isRepresentable(lit(-1), unsigned(8)));
        assertTrue(isRepresentable(lit(255), unsigned(8)));
        assertTrue(isRepresentable(lit(16_777_216), FLOAT32));
        assertFalse(isRepresentable(lit(16_777_217), FLOAT32));
        assertTrue(isRepresentable(lit("0.5"), FLOAT32));
        assertFalse(isRepresentable(lit("0.1"), FLOAT64));          // not exact in binary
        assertTrue(isRepresentable(lit("0.1"), decimalFloat(32)));
        assertFalse(isRepresentable(lit("2.0"), INT32));            // decimal literals never become integers
    }

    // ---- 3.3 ⊔ --------------------------------------------------------------------------------

    @Test
    void joinTableRows() {
        assertEquals(BIG_DECIMAL, join(BIG_DECIMAL, INT32));                       // 1
        assertEquals(BIG_DECIMAL, join(BIG_INT, FLOAT32));                         // 2
        assertEquals(BIG_DECIMAL, join(decimalFloat(32), BIG_INT));                // 2
        assertEquals(BIG_INT, join(BIG_INT, INT64));                               // 3
        assertEquals(decimalFloat(64), join(decimalFloat(32), FLOAT64));           // 4: D(32) ⊔ F(64) = D(64)
        assertEquals(decimalFloat(32), join(decimalFloat(32), INT64));             // 4: integer ignored
        assertEquals(FLOAT32, join(INT64, FLOAT32));                               // 5: Z(64) ⊔ F(32) = F(32)
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
        assertEquals(INT32, arithmetic(ADD, signed(8), signed(8)));
        assertEquals(unsigned(32), arithmetic(MUL, unsigned(8), unsigned(16)));
        assertEquals(INT64, arithmetic(ADD, INT64, signed(8)));
        // join alone (e.g. for concatenation) does not widen
        assertEquals(signed(8), join(signed(8), signed(8)));
    }

    @Test
    void operatorExceptions() {
        // unary minus on Z+(n) -> Z(n+1), widened
        assertEquals(INT32, Promotion.negate(unsigned(16), true));
        assertEquals(signed(33), Promotion.negate(unsigned(32), true));
        // Z+ - Z+ -> Z(max + 1)
        assertEquals(INT32, arithmetic(SUB, unsigned(16), unsigned(16)));     // char - char
        assertEquals(signed(65), arithmetic(SUB, unsigned(64), unsigned(8)));
        // integer / integer is true division
        assertEquals(FLOAT64, arithmetic(DIV, INT32, INT32));
        assertEquals(BIG_DECIMAL, arithmetic(DIV, BIG_INT, INT32));
        assertEquals(FLOAT32, arithmetic(DIV, FLOAT32, INT32));
        // quotient / remainder follow ⊔
        assertEquals(INT64, arithmetic(FLOOR_MOD, INT32, INT64));
        // ^ with a non-negative integer literal keeps the base; anything else joins F(64)
        assertEquals(INT32, arithmetic(POW, signed(8), lit(2)));
        assertEquals(FLOAT64, arithmetic(POW, INT32, lit(-1)));
        assertEquals(FLOAT64, arithmetic(POW, FLOAT32, FLOAT32));
        assertEquals(BIG_DECIMAL, arithmetic(POW, BIG_INT, INT32));
    }

    @Test
    void boolArithmetic() {
        assertEquals(BOOL, arithmetic(ADD, BOOL, BOOL)); // OR
        assertEquals(BOOL, arithmetic(MUL, BOOL, BOOL)); // AND
        assertThrows(VelTypeException.class, () -> arithmetic(SUB, BOOL, BOOL));
        assertThrows(VelTypeException.class, () -> arithmetic(POW, BOOL, lit(2)));
        assertThrows(VelTypeException.class, () -> Promotion.negate(BOOL, true));
    }

    // ---- 3.4 literals -------------------------------------------------------------------------

    @Test
    void literalsFollowTheirPartner() {
        // 0.5 * x with x: F(32) stays F(32)
        assertEquals(FLOAT32, arithmetic(MUL, lit("0.5"), FLOAT32));
        // an integer literal follows an integer partner when it fits
        assertEquals(INT32, arithmetic(ADD, signed(8), lit(1)));          // widened from Z(8)
        assertEquals(INT64, arithmetic(ADD, INT64, lit(1)));
        // ... and uses its default when it doesn't
        assertEquals(INT64, arithmetic(ADD, signed(8), lit(1L << 40)));
        // a decimal literal only follows floating partners
        assertEquals(FLOAT64, arithmetic(ADD, INT32, lit("0.5")));
        assertEquals(decimalFloat(64), arithmetic(ADD, decimalFloat(64), lit("0.1")));
        assertEquals(BIG_DECIMAL, arithmetic(ADD, BIG_DECIMAL, lit("0.1")));
    }

    @Test
    void literalDefaults() {
        assertEquals(INT32, Promotion.defaultOf(lit(Integer.MAX_VALUE)));
        assertEquals(INT64, Promotion.defaultOf(lit(Integer.MAX_VALUE + 1L)));
        assertEquals(BIG_INT, Promotion.defaultOf(Resolution.literal(BigInteger.TWO.pow(63))));
        assertEquals(FLOAT64, Promotion.defaultOf(lit("1.5")));
        // two literals use their defaults
        assertEquals(INT32, arithmetic(ADD, lit(1), lit(2)));
        assertEquals(FLOAT64, arithmetic(DIV, lit(1), lit(2)));
        // unary minus keeps a literal a literal, so x * -1 still follows x
        assertEquals(lit(-1), Promotion.negate(lit(1), true));
        assertEquals(signed(64), arithmetic(MUL, INT64, Promotion.negate(lit(1), true)));
    }
}
