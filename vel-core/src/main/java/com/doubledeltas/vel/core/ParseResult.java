package com.doubledeltas.vel.core;

import java.util.List;

import org.antlr.v4.runtime.ParserRuleContext;

/** A parse tree plus any syntax errors. The tree is still usable (error-recovered) when errors exist. */
public record ParseResult<T extends ParserRuleContext>(T tree, List<VelDiagnostic> diagnostics) {

    public ParseResult {
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.severity() == VelDiagnostic.Severity.ERROR);
    }
}
