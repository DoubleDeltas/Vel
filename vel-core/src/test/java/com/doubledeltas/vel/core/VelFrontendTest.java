package com.doubledeltas.vel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VelFrontendTest {

    @Test
    void parsesMultiStatementProgram() {
        var result = VelFrontend.parseProgram("""
                // semi-implicit Euler
                v += a * dt
                return p + v * dt + 0.5 * a * dt^2
                """);
        assertFalse(result.hasErrors(), () -> result.diagnostics().toString());
        assertEquals(2, result.tree().statement().size());
    }

    @Test
    void newlinesInsideBracketsAreIgnored() {
        var result = VelFrontend.parseProgram("""
                m = [1, 2;
                     3, 4]
                return dot(
                    m[0, 1],
                    x)
                """);
        assertFalse(result.hasErrors(), () -> result.diagnostics().toString());
        assertEquals(2, result.tree().statement().size());
    }

    @Test
    void unaryMinusBindsLooserThanPower() {
        var result = VelFrontend.parseExpression("-a^2");
        assertFalse(result.hasErrors());
        assertEquals("(singleExpression (expr - (expr (expr (primary a)) ^ (expr (primary 2)))) <EOF>)",
                result.tree().toStringTree(new com.doubledeltas.vel.parser.VelParser(null)));
    }

    @Test
    void powerIsRightAssociative() {
        var result = VelFrontend.parseExpression("a^b^c");
        assertFalse(result.hasErrors());
        assertEquals("(singleExpression (expr (expr (primary a)) ^ (expr (expr (primary b)) ^ (expr (primary c)))) <EOF>)",
                result.tree().toStringTree(new com.doubledeltas.vel.parser.VelParser(null)));
    }

    @Test
    void tupleIsColumnVectorAndParenIsGrouping() {
        assertEquals("(singleExpression (expr (primary ( (expr (primary x)) , (expr (primary y)) , (expr (primary z)) ))) <EOF>)",
                tree("(x, y, z)"));
        assertEquals("(singleExpression (expr (primary ( (expr (primary x)) ))) <EOF>)",
                tree("(x)"));
    }

    @Test
    void transposeIsPostfixAndBindsTighterThanPower() {
        assertEquals("(singleExpression (expr (expr (primary [ (matrixRow (expr (primary x)) , (expr (primary y))) ])) ') <EOF>)",
                tree("[x, y]'"));
        // a^b' == a^(b')
        assertEquals("(singleExpression (expr (expr (primary a)) ^ (expr (expr (primary b)) ')) <EOF>)",
                tree("a^b'"));
        // -v' == -(v')
        assertEquals("(singleExpression (expr - (expr (expr (primary v)) ')) <EOF>)",
                tree("-v'"));
    }

    private static String tree(String expression) {
        var result = VelFrontend.parseExpression(expression);
        assertFalse(result.hasErrors(), () -> result.diagnostics().toString());
        return result.tree().toStringTree(new com.doubledeltas.vel.parser.VelParser(null));
    }

    @Test
    void reportsSyntaxErrorPosition() {
        var result = VelFrontend.parseProgram("x = 1\ny = (2 + \n");
        assertTrue(result.hasErrors());
        var first = result.diagnostics().getFirst();
        assertEquals(3, first.line());
    }
}
