package com.doubledeltas.vel.javac;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

import com.doubledeltas.vel.core.VelDiagnostic;
import com.doubledeltas.vel.core.VelFrontend;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

/**
 * Handles inline {@code vel("...")} expressions (style C). Enabled with {@code -Xplugin:Vel}.
 * <p>
 * Current stage: after attribution, finds calls to {@code VelInline.vel}, checks that the
 * argument is a string literal and that it parses. Next stage: type-check against the
 * attributed types of referenced locals and replace the call with generated code.
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
        Trees trees = Trees.instance(task);
        task.addTaskListener(new TaskListener() {
            @Override
            public void finished(TaskEvent e) {
                if (e.getKind() == TaskEvent.Kind.ANALYZE) {
                    new InlineScanner(trees, e.getCompilationUnit()).scan(e.getCompilationUnit(), null);
                }
            }
        });
    }

    private static final class InlineScanner extends TreePathScanner<Void, Void> {
        private final Trees trees;
        private final CompilationUnitTree unit;

        InlineScanner(Trees trees, CompilationUnitTree unit) {
            this.trees = trees;
            this.unit = unit;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            if (isVelInline(trees.getElement(getCurrentPath()))) {
                handle(node);
            }
            return super.visitMethodInvocation(node, unused);
        }

        private void handle(MethodInvocationTree call) {
            ExpressionTree arg = call.getArguments().getFirst();
            if (!(arg instanceof LiteralTree lit) || !(lit.getValue() instanceof String source)) {
                trees.printMessage(Diagnostic.Kind.ERROR,
                        "vel(...) argument must be a string literal", arg, unit);
                return;
            }
            var result = VelFrontend.parseExpression(source);
            for (VelDiagnostic d : result.diagnostics()) {
                trees.printMessage(d.severity() == VelDiagnostic.Severity.ERROR
                        ? Diagnostic.Kind.ERROR : Diagnostic.Kind.WARNING, d.format(), arg, unit);
            }
            // TODO: resolve referenced names via trees.getScope(getCurrentPath()) and rewrite the call.
        }

        private static boolean isVelInline(Element element) {
            return element instanceof ExecutableElement method
                    && method.getKind() == ElementKind.METHOD
                    && method.getSimpleName().contentEquals(INLINE_METHOD)
                    && method.getEnclosingElement() instanceof TypeElement owner
                    && owner.getQualifiedName().contentEquals(INLINE_CLASS);
        }
    }
}
