package com.doubledeltas.vel.core.codegen;

import java.util.ArrayList;
import java.util.List;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * {@link MethodLookup} over the standard language model ({@code javax.lang.model}): the public static
 * methods of a class named in VEL by its qualified name or, for {@code java.lang}, its simple name.
 */
public final class ModelMethods implements MethodLookup {

    private final Elements elements;
    private final Types types;
    private final ModelBoundaries boundaries;

    public ModelMethods(Elements elements, Types types) {
        this.elements = elements;
        this.types = types;
        this.boundaries = new ModelBoundaries(types);
    }

    @Override
    public List<Method> staticMethods(String owner, String name) {
        TypeElement type = owner.contains(".") ? elements.getTypeElement(owner) : null;
        if (type == null) {
            type = elements.getTypeElement("java.lang." + owner);
        }
        if (type == null) {
            return null;
        }
        List<Method> found = new ArrayList<>();
        for (ExecutableElement m : ElementFilter.methodsIn(type.getEnclosedElements())) {
            if (!m.getSimpleName().contentEquals(name) || !m.getModifiers().contains(Modifier.STATIC)
                    || !m.getModifiers().contains(Modifier.PUBLIC) || !m.getTypeParameters().isEmpty() || m.isVarArgs()) {
                continue;
            }
            List<Param> params = new ArrayList<>();
            for (var p : m.getParameters()) {
                params.add(new Param(types.erasure(p.asType()).toString(), direct(p.asType())));
            }
            found.add(new Method(type.getQualifiedName().toString(), name, params, direct(m.getReturnType())));
        }
        return found;
    }

    /** The VEL type of a primitive or an array of one, or null for any other type. */
    private ConcreteType direct(TypeMirror t) {
        if (!t.getKind().isPrimitive() && t.getKind() != javax.lang.model.type.TypeKind.ARRAY) {
            return null;
        }
        try {
            return boundaries.of(t) instanceof Boundary.Direct d ? d.type() : null;
        } catch (RuntimeException e) { // not a VEL type
            return null;
        }
    }
}
