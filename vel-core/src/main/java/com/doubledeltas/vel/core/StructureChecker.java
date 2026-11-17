package com.doubledeltas.vel.core;

import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.Token;

import com.doubledeltas.vel.parser.VelBaseVisitor;
import com.doubledeltas.vel.parser.VelParser;

/**
 * Structural rules that the grammar alone does not enforce. Runs only on trees without syntax errors.
 * <ul>
 *   <li>A chain of cut points {@code a:b:c…} (a partition) is allowed only as a subscript; elsewhere a
 *       range is just {@code a:b}.</li>
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
    public Void visitMatrixPattern(VelParser.MatrixPatternContext ctx) {
        int width = ctx.patternRow().getFirst().patternItem().size();
        for (VelParser.PatternRowContext row : ctx.patternRow()) {
            if (row.patternItem().size() != width) {
                error(row.getStart(), "every row of a matrix pattern must have " + width + " items");
            }
        }
        checkDistinct(ctx.patternRow().stream().flatMap(r -> r.patternItem().stream()).toList());
        return visitChildren(ctx);
    }

    @Override
    public Void visitTuplePattern(VelParser.TuplePatternContext ctx) {
        checkDistinct(ctx.patternItem());
        return visitChildren(ctx);
    }

    /** A target may appear only once in a pattern; any number of '?' placeholders are allowed. */
    private void checkDistinct(List<VelParser.PatternItemContext> items) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (VelParser.PatternItemContext item : items) {
            if (item instanceof VelParser.BindItemContext bind && !seen.add(bind.target().getText())) {
                error(bind.getStart(), "'" + bind.target().getText() + "' appears more than once in the pattern");
            }
        }
    }

    @Override
    public Void visitRangeExpr(VelParser.RangeExprContext ctx) {
        // a:b:c parses left-associatively as (a:b):c; such a chain of cut points is a partition
        if (ctx.expr(0) instanceof VelParser.RangeExprContext && !partitions.contains(ctx)) {
            error(ctx.getStart(), "cut points a:b:c are only allowed as a subscript (m[a:b:c])");
        }
        if (ctx.expr(1) instanceof VelParser.RangeExprContext) {
            error(ctx.getStart(), "a range cannot contain a range");
        }
        return visitChildren(ctx);
    }

    /** Ranges forming the cut-point chain of a subscript, which may be nested. */
    private final java.util.Set<VelParser.RangeExprContext> partitions = new java.util.HashSet<>();

    @Override
    public Void visitExprIndex(VelParser.ExprIndexContext ctx) {
        VelParser.ExprContext e = ctx.expr();
        while (e instanceof VelParser.RangeExprContext range) {
            partitions.add(range);
            e = range.expr(0);
        }
        return visitChildren(ctx);
    }

    private void error(Token at, String message) {
        diagnostics.add(VelDiagnostic.error(at.getLine(), at.getCharPositionInLine(), message));
    }
}
