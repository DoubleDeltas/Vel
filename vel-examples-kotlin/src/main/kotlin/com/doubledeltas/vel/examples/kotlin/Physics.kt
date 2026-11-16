package com.doubledeltas.vel.examples.kotlin

import com.doubledeltas.vel.Vel

interface Physics {

    // B: whole function body in VEL, processed by vel-ksp
    @Vel("""
        # constant-acceleration step
        return p + v * dt + 0.5 * a * dt^2
        """)
    fun position(p: Double, v: Double, a: Double, dt: Double): Double
}
