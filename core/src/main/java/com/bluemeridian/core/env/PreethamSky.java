package com.bluemeridian.core.env;

import com.bluemeridian.core.math.Mth;

/**
 * The Preetham daylight model, in plain Java.
 *
 * <p>This is a duplicate of what {@code lib_sky.glsl} evaluates per pixel, and it
 * exists for two reasons. First, it makes the coefficient tables testable: a
 * mistyped digit in a Perez coefficient produces a sky that is subtly the wrong
 * colour, which is close to impossible to spot by eye but trivial to catch with
 * an assertion. Second, the renderer needs the absolute luminance of the sky on
 * the CPU to choose an exposure, and evaluating the same model that produced the
 * image is more stable than measuring the image afterwards.
 *
 * <p>Luminance is returned in kcd/m^2, the units the model's zenith formula is
 * written in. A clear midday zenith lands around 8, an overcast one lower, and a
 * sun a few degrees above the horizon around 1.
 *
 * <p>The model is only defined for a sun above the horizon; below that it returns
 * zero rather than the negative values the formula would otherwise produce.
 */
public final class PreethamSky {

    private PreethamSky() {
    }

    /** Perez luminance coefficients A..E as linear functions of turbidity. */
    private static double[] luminanceCoefficients(double t) {
        return new double[] {
                0.1787 * t - 1.4630,
                -0.3554 * t + 0.4275,
                -0.0227 * t + 5.3251,
                0.1206 * t - 2.5771,
                -0.0670 * t + 0.3703,
        };
    }

    private static double[] chromaticityXCoefficients(double t) {
        return new double[] {
                -0.0193 * t - 0.2592,
                -0.0665 * t + 0.0008,
                -0.0004 * t + 0.2125,
                -0.0641 * t - 0.8989,
                -0.0033 * t + 0.0452,
        };
    }

    private static double[] chromaticityYCoefficients(double t) {
        return new double[] {
                -0.0167 * t - 0.2608,
                -0.0950 * t + 0.0092,
                -0.0079 * t + 0.2102,
                -0.0441 * t - 1.6537,
                -0.0109 * t + 0.0529,
        };
    }

    /**
     * The Perez sky luminance distribution function.
     *
     * @param cosTheta cosine of the angle from the zenith to the view direction
     * @param gamma    angle between the view direction and the sun, radians
     */
    public static double perez(double cosTheta, double gamma, double[] c) {
        double cosGamma = Math.cos(gamma);
        return (1.0 + c[0] * Math.exp(c[1] / Math.max(cosTheta, 0.01)))
                * (1.0 + c[2] * Math.exp(c[3] * gamma) + c[4] * cosGamma * cosGamma);
    }

    /**
     * Sun elevation below which the zenith formula stops being trustworthy.
     *
     * <p>Preetham's zenith luminance is a fit over daytime measurements and it does
     * not hold near sunrise or sunset: with clear air at a 5 degree elevation it
     * returns a <em>negative</em> luminance. That is a known limitation of the
     * model, not a transcription error - the midday value it produces, around
     * 9 kcd/m^2, matches reality closely - and it is one of the reasons
     * Hosek-Wilkie was published. {@code PreethamSkyTest} pins both behaviours.
     */
    public static final double VALIDITY_ELEVATION_LIMIT = Math.toRadians(10.0);

    /** How fast twilight darkens below the validity limit, per radian of elevation. */
    private static final double TWILIGHT_FALLOFF = 17.2;

    /**
     * Absolute zenith luminance straight from the model, kcd/m^2.
     *
     * <p>Can return a negative value for a low sun; see
     * {@link #VALIDITY_ELEVATION_LIMIT}. Use {@link #usableZenithLuminance} for
     * anything that has to render.
     */
    public static double zenithLuminance(double sunZenithAngle, double turbidity) {
        double chi = (4.0 / 9.0 - turbidity / 120.0) * (Math.PI - 2.0 * sunZenithAngle);
        return (4.0453 * turbidity - 4.9710) * Math.tan(chi) - 0.2155 * turbidity + 0.1208;
    }

    /**
     * Zenith luminance that is always positive and always decreases as the sun
     * sets, kcd/m^2.
     *
     * <p>Above the validity limit this is the model verbatim. Below it, the model's
     * value <em>at</em> the limit is carried down and faded exponentially, which
     * keeps twilight monotonic and physically ordered without pretending the
     * formula still applies. A sunset therefore darkens smoothly instead of
     * inverting, at the cost of not reproducing the exact radiance of the last few
     * degrees - which is the part Preetham never described anyway.
     */
    public static double usableZenithLuminance(double sunZenithAngle, double turbidity) {
        double elevation = Math.PI / 2.0 - sunZenithAngle;
        if (elevation >= VALIDITY_ELEVATION_LIMIT) {
            return zenithLuminance(sunZenithAngle, turbidity);
        }
        double atLimit = zenithLuminance(Math.PI / 2.0 - VALIDITY_ELEVATION_LIMIT, turbidity);
        return atLimit * Math.exp((elevation - VALIDITY_ELEVATION_LIMIT) * TWILIGHT_FALLOFF);
    }

    /** Zenith chromaticity x. */
    public static double zenithChromaticityX(double sunZenithAngle, double turbidity) {
        double ts = sunZenithAngle;
        double ts2 = ts * ts;
        double ts3 = ts2 * ts;
        double t2 = turbidity * turbidity;
        return (0.00166 * ts3 - 0.00375 * ts2 + 0.00209 * ts) * t2
                + (-0.02903 * ts3 + 0.06377 * ts2 - 0.03202 * ts + 0.00394) * turbidity
                + (0.11693 * ts3 - 0.21196 * ts2 + 0.06052 * ts + 0.25886);
    }

    /** Zenith chromaticity y. */
    public static double zenithChromaticityY(double sunZenithAngle, double turbidity) {
        double ts = sunZenithAngle;
        double ts2 = ts * ts;
        double ts3 = ts2 * ts;
        double t2 = turbidity * turbidity;
        return (0.00275 * ts3 - 0.00610 * ts2 + 0.00317 * ts) * t2
                + (-0.04214 * ts3 + 0.08970 * ts2 - 0.04153 * ts + 0.00516) * turbidity
                + (0.15346 * ts3 - 0.26756 * ts2 + 0.06670 * ts + 0.26688);
    }

    /**
     * Luminance in a given direction, kcd/m^2.
     *
     * @param viewZenithAngle angle from straight up to the view direction, radians
     * @param gamma           angle between the view direction and the sun, radians
     * @param sunZenithAngle  angle from straight up to the sun, radians
     * @param turbidity       atmospheric turbidity; 2 is exceptionally clear, 6 is hazy
     */
    public static double luminance(double viewZenithAngle, double gamma, double sunZenithAngle,
            double turbidity) {
        double[] c = luminanceCoefficients(turbidity);
        double zenith = usableZenithLuminance(sunZenithAngle, turbidity);
        double f = perez(Math.cos(viewZenithAngle), gamma, c);
        double f0 = perez(1.0, sunZenithAngle, c);
        return Math.max(0.0, zenith * f / f0);
    }

    /**
     * A luminance representative of the whole sky dome, used to pick an exposure.
     *
     * <p>Averaged over a coarse hemisphere with a cosine weight, which approximates
     * what a horizontal surface actually receives. The sun's own disc is excluded:
     * including it would let a single tiny direction dominate the average and the
     * exposure would swing wildly as the sun crossed the horizon.
     *
     * @param sunElevation sun elevation above the horizon, radians
     */
    public static double meanDomeLuminance(double sunElevation, double turbidity) {
        double sunZenith = Math.PI / 2.0 - sunElevation;
        int thetaSteps = 12;
        int phiSteps = 24;
        double sum = 0.0;
        double weightSum = 0.0;
        for (int i = 0; i < thetaSteps; i++) {
            double theta = (i + 0.5) / thetaSteps * (Math.PI / 2.0);
            double cosTheta = Math.cos(theta);
            double sinTheta = Math.sin(theta);
            for (int j = 0; j < phiSteps; j++) {
                double phi = (j + 0.5) / phiSteps * Mth.TAU;
                // Angle between this direction and the sun, both on the unit sphere.
                double cosGamma = sinTheta * Math.cos(phi) * Math.sin(sunZenith)
                        + cosTheta * Math.cos(sunZenith);
                double gamma = Math.acos(Math.max(-1.0, Math.min(1.0, cosGamma)));
                // Skip the solar aureole so one direction cannot dominate.
                if (gamma < Math.toRadians(6.0)) {
                    continue;
                }
                double weight = cosTheta * sinTheta;
                sum += luminance(theta, gamma, sunZenith, turbidity) * weight;
                weightSum += weight;
            }
        }
        return weightSum > 0.0 ? sum / weightSum : 0.0;
    }
}
