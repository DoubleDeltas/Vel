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

    @Test
    void kineticEnergyIsRewrittenFromInlineVel() {
        // default method using vel("half * m * v^2"), rewritten by the javac plugin
        assertEquals(0.5 * 2 * 9, new PhysicsVel().kineticEnergy(2, 3));
    }
}
