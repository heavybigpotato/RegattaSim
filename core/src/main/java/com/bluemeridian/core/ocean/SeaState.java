package com.bluemeridian.core.ocean;

import com.bluemeridian.core.math.Mth;
import com.bluemeridian.core.ocean.spectrum.CosinePowerSpreading;
import com.bluemeridian.core.ocean.spectrum.GaussianSwellSpectrum;
import com.bluemeridian.core.ocean.spectrum.JonswapSpectrum;

/**
 * The complete description of a sea surface: a fetch-limited wind sea plus an
 * independent swell train, in water of a given depth.
 *
 * <p>Immutable. The weather layer produces one of these per location and time,
 * and both the renderer and the authoritative server build their wave field from
 * exactly this object, so a boat's motion on a player's phone matches the
 * server's replay of it.
 *
 * <p>Directions are the direction energy travels <em>toward</em>, in radians,
 * measured in the world XZ plane from +X toward +Z. Note that this is the
 * opposite of the meteorological convention, where a "northerly" is a wind
 * coming <em>from</em> the north; the conversion happens once, in the weather
 * layer, and never appears below it.
 */
public final class SeaState {

    /** Wind speed at the 10 m reference height, m/s. */
    public final double windSpeed;
    /** Direction the wind blows toward, radians in the XZ plane. */
    public final double windDirection;
    /** Fetch, metres. */
    public final double fetch;
    /** JONSWAP peak enhancement. */
    public final double gamma;
    /** Still-water depth, metres. Infinite for open ocean. */
    public final double depth;

    /** Swell significant height, metres. Zero disables the swell entirely. */
    public final double swellHeight;
    /** Swell peak period, seconds. */
    public final double swellPeriod;
    /** Direction the swell travels toward, radians. */
    public final double swellDirection;
    /** Swell fractional bandwidth; smaller is a cleaner, more ordered swell. */
    public final double swellNarrowness;
    /** Longuet-Higgins exponent for the swell's angular spread. */
    public final double swellSpreadExponent;

    /**
     * Horizontal displacement scale (Tessendorf's lambda). 0 gives round
     * Gerstner-free swells, 1 gives physically-scaled sharp crests. Above about
     * 1.3 the surface self-intersects and the foam mask saturates.
     */
    public final double choppiness;

    /** Seconds after which the wave field repeats exactly. */
    public final double repeatPeriod;

    /** Seed for the phase field; identical seeds give identical oceans. */
    public final long seed;

    public SeaState(double windSpeed, double windDirection, double fetch, double gamma, double depth,
            double swellHeight, double swellPeriod, double swellDirection, double swellNarrowness,
            double swellSpreadExponent, double choppiness, double repeatPeriod, long seed) {
        if (windSpeed <= 0.0) {
            throw new IllegalArgumentException("wind speed must be positive, got " + windSpeed);
        }
        if (fetch <= 0.0) {
            throw new IllegalArgumentException("fetch must be positive, got " + fetch);
        }
        if (depth <= 0.0) {
            throw new IllegalArgumentException("depth must be positive, got " + depth);
        }
        this.windSpeed = windSpeed;
        this.windDirection = windDirection;
        this.fetch = fetch;
        this.gamma = gamma;
        this.depth = depth;
        this.swellHeight = Math.max(0.0, swellHeight);
        this.swellPeriod = swellPeriod;
        this.swellDirection = swellDirection;
        this.swellNarrowness = swellNarrowness;
        this.swellSpreadExponent = swellSpreadExponent;
        this.choppiness = choppiness;
        this.repeatPeriod = repeatPeriod;
        this.seed = seed;
    }

    /** The wind-sea component. */
    public JonswapSpectrum windSeaSpectrum() {
        return new JonswapSpectrum(windSpeed, fetch, gamma);
    }

    /** Angular spreading of the wind sea. */
    public CosinePowerSpreading windSeaSpreading() {
        return CosinePowerSpreading.windSea(windDirection, windSeaSpectrum().peakOmega(), windSpeed);
    }

    /** The swell component, or null when {@link #swellHeight} is zero. */
    public GaussianSwellSpectrum swellSpectrum() {
        if (swellHeight <= 0.0) {
            return null;
        }
        return new GaussianSwellSpectrum(swellHeight, swellPeriod, swellNarrowness);
    }

    /** Angular spreading of the swell. */
    public CosinePowerSpreading swellSpreading() {
        return CosinePowerSpreading.constant(swellDirection, swellSpreadExponent);
    }

    /**
     * Combined significant wave height of sea and swell.
     *
     * <p>Variances add because the two components are independent, so heights add
     * in quadrature rather than linearly.
     */
    public double significantWaveHeight() {
        double m0 = windSeaSpectrum().zerothMoment();
        if (swellHeight > 0.0) {
            m0 += swellHeight * swellHeight / 16.0;
        }
        return 4.0 * Math.sqrt(m0);
    }

    public SeaState withSeed(long newSeed) {
        return new SeaState(windSpeed, windDirection, fetch, gamma, depth, swellHeight, swellPeriod,
                swellDirection, swellNarrowness, swellSpreadExponent, choppiness, repeatPeriod, newSeed);
    }

    public SeaState withWind(double newWindSpeed, double newWindDirection) {
        return new SeaState(newWindSpeed, newWindDirection, fetch, gamma, depth, swellHeight, swellPeriod,
                swellDirection, swellNarrowness, swellSpreadExponent, choppiness, repeatPeriod, seed);
    }

    /**
     * An open-ocean sea state for the given wind, with a moderate swell running
     * 40 degrees off the breeze.
     *
     * <p>The swell height here is a plausible companion to the wind, not a
     * measurement: real swell comes from a distant storm and is supplied by the
     * weather layer. This factory exists so the renderer has something sensible
     * to show before the weather service is wired in.
     */
    public static SeaState openOcean(double windSpeedMetresPerSecond, double windDirection, long seed) {
        double swellHs = 0.6 + 0.055 * windSpeedMetresPerSecond * windSpeedMetresPerSecond / 4.0;
        return new SeaState(
                windSpeedMetresPerSecond,
                windDirection,
                300_000.0,
                JonswapSpectrum.DEFAULT_GAMMA,
                4000.0,
                swellHs,
                11.0,
                windDirection + Math.toRadians(40.0),
                0.07,
                24.0,
                1.0,
                200.0,
                seed);
    }

    /**
     * A flat-water inshore course: short fetch, shallow, no swell.
     */
    public static SeaState inshore(double windSpeedMetresPerSecond, double windDirection, long seed) {
        return new SeaState(
                windSpeedMetresPerSecond,
                windDirection,
                4_000.0,
                JonswapSpectrum.DEFAULT_GAMMA,
                18.0,
                0.0,
                8.0,
                windDirection,
                0.1,
                24.0,
                0.9,
                200.0,
                seed);
    }

    @Override
    public String toString() {
        return String.format(
                "SeaState[wind=%.1f m/s @ %.0f deg, fetch=%.0f km, depth=%.0f m, swell=%.1f m / %.0f s, Hs=%.2f m]",
                windSpeed, Mth.wrap360(Math.toDegrees(windDirection)),
                fetch / 1000.0, depth, swellHeight, swellPeriod, significantWaveHeight());
    }
}
