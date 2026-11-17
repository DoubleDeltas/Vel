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

    /** A line ending in a binary operator, '=', ',', ':' or '.' continues on the next line. */
    private boolean continuesLine() {
        switch (lastType) {
            case PLUS: case MINUS: case STAR: case SLASH: case SLASH_SLASH: case SLASH_SLASH_SLASH:
            case SLASH_LT: case SLASH_GT: case XOR: case XOR_ASSIGN:
            case PERCENT_PERCENT: case PERCENT_PERCENT_PERCENT: case CARET:
            case DOT_PLUS: case DOT_MINUS: case DOT_STAR: case DOT_SLASH: case DOT_CARET:
            case ASSIGN: case PLUS_ASSIGN: case MINUS_ASSIGN: case STAR_ASSIGN: case SLASH_ASSIGN:
            case SLASH_SLASH_ASSIGN: case SLASH_SLASH_SLASH_ASSIGN: case PERCENT_PERCENT_ASSIGN: case PERCENT_PERCENT_PERCENT_ASSIGN:
            case CARET_ASSIGN:
            case DOT_PLUS_ASSIGN: case DOT_MINUS_ASSIGN: case DOT_STAR_ASSIGN: case DOT_SLASH_ASSIGN:
            case DOT_CARET_ASSIGN:
            case COMMA: case COLON: case DOT:
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
    | pattern '=' expr                                      # destructuringStmt
    | target op=( '='
                | '+=' | '-=' | '*=' | '/=' | '//=' | '///=' | '%%=' | '%%%=' | '^='
                | '.+=' | '.-=' | '.*=' | './=' | '.^=' | 'o+=' ) expr  # assignStmt
    | expr                                                  # exprStmt
    ;

/**
 * Assignment target: a variable, or an element / sub-matrix of one (`m[i, j]`, `m[0:2, :]`).
 * Member targets (`v.x = ...`) are not allowed.
 * All values are immutable: `m[i] = x` binds `m` to a modified copy (copy and rewrite, as in MATLAB).
 */
target
    : (ID | QUOTED_ID) ('[' indexList ']')?
    ;

/**
 * Destructuring pattern, shaped like the tuple and matrix literals: each target receives one element.
 * `(x, y, z) = v` takes a 3x1 value, `[a, b; c, d] = m` a 2x2 value.
 * `?` discards the element at its position (it still counts for the shape).
 */
pattern
    : '(' patternItem (',' patternItem)+ ')'                # tuplePattern
    | '[' patternRow (';' patternRow)* ']'                  # matrixPattern
    ;

patternRow
    : patternItem (',' patternItem)*
    ;

patternItem
    : target                                                # bindItem
    | '?'                                                   # discardItem
    ;

// Alternatives are listed from highest to lowest precedence.
//
// Operators follow MATLAB:
//   *  /  ^    matrix product / right division / matrix power (scalar operands: ordinary arithmetic;
//              `/` on scalars is true division)
//   />         right division A/>B = A B^-1 (B square), same as /
//   /<         left division  A/<B = A^-1 B (A square)
//   .* ./ .^   element-wise
//   .+ .-      aliases of + and - (which are already element-wise), for symmetry
//   '          conjugate transpose (same as .' for real values)
//   .'         transpose
//   a:b        inclusive range of indices (lowest precedence: `a+1:b-1` == `(a+1):(b-1)`).
//              There is no step form a:s:b, and a range cannot contain a range.
// Bool:
//   o+         exclusive or (⊕), additive precedence; + is OR and * is AND on Bool
// Integer-style division comes in two pairs; the number of symbols tells the rounding:
//   // and %%    floor: quotient toward -infinity, remainder has the divisor's sign
//                (Python // and %, Java Math.floorDiv / Math.floorMod)
//   /// and %%%  truncate: quotient toward zero, remainder has the dividend's sign
//                (Java integer / and %)
// A single % does not exist, so Java's `a % b` must be written `a %%% b`.
expr
    : primary                                               # primaryExpr
    | expr '.' ID '(' arguments? ')'                        # methodCallExpr
    | expr '.' ID                                           # memberExpr
    | expr '[' indexList ']'                                # indexExpr
    | expr op=('\'' | '.\'')                                # transposeExpr
    | <assoc=right> expr op=('^' | '.^') expr               # powExpr
    | op=('-' | '+') expr                                   # unaryExpr
    | expr op=('*' | '/' | '/>' | '/<' | '//' | '///' | '%%' | '%%%' | '.*' | './') expr  # mulExpr
    | expr op=('+' | '-' | '.+' | '.-' | 'o+') expr         # addExpr
    | expr ':' expr                                         # rangeExpr
    ;

primary
    : NUMBER                                                # numberLiteral
    | ('true' | 'false')                                    # boolLiteral
    | (ID | QUOTED_ID) '(' arguments? ')'                   # callExpr
    | ID                                                    # identifier
    | QUOTED_ID                                             # quotedIdentifier
    | '(' expr ')'                                          # parenExpr
    | '(' expr (',' expr)+ ')'                              # tupleLiteral
    | '[' matrixRow (';' matrixRow)* ']'                    # matrixLiteral
    ;

arguments
    : expr (',' expr)*
    ;

/** MATLAB-style subscripts: an index, a range (`1:3`, `0:end`), or `:` for the whole dimension. */
indexList
    : index (',' index)*
    ;

index
    : ':'                                                   # wholeIndex
    | expr                                                  # exprIndex
    ;

/**
 * Row-major literal: `[1, 2, 3]` is a 1x3 row vector, `[1, 2; 3, 4]` is a 2x2 matrix.
 * Elements may themselves be matrices (block matrix): `[A, B; C, D]`.
 * A column vector is written as a tuple: `(x, y, z)` == `[x; y; z]` (3x1).
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
//
// Only Java keywords are reserved (`return`, `true`, `false`), so every Java identifier can be
// referenced by name. `end` (inside subscripts) and `j` (imaginary unit) are predeclared names,
// lexed as ordinary identifiers and resolved by the semantic analysis. As in Kotlin, a name in
// backticks (QUOTED_ID) bypasses the predeclared names: plain j is the imaginary unit, while
// j wrapped in backticks is the Java variable named j.
// ---------------------------------------------------------------------------

RETURN : 'return' ;
TRUE   : 'true' ;
FALSE  : 'false' ;

LPAREN : '(' { nesting++; } ;
RPAREN : ')' { if (nesting > 0) nesting--; } ;
LBRACK : '[' { nesting++; } ;
RBRACK : ']' { if (nesting > 0) nesting--; } ;

COMMA    : ',' ;
QUESTION : '?' ;
SEMI     : ';' ;
COLON    : ':' ;
TICK     : '\'' ;
DOT_TICK : '.\'' ;

PLUS            : '+' ;
MINUS           : '-' ;
STAR            : '*' ;
SLASH           : '/' ;
SLASH_GT        : '/>' ;
SLASH_LT        : '/<' ;
SLASH_SLASH       : '//' ;
SLASH_SLASH_SLASH : '///' ;
PERCENT_PERCENT         : '%%' ;
PERCENT_PERCENT_PERCENT : '%%%' ;
CARET           : '^' ;

DOT_PLUS  : '.+' ;
DOT_MINUS : '.-' ;
DOT_STAR  : '.*' ;
DOT_SLASH : './' ;
DOT_CARET : '.^' ;

ASSIGN                 : '=' ;
PLUS_ASSIGN            : '+=' ;
MINUS_ASSIGN           : '-=' ;
STAR_ASSIGN            : '*=' ;
SLASH_ASSIGN           : '/=' ;
SLASH_SLASH_ASSIGN       : '//=' ;
SLASH_SLASH_SLASH_ASSIGN : '///=' ;
PERCENT_PERCENT_ASSIGN         : '%%=' ;
PERCENT_PERCENT_PERCENT_ASSIGN : '%%%=' ;
CARET_ASSIGN           : '^=' ;
DOT_PLUS_ASSIGN        : '.+=' ;
DOT_MINUS_ASSIGN       : '.-=' ;
DOT_STAR_ASSIGN        : '.*=' ;
DOT_SLASH_ASSIGN       : './=' ;
DOT_CARET_ASSIGN       : '.^=' ;
XOR_ASSIGN             : 'o+=' ;

/**
 * Bool exclusive or (⊕, "o-plus"). Being longer than the identifier `o`, it wins when a variable
 * named `o` is directly followed by '+': write `o + 1` (or `o`+1 with backticks) in that case.
 */
XOR : 'o+' ;

DOT : '.' ;

/** A '.' must be followed by digits, so `2.*x` lexes as `2 .* x`, as in MATLAB. */
NUMBER
    : DIGITS ('.' DIGITS)? EXPONENT?
    | '.' DIGITS EXPONENT?
    ;

/** Same character classes as Java identifiers, so any Java local can be referenced by name. */
ID : ID_START ID_PART* ;

/** A Java identifier in backticks: never resolved to a predeclared name such as `j` or `end`. */
QUOTED_ID : '`' ID_START ID_PART* '`' ;

NEWLINE : ('\r'? '\n' | '\r') { if (!significantNewline()) skip(); } ;

WS : [ \t\f]+ -> skip ;

/** `#` starts a line comment (`//` and `///` are quotient operators). */
LINE_COMMENT : '#' ~[\r\n]* -> skip ;

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

/**
 * Java identifier characters (Character.isJavaIdentifierStart / isJavaIdentifierPart):
 * '$' and '_' are listed explicitly; they are also covered by \p{Sc} (currency) and \p{Pc}.
 */
fragment ID_START : [$_\p{L}\p{Nl}\p{Sc}\p{Pc}] ;
fragment ID_PART  : ID_START | [\p{Nd}\p{Mn}\p{Mc}] ;
fragment DIGITS   : [0-9] ('_'? [0-9])* ;
fragment EXPONENT : [eE] [+-]? DIGITS ;
