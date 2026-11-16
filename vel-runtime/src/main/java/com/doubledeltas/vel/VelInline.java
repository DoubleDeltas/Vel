package com.doubledeltas.vel;

/**
 * Entry point for inline VEL expressions inside ordinary Java code.
 *
 * <pre>{@code
 * import static com.doubledeltas.vel.VelInline.vel;
 *
 * Vec3 next = vel("p + v * dt");
 * }</pre>
 *
 * Calls to {@link #vel(String)} are rewritten at compile time by the VEL javac plugin
 * ({@code -Xplugin:Vel}). Local variables, parameters and fields are referenced by name.
 */
public final class VelInline {

    private VelInline() {
    }

    /**
     * Placeholder replaced at compile time. Reaching this method at runtime means the
     * VEL javac plugin was not enabled for the compilation.
     *
     * @param source VEL expression; must be a compile-time constant string
     */
    public static <T> T vel(String source) {
        throw new IllegalStateException(
                "VEL expression was not compiled. Add the vel-javac plugin to the annotation "
                        + "processor path and pass -Xplugin:Vel to javac. Source: " + source);
    }
}
