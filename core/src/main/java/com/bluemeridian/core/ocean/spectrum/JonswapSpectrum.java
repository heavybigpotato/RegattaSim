package com.bluemeridian.core.ocean.spectrum;

import com.bluemeridian.core.math.Mth;

/**
 * JONSWAP spectrum: a fetch-limited wind sea, after Hasselmann et al. (1973).
 *
 * <pre>
 *   S(w) = alpha * g^2 * w^-5 * exp(-1.25 * (wp/w)^4) * gamma^r
 *   r     = exp( -(w - wp)^2 / (2 * sigma^2 * wp^2) )
 *   sigma = 0.07 for w &lt;= wp, 0.09 otherwise
 * </pre>
 *
 * with the standard fetch relations, where {@code chi = g*F/U10^2}:
 *
 * <pre>
 *   alpha = 0.076 * chi^-0.22
 *   wp    = 22 * (g^2 / (U10 * F))^(1/3)
 * </pre>
 *
 * <p>This is the right spectrum for a game about racing: the whole point of a
 * fetch-limited form is that a 25 kt breeze produces a completely different sea
 * 5 miles off a weather shore than it does 500 miles into the Southern Ocean,
 * and that difference is what makes a course feel like a place. A fully
 * developed sea is recovered by setting {@code gamma = 1} and a large fetch,
 * which reduces the formula to Pierson-Moskowitz.
 */
public final class JonswapSpectrum implements WaveSpectrum {

    /** Peak enhancement factor for a mean JONSWAP sea. */
    public static final double DEFAULT_GAMMA = 3.3;

    /**
     * Constant in {@code F_full = U^2 / FULL_DEVELOPMENT_COEFFICIENT}, the fetch at
     * which the JONSWAP peak frequency reaches the Pierson-Moskowitz one.
     *
     * <p>Derived by setting {@code 22*(g^2/(U*F))^(1/3) = 0.877*g/(1.026*U)} and
     * solving for {@code F}. For 25 kt this puts full development at about 290 km,
     * a dimensionless fetch of roughly 1.7e4, which is consistent with the
     * published range for a fully developed sea.
     */
    private static final double FULL_DEVELOPMENT_COEFFICIENT = 5.754e-4;

    private final double alpha;
    private final double peakOmega;
    private final double gamma;
    private final double effectiveFetch;
    private final boolean fullyDeveloped;

    /**
     * Fetch beyond which the sea stops growing for the given wind, in metres.
     */
    public static double fullDevelopmentFetch(double windSpeed10m) {
        return windSpeed10m * windSpeed10m / FULL_DEVELOPMENT_COEFFICIENT;
    }

    /**
     * @param windSpeed10m wind speed at 10 m reference height, m/s (must be > 0)
     * @param fetchMetres  distance over which the wind has blown, m (must be > 0)
     * @param gamma        peak enhancement; 3.3 is the JONSWAP mean, 1.0 gives Pierson-Moskowitz
     */
    public JonswapSpectrum(double windSpeed10m, double fetchMetres, double gamma) {
        if (windSpeed10m <= 0.0) {
            throw new IllegalArgumentException("wind speed must be positive, got " + windSpeed10m);
        }
        if (fetchMetres <= 0.0) {
            throw new IllegalArgumentException("fetch must be positive, got " + fetchMetres);
        }
        // The JONSWAP fetch relations describe a *growing* sea. Applied past full
        // development they keep lowering the peak frequency, and since energy goes
        // as wp^-4 they produce a sea taller than the wind can physically raise:
        // 25 kt over 500 km came out at 6.5 m instead of about 4 m. The fetch is
        // therefore capped at the point where the relations meet Pierson-Moskowitz,
        // and beyond it the sea simply stops growing, which is the observed
        // behaviour.
        double fullFetch = fullDevelopmentFetch(windSpeed10m);
        this.fullyDeveloped = fetchMetres >= fullFetch;
        this.effectiveFetch = Math.min(fetchMetres, fullFetch);

        double g = Mth.GRAVITY;
        double chi = g * effectiveFetch / (windSpeed10m * windSpeed10m);
        this.alpha = 0.076 * Math.pow(chi, -0.22);
        this.peakOmega = 22.0 * Math.cbrt(g * g / (windSpeed10m * effectiveFetch));
        this.gamma = gamma;
    }

    /** The fetch actually used, after capping at full development. */
    public double effectiveFetch() {
        return effectiveFetch;
    }

    /** True when the requested fetch was long enough for the sea to be fully developed. */
    public boolean isFullyDeveloped() {
        return fullyDeveloped;
    }

    public JonswapSpectrum(double windSpeed10m, double fetchMetres) {
        this(windSpeed10m, fetchMetres, DEFAULT_GAMMA);
    }

    @Override
    public double energy(double omega) {
        if (omega <= 1e-6) {
            return 0.0;
        }
        double g2 = Mth.GRAVITY * Mth.GRAVITY;
        double wp = peakOmega;
        double base = alpha * g2 / Math.pow(omega, 5.0) * Math.exp(-1.25 * Math.pow(wp / omega, 4.0));
        double sigma = omega <= wp ? 0.07 : 0.09;
        double d = omega - wp;
        double r = Math.exp(-(d * d) / (2.0 * sigma * sigma * wp * wp));
        return base * Math.pow(gamma, r);
    }

    @Override
    public double peakOmega() {
        return peakOmega;
    }

    /** Equilibrium range constant, dimensionless. */
    public double alpha() {
        return alpha;
    }

    public double gamma() {
        return gamma;
    }
}
