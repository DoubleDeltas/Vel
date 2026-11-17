package com.doubledeltas.vel.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import com.doubledeltas.vel.parser.VelLexer;
import com.doubledeltas.vel.parser.VelParser;

/**
 * Compiler-agnostic entry point. Annotation processor, javac plugin and (later) the
 * standalone .vel build task all go through this class; none of it depends on javac.
 */
public final class VelFrontend {

    private VelFrontend() {
    }

    /** Parses a {@code @Vel} method body and checks its structure (see {@link StructureChecker}). */
    public static ParseResult<VelParser.ProgramContext> parseProgram(String source) {
        ParseResult<VelParser.ProgramContext> result = parse(source, VelParser::program);
        return result.hasErrors() ? result : result.with(StructureChecker.checkProgram(result.tree()));
    }

    /** Parses an inline {@code vel("...")} expression and checks its structure. */
    public static ParseResult<VelParser.SingleExpressionContext> parseExpression(String source) {
        ParseResult<VelParser.SingleExpressionContext> result = parse(source, VelParser::singleExpression);
        return result.hasErrors() ? result : result.with(StructureChecker.checkExpression(result.tree()));
    }

    private static <T extends ParserRuleContext> ParseResult<T> parse(
            String source, Function<VelParser, T> entryRule) {
        List<VelDiagnostic> diagnostics = new ArrayList<>();
        BaseErrorListener collector = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPositionInLine, String msg,
                                    RecognitionException e) {
                diagnostics.add(VelDiagnostic.error(line, charPositionInLine, msg));
            }
        };

        VelLexer lexer = new VelLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(collector);

        VelParser parser = new VelParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(collector);

        T tree = entryRule.apply(parser);
        return new ParseResult<>(tree, diagnostics);
    }
}
