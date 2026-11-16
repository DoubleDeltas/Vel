package com.doubledeltas.vel.core.type;

/** A VEL type error: the operation is not defined for the given types. */
public final class VelTypeException extends RuntimeException {

    public VelTypeException(String message) {
        super(message);
    }
}
