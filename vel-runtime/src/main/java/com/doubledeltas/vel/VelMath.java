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

    // ---- unsigned ----------------------------------------------------------------------------------

    /** The value of the unsigned 64-bit integer stored in {@code bits}, rounded to the nearest double. */
    public static double unsignedToDouble(long bits) {
        if (bits >= 0) {
            return bits;
        }
        // halve (keeping the lowest bit so that rounding stays correct), convert, double
        return ((bits >>> 1) | (bits & 1)) * 2.0;
    }

    // ---- arrays -------------------------------------------------------------------------------------

    /**
     * A new zero-filled array of {@code lengths.length} levels (outermost first) whose elements are of
     * the primitive with the given JVM descriptor ({@code 'D'} double, {@code 'F'}, {@code 'J'} long,
     * {@code 'I'}, {@code 'S'}, {@code 'B'}, {@code 'C'}, {@code 'Z'} boolean). Used where the target
     * has no multi-dimensional array creation (Kotlin IR).
     */
    public static Object newArray(char primitive, int... lengths) {
        Class<?> element = switch (primitive) {
            case 'D' -> double.class;
            case 'F' -> float.class;
            case 'J' -> long.class;
            case 'I' -> int.class;
            case 'S' -> short.class;
            case 'B' -> byte.class;
            case 'C' -> char.class;
            case 'Z' -> boolean.class;
            default -> throw new IllegalArgumentException("not a primitive descriptor: " + primitive);
        };
        return java.lang.reflect.Array.newInstance(element, lengths);
    }

    /**
     * A new array of the wrappers of the primitive with the given JVM descriptor (see
     * {@link #newArray}), e.g. {@code Double[]} for Kotlin {@code Array<Double>}.
     */
    public static Object[] newBoxedArray(char primitive, int length) {
        Class<?> element = switch (primitive) {
            case 'D' -> Double.class;
            case 'F' -> Float.class;
            case 'J' -> Long.class;
            case 'I' -> Integer.class;
            case 'S' -> Short.class;
            case 'B' -> Byte.class;
            case 'C' -> Character.class;
            case 'Z' -> Boolean.class;
            default -> throw new IllegalArgumentException("not a primitive descriptor: " + primitive);
        };
        return (Object[]) java.lang.reflect.Array.newInstance(element, length);
    }

    /**
     * A new array whose elements have the class of {@code prototype}, e.g. Kotlin {@code Array<UIntArray>}
     * (an array of boxed {@code UIntArray} objects).
     */
    public static Object[] newArrayLike(Object prototype, int length) {
        return (Object[]) java.lang.reflect.Array.newInstance(prototype.getClass(), length);
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
