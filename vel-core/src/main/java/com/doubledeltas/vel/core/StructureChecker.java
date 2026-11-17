package com.doubledeltas.vel.core;

import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.Token;

import com.doubledeltas.vel.parser.VelBaseVisitor;
import com.doubledeltas.vel.parser.VelParser;

/**
 * Structural rules that the grammar alone does not enforce. Runs only on trees without syntax errors.
 * <ul>
 *   <li>A range cannot contain a range: only {@code a:b} (no {@code a:s:b}, no {@code a:b:c:d}).</li>
 *   <li>A program that is a single expression returns that expression. Any other program must end
 *       with {@code return}, and {@code return} can only be the last statement.</li>
 * </ul>
 */
final class StructureChecker extends VelBaseVisitor<Void> {

    private final List<VelDiagnostic> diagnostics = new ArrayList<>();

    static List<VelDiagnostic> checkProgram(VelParser.ProgramContext program) {
        StructureChecker checker = new StructureChecker();
        checker.checkReturn(program);
        checker.visit(program);
        return checker.diagnostics;
    }

    static List<VelDiagnostic> checkExpression(VelParser.SingleExpressionContext expression) {
        StructureChecker checker = new StructureChecker();
        checker.visit(expression);
        return checker.diagnostics;
    }

    private void checkReturn(VelParser.ProgramContext program) {
        List<VelParser.StatementContext> statements = program.statement();
        if (statements.size() == 1 && statements.getFirst() instanceof VelParser.ExprStmtContext) {
            return; // a single expression is the result
        }
        for (int i = 0; i < statements.size() - 1; i++) {
            if (statements.get(i) instanceof VelParser.ReturnStmtContext ret) {
                error(ret.getStart(), "return must be the last statement");
            }
        }
        if (statements.isEmpty() || !(statements.getLast() instanceof VelParser.ReturnStmtContext)) {
            Token at = statements.isEmpty() ? program.getStop() : statements.getLast().getStart();
            error(at, "a program with more than one expression must end with return");
        }
    }

    @Override
    public Void visitRangeExpr(VelParser.RangeExprContext ctx) {
        for (VelParser.ExprContext bound : ctx.expr()) {
            if (bound instanceof VelParser.RangeExprContext) {
                error(ctx.getStart(), "a range cannot contain a range (only a:b is allowed)");
            }
        }
        return visitChildren(ctx);
    }

    private void error(Token at, String message) {
        diagnostics.add(VelDiagnostic.error(at.getLine(), at.getCharPositionInLine(), message));
    }
}
