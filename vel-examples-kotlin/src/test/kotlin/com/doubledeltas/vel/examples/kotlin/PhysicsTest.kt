package com.doubledeltas.vel.examples.kotlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Runs the code generated for [Physics]: by vel-ksp (@Vel) and by vel-kotlinc (inline vel). */
class PhysicsTest {

    @Test
    fun positionIsGeneratedByKsp() {
        // p + v * dt + 0.5 * a * dt^2
        assertEquals(1 + 2 * 3 + 0.5 * 4 * 9.0, PhysicsVel().position(1.0, 2.0, 4.0, 3.0))
    }

    @Test
    fun kineticEnergyIsRewrittenByTheCompilerPlugin() {
        // vel("half * m * v^2")
        assertEquals(0.5 * 2 * 9, PhysicsVel().kineticEnergy(2.0, 3.0))
    }
}
