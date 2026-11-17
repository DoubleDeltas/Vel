package com.doubledeltas.vel.core.type;

/**
 * The algebra of a VEL value (type-system.md 0.2). Inside VEL only algebras are handled; resolutions
 * are applied when a value is concretized into a Java or Kotlin type (type-system.md 0.3). Every VEL
 * object is a function (type-system.md 1); arrays are functions from indices to scalars. Function
 * algebras other than arrays will be added together with the function syntax.
 */
public sealed interface VelAlgebra permits ArrayAlgebra {
}
