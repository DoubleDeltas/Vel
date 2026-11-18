package com.doubledeltas.vel.core.codegen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.doubledeltas.vel.core.type.Card;

/**
 * Kotlin source that converts values at the boundary of VEL ({@link Boundary}), the counterpart of
 * {@link JavaBoundary}: lists ({@code size}, {@code get}), arrays of boxed values, component objects
 * ({@code componentN()}, constructors) and nullable values ({@link Boundary.Boxed}).
 */
public final class KotlinBoundary {

    private final StringBuilder out = new StringBuilder();
    private final String prefix;
    private int indent;
    private int counter;

    private KotlinBoundary(String prefix, int indent) {
        this.prefix = prefix;
        this.indent = indent;
    }

    // ------------------------------------------------------------------------------------------
    // Reading (abstraction)
    // ------------------------------------------------------------------------------------------

    /** Statements declaring {@code target}, the value of {@code source} as {@code b.type()}. */
    public static String read(Boundary b, String source, String target) {
        KotlinBoundary k = new KotlinBoundary(target, 0);
        k.declareRead(b, source, target);
        return k.out.toString();
    }

    private void declareRead(Boundary b, String source, String target) {
        ConcreteType t = b.type();
        String element = JavaTypes.javaName(t.element().resolution());
        String type = KotlinPrinter.type(new Code.Type(element, t.javaRank()));
        if (t.javaRank() == 0) {
            line("val " + target + ": " + type + " = " + scalar(b, source, element));
            return;
        }
        String src = temp(source);
        List<String> lengths = new ArrayList<>();
        int rank = b.dims().size();
        for (int k = 0; k < rank; k++) {
            lengths.add(temp(length(b, src, k)));
        }
        List<String> outerFirst = new ArrayList<>();
        for (int k = t.javaRank() - 1; k >= 0; k--) {
            outerFirst.add(lengths.get(k));
        }
        line("val " + target + ": " + type + " = " + newArray(element, outerFirst));
        String[] idx = new String[Math.max(rank, 2)];
        Arrays.fill(idx, "0");
        fill(b, src, 0, idx, target, t.javaRank(), element);
    }

    /** The scalar {@code source} (a primitive or a nullable one) as the primitive {@code element}. */
    private static String scalar(Boundary b, String source, String element) {
        String from = b instanceof Boundary.Boxed boxed ? boxed.primitive() : JavaTypes.javaName(b.element().resolution());
        String value = b instanceof Boundary.Boxed ? "(" + source + ")!!" : source;
        return KotlinPrinter.convert(value, from, element);
    }

    private void fill(Boundary b, String source, int offset, String[] idx, String target, int javaRank, String element) {
        switch (b) {
            case Boundary.Boxed x -> line(at(target, idx, javaRank) + " = " + scalar(x, source, element));
            case Boundary.Direct d -> {
                if (d.rank() == 0) {
                    line(at(target, idx, javaRank) + " = " + scalar(d, source, element));
                    return;
                }
                String[] loops = new String[d.rank()];
                for (int k = d.rank() - 1; k >= 0; k--) {
                    loops[k] = fresh("i");
                    String n = d.dims().get(k) instanceof Card.Finite f ? String.valueOf(f.size())
                            : source + "[0]".repeat(d.rank() - 1 - k) + ".size";
                    line("for (" + loops[k] + " in 0 until " + n + ") {");
                    indent++;
                    idx[offset + k] = loops[k];
                }
                StringBuilder e = new StringBuilder(source);
                for (int k = d.rank() - 1; k >= 0; k--) {
                    e.append('[').append(loops[k]).append(']');
                }
                String from = JavaTypes.javaName(d.element().resolution());
                line(at(target, idx, javaRank) + " = " + KotlinPrinter.convert(e.toString(), from, element));
                for (int k = 0; k < d.rank(); k++) {
                    indent--;
                    line("}");
                }
            }
            case Boundary.Sequence s -> {
                String i = fresh("i");
                String n = s.length() instanceof Card.Finite f ? String.valueOf(f.size()) : source + ".size";
                line("for (" + i + " in 0 until " + n + ") {");
                indent++;
                idx[offset + s.of().dims().size()] = i;
                fill(s.of(), temp(source + "[" + i + "]"), offset, idx, target, javaRank, element);
                indent--;
                line("}");
            }
            case Boundary.Components c -> {
                for (int n = 0; n < c.parts().size(); n++) {
                    idx[offset] = String.valueOf(n);
                    Boundary.Part p = c.parts().get(n);
                    fill(p.boundary(), temp(source + "." + p.accessor()), offset + 1, idx, target, javaRank, element);
                }
            }
        }
    }

    private static String length(Boundary b, String source, int k) {
        return switch (b) {
            case Boundary.Direct d -> d.dims().get(k) instanceof Card.Finite f ? String.valueOf(f.size())
                    : source + "[0]".repeat(d.rank() - 1 - k) + ".size";
            case Boundary.Boxed ignored -> "1";
            case Boundary.Sequence s -> {
                int inner = s.of().dims().size();
                if (k == inner) {
                    yield s.length() instanceof Card.Finite f ? String.valueOf(f.size()) : source + ".size";
                }
                yield "(if (" + source + ".size == 0) 0 else " + length(s.of(), source + "[0]", k) + ")";
            }
            case Boundary.Components c -> k == 0 ? String.valueOf(c.parts().size())
                    : length(c.parts().getFirst().boundary(), source + "." + c.parts().getFirst().accessor(), k - 1);
        };
    }

    // ------------------------------------------------------------------------------------------
    // Writing (concretization)
    // ------------------------------------------------------------------------------------------

    /** Statements that turn {@code value} (an expression of {@code b.type()}) into the declared type and return it. */
    public static String write(Boundary b, String value, String prefix) {
        KotlinBoundary k = new KotlinBoundary(prefix, 0);
        ConcreteType t = b.type();
        String v = k.fresh("r");
        k.line("val " + v + " = " + value);
        String[] idx = new String[Math.max(b.dims().size(), 2)];
        Arrays.fill(idx, "0");
        k.line("return " + k.build(b, v, t, 0, idx));
        return k.out.toString();
    }

    private String build(Boundary b, String v, ConcreteType t, int offset, String[] idx) {
        String element = JavaTypes.javaName(t.element().resolution());
        switch (b) {
            case Boundary.Boxed x -> {
                return KotlinPrinter.convert(at(v, idx, t.javaRank()), element, x.primitive());
            }
            case Boundary.Direct d -> {
                String target = JavaTypes.javaName(d.element().resolution());
                if (d.rank() == 0) {
                    return KotlinPrinter.convert(at(v, idx, t.javaRank()), element, target);
                }
                List<String> lengths = new ArrayList<>();
                for (int k = 0; k < d.rank(); k++) {
                    lengths.add(temp(lengthOfValue(v, t, offset + k)));
                }
                List<String> outerFirst = new ArrayList<>();
                for (int k = d.rank() - 1; k >= 0; k--) {
                    outerFirst.add(lengths.get(k));
                }
                String result = fresh("a");
                line("val " + result + " = " + newArray(target, outerFirst));
                String[] loops = new String[d.rank()];
                for (int k = d.rank() - 1; k >= 0; k--) {
                    loops[k] = fresh("i");
                    line("for (" + loops[k] + " in 0 until " + lengths.get(k) + ") {");
                    indent++;
                    idx[offset + k] = loops[k];
                }
                StringBuilder lhs = new StringBuilder(result);
                for (int k = d.rank() - 1; k >= 0; k--) {
                    lhs.append('[').append(loops[k]).append(']');
                }
                line(lhs + " = " + KotlinPrinter.convert(at(v, idx, t.javaRank()), element, target));
                for (int k = 0; k < d.rank(); k++) {
                    indent--;
                    line("}");
                }
                return result;
            }
            case Boundary.Sequence s -> {
                int inner = s.of().dims().size();
                String n = temp(lengthOfValue(v, t, offset + inner));
                String elementType = s.className().substring(s.className().indexOf('<') + 1, s.className().lastIndexOf('>'));
                String result = fresh("s");
                String i = fresh("i");
                if (s.kind() == Boundary.Sequence.Kind.LIST) {
                    line("val " + result + " = java.util.ArrayList<" + elementType + ">(" + n + ")");
                } else {
                    line("val " + result + " = arrayOfNulls<" + elementType + ">(" + n + ")");
                }
                line("for (" + i + " in 0 until " + n + ") {");
                indent++;
                idx[offset + inner] = i;
                String e = build(s.of(), v, t, offset, idx);
                line(s.kind() == Boundary.Sequence.Kind.LIST ? result + ".add(" + e + ")" : result + "[" + i + "] = " + e);
                indent--;
                line("}");
                if (s.kind() == Boundary.Sequence.Kind.ARRAY) {
                    return "(" + result + " as " + s.className() + ")";
                }
                return result;
            }
            case Boundary.Components c -> {
                List<String> args = new ArrayList<>();
                for (int k = 0; k < c.parts().size(); k++) {
                    idx[offset] = String.valueOf(k);
                    args.add(temp(build(c.parts().get(k).boundary(), v, t, offset + 1, idx)));
                }
                return c.className() + "(" + String.join(", ", args) + ")";
            }
        }
    }

    private static String lengthOfValue(String v, ConcreteType t, int k) {
        List<Card> dims = t.dims();
        if (k >= dims.size()) {
            return "1";
        }
        if (dims.get(k) instanceof Card.Finite f) {
            return String.valueOf(f.size());
        }
        return v + "[0]".repeat(t.javaRank() - 1 - k) + ".size";
    }

    // ------------------------------------------------------------------------------------------

    private static String newArray(String primitive, List<String> outerFirst) {
        String inner = KotlinPrinter.type(new Code.Type(primitive, 1)) + "(" + outerFirst.getLast() + ")";
        for (int k = outerFirst.size() - 2; k >= 0; k--) {
            inner = "Array(" + outerFirst.get(k) + ") { " + inner + " }";
        }
        return inner;
    }

    private static String at(String array, String[] idx, int javaRank) {
        StringBuilder s = new StringBuilder(array);
        for (int k = javaRank - 1; k >= 0; k--) {
            s.append('[').append(idx[k]).append(']');
        }
        return s.toString();
    }

    private String temp(String code) {
        if (code.matches("[\\w]+")) {
            return code;
        }
        String name = fresh("b");
        line("val " + name + " = " + code);
        return name;
    }

    private String fresh(String base) {
        return prefix + "__" + base + counter++;
    }

    private void line(String s) {
        out.append("    ".repeat(indent)).append(s).append('\n');
    }
}
