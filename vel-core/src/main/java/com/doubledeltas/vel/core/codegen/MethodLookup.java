package com.doubledeltas.vel.core.codegen;

import java.util.List;

/**
 * Finds the static methods of target-language classes for VEL calls {@code T.foo(x)} (java.md 3).
 * Each host (annotation processor, javac plugin, KSP, Kotlin IR) implements it over its own model.
 */
public interface MethodLookup {

    /** No class is known: every call is reported as an unknown class. */
    MethodLookup NONE = (owner, name) -> null;

    /**
     * The public static methods named {@code name} of the class written {@code owner} in VEL (a simple
     * name of {@code java.lang} or a qualified name), or null when no such class is known.
     */
    List<Method> staticMethods(String owner, String name);

    /**
     * A static method.
     *
     * @param owner  the qualified class name, as the generated code writes it
     * @param params the parameters
     * @param result the return type, or null when VEL cannot use it (void, an object, …)
     */
    record Method(String owner, String name, List<Param> params, ConcreteType result) {
    }

    /**
     * A parameter.
     *
     * @param typeName the type as a signature {@code {…}} names it, erased and qualified, e.g. {@code double},
     *                 {@code java.lang.Double}, {@code double[]}, {@code kotlin.Double?}
     * @param type     the VEL type passed directly (a primitive or an array of one), or null otherwise
     */
    record Param(String typeName, ConcreteType type) {
    }
}
