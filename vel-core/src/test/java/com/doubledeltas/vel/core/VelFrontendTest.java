package com.doubledeltas.vel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VelFrontendTest {

    @Test
    void parsesMultiStatementProgram() {
        var result = VelFrontend.parseProgram("""
                # semi-implicit Euler
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

    @Test
    void plainTransposeIsDotTick() {
        assertEquals("(singleExpression (expr (expr (primary v)) .') <EOF>)", tree("v.'"));
        // number followed by .' is a transpose, not a decimal point
        assertEquals("(singleExpression (expr (expr (primary 2)) .') <EOF>)", tree("2.'"));
        // a.'' == (a.')'
        assertEquals("(singleExpression (expr (expr (expr (primary a)) .') ') <EOF>)", tree("a.''"));
    }

    @Test
    void elementWiseOperatorsShareMatrixOperatorPrecedence() {
        // a .* b + c ./ d  ==  (a .* b) + (c ./ d)
        assertEquals("(singleExpression (expr (expr (expr (primary a)) .* (expr (primary b))) + (expr (expr (primary c)) ./ (expr (primary d)))) <EOF>)",
                tree("a .* b + c ./ d"));
        // a .^ b .^ c  ==  a .^ (b .^ c)
        assertEquals("(singleExpression (expr (expr (primary a)) .^ (expr (expr (primary b)) .^ (expr (primary c)))) <EOF>)",
                tree("a .^ b .^ c"));
        // -a .^ 2  ==  -(a .^ 2)
        assertEquals("(singleExpression (expr - (expr (expr (primary a)) .^ (expr (primary 2)))) <EOF>)",
                tree("-a .^ 2"));
    }

    @Test
    void dotPlusAndDotMinusParseLikePlusAndMinus() {
        assertEquals("(singleExpression (expr (expr (expr (primary a)) .+ (expr (primary b))) .- (expr (primary c))) <EOF>)",
                tree("a .+ b .- c"));
        // only binary: there is no unary .-
        assertTrue(VelFrontend.parseExpression(".-a").hasErrors());
    }

    @Test
    void numberFollowedByDotOperatorIsElementWise() {
        assertEquals("(singleExpression (expr (expr (primary 2)) .* (expr (primary x))) <EOF>)", tree("2.*x"));
        assertEquals("(singleExpression (expr (expr (primary 1.5)) .^ (expr (primary 2))) <EOF>)", tree("1.5.^2"));
        assertEquals("(singleExpression (expr (expr (primary 1e3)) ./ (expr (primary x))) <EOF>)", tree("1e3./x"));
        assertEquals("(singleExpression (expr (primary .5)) <EOF>)", tree(".5"));
    }

    @Test
    void identifiersFollowJavaRules() {
        assertEquals("(singleExpression (expr (expr (primary θ)) * (expr (primary 속도))) <EOF>)", tree("θ * 속도"));
        assertEquals("(singleExpression (expr (primary a$b_1)) <EOF>)", tree("a$b_1"));
    }

    @Test
    void lineEndingInOperatorContinues() {
        var result = VelFrontend.parseProgram("""
                x = a +
                    b .*
                    c
                y =
                    x
                return y
                """);
        assertFalse(result.hasErrors(), () -> result.diagnostics().toString());
        assertEquals(3, result.tree().statement().size());
    }

    @Test
    void multiLineBlockCommentSeparatesStatements() {
        var result = VelFrontend.parseProgram("x = 1 /* one\n two */ y = 2 /* same line */ + 1");
        assertFalse(result.hasErrors(), () -> result.diagnostics().toString());
        assertEquals(2, result.tree().statement().size());
    }

    @Test
    void rangesAreInclusiveMatlabStyleWithLowestPrecedence() {
        assertEquals("(singleExpression (expr (expr (primary a)) : (expr (primary b))) <EOF>)", tree("a:b"));
        assertEquals("(singleExpression (expr (expr (primary 0)) : (expr (primary 0.1)) : (expr (primary 1))) <EOF>)",
                tree("0:0.1:1"));
        // a+1:b-1 == (a+1):(b-1)
        assertEquals("(singleExpression (expr (expr (expr (primary a)) + (expr (primary 1))) : (expr (expr (primary b)) - (expr (primary 1)))) <EOF>)",
                tree("a+1:b-1"));
    }

    @Test
    void slicingWithColonAndEnd() {
        assertEquals("(singleExpression (expr (expr (primary m)) [ (indexList (index :) , (index (expr (expr (primary 1)) : (expr (primary end))))) ]) <EOF>)",
                tree("m[:, 1:end]"));
    }

    @Test
    void javaStyleMethodCalls() {
        assertEquals("(singleExpression (expr (expr (primary t)) . foo ( (arguments (expr (primary x))) )) <EOF>)",
                tree("t.foo(x)"));
        assertEquals("(singleExpression (expr (expr (primary v)) . norm ( )) <EOF>)", tree("v.norm()"));
        // qualified static call: java.lang.Math.sqrt(x)
        assertEquals("(singleExpression (expr (expr (expr (expr (primary java)) . lang) . Math) . sqrt ( (arguments (expr (primary x))) )) <EOF>)",
                tree("java.lang.Math.sqrt(x)"));
    }

    @Test
    void boolLiteralsAndImaginaryUnitIdentifier() {
        assertEquals("(singleExpression (expr (primary true)) <EOF>)", tree("true"));
        assertEquals("(singleExpression (expr (primary false)) <EOF>)", tree("false"));
        // j is an ordinary (predeclared) identifier, not a literal suffix
        assertEquals("(singleExpression (expr (expr (primary 2)) + (expr (expr (primary 3)) * (expr (primary j)))) <EOF>)",
                tree("2 + 3*j"));
    }

    @Test
    void quotientAndModuloOperators() {
        // all share the multiplicative precedence, left-associative
        assertEquals("(singleExpression (expr (expr (expr (expr (primary a)) // (expr (primary b))) %% (expr (primary c))) %%% (expr (primary d))) <EOF>)",
                tree("a // b %% c %%% d"));
        // a single % does not exist
        assertTrue(VelFrontend.parseExpression("a % b").hasErrors());
        assertTrue(VelFrontend.parseProgram("x %= 2").hasErrors());
        // /// (floor quotient) is one token, distinct from // and /
        assertEquals("(singleExpression (expr (expr (expr (primary a)) /// (expr (primary b))) / (expr (primary c))) <EOF>)",
                tree("a /// b / c"));
    }

    @Test
    void hashStartsLineComment() {
        var result = VelFrontend.parseProgram("x = 1 # comment\ny = x //\n    2");
        assertFalse(result.hasErrors(), () -> result.diagnostics().toString());
        assertEquals(2, result.tree().statement().size());
    }

    @Test
    void indexAndSubMatrixAssignment() {
        for (String src : new String[] {"m[0, 1] = 2", "m[0:2, :] = B", "v[end] += 1", "m[:, j] .*= 3"}) {
            var result = VelFrontend.parseProgram(src);
            assertFalse(result.hasErrors(), () -> src + " " + result.diagnostics());
        }
        // member assignment is not allowed
        assertTrue(VelFrontend.parseProgram("v.x = 1").hasErrors());
    }

    @Test
    void blockMatrixLiteral() {
        assertEquals("(singleExpression (expr (primary [ (matrixRow (expr (primary A)) , (expr (primary B))) ; (matrixRow (expr (primary C)) , (expr (primary D))) ])) <EOF>)",
                tree("[A, B; C, D]"));
    }

    @Test
    void compoundAssignments() {
        for (String op : new String[] {"=", "+=", "-=", "*=", "/=", "//=", "///=", "%%=", "%%%=", "^=",
                ".+=", ".-=", ".*=", "./=", ".^="}) {
            var result = VelFrontend.parseProgram("x " + op + " y");
            assertFalse(result.hasErrors(), () -> op + " " + result.diagnostics());
        }
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
