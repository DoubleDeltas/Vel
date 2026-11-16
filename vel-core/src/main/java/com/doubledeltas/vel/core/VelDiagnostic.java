package com.doubledeltas.vel.core;

/**
 * A problem found in VEL source.
 *
 * @param line   1-based line within the VEL source
 * @param column 0-based column within the line
 */
public record VelDiagnostic(Severity severity, int line, int column, String message) {

    public enum Severity { ERROR, WARNING }

    public static VelDiagnostic error(int line, int column, String message) {
        return new VelDiagnostic(Severity.ERROR, line, column, message);
    }

    /** {@code "VEL 2:5: message"} — prefix used when reporting through javac. */
    public String format() {
        return "VEL " + line + ":" + column + ": " + message;
    }
}
