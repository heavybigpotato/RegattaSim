package com.bluemeridian.core.math;

/**
 * Small scalar helpers shared by the ocean and environment code.
 *
 * <p>Deliberately not named {@code MathUtils}: libGDX ships a class with that
 * name and the two must never be confused, because only this one is allowed to
 * run on the server.
 */
public final class Mth {

    /**
     * Standard gravity, m/s^2. Fixed constant: client and server must agree bit for
     * bit.
     *
     * <p>A double, not a float. Every use of it is inside a double expression -
     * dispersion, the spectra, the directional spreading - so a float constant was
     * silently widening 9.806650161743164 into arithmetic that then carried a
     * relative error of 1.6e-8 through every wavenumber and frequency. Nothing
     * visible came of it, but this constant is the one the authoritative server
     * will replay physics against, and "close enough" is not a property that
     * survives being multiplied for three weeks of an offshore race.
     */
    public static final double GRAVITY = 9.80665;

    /**
     * Doubles for the same reason {@link #GRAVITY} is. Every use of TAU is a
     * wavenumber, a frequency or an angle inside double arithmetic, and a float
     * 2*pi carries a relative error of 2.8e-8 into all of them. That was enough to
     * make the browser build and the Java build disagree about wave frequencies in
     * the eighth digit, which is how it was found.
     */
    public static final double TAU = Math.PI * 2.0;
    public static final double PI = Math.PI;

    private Mth() {
    }

    public static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    public static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Smoothstep with the usual cubic easing, clamped to [0,1]. */
    public static float smoothstep(float edge0, float edge1, float x) {
        if (edge1 == edge0) {
            return x < edge0 ? 0f : 1f;
        }
        float t = clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /**
     * Wraps an angle in radians into [-PI, PI).
     *
     * <p>The double overload is the one the physics uses. The float overload exists
     * for shading and direction code where float is the natural width, and it
     * simply narrows the double result - a float parameter in a double pipeline
     * silently costs about 1e-5 of a degree, which is enough to make an exact
     * assertion about a beam reach fail.
     */
    public static double wrapPi(double radians) {
        double r = (radians + PI) % TAU;
        if (r < 0.0) {
            r += TAU;
        }
        return r - PI;
    }

    /** Float convenience overload; see {@link #wrapPi(double)}. */
    public static float wrapPi(float radians) {
        return (float) wrapPi((double) radians);
    }

    /** Wraps an angle in degrees into [0, 360). */
    public static double wrap360(double degrees) {
        double d = degrees % 360.0;
        return d < 0.0 ? d + 360.0 : d;
    }

    /** Signed shortest difference between two headings in degrees, in [-180, 180). */
    public static double headingDelta(double fromDeg, double toDeg) {
        return wrap360(toDeg - fromDeg + 180.0) - 180.0;
    }

    /** True if {@code v} is a power of two and strictly positive. */
    public static boolean isPowerOfTwo(int v) {
        return v > 0 && (v & (v - 1)) == 0;
    }

    /** log2 of a positive power of two. */
    public static int log2(int powerOfTwo) {
        if (!isPowerOfTwo(powerOfTwo)) {
            throw new IllegalArgumentException("not a power of two: " + powerOfTwo);
        }
        return Integer.numberOfTrailingZeros(powerOfTwo);
    }

    /** Reverses the low {@code bits} bits of {@code value}. */
    public static int reverseBits(int value, int bits) {
        int r = 0;
        for (int i = 0; i < bits; i++) {
            r = (r << 1) | ((value >>> i) & 1);
        }
        return r;
    }
}
