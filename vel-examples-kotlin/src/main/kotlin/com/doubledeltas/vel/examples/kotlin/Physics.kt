package com.doubledeltas.vel.examples.kotlin

import com.doubledeltas.vel.Vel
import com.doubledeltas.vel.VelInline.vel

interface Physics {

    // B: whole function body in VEL, processed by vel-ksp
    @Vel("""
        # constant-acceleration step
        return p + v * dt + 0.5 * a * dt^2
        """)
    fun position(p: Double, v: Double, a: Double, dt: Double): Double

    // C: inline expression inside ordinary Kotlin, processed by vel-kotlinc
    fun kineticEnergy(m: Double, v: Double): Double {
        val half = 0.5
        return vel("half * m * v^2")
    }
}
