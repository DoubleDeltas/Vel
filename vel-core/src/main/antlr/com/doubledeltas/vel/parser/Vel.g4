grammar Vel;

@lexer::members {
    /**
     * Open brackets, innermost last. Newlines inside (), [] are insignificant, so expressions can span
     * lines; inside a block {} they separate statements again, even when the block is inside ().
     */
    private final java.util.ArrayDeque<Integer> brackets = new java.util.ArrayDeque<>();

    private void open(int type) {
        brackets.push(type);
    }

    private void close() {
        brackets.poll();
    }

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
            case IF: case THEN: case ELSE:
            case EQ: case NE: case LT: case LE: case GT: case GE: case ARROW:
            case LBRACE:
                return true;
            default:
                return false;
        }
    }

    /** Whether a line break at this point is a statement separator. */
    private boolean significantNewline() {
        Integer innermost = brackets.peek();
        return (innermost == null || innermost == LBRACE) && !continuesLine();
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
    // Statement form of if, without else: `if n == 0 then return 0` (inside a block)
    | 'if' expr NEWLINE* 'then' statement                   # ifStmt
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
//   a:b        range of indices [a, b), end-exclusive as in Python
//              (lowest precedence: `a+1:b-1` == `(a+1):(b-1)`).
//   a:b:c…     cut points of a partition, only as a subscript: m[0:2:3, 0:1:3] splits m into blocks
//              [0,2)x[0,1), [0,2)x[1,3), [2,3)x[0,1), [2,3)x[1,3) (checked by StructureChecker)
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
    | expr '.' ID signature? '(' arguments? ')'             # methodCallExpr
    // Element-wise application `t.foo.(x)`, `Math.sqrt.(v)` (Julia-style dot call)
    | expr '.' ID signature? '.' '(' arguments? ')'         # broadcastMethodCallExpr
    // Reference to one overload as a function value: `Math.abs{double}`
    | expr '.' ID signature                                 # methodRefExpr
    | expr '.' ID                                           # memberExpr
    | expr '[' indexList ']'                                # indexExpr
    | expr op=('\'' | '.\'')                                # transposeExpr
    | <assoc=right> expr op=('^' | '.^') expr               # powExpr
    | op=('-' | '+') expr                                   # unaryExpr
    | expr op=('*' | '/' | '/>' | '/<' | '//' | '///' | '%%' | '%%%' | '.*' | './') expr  # mulExpr
    | expr op=('+' | '-' | '.+' | '.-' | 'o+') expr         # addExpr
    | expr ':' expr                                         # rangeExpr
    // Comparisons give Bool, element by element (type-system.md 3.6)
    | expr op=('==' | '!=' | '<' | '<=' | '>' | '>=') expr   # compareExpr
    // Conditional expression: the else branch extends as far as possible.
    // Only the chosen branch is evaluated. Line breaks before `then` and `else` are allowed.
    | 'if' expr NEWLINE* 'then' expr NEWLINE* 'else' expr   # conditionalExpr
    // Function literal, the lowest precedence: `(a: Real, b: Real) => sqrt(a^2 + b^2)`, `i => x[i]`,
    // `(n: Card) => { ... }` (type-system.md 1.10)
    | parameters '=>' (expr | block)                        # functionExpr
    ;

/** Parameters of a function literal: one untyped name, or a parenthesized list. */
parameters
    : (ID | QUOTED_ID)                                      # singleParameter
    | '(' (parameter (',' parameter)*)? ')'                 # parameterList
    ;

/** A name or a destructuring pattern, with its algebra (omitted when it can be inferred from the use). */
parameter
    : (ID | QUOTED_ID | pattern) (':' algebra)?
    ;

/** An algebra written in VEL: `Real`, `Card`, `Vec(2, Real)`, `Mat(inf, 3, Complex)`. */
algebra
    : (ID | QUOTED_ID) ('(' arguments ')')?
    ;

/** A block body: statements separated by newlines or ';', the result given by return. */
block
    : '{' sep* (statement (sep+ statement)*)? sep* '}'
    ;

primary
    : NUMBER                                                # numberLiteral
    | ('true' | 'false')                                    # boolLiteral
    | (ID | QUOTED_ID) signature? '(' arguments? ')'        # callExpr
    // Element-wise application `f.(x)`: f is applied to each element (type-system.md 1.9)
    | (ID | QUOTED_ID) signature? '.' '(' arguments? ')'    # broadcastCallExpr
    // Reference to one overload as a function value: `max{int, int}`
    | (ID | QUOTED_ID) signature                            # functionRef
    | ID                                                    # identifier
    | QUOTED_ID                                             # quotedIdentifier
    | '(' expr ')'                                          # parenExpr
    | '(' expr (',' expr)+ ')'                              # tupleLiteral
    | '[' matrixRow (';' matrixRow)* ']'                    # matrixLiteral
    ;

arguments
    : expr (',' expr)*
    ;

/**
 * Selects one overload of a target-language method by its parameter types, written as types of the
 * target language: `Math.abs{double}`, `Math.max{int, int}`, `v.norm{}` (java.md 3.1).
 */
signature
    : '{' (typeName (',' typeName)*)? '}'
    ;

/** A target-language type: a (qualified) name with array brackets, e.g. `double`, `java.math.BigDecimal`, `double[][]`. */
typeName
    : (ID | QUOTED_ID) ('.' (ID | QUOTED_ID))* ('[' ']')*
    ;

/** Subscripts: an index, a range (`1:3` = [1, 3)), cut points (`0:2:3`), or `:` for the whole dimension. */
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
 * A tuple stacks its items along a new first dimension: `(x, y, z)` of scalars is the 3x1 column
 * `[x; y; z]`, `(u, v)` of vectors of length n is a 2xn matrix with u and v as rows.
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
// Reserved words are `return`, `true`, `false`, `if`, `else` (all Java keywords) and `then` (the only
// one that is not; a Java variable named then must be written in backticks). Every other Java identifier can be
// referenced by name. `end` (inside subscripts: the length of the dimension), `inf` (the cardinal ⊤) and `j` (imaginary unit) are predeclared names,
// lexed as ordinary identifiers and resolved by the semantic analysis. As in Kotlin, a name in
// backticks (QUOTED_ID) bypasses the predeclared names: plain j is the imaginary unit, while
// j wrapped in backticks is the Java variable named j.
// ---------------------------------------------------------------------------

RETURN : 'return' ;
IF     : 'if' ;
THEN   : 'then' ;   // not a Java keyword: a Java variable named `then` is written in backticks
ELSE   : 'else' ;
TRUE   : 'true' ;
FALSE  : 'false' ;

LPAREN : '(' { open(LPAREN); } ;
RPAREN : ')' { close(); } ;
LBRACK : '[' { open(LBRACK); } ;
RBRACK : ']' { close(); } ;
LBRACE : '{' { open(LBRACE); } ;
RBRACE : '}' { close(); } ;

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

EQ    : '==' ;
NE    : '!=' ;
LT    : '<' ;
LE    : '<=' ;
GT    : '>' ;
GE    : '>=' ;
ARROW : '=>' ;

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
