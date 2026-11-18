package com.doubledeltas.vel.core.codegen;

import java.util.List;

import com.doubledeltas.vel.core.concretization.Resolution;
import com.doubledeltas.vel.core.concretization.ScalarType;
import com.doubledeltas.vel.core.type.Card;
import com.doubledeltas.vel.core.type.VelTypeException;

/** Java types of resolutions (java.md 1.1) as used by the generated code. */
public final class JavaTypes {

    private JavaTypes() {
    }

    /** The scalar type of a Java primitive (java.md 1.1), or null when the name is not a supported primitive. */
    public static ScalarType ofPrimitive(String name) {
        return switch (name) {
            case "boolean" -> ScalarType.BOOL;
            case "byte" -> ScalarType.integer(Resolution.signed(8));
            case "short" -> ScalarType.integer(Resolution.signed(16));
            case "char" -> ScalarType.integer(Resolution.unsigned(16));
            case "int" -> ScalarType.integer(Resolution.INT32);
            case "long" -> ScalarType.integer(Resolution.INT64);
            case "float" -> ScalarType.real(Resolution.FLOAT32);
            case "double" -> ScalarType.real(Resolution.FLOAT64);
            default -> null;
        };
    }

    /**
     * The Java primitive that holds a resolution: the narrowest one that represents every value.
     * Resolutions without a Java primitive (Z∞, Dn, D∞, wider than 64 bits) are not supported yet.
     */
    public static String javaName(Resolution r) {
        switch (r) {
            case Resolution.Bool ignored -> {
                return "boolean";
            }
            case Resolution.CardRes ignored -> {
                return "int";
            }
            case Resolution.FixedInt f -> {
                if (!f.signed() && f.bits() == 16) {
                    return "char";
                }
                int bits = f.signed() ? f.bits() : f.bits() + 1;
                if (bits <= 8) {
                    return "byte";
                }
                if (bits <= 16) {
                    return "short";
                }
                if (bits <= 32) {
                    return "int";
                }
                if (bits <= 64) {
                    return "long";
                }
            }
            case Resolution.BinaryFloat f -> {
                if (f.bits() == 32) {
                    return "float";
                }
                if (f.bits() == 64) {
                    return "double";
                }
            }
            default -> {
            }
        }
        throw new VelTypeException("the resolution " + r + " is not supported by the Java code generator yet");
    }

    /** The boxed class of a primitive name, for generic helpers. */
    static String boxed(String primitive) {
        return switch (primitive) {
            case "boolean" -> "Boolean";
            case "byte" -> "Byte";
            case "short" -> "Short";
            case "char" -> "Character";
            case "int" -> "Integer";
            case "long" -> "Long";
            case "float" -> "Float";
            case "double" -> "Double";
            default -> throw new IllegalArgumentException(primitive);
        };
    }

    /** Number of Java array levels for VEL dimensions: up to the last one not known to be 1. */
    static int javaRank(List<Card> dims) {
        for (int k = dims.size() - 1; k >= 0; k--) {
            if (!dims.get(k).isOne()) {
                return k + 1;
            }
        }
        return 0;
    }
}
