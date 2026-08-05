package com.bluemeridian.core.ocean.spectrum;

import com.bluemeridian.core.math.Mth;

/**
 * Pierson-Moskowitz spectrum: a fully developed sea, unlimited fetch.
 *
 * <pre>
 *   S(w) = alpha * g^2 * w^-5 * exp(-1.25 * (wp/w)^4),  alpha = 0.0081
 * </pre>
 *
 * <p>The original 1964 formulation is written against the wind speed at 19.5 m,
 * the height of the anemometers on the weather ships the data came from. Blue
 * Meridian carries wind at the 10 m reference height everywhere (that is what
 * GFS publishes), so the conversion {@code U19.5 = 1.026 * U10} is applied here
 * once, and nowhere else in the codebase.
 */
public final class PiersonMoskowitzSpectrum implements WaveSpectrum {

    private static final double ALPHA = 0.0081;
    /** Ratio between the 19.5 m and 10 m wind speeds, from the standard log wind profile. */
    private static final double U19_5_OVER_U10 = 1.026;

    private final double peakOmega;

    /**
     * @param windSpeed10m wind speed at 10 m reference height, m/s (must be > 0)
     */
    public PiersonMoskowitzSpectrum(double windSpeed10m) {
        if (windSpeed10m <= 0.0) {
            throw new IllegalArgumentException("wind speed must be positive, got " + windSpeed10m);
        }
        double u195 = windSpeed10m * U19_5_OVER_U10;
        this.peakOmega = 0.877 * Mth.GRAVITY / u195;
    }

    @Override
    public double energy(double omega) {
        if (omega <= 1e-6) {
            return 0.0;
        }
        double g2 = Mth.GRAVITY * Mth.GRAVITY;
        return ALPHA * g2 / Math.pow(omega, 5.0)
                * Math.exp(-1.25 * Math.pow(peakOmega / omega, 4.0));
    }

    @Override
    public double peakOmega() {
        return peakOmega;
    }

    /**
     * Closed-form zeroth moment, {@code alpha*g^2 / (5*wp^4)}.
     *
     * <p>Overridden because the analytic value exists and makes the numeric
     * integration in the interface testable against it.
     */
    @Override
    public double zerothMoment() {
        double wp4 = peakOmega * peakOmega * peakOmega * peakOmega;
        return ALPHA * Mth.GRAVITY * Mth.GRAVITY / (5.0 * wp4);
    }
}
