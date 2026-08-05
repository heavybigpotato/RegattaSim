package com.bluemeridian.core.ocean.spectrum;

import com.bluemeridian.core.math.Gamma;
import com.bluemeridian.core.math.Mth;

/**
 * Longuet-Higgins {@code cos^{2s}(theta/2)} directional spreading.
 *
 * <pre>
 *   D(theta) = N(s) * cos^{2s}((theta - theta0)/2)
 *   N(s)     = Gamma(s+1) / (2*sqrt(pi)*Gamma(s+1/2))
 * </pre>
 *
 * <p>The normalisation is analytic, from
 * {@code integral cos^{2s}(theta/2) d(theta) over [-pi,pi] = 2*sqrt(pi)*Gamma(s+1/2)/Gamma(s+1)}.
 *
 * <p>Two modes are provided. With a constant exponent this is the plain
 * Longuet-Higgins form, which suits a swell train (large {@code s}, narrow). With
 * the Mitsuyasu frequency dependence enabled, {@code s} peaks at the spectral peak
 * and falls off either side, which is the observed behaviour of a wind sea: long
 * components arrive tightly aligned with the wind while short chop is scattered
 * over a wide arc. The frequency-dependent exponents are an empirical fit
 * (Mitsuyasu et al., 1975), not a first-principles result.
 */
public final class CosinePowerSpreading implements DirectionalSpreading {

    private final double meanDirection;
    private final double peakOmega;
    private final double peakExponent;
    private final boolean frequencyDependent;

    /** Exponent below which the distribution is treated as flat enough to skip. */
    private static final double MIN_EXPONENT = 0.05;
    /** Above this the distribution is a needle and the gamma ratio starts to lose precision. */
    private static final double MAX_EXPONENT = 200.0;

    private CosinePowerSpreading(double meanDirection, double peakOmega, double peakExponent,
            boolean frequencyDependent) {
        this.meanDirection = meanDirection;
        this.peakOmega = peakOmega;
        this.peakExponent = Mth.clamp((float) peakExponent, (float) MIN_EXPONENT, (float) MAX_EXPONENT);
        this.frequencyDependent = frequencyDependent;
    }

    /**
     * Constant-exponent spreading.
     *
     * @param meanDirection direction the waves travel toward, radians
     * @param exponentS     spreading exponent; ~2 is a broad wind sea, ~30 a clean swell
     */
    public static CosinePowerSpreading constant(double meanDirection, double exponentS) {
        return new CosinePowerSpreading(meanDirection, 1.0, exponentS, false);
    }

    /**
     * Wind-sea spreading with the Mitsuyasu frequency dependence, where the peak
     * exponent follows {@code sp = 11.5 * (g / (wp * U10))^2.5}.
     *
     * @param meanDirection wind direction (the direction the wind blows toward), radians
     * @param peakOmega     spectral peak angular frequency, rad/s
     * @param windSpeed10m  wind speed at 10 m, m/s
     */
    public static CosinePowerSpreading windSea(double meanDirection, double peakOmega,
            double windSpeed10m) {
        double sp = 11.5 * Math.pow(Mth.GRAVITY / (peakOmega * windSpeed10m), 2.5);
        return new CosinePowerSpreading(meanDirection, peakOmega, sp, true);
    }

    private double exponentAt(double omega) {
        if (!frequencyDependent || omega <= 0.0) {
            return peakExponent;
        }
        double ratio = omega / peakOmega;
        double s = ratio <= 1.0
                ? peakExponent * Math.pow(ratio, 5.0)
                : peakExponent * Math.pow(ratio, -2.5);
        return Mth.clamp((float) s, (float) MIN_EXPONENT, (float) MAX_EXPONENT);
    }

    /** Analytic normalisation constant {@code N(s)}. */
    public static double normalisation(double s) {
        return Math.exp(Gamma.logGamma(s + 1.0) - Gamma.logGamma(s + 0.5)) / (2.0 * Math.sqrt(Math.PI));
    }

    @Override
    public double density(double theta, double omega) {
        double s = exponentAt(omega);
        double half = 0.5 * Mth.wrapPi((float) (theta - meanDirection));
        double c = Math.cos(half);
        if (c <= 0.0) {
            return 0.0;
        }
        return normalisation(s) * Math.pow(c, 2.0 * s);
    }

    public double meanDirection() {
        return meanDirection;
    }

    public double peakExponent() {
        return peakExponent;
    }
}
