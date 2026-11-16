package com.doubledeltas.vel;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the body of an abstract method in VEL.
 * <p>
 * The annotation processor ({@code vel-processor}) parses {@link #value()} and generates
 * an implementation of the enclosing interface / abstract class.
 *
 * <pre>{@code
 * public interface Physics {
 *     @Vel("""
 *         return p + v * dt + 0.5 * a * dt^2
 *         """)
 *     Vec3 step(Vec3 p, Vec3 v, Vec3 a, double dt);
 * }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Vel {
    /** VEL source code of the method body. Method parameters are visible by name. */
    String value();
}
