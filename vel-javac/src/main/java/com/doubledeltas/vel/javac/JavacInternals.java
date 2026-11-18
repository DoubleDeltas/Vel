package com.doubledeltas.vel.javac;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Makes the jdk.compiler internals used by {@link InlineRewriter} accessible to this plugin.
 * <p>
 * Rewriting trees needs {@code com.sun.tools.javac.*}, which jdk.compiler does not export. Asking users
 * to pass {@code --add-exports} to javac (and to Gradle's compiler JVM) would be fragile, so the plugin
 * exports the packages to its own module at startup, as other javac plugins (e.g. Lombok) do. This
 * relies on {@code sun.misc.Unsafe} and may need revisiting on future JDKs.
 */
final class JavacInternals {

    private static final String[] PACKAGES = {
            "com.sun.tools.javac.api",
            "com.sun.tools.javac.code",
            "com.sun.tools.javac.parser",
            "com.sun.tools.javac.tree",
            "com.sun.tools.javac.util",
    };

    private JavacInternals() {
    }

    static void open() {
        Module compiler = ModuleLayer.boot().findModule("jdk.compiler").orElse(null);
        Module self = JavacInternals.class.getModule();
        if (compiler == null) {
            return;
        }
        boolean exported = true;
        for (String pkg : PACKAGES) {
            exported &= compiler.isExported(pkg, self);
        }
        if (exported) {
            return; // e.g. javac was run with --add-exports
        }
        try {
            Method addExports = Module.class.getDeclaredMethod("implAddExports", String.class, Module.class);
            forceAccessible(addExports);
            for (String pkg : PACKAGES) {
                addExports.invoke(compiler, pkg, self);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("VEL: cannot access the javac internals the vel(...) rewriter needs."
                    + " Run javac with --add-exports jdk.compiler/<package>=ALL-UNNAMED for " + String.join(", ", PACKAGES), e);
        }
    }

    /** Sets {@code AccessibleObject.override}, whose offset matches the first field of {@link Layout}. */
    private static void forceAccessible(AccessibleObject target) throws ReflectiveOperationException {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        long offset = (long) unsafeClass.getMethod("objectFieldOffset", Field.class)
                .invoke(unsafe, Layout.class.getDeclaredField("override"));
        unsafeClass.getMethod("putBoolean", Object.class, long.class, boolean.class).invoke(unsafe, target, offset, true);
    }

    /** Same field layout as the start of {@link AccessibleObject}. */
    @SuppressWarnings("unused")
    private static final class Layout {
        boolean override;
    }
}
