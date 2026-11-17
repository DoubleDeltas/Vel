package com.doubledeltas.vel.core.type;

/**
 * A VEL type: a algebra with a resolution applied (type-system.md 0.2). Types are what Java and Kotlin
 * types map to. Every VEL object is a function (type-system.md 1); arrays are functions from indices
 * to scalars. Function types other than arrays will be added together with the function syntax.
 */
public sealed interface VelType permits ArrayType {
}
