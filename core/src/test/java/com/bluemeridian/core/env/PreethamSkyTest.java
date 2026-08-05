package com.bluemeridian.core.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the Preetham coefficient tables.
 *
 * <p>A sky model with one mistyped digit still produces a smooth blue gradient
 * that looks perfectly acceptable in isolation and is quietly the wrong colour
 * forever. These assertions pin the behaviour that the tables have to reproduce:
 * absolute brightness in the right units, the right direction of change as the
 * sun moves, and chromaticity in the range a daylight sky actually occupies.
 */
class PreethamSkyTest {

    private static final double CLEAR = 2.2;

    @Test
    @DisplayName("looking at the zenith returns the zenith luminance exactly")
    void zenithNormalisationIsExact() {
        for (double elevation : new double[] {5, 20, 45, 80}) {
            double sunZenith = Math.toRadians(90 - elevation);
            double expected = PreethamSky.usableZenithLuminance(sunZenith, CLEAR);
            // Looking straight up, gamma equals the sun's zenith angle.
            double actual = PreethamSky.luminance(0.0, sunZenith, sunZenith, CLEAR);
            assertEquals(expected, actual, Math.abs(expected) * 1e-9,
                    "sun elevation " + elevation);
        }
    }

    @Test
    @DisplayName("a clear midday zenith is a few kcd/m^2")
    void zenithLuminanceIsPhysical() {
        double midday = PreethamSky.zenithLuminance(Math.toRadians(10), CLEAR);
        // Real clear-sky zenith luminance is roughly 5000-10000 cd/m^2.
        assertTrue(midday > 3.0 && midday < 15.0, "midday zenith = " + midday + " kcd/m^2");

        double lowSun = PreethamSky.usableZenithLuminance(Math.toRadians(85), CLEAR);
        assertTrue(lowSun > 0.0 && lowSun < midday,
                "zenith should dim as the sun sets: " + lowSun + " vs " + midday);
    }

    @Test
    @DisplayName("the raw model is documented as invalid near sunrise")
    void rawModelGoesNegativeAtLowSun() {
        // Pinned deliberately. This is the published model's actual behaviour, and
        // the reason usableZenithLuminance exists. If a future edit "fixes" the
        // coefficients so this passes, the coefficients have been changed away from
        // Preetham and every other value in the table is now suspect.
        double raw = PreethamSky.zenithLuminance(Math.toRadians(85), CLEAR);
        assertTrue(raw < 0.0, "expected the unclamped model to be negative, got " + raw);

        double usable = PreethamSky.usableZenithLuminance(Math.toRadians(85), CLEAR);
        assertTrue(usable > 0.0 && usable < 1.0, "usable twilight zenith = " + usable);
    }

    @Test
    @DisplayName("the sky brightens monotonically as the sun rises")
    void brightnessTracksSunElevation() {
        double previous = -1.0;
        for (double elevation = -4; elevation <= 88; elevation += 4) {
            double mean = PreethamSky.meanDomeLuminance(Math.toRadians(elevation), CLEAR);
            assertTrue(mean > previous,
                    "mean dome luminance fell from " + previous + " to " + mean
                            + " at elevation " + elevation);
            previous = mean;
        }
    }

    @Test
    @DisplayName("the sky is brighter toward the sun than away from it")
    void solarAureoleIsBrighter() {
        double sunZenith = Math.toRadians(60);
        // Same zenith angle as the sun, once at the sun and once opposite it.
        double towardSun = PreethamSky.luminance(sunZenith, Math.toRadians(15), sunZenith, CLEAR);
        double awayFromSun = PreethamSky.luminance(sunZenith, Math.toRadians(150), sunZenith, CLEAR);
        assertTrue(towardSun > awayFromSun * 1.5,
                "toward sun " + towardSun + " should clearly exceed away " + awayFromSun);
    }

    @Test
    @DisplayName("the horizon is brighter than the zenith when the sun is low")
    void horizonBrightensAtLowSun() {
        double sunZenith = Math.toRadians(85);
        double zenith = PreethamSky.luminance(0.0, sunZenith, sunZenith, CLEAR);
        double horizonNearSun = PreethamSky.luminance(
                Math.toRadians(88), Math.toRadians(10), sunZenith, CLEAR);
        assertTrue(horizonNearSun > zenith,
                "low sun should light the horizon (" + horizonNearSun
                        + ") above the zenith (" + zenith + ")");
    }

    @Test
    @DisplayName("zenith chromaticity stays inside the daylight locus")
    void chromaticityIsPlausible() {
        for (double elevation : new double[] {3, 15, 40, 75}) {
            double sunZenith = Math.toRadians(90 - elevation);
            for (double turbidity : new double[] {2.0, 3.5, 6.0}) {
                double x = PreethamSky.zenithChromaticityX(sunZenith, turbidity);
                double y = PreethamSky.zenithChromaticityY(sunZenith, turbidity);
                // Daylight ranges roughly x in [0.24, 0.50], y in [0.23, 0.45].
                assertTrue(x > 0.2 && x < 0.55,
                        "x=" + x + " at elevation " + elevation + " turbidity " + turbidity);
                assertTrue(y > 0.2 && y < 0.5,
                        "y=" + y + " at elevation " + elevation + " turbidity " + turbidity);
                assertTrue(x + y < 1.0, "x+y must stay inside the chromaticity triangle");
            }
        }
    }

    @Test
    @DisplayName("haze raises the zenith luminance and desaturates it")
    void turbidityBehaves() {
        double sunZenith = Math.toRadians(40);
        double clear = PreethamSky.zenithLuminance(sunZenith, 2.0);
        double hazy = PreethamSky.zenithLuminance(sunZenith, 6.0);
        assertTrue(hazy > clear, "haze scatters more light: " + hazy + " vs " + clear);

        // More haze means a whiter sky, so chromaticity moves toward the equal
        // energy point at (1/3, 1/3).
        double clearX = PreethamSky.zenithChromaticityX(sunZenith, 2.0);
        double hazyX = PreethamSky.zenithChromaticityX(sunZenith, 6.0);
        assertTrue(Math.abs(hazyX - 1.0 / 3.0) < Math.abs(clearX - 1.0 / 3.0),
                "hazy x " + hazyX + " should sit closer to white than clear x " + clearX);
    }

    @Test
    @DisplayName("twilight stays positive and keeps darkening below the horizon")
    void twilightRemainsUsable() {
        double justSet = PreethamSky.meanDomeLuminance(Math.toRadians(-1), CLEAR);
        double deeper = PreethamSky.meanDomeLuminance(Math.toRadians(-6), CLEAR);
        assertTrue(justSet > 0.0, "twilight must still return a usable value for exposure");
        assertTrue(deeper > 0.0 && deeper < justSet,
                "it must keep darkening: " + deeper + " vs " + justSet);
    }
}
