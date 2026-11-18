package com.doubledeltas.vel.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Runs the code that the @Vel processor generated for {@link Physics}. */
class PhysicsTest {

    @Test
    void positionIsGeneratedFromVel() {
        Physics physics = new PhysicsVel();
        // p + v * dt + 0.5 * a * dt^2
        assertEquals(1 + 2 * 3 + 0.5 * 4 * 9.0, physics.position(1, 2, 4, 3));
    }
}
