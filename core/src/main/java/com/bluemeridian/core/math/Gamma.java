package com.bluemeridian.core.math;

/**
 * Log-gamma via the Lanczos approximation (g = 7, 9 coefficients).
 *
 * <p>Needed to normalise the {@code cos^2s} directional spreading analytically
 * instead of integrating it numerically every time the sea state changes. The
 * coefficients are checked against known values of the gamma function in
 * {@code GammaTest}; if they were transcribed wrongly, that test fails.
 */
public final class Gamma {

    private static final double[] LANCZOS = {
            0.99999999999980993,
            676.5203681218851,
            -1259.1392167224028,
            771.32342877765313,
            -176.61502916214059,
            12.507343278686905,
            -0.13857109526572012,
            9.9843695780195716e-6,
            1.5056327351493116e-7,
    };

    private static final double SQRT_TWO_PI = Math.sqrt(2.0 * Math.PI);

    private Gamma() {
    }

    /** Natural logarithm of the gamma function, for {@code x > 0}. */
    public static double logGamma(double x) {
        if (x <= 0.0) {
            throw new IllegalArgumentException("logGamma requires x > 0, got " + x);
        }
        if (x < 0.5) {
            // Reflection formula: G(x)G(1-x) = pi / sin(pi x)
            return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - logGamma(1.0 - x);
        }
        double z = x - 1.0;
        double a = LANCZOS[0];
        for (int i = 1; i < LANCZOS.length; i++) {
            a += LANCZOS[i] / (z + i);
        }
        double t = z + 7.5;
        return Math.log(SQRT_TWO_PI) + (z + 0.5) * Math.log(t) - t + Math.log(a);
    }

    /** Gamma function for {@code x > 0}. Overflows to infinity beyond roughly x = 171. */
    public static double gamma(double x) {
        return Math.exp(logGamma(x));
    }
}
