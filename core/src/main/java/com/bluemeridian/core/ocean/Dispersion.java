package com.bluemeridian.core.ocean;

import com.bluemeridian.core.math.Mth;

/**
 * Linear gravity-wave dispersion, {@code omega^2 = g*k*tanh(k*d)}.
 *
 * <p>Finite depth is kept rather than assuming deep water because Blue Meridian
 * has coastal finishes: in 15 m of water a 12 s swell travels visibly slower and
 * steepens, and that is exactly the sea state a shore-side leg is about.
 */
public final class Dispersion {

    /** Beyond this value of {@code k*d}, tanh(k*d) is 1 to float precision. */
    private static final double DEEP_WATER_KD = 20.0;

    private Dispersion() {
    }

    /**
     * Angular frequency for wavenumber {@code k} in water of the given depth.
     *
     * @param k     wavenumber magnitude, rad/m (must be > 0)
     * @param depth still-water depth in metres; use {@link Double#POSITIVE_INFINITY}
     *              or any large value for deep water
     */
    public static double omega(double k, double depth) {
        double kd = k * depth;
        double t = kd >= DEEP_WATER_KD || Double.isInfinite(kd) ? 1.0 : Math.tanh(kd);
        return Math.sqrt(Mth.GRAVITY * k * t);
    }

    /** Derivative {@code d(omega)/dk}, needed to convert a frequency spectrum to a wavenumber spectrum. */
    public static double dOmegaDk(double k, double depth) {
        double w = omega(k, depth);
        if (w <= 0.0) {
            return 0.0;
        }
        double kd = k * depth;
        if (kd >= DEEP_WATER_KD || Double.isInfinite(kd)) {
            return Mth.GRAVITY / (2.0 * w);
        }
        double t = Math.tanh(kd);
        double sech2 = 1.0 - t * t;
        return Mth.GRAVITY * (t + kd * sech2) / (2.0 * w);
    }

    /**
     * Quantises an angular frequency onto a multiple of {@code 2*pi/repeatPeriod}
     * so the whole surface repeats exactly after {@code repeatPeriod} seconds.
     *
     * <p>Without this the FFT patch never loops and long sessions accumulate
     * floating-point drift in the phase term. The visible cost is a small error
     * in wave speed; at a 200 s period it is far below perception.
     */
    public static double quantiseForLoop(double omega, double repeatPeriod) {
        if (repeatPeriod <= 0.0) {
            return omega;
        }
        double base = Mth.TAU / repeatPeriod;
        return Math.floor(omega / base) * base;
    }

    /** Deep-water phase speed {@code c = omega/k}, m/s. */
    public static double phaseSpeed(double k, double depth) {
        return k > 0.0 ? omega(k, depth) / k : 0.0;
    }
}
