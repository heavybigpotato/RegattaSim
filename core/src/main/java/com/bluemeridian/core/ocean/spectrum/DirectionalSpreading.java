package com.bluemeridian.core.ocean.spectrum;

/**
 * Angular distribution of wave energy, normalised so that
 * {@code integral over [-pi, pi] of density(theta, omega) d(theta) == 1}.
 *
 * <p>Directional spreading is what stops an FFT ocean from looking like a
 * corrugated roof. A perfectly unidirectional sea reads as fake instantly; real
 * wind seas are broad near the peak and broader still away from it, which is
 * what produces the short-crested, "crossed" look at 25 kt.
 */
public interface DirectionalSpreading {

    /**
     * @param theta direction of the wave component in radians, world frame
     * @param omega angular frequency of the component, rad/s
     * @return angular energy density, 1/rad
     */
    double density(double theta, double omega);
}
