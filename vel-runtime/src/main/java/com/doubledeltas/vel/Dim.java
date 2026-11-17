package com.doubledeltas.vel;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the compile-time size of each dimension of a Java array or {@code List} used from VEL.
 * <p>
 * Without {@code @Dim}, every dimension is {@link #TOP} (unknown until runtime).
 * Exactly one of {@link #value()}, {@link #vel()} or {@link #java()} must be given; giving more than
 * one is a compile error. {@code value} and {@code vel} are in VEL order, {@code java} in Java order;
 * the two orders are reversed because a Java {@code F[m][n]} is the VEL matrix {@code Mat(n, m)}:
 *
 * <pre>{@code
 * @Dim(3)                      double[]   v   // VEL: Vec(3)
 * @Dim({3, 4})                 double[][] m   // VEL order -> Mat(3, 4)
 * @Dim(vel  = {3, 4})          double[][] m   // same as above
 * @Dim(java = {4, 3})          double[][] m   // 4 arrays of length 3 -> Mat(3, 4)
 * @Dim(java = {Dim.TOP, 3})    double[][] rs  // outer length unknown -> Mat(3, ⊤)
 * }</pre>
 *
 * Known sizes let VEL unroll loops, drop temporaries and check shapes at compile time.
 * They are trusted, not checked at runtime.
 * <p>
 * Retained in class files so that the sizes of fields and method signatures in already compiled
 * code are visible when other sources are compiled.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.LOCAL_VARIABLE, ElementType.METHOD})
public @interface Dim {

    /** Marks a dimension whose size is unknown at compile time (⊤). */
    int TOP = -1;

    /** Sizes in VEL order (same as {@link #vel()}): rows first ({@code Mat(m, n)} is {@code {m, n}}). */
    int[] value() default {};

    /** Sizes in VEL order: rows first ({@code Mat(m, n)} is {@code {m, n}}). */
    int[] vel() default {};

    /** Sizes in Java declaration order: outermost array first. */
    int[] java() default {};
}
