package com.doubledeltas.vel.examples;

import static com.doubledeltas.vel.VelInline.vel;

import com.doubledeltas.vel.Vel;

public interface Physics {

    // B: whole method body in VEL
    @Vel("""
        // constant-acceleration step
        return p + v * dt + 0.5 * a * dt^2
        """)
    double position(double p, double v, double a, double dt);

    // C: inline expression inside ordinary Java
    default double kineticEnergy(double m, double v) {
        double half = 0.5;
        return vel("half * m * v^2");
    }
}
