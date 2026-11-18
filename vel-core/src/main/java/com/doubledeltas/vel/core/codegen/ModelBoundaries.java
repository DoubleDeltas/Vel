package com.doubledeltas.vel.core.codegen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Types;

import com.doubledeltas.vel.core.concretization.ScalarType;
import com.doubledeltas.vel.core.type.Card;
import com.doubledeltas.vel.core.type.DimSpec;
import com.doubledeltas.vel.core.type.VelTypeException;

/**
 * {@link Boundary Boundaries} of Java types given through the standard language model
 * ({@code javax.lang.model}, part of Java SE): java.md 1, 2.
 * <ul>
 *   <li>primitives and arrays of primitives: {@link Boundary.Direct};</li>
 *   <li>wrappers ({@code Double}, …): {@link Boundary.Boxed};</li>
 *   <li>{@code java.util.List} / {@code ArrayList} and arrays of non-primitives: {@link Boundary.Sequence};</li>
 *   <li>records, and classes with {@code component1()}…{@code componentN()} (Kotlin {@code Pair},
 *       data classes): {@link Boundary.Components} (java.md 2.5).</li>
 * </ul>
 */
public final class ModelBoundaries {

    private static final Map<String, String> WRAPPERS = Map.of(
            "java.lang.Boolean", "boolean", "java.lang.Byte", "byte", "java.lang.Short", "short",
            "java.lang.Character", "char", "java.lang.Integer", "int", "java.lang.Long", "long",
            "java.lang.Float", "float", "java.lang.Double", "double");

    private final Types types;

    public ModelBoundaries(Types types) {
        this.types = types;
    }

    /** The boundary of a type, sized by the {@code @Dim} annotation of {@code annotated} (may be null). */
    public Boundary of(TypeMirror type, Element annotated) {
        Boundary b = of(type);
        List<Card> dims = annotated == null ? null : dims(annotated);
        if (dims != null && b instanceof Boundary.Direct d && d.rank() == 0) {
            throw new VelTypeException("@Dim on the scalar type " + type);
        }
        return Boundary.withDims(b, dims);
    }

    public Boundary of(TypeMirror type) {
        TypeKind kind = type.getKind();
        if (kind.isPrimitive()) {
            return new Boundary.Direct(List.of(), scalar(type.toString()));
        }
        if (kind == TypeKind.ARRAY) {
            int rank = 0;
            TypeMirror t = type;
            while (t.getKind() == TypeKind.ARRAY) {
                t = ((ArrayType) t).getComponentType();
                rank++;
            }
            if (t.getKind().isPrimitive()) {
                List<Card> dims = new ArrayList<>(java.util.Collections.nCopies(rank, Card.TOP));
                return new Boundary.Direct(dims, scalar(t.toString()));
            }
            Boundary element = of(((ArrayType) type).getComponentType());
            return new Boundary.Sequence(Boundary.Sequence.Kind.ARRAY, element, Card.TOP, type.toString());
        }
        if (kind == TypeKind.DECLARED) {
            DeclaredType declared = (DeclaredType) type;
            TypeElement element = (TypeElement) declared.asElement();
            String name = element.getQualifiedName().toString();
            String primitive = WRAPPERS.get(name);
            if (primitive != null) {
                return new Boundary.Boxed(scalar(primitive), primitive);
            }
            if (name.equals("java.util.List") || name.equals("java.util.ArrayList")) {
                if (declared.getTypeArguments().size() != 1 || declared.getTypeArguments().getFirst().getKind() == TypeKind.WILDCARD) {
                    throw new VelTypeException("the list type " + type + " needs its element type");
                }
                return new Boundary.Sequence(Boundary.Sequence.Kind.LIST, of(declared.getTypeArguments().getFirst()),
                        Card.TOP, type.toString());
            }
            if (element.getKind() == ElementKind.RECORD) {
                List<Boundary.Part> parts = new ArrayList<>();
                for (RecordComponentElement c : element.getRecordComponents()) {
                    TypeMirror t = types.asMemberOf(declared, c.getAccessor()) instanceof ExecutableType e
                            ? e.getReturnType() : c.asType();
                    // @Dim (no RECORD_COMPONENT target) reaches the accessor, the field and the parameter
                    Element annotated = dims(c) != null ? c : c.getAccessor();
                    parts.add(new Boundary.Part(c.getSimpleName() + "()", of(t, annotated)));
                }
                return new Boundary.Components(type.toString(), parts);
            }
            List<Boundary.Part> parts = componentFunctions(declared, element);
            if (!parts.isEmpty()) {
                return new Boundary.Components(type.toString(), parts);
            }
        }
        throw new VelTypeException("the Java type " + type + " is not supported by VEL yet");
    }

    /** {@code component1()}, {@code component2()}, …, as long as they exist. */
    private List<Boundary.Part> componentFunctions(DeclaredType declared, TypeElement element) {
        List<Boundary.Part> parts = new ArrayList<>();
        List<ExecutableElement> methods = ElementFilter.methodsIn(element.getEnclosedElements());
        for (int n = 1; ; n++) {
            String name = "component" + n;
            ExecutableElement m = methods.stream()
                    .filter(e -> e.getSimpleName().contentEquals(name) && e.getParameters().isEmpty())
                    .findFirst().orElse(null);
            if (m == null) {
                return parts;
            }
            TypeMirror t = ((ExecutableType) types.asMemberOf(declared, m)).getReturnType();
            parts.add(new Boundary.Part(name + "()", of(t)));
        }
    }

    private static ScalarType scalar(String primitive) {
        ScalarType s = JavaTypes.ofPrimitive(primitive);
        if (s == null) {
            throw new VelTypeException("the Java type " + primitive + " is not supported by VEL");
        }
        return s;
    }

    /** The VEL sizes given by {@code @Dim} on an element, or null. */
    public static List<Card> dims(Element annotated) {
        for (AnnotationMirror m : annotated.getAnnotationMirrors()) {
            TypeElement type = (TypeElement) m.getAnnotationType().asElement();
            if (type.getQualifiedName().contentEquals("com.doubledeltas.vel.Dim")) {
                return DimSpec.fromAnnotation(ints(m, "value"), ints(m, "java"), ints(m, "vel"));
            }
        }
        return null;
    }

    private static int[] ints(AnnotationMirror mirror, String name) {
        for (var e : mirror.getElementValues().entrySet()) {
            if (e.getKey().getSimpleName().contentEquals(name)) {
                Object v = e.getValue().getValue();
                if (v instanceof List<?> list) {
                    return list.stream().mapToInt(x -> (Integer) ((AnnotationValue) x).getValue()).toArray();
                }
                return new int[] {(Integer) v};
            }
        }
        return new int[0];
    }
}
