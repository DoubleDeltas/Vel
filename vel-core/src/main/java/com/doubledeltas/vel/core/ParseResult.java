package com.doubledeltas.vel.core;

import java.util.List;

import org.antlr.v4.runtime.ParserRuleContext;

/** A parse tree plus any syntax errors. The tree is still usable (error-recovered) when errors exist. */
public record ParseResult<T extends ParserRuleContext>(T tree, List<VelDiagnostic> diagnostics) {

    public ParseResult {
        diagnostics = List.copyOf(diagnostics);
    }

    /** The same tree with additional diagnostics. */
    public ParseResult<T> with(List<VelDiagnostic> more) {
        if (more.isEmpty()) {
            return this;
        }
        List<VelDiagnostic> all = new java.util.ArrayList<>(diagnostics);
        all.addAll(more);
        return new ParseResult<>(tree, all);
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.severity() == VelDiagnostic.Severity.ERROR);
    }
}
