package com.doubledeltas.vel.core.codegen;

import java.util.ArrayList;
import java.util.List;

import com.doubledeltas.vel.core.concretization.Promotion;
import com.doubledeltas.vel.core.concretization.Resolution;
import com.doubledeltas.vel.core.concretization.ScalarType;
import com.doubledeltas.vel.core.type.Card;
import com.doubledeltas.vel.core.type.ScalarAlgebra;
import com.doubledeltas.vel.core.type.Shapes;
import com.doubledeltas.vel.core.type.VelTypeException;

/**
 * How a value of the target language crosses the boundary of VEL (type-system.md 0.3, java.md 2).
 * <p>
 * Generated code works on JVM primitives and arrays of them ({@link ConcreteType}). Other values are
 * converted when they enter (abstraction) and rebuilt when they leave (concretization):
 * <ul>
 *   <li>{@link Direct}: a primitive or an array of primitives, used as it is;</li>
 *   <li>{@link Boxed}: a wrapper ({@code Double}, Kotlin {@code Double?}), unboxed (null throws);</li>
 *   <li>{@link Sequence}: a {@code List} or an array of non-primitives, whose index is the last VEL
 *       dimension of the value (a Java {@code a[m][n]} is {@code Mat(n, m)});</li>
 *   <li>{@link Components}: a component object (record, {@code Pair}, data class, java.md 2.5), whose
 *       component index is the first VEL dimension.</li>
 * </ul>
 * Each backend reads and writes values following this description.
 */
public sealed interface Boundary {

    /** The VEL type inside: dimension sizes (before the canonical padding) and the element scalar type. */
    List<Card> dims();

    ScalarType element();

    /** The type generated code works with. */
    default ConcreteType type() {
        List<Card> d = dims().isEmpty() ? List.of(Card.ONE) : dims();
        return new ConcreteType(d, element(), this instanceof Direct direct ? direct.representation() : null);
    }

    /** The {@link Code.Type} primitive holding the elements as the target language gives them. */
    default String primitive() {
        return JavaTypes.javaName(element().resolution());
    }

    /** Whether the value is used as it is (no conversion at the boundary). */
    default boolean isDirect() {
        return this instanceof Direct;
    }

    /**
     * A primitive ({@code rank} 0) or an array of primitives of {@code rank} levels.
     *
     * @param dims           the VEL sizes, innermost level first ({@code rank} of them)
     * @param representation the primitive holding the elements when it is not the default one of the
     *                       resolution (Kotlin {@code UShort}: {@code ushort}), or null
     */
    record Direct(List<Card> dims, ScalarType element, String representation) implements Boundary {
        public Direct {
            dims = List.copyOf(dims);
        }

        public Direct(List<Card> dims, ScalarType element) {
            this(dims, element, null);
        }

        @Override
        public String primitive() {
            return representation != null ? representation : JavaTypes.javaName(element.resolution());
        }

        public int rank() {
            return dims.size();
        }
    }

    /**
     * A wrapper of a primitive: {@code java.lang.Double} or a Kotlin nullable {@code Double?}.
     *
     * @param primitive the JVM primitive it wraps ({@code double}, …)
     */
    record Boxed(ScalarType element, String primitive) implements Boundary {
        @Override
        public List<Card> dims() {
            return List.of();
        }
    }

    /**
     * A sequence of elements: a {@code java.util.List} ({@code LIST}) or an array of non-primitive
     * elements ({@code ARRAY}, e.g. Java {@code Double[]}, Kotlin {@code Array<Double>}).
     *
     * @param length    the number of elements (⊤ unless given by {@code @Dim})
     * @param className the Java/Kotlin class of the sequence as written in source (for creating one)
     */
    record Sequence(Kind kind, Boundary of, Card length, String className) implements Boundary {

        public enum Kind { LIST, ARRAY }

        @Override
        public List<Card> dims() {
            List<Card> d = new ArrayList<>(of.dims());
            d.add(length);
            return d;
        }

        @Override
        public ScalarType element() {
            return of.element();
        }
    }

    /**
     * A component object with {@code parts.size()} components of the same shape (java.md 2.5).
     *
     * @param className the class to construct when a value leaves VEL
     * @param parts     the components in order
     */
    record Components(String className, List<Part> parts) implements Boundary {

        public Components {
            parts = List.copyOf(parts);
            if (parts.isEmpty()) {
                throw new VelTypeException(className + " has no components");
            }
            List<Card> shape = parts.getFirst().boundary().dims();
            for (Part p : parts) {
                if (p.boundary().dims().size() != shape.size()) {
                    throw new VelTypeException("the components of " + className + " have different shapes");
                }
                for (int i = 0; i < shape.size(); i++) {
                    Shapes.unify(shape.get(i), p.boundary().dims().get(i),
                            "the components of " + className + " have different shapes:");
                }
            }
            // the components must have a common algebra (throws otherwise)
            parts.stream().map(p -> p.boundary().element().algebra()).reduce(ScalarAlgebra::join);
        }

        @Override
        public List<Card> dims() {
            List<Card> d = new ArrayList<>();
            d.add(Card.of(parts.size()));
            d.addAll(parts.getFirst().boundary().dims());
            return d;
        }

        /** The join of the algebras and the largest resolution of the components (java.md 2.5). */
        @Override
        public ScalarType element() {
            ScalarAlgebra k = null;
            Resolution r = null;
            for (Part p : parts) {
                ScalarType e = p.boundary().element();
                k = k == null ? e.algebra() : ScalarAlgebra.join(k, e.algebra());
                r = r == null ? e.resolution() : Promotion.join(r, e.resolution());
            }
            return ScalarType.of(k, r);
        }
    }

    /**
     * A component of a component object.
     *
     * @param accessor how to read it, as written in source: a record accessor {@code x()}, a Kotlin
     *                 property {@code first}, or {@code component1()}
     */
    record Part(String accessor, Boundary boundary) {
    }

    /** The given VEL sizes ({@code @Dim}) applied to the ⊤ sizes of a boundary, in VEL order. */
    static Boundary withDims(Boundary b, List<Card> dims) {
        if (dims == null) {
            return b;
        }
        if (dims.size() != b.dims().size()) {
            throw new VelTypeException("@Dim gives " + dims.size() + " sizes for a value of " + b.dims().size() + " dimensions");
        }
        return sized(b, dims, 0);
    }

    private static Boundary sized(Boundary b, List<Card> dims, int offset) {
        return switch (b) {
            case Direct d -> new Direct(dims.subList(offset, offset + d.rank()), d.element(), d.representation());
            case Boxed x -> x;
            case Sequence s -> new Sequence(s.kind(), sized(s.of(), dims, offset),
                    dims.get(offset + s.of().dims().size()), s.className());
            case Components c -> {
                Shapes.unify(dims.get(offset), Card.of(c.parts().size()), "@Dim does not match " + c.className() + ":");
                List<Part> parts = c.parts().stream()
                        .map(p -> new Part(p.accessor(), sized(p.boundary(), dims, offset + 1))).toList();
                yield new Components(c.className(), parts);
            }
        };
    }
}
