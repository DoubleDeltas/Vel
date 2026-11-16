grammar Vel;

@lexer::members {
    /** Newlines inside (), [] are insignificant, so expressions can span lines. */
    private int nesting = 0;

    /** Type of the last token actually emitted (skipped tokens are not counted). */
    private int lastType = Token.INVALID_TYPE;

    @Override
    public Token emit() {
        Token t = super.emit();
        lastType = t.getType();
        return t;
    }

    /** A line ending in a binary operator, '=', ',' or '.' continues on the next line. */
    private boolean continuesLine() {
        switch (lastType) {
            case PLUS: case MINUS: case STAR: case SLASH: case PERCENT: case CARET:
            case DOT_PLUS: case DOT_MINUS: case DOT_STAR: case DOT_SLASH: case DOT_CARET:
            case ASSIGN: case PLUS_ASSIGN: case MINUS_ASSIGN: case STAR_ASSIGN: case SLASH_ASSIGN:
            case PERCENT_ASSIGN: case CARET_ASSIGN: case DOT_PLUS_ASSIGN: case DOT_MINUS_ASSIGN:
            case DOT_STAR_ASSIGN: case DOT_SLASH_ASSIGN: case DOT_CARET_ASSIGN:
            case COMMA: case DOT:
                return true;
            default:
                return false;
        }
    }

    /** Whether a line break at this point is a statement separator. */
    private boolean significantNewline() {
        return nesting == 0 && !continuesLine();
    }
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
    | ID op=( '='
            | '+=' | '-=' | '*=' | '/=' | '%=' | '^='
            | '.+=' | '.-=' | '.*=' | './=' | '.^=' ) expr  # assignStmt
    | expr                                                  # exprStmt
    ;

// Alternatives are listed from highest to lowest precedence.
//
// Operators follow MATLAB:
//   *  /  ^    matrix product / right division / matrix power (scalar operands: ordinary arithmetic)
//   .* ./ .^   element-wise
//   .+ .-      aliases of + and - (which are already element-wise), for symmetry
//   '          conjugate transpose (same as .' for real values)
//   .'         transpose
expr
    : primary                                               # primaryExpr
    | expr '.' ID                                           # memberExpr
    | expr '[' expr (',' expr)* ']'                         # indexExpr
    | expr op=('\'' | '.\'')                                # transposeExpr
    | <assoc=right> expr op=('^' | '.^') expr               # powExpr
    | op=('-' | '+') expr                                   # unaryExpr
    | expr op=('*' | '/' | '%' | '.*' | './') expr          # mulExpr
    | expr op=('+' | '-' | '.+' | '.-') expr                # addExpr
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
 * A column vector is written as a tuple: `(x, y, z)` == `[x, y, z].'` (3x1).
 * `(x)` is just a parenthesized expression, not a 1-element tuple.
 */
matrixRow
    : expr (',' expr)*
    ;

// ---------------------------------------------------------------------------
// Lexer
//
// Every operator / punctuation has a named token so lexer actions can refer to it.
// String literals used in parser rules (e.g. '+') resolve to these named tokens.
// ---------------------------------------------------------------------------

RETURN : 'return' ;

LPAREN : '(' { nesting++; } ;
RPAREN : ')' { if (nesting > 0) nesting--; } ;
LBRACK : '[' { nesting++; } ;
RBRACK : ']' { if (nesting > 0) nesting--; } ;

COMMA  : ',' ;
SEMI   : ';' ;
TICK     : '\'' ;
DOT_TICK : '.\'' ;

PLUS    : '+' ;
MINUS   : '-' ;
STAR    : '*' ;
SLASH   : '/' ;
PERCENT : '%' ;
CARET   : '^' ;

DOT_PLUS  : '.+' ;
DOT_MINUS : '.-' ;
DOT_STAR  : '.*' ;
DOT_SLASH : './' ;
DOT_CARET : '.^' ;

ASSIGN           : '=' ;
PLUS_ASSIGN      : '+=' ;
MINUS_ASSIGN     : '-=' ;
STAR_ASSIGN      : '*=' ;
SLASH_ASSIGN     : '/=' ;
PERCENT_ASSIGN   : '%=' ;
CARET_ASSIGN     : '^=' ;
DOT_PLUS_ASSIGN  : '.+=' ;
DOT_MINUS_ASSIGN : '.-=' ;
DOT_STAR_ASSIGN  : '.*=' ;
DOT_SLASH_ASSIGN : './=' ;
DOT_CARET_ASSIGN : '.^=' ;

DOT : '.' ;

/** A '.' must be followed by digits, so `2.*x` lexes as `2 .* x`, as in MATLAB. */
NUMBER
    : DIGITS ('.' DIGITS)? EXPONENT?
    | '.' DIGITS EXPONENT?
    ;

/** Same character classes as Java identifiers, so any Java local can be referenced by name. */
ID : ID_START ID_PART* ;

NEWLINE : ('\r'? '\n' | '\r') { if (!significantNewline()) skip(); } ;

WS           : [ \t\f]+ -> skip ;
LINE_COMMENT : '//' ~[\r\n]* -> skip ;

/** A block comment spanning lines acts as a line break, like the newline it hides. */
BLOCK_COMMENT
    : '/*' .*? '*/'
      {
          String text = getText();
          if ((text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) && significantNewline()) {
              setType(NEWLINE);
          } else {
              skip();
          }
      }
    ;

fragment ID_START : [\p{L}\p{Nl}\p{Sc}\p{Pc}] ;
fragment ID_PART  : ID_START | [\p{Nd}\p{Mn}\p{Mc}] ;
fragment DIGITS   : [0-9] ('_'? [0-9])* ;
fragment EXPONENT : [eE] [+-]? DIGITS ;
