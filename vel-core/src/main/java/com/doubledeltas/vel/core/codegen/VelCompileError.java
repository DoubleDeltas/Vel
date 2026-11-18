package com.doubledeltas.vel.core.codegen;

import com.doubledeltas.vel.core.type.VelTypeException;

/** A type error found while generating code, with its position in the VEL source (1-based line, 0-based column). */
public final class VelCompileError extends VelTypeException {

    private final int line;
    private final int column;

    public VelCompileError(int line, int column, String message) {
        super(message);
        this.line = line;
        this.column = column;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }
}
