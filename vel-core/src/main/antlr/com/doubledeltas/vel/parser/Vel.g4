grammar Vel;

@lexer::members {
    /** Newlines inside (), [] are insignificant, so expressions can span lines. */
    private int nesting = 0;
}

// ---------------------------------------------------------------------------
// Parser
// ---------------------------------------------------------------------------

/** A full @Vel method body: statements separated by newlines or ';'. */
program
    : sep* (statement (sep+ statement)*)? sep* EOF
    ;

/** A single inline expression, as used by vel("..."). */
singleExpression
    : NEWLINE* expr NEWLINE* EOF
    ;

sep
    : NEWLINE
    | ';'
    ;

statement
    : 'return' expr                                         # returnStmt
    | ID op=('=' | '+=' | '-=' | '*=' | '/=') expr          # assignStmt
    | expr                                                  # exprStmt
    ;

// Alternatives are listed from highest to lowest precedence.
expr
    : primary                                               # primaryExpr
    | expr '.' ID                                           # memberExpr
    | expr '[' expr (',' expr)* ']'                         # indexExpr
    | expr '\''                                             # transposeExpr
    | <assoc=right> expr '^' expr                           # powExpr
    | op=('-' | '+') expr                                   # unaryExpr
    | expr op=('*' | '/' | '%') expr                        # mulExpr
    | expr op=('+' | '-') expr                              # addExpr
    ;

primary
    : NUMBER                                                # numberLiteral
    | ID '(' (expr (',' expr)*)? ')'                        # callExpr
    | ID                                                    # identifier
    | '(' expr ')'                                          # parenExpr
    | '(' expr (',' expr)+ ')'                              # tupleLiteral
    | '[' matrixRow (';' matrixRow)* ']'                    # matrixLiteral
    ;

/**
 * Row-major literal: `[1, 2, 3]` is a 1x3 row vector, `[1, 2; 3, 4]` is a 2x2 matrix.
 * A column vector is written as a tuple: `(x, y, z)` == `[x, y, z]'` (3x1).
 * `(x)` is just a parenthesized expression, not a 1-element tuple.
 */
matrixRow
    : expr (',' expr)*
    ;

// ---------------------------------------------------------------------------
// Lexer
// ---------------------------------------------------------------------------

LPAREN   : '(' { nesting++; } ;
RPAREN   : ')' { if (nesting > 0) nesting--; } ;
LBRACK   : '[' { nesting++; } ;
RBRACK   : ']' { if (nesting > 0) nesting--; } ;

NUMBER
    : DIGITS ('.' DIGITS?)? EXPONENT?
    | '.' DIGITS EXPONENT?
    ;

ID : [a-zA-Z_] [a-zA-Z_0-9]* ;

NEWLINE : ('\r'? '\n' | '\r') { if (nesting > 0) skip(); } ;

WS           : [ \t\f]+ -> skip ;
LINE_COMMENT : '//' ~[\r\n]* -> skip ;
BLOCK_COMMENT: '/*' .*? '*/' -> skip ;

fragment DIGITS   : [0-9] ('_'? [0-9])* ;
fragment EXPONENT : [eE] [+-]? DIGITS ;
