package com.bluemeridian.core.ocean;

/**
 * Anything a hull can float on.
 *
 * <p>{@link CpuOceanSurface} is the real implementation and evaluates the same
 * FFT field the renderer draws. The interface exists for two reasons that both
 * matter later: a server running a thousand offshore boats can substitute a
 * cheaper surface where nobody is watching, and a test can substitute an analytic
 * one. Checking that a hull pitches correctly on a known sine wave is a far
 * sharper test than checking that it does something plausible on a random sea.
 */
public interface WaveSurface {

    /** Surface elevation above mean water at a world position, metres. */
    float heightAt(double worldX, double worldZ);

    /** Flat water, for tests and for the cheapest server tier. */
    WaveSurface FLAT = (x, z) -> 0f;

    /**
     * A single travelling sine wave, for tests with a known answer.
     *
     * @param amplitude   half the wave height, metres
     * @param wavelength  crest to crest, metres
     * @param direction   direction of travel in the XZ plane, radians
     * @param phase       phase offset, radians
     */
    static WaveSurface sine(double amplitude, double wavelength, double direction, double phase) {
        double k = 2.0 * Math.PI / wavelength;
        double kx = k * Math.cos(direction);
        double kz = k * Math.sin(direction);
        return (x, z) -> (float) (amplitude * Math.sin(kx * x + kz * z + phase));
    }
}
