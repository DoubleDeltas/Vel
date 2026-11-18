package com.doubledeltas.vel.javac;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;

/**
 * Handles inline {@code vel("...")} expressions (style C). Enabled with {@code -Xplugin:Vel}.
 * <p>
 * Before javac attributes a compilation unit, every call to {@code VelInline.vel} with a string
 * literal is replaced by the Java code generated from the VEL expression (see {@link InlineRewriter}).
 * Javac then type-checks and compiles that code like any other.
 */
public final class VelJavacPlugin implements Plugin {

    static final String INLINE_CLASS = "com.doubledeltas.vel.VelInline";
    static final String INLINE_METHOD = "vel";

    @Override
    public String getName() {
        return "Vel";
    }

    @Override
    public boolean autoStart() {
        // Lets the plugin run without -Xplugin when found on the processor path (JDK 14+).
        return true;
    }

    @Override
    public void init(JavacTask task, String... args) {
        // The rewriter uses jdk.compiler internals: open them before its class is linked.
        JavacInternals.open();
        InlineRewriter.install(task);
    }
}
