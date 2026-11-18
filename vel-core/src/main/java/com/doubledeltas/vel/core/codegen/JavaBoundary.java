package com.doubledeltas.vel.core.codegen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.doubledeltas.vel.core.type.Card;

/**
 * Java source that converts values at the boundary of VEL ({@link Boundary}): reading a parameter or
 * variable into the primitive (array) generated code works with, and building the declared type
 * from a result.
 */
public final class JavaBoundary {

    private final StringBuilder out = new StringBuilder();
    private final String prefix;
    private int indent;
    private int counter;

    private JavaBoundary(String prefix, int indent) {
        this.prefix = prefix;
        this.indent = indent;
    }

    // ------------------------------------------------------------------------------------------
    // Reading (abstraction)
    // ------------------------------------------------------------------------------------------

    /** Statements declaring {@code target}, the value of {@code source} as {@code b.type()}. */
    public static String read(Boundary b, String source, String target) {
        JavaBoundary j = new JavaBoundary(target, 0);
        j.declareRead(b, source, target);
        return j.out.toString();
    }

    private void declareRead(Boundary b, String source, String target) {
        ConcreteType t = b.type();
        String element = JavaTypes.storage(JavaTypes.javaName(t.element().resolution()));
        if (t.javaRank() == 0) {
            line("final " + element + " " + target + " = " + scalar(b, source, element) + ";");
            return;
        }
        String src = temp("var", source);
        List<String> lengths = new ArrayList<>();
        int rank = b.dims().size();
        for (int k = 0; k < rank; k++) {
            lengths.add(temp("int", length(b, src, k)));
        }
        StringBuilder sizes = new StringBuilder();
        for (int k = t.javaRank() - 1; k >= 0; k--) {
            sizes.append('[').append(lengths.get(k)).append(']');
        }
        line("final " + t.javaName() + " " + target + " = new " + element + sizes + ";");
        String[] idx = new String[Math.max(rank, 2)];
        Arrays.fill(idx, "0");
        fill(b, src, 0, idx, target, t.javaRank(), element);
    }

    /** The scalar {@code source} (a primitive or a wrapper) as the primitive {@code element}. */
    private static String scalar(Boundary b, String source, String element) {
        String value = b instanceof Boundary.Boxed boxed ? source + "." + boxed.primitive() + "Value()" : source;
        String from = b instanceof Boundary.Boxed boxed ? boxed.primitive()
                : JavaTypes.storage(JavaTypes.javaName(b.element().resolution()));
        return from.equals(element) ? value : "((" + element + ") " + value + ")";
    }

    /** Copies the elements of {@code source} (a value of {@code b}) into {@code target} at VEL dimensions {@code offset}…. */
    private void fill(Boundary b, String source, int offset, String[] idx, String target, int javaRank, String element) {
        switch (b) {
            case Boundary.Boxed x -> line(at(target, idx, javaRank) + " = " + scalar(x, source, element) + ";");
            case Boundary.Direct d -> {
                if (d.rank() == 0) {
                    line(at(target, idx, javaRank) + " = " + scalar(d, source, element) + ";");
                    return;
                }
                String[] loops = new String[d.rank()];
                for (int k = d.rank() - 1; k >= 0; k--) {
                    loops[k] = fresh("i");
                    String n = d.dims().get(k) instanceof Card.Finite f ? String.valueOf(f.size())
                            : source + "[0]".repeat(d.rank() - 1 - k) + ".length";
                    line("for (int " + loops[k] + " = 0; " + loops[k] + " < " + n + "; " + loops[k] + "++) {");
                    indent++;
                    idx[offset + k] = loops[k];
                }
                StringBuilder e = new StringBuilder(source);
                for (int k = d.rank() - 1; k >= 0; k--) {
                    e.append('[').append(loops[k]).append(']');
                }
                String from = JavaTypes.storage(JavaTypes.javaName(d.element().resolution()));
                String value = from.equals(element) ? e.toString() : "((" + element + ") " + e + ")";
                line(at(target, idx, javaRank) + " = " + value + ";");
                for (int k = 0; k < d.rank(); k++) {
                    indent--;
                    line("}");
                }
            }
            case Boundary.Sequence s -> {
                String i = fresh("i");
                String n = s.length() instanceof Card.Finite f ? String.valueOf(f.size()) : size(s, source);
                line("for (int " + i + " = 0; " + i + " < " + n + "; " + i + "++) {");
                indent++;
                idx[offset + s.of().dims().size()] = i;
                fill(s.of(), temp("var", get(s, source, i)), offset, idx, target, javaRank, element);
                indent--;
                line("}");
            }
            case Boundary.Components c -> {
                for (int n = 0; n < c.parts().size(); n++) {
                    idx[offset] = String.valueOf(n);
                    Boundary.Part p = c.parts().get(n);
                    fill(p.boundary(), temp("var", source + "." + p.accessor()), offset + 1, idx, target, javaRank, element);
                }
            }
        }
    }

    /** The length of VEL dimension {@code k} of a value of {@code b} held in {@code source}. */
    private static String length(Boundary b, String source, int k) {
        return switch (b) {
            case Boundary.Direct d -> d.dims().get(k) instanceof Card.Finite f ? String.valueOf(f.size())
                    : source + "[0]".repeat(d.rank() - 1 - k) + ".length";
            case Boundary.Boxed ignored -> "1";
            case Boundary.Sequence s -> {
                int inner = s.of().dims().size();
                if (k == inner) {
                    yield s.length() instanceof Card.Finite f ? String.valueOf(f.size()) : size(s, source);
                }
                yield "(" + size(s, source) + " == 0 ? 0 : " + length(s.of(), get(s, source, "0"), k) + ")";
            }
            case Boundary.Components c -> k == 0 ? String.valueOf(c.parts().size())
                    : length(c.parts().getFirst().boundary(), source + "." + c.parts().getFirst().accessor(), k - 1);
        };
    }

    private static String size(Boundary.Sequence s, String source) {
        return s.kind() == Boundary.Sequence.Kind.LIST ? source + ".size()" : source + ".length";
    }

    private static String get(Boundary.Sequence s, String source, String i) {
        return s.kind() == Boundary.Sequence.Kind.LIST ? source + ".get(" + i + ")" : source + "[" + i + "]";
    }

    // ------------------------------------------------------------------------------------------
    // Writing (concretization)
    // ------------------------------------------------------------------------------------------

    /**
     * Statements that turn {@code value} (an expression of {@code b.type()}) into the declared type and
     * end with {@code keyword} ({@code return} or {@code yield}) of it.
     */
    public static String write(Boundary b, String value, String keyword, String prefix) {
        JavaBoundary j = new JavaBoundary(prefix, 1);
        ConcreteType t = b.type();
        String v = j.fresh("r");
        j.line("final " + t.javaName() + " " + v + " = " + value + ";");
        String[] idx = new String[Math.max(b.dims().size(), 2)];
        Arrays.fill(idx, "0");
        String result = j.build(b, v, t, 0, idx);
        j.line(keyword + " " + result + ";");
        return "{\n" + j.out + "}";
    }

    /** An expression of the declared type for the part of {@code v} at VEL dimensions {@code offset}…. */
    private String build(Boundary b, String v, ConcreteType t, int offset, String[] idx) {
        String element = JavaTypes.storage(JavaTypes.javaName(t.element().resolution()));
        switch (b) {
            case Boundary.Boxed x -> {
                String e = at(v, idx, t.javaRank());
                String value = x.primitive().equals(element) ? e : "((" + x.primitive() + ") " + e + ")";
                return JavaTypes.boxed(x.primitive()) + ".valueOf(" + value + ")";
            }
            case Boundary.Direct d -> {
                String target = JavaTypes.storage(JavaTypes.javaName(d.element().resolution()));
                if (d.rank() == 0) {
                    String e = at(v, idx, t.javaRank());
                    return target.equals(element) ? e : "((" + target + ") " + e + ")";
                }
                String result = fresh("a");
                StringBuilder sizes = new StringBuilder();
                List<String> lengths = new ArrayList<>();
                for (int k = 0; k < d.rank(); k++) {
                    lengths.add(temp("int", lengthOfValue(v, t, offset + k)));
                }
                for (int k = d.rank() - 1; k >= 0; k--) {
                    sizes.append('[').append(lengths.get(k)).append(']');
                }
                line("final " + target + "[]".repeat(d.rank()) + " " + result + " = new " + target + sizes + ";");
                String[] loops = new String[d.rank()];
                for (int k = d.rank() - 1; k >= 0; k--) {
                    loops[k] = fresh("i");
                    line("for (int " + loops[k] + " = 0; " + loops[k] + " < " + lengths.get(k) + "; " + loops[k] + "++) {");
                    indent++;
                    idx[offset + k] = loops[k];
                }
                StringBuilder lhs = new StringBuilder(result);
                for (int k = d.rank() - 1; k >= 0; k--) {
                    lhs.append('[').append(loops[k]).append(']');
                }
                String e = at(v, idx, t.javaRank());
                line(lhs + " = " + (target.equals(element) ? e : "((" + target + ") " + e + ")") + ";");
                for (int k = 0; k < d.rank(); k++) {
                    indent--;
                    line("}");
                }
                return result;
            }
            case Boundary.Sequence s -> {
                int inner = s.of().dims().size();
                String n = temp("int", lengthOfValue(v, t, offset + inner));
                String result = fresh("s");
                String i = fresh("i");
                if (s.kind() == Boundary.Sequence.Kind.LIST) {
                    line("final " + s.className() + " " + result + " = new java.util.ArrayList<>(" + n + ");");
                } else {
                    String base = s.className().substring(0, s.className().indexOf('['));
                    String rest = s.className().substring(s.className().indexOf('[') + 2);
                    line("final " + s.className() + " " + result + " = new " + base + "[" + n + "]" + rest + ";");
                }
                line("for (int " + i + " = 0; " + i + " < " + n + "; " + i + "++) {");
                indent++;
                idx[offset + inner] = i;
                String e = build(s.of(), v, t, offset, idx);
                line(s.kind() == Boundary.Sequence.Kind.LIST ? result + ".add(" + e + ");" : result + "[" + i + "] = " + e + ";");
                indent--;
                line("}");
                return result;
            }
            case Boundary.Components c -> {
                List<String> args = new ArrayList<>();
                for (int k = 0; k < c.parts().size(); k++) {
                    idx[offset] = String.valueOf(k);
                    args.add(temp("var", build(c.parts().get(k).boundary(), v, t, offset + 1, idx)));
                }
                return "new " + c.className() + "(" + String.join(", ", args) + ")";
            }
        }
    }

    /** The length of VEL dimension {@code k} of the generated value {@code v}. */
    private static String lengthOfValue(String v, ConcreteType t, int k) {
        List<Card> dims = t.dims();
        if (k >= dims.size()) {
            return "1";
        }
        if (dims.get(k) instanceof Card.Finite f) {
            return String.valueOf(f.size());
        }
        return v + "[0]".repeat(t.javaRank() - 1 - k) + ".length";
    }

    // ------------------------------------------------------------------------------------------

    private static String at(String array, String[] idx, int javaRank) {
        StringBuilder s = new StringBuilder(array);
        for (int k = javaRank - 1; k >= 0; k--) {
            s.append('[').append(idx[k]).append(']');
        }
        return s.toString();
    }

    private String temp(String type, String code) {
        if (code.matches("[\\w$]+")) {
            return code;
        }
        String name = fresh("b");
        line("final " + type + " " + name + " = " + code + ";");
        return name;
    }

    private String fresh(String base) {
        return prefix + "__" + base + counter++;
    }

    private void line(String s) {
        out.append("    ".repeat(indent)).append(s).append('\n');
    }
}
