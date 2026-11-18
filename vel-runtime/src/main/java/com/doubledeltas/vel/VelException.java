package com.doubledeltas.vel;

/**
 * Thrown by the VEL built-in {@code error()} (type-system.md 1.1, algebra {@code Not}).
 * <p>
 * An unchecked exception, so Java callers need no {@code throws} clause and an ordinary
 * {@code catch (Exception e)} catches it. Using an invalid cardinal (⊥, or ⊤ converted to Int) throws
 * {@link NullPointerException} instead.
 */
public class VelException extends RuntimeException {

    public VelException() {
        super("error() was called in VEL code");
    }

    public VelException(String message) {
        super(message);
    }
}
