package com.bluemeridian.core.util;

/**
 * Position-seeded random values.
 *
 * <p>Every random number in Blue Meridian is derived from an explicit
 * {@code (seed, coordinate)} pair rather than drawn from a running stream. That
 * is a hard requirement, not a style choice: the client and the authoritative
 * server generate the same ocean and the same gusts independently, in different
 * orders, on different hardware, and they must agree. A stateful generator
 * would make the result depend on iteration order.
 *
 * <p>The mixer is SplitMix64's finaliser, which is a published,
 * well-distributed 64-bit avalanche function.
 */
public final class DeterministicRandom {

    private DeterministicRandom() {
    }

    /** SplitMix64 finalising mix. */
    public static long mix64(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Hashes a seed and two integer coordinates into a 64-bit value. */
    public static long hash(long seed, int x, int y) {
        long h = seed;
        h = mix64(h ^ (x * 0x9E3779B97F4A7C15L));
        h = mix64(h ^ (y * 0xC2B2AE3D27D4EB4FL));
        return h;
    }

    /** Hashes a seed and three integer coordinates into a 64-bit value. */
    public static long hash(long seed, int x, int y, int z) {
        return mix64(hash(seed, x, y) ^ (z * 0x165667B19E3779F9L));
    }

    /** Uniform value in [0,1) derived from a 64-bit hash. */
    public static double toUnitDouble(long hash) {
        return (hash >>> 11) * 0x1.0p-53;
    }

    /** Uniform value in [0,1) for the given seed and coordinate. */
    public static double uniform(long seed, int x, int y) {
        return toUnitDouble(hash(seed, x, y));
    }

    /**
     * Two independent standard normal deviates for the given seed and
     * coordinate, via the Box-Muller transform.
     *
     * @param out array of length >= 2 receiving the pair
     */
    public static void gaussianPair(long seed, int x, int y, double[] out) {
        long h = hash(seed, x, y);
        double u1 = toUnitDouble(h);
        double u2 = toUnitDouble(mix64(h ^ 0x5851F42D4C957F2DL));
        // Guard the log against exactly zero; 2^-53 keeps the deviate finite.
        if (u1 < 1e-12) {
            u1 = 1e-12;
        }
        double r = Math.sqrt(-2.0 * Math.log(u1));
        double a = 2.0 * Math.PI * u2;
        out[0] = r * Math.cos(a);
        out[1] = r * Math.sin(a);
    }
}
