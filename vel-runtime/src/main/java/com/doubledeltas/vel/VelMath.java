package com.doubledeltas.vel;

/**
 * Helpers called by code generated from VEL. Not meant to be called directly.
 * <p>
 * Each helper implements one VEL operation for a Java type where Java has no operator for it
 * (type-system.md 3.2, java.md 7).
 */
public final class VelMath {

    private VelMath() {
    }

    // ---- A ^ n with a non-negative integer exponent: repeated multiplication -----------------------

    public static int pow(int base, int n) {
        int result = 1;
        for (int i = 0; i < n; i++) {
            result *= base;
        }
        return result;
    }

    public static long pow(long base, int n) {
        long result = 1;
        for (int i = 0; i < n; i++) {
            result *= base;
        }
        return result;
    }

    public static float pow(float base, int n) {
        float result = 1;
        for (int i = 0; i < n; i++) {
            result *= base;
        }
        return result;
    }

    public static double pow(double base, int n) {
        double result = 1;
        for (int i = 0; i < n; i++) {
            result *= base;
        }
        return result;
    }

    // ---- quotients and remainders of floating values (integers use Math.floorDiv / floorMod, / and %) ---

    /** {@code a // b}: the quotient rounded toward -infinity. */
    public static double floorDiv(double a, double b) {
        return Math.floor(a / b);
    }

    public static float floorDiv(float a, float b) {
        return (float) Math.floor(a / b);
    }

    /** {@code a /// b}: the quotient rounded toward zero. */
    public static double truncDiv(double a, double b) {
        double q = a / b;
        return q < 0 ? Math.ceil(q) : Math.floor(q);
    }

    public static float truncDiv(float a, float b) {
        return (float) truncDiv((double) a, (double) b);
    }

    /** {@code a %% b}: the remainder with the sign of the divisor, {@code a - (a // b) * b}. */
    public static double floorMod(double a, double b) {
        return a - Math.floor(a / b) * b;
    }

    public static float floorMod(float a, float b) {
        return (float) floorMod((double) a, (double) b);
    }

    // ---- error() ------------------------------------------------------------------------------------

    /**
     * {@code error()}: always throws {@link VelException}. Declared to return any type so that it can
     * stand where a value is expected (its algebra is {@code Not}).
     */
    public static <T> T error() {
        throw new VelException();
    }

    /** Reports an index outside the array (⊥, or beyond a size ⊤) as null, as VEL defines (java.md 2.2). */
    public static NullPointerException outOfRange(RuntimeException cause) {
        NullPointerException e = new NullPointerException("VEL: index out of range (null element)");
        e.initCause(cause);
        return e;
    }
}
