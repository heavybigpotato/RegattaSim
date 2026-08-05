package com.bluemeridian.core.ocean.spectrum;

/**
 * A one-dimensional wave energy density spectrum {@code S(omega)}, in
 * m^2 / (rad/s).
 *
 * <p>Integrating {@code S} over all angular frequencies gives the variance of
 * the surface elevation, {@code m0}, from which the significant wave height
 * follows as {@code Hs = 4*sqrt(m0)}.
 */
public interface WaveSpectrum {

    /**
     * Energy density at the given angular frequency.
     *
     * @param omega angular frequency in rad/s; implementations must return 0 for
     *              non-positive input rather than diverge
     */
    double energy(double omega);

    /** Peak angular frequency in rad/s, used to size integration ranges and directional spreading. */
    double peakOmega();

    /**
     * Numerically integrated zeroth moment {@code m0 = integral S(omega) d(omega)}.
     *
     * <p>Uses the trapezoid rule over a range wide enough for the tail to be
     * negligible. This is a diagnostic, not something on the per-frame path.
     */
    default double zerothMoment() {
        double wp = peakOmega();
        double lo = Math.max(1e-3, wp * 0.02);
        double hi = wp * 12.0;
        int steps = 4096;
        double step = (hi - lo) / steps;
        double sum = 0.5 * (energy(lo) + energy(hi));
        for (int i = 1; i < steps; i++) {
            sum += energy(lo + i * step);
        }
        return sum * step;
    }

    /** Significant wave height implied by this spectrum, in metres. */
    default double significantWaveHeight() {
        return 4.0 * Math.sqrt(Math.max(0.0, zerothMoment()));
    }
}
