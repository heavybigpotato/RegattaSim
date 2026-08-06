package com.bluemeridian.core.sailing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A polar is the one part of the performance model that could in principle be
 * checked against a real boat, so it is worth checking that it at least behaves
 * like a boat. These assertions are about shape rather than exact numbers: the
 * shipped table is an approximation and says so, but an approximation that let you
 * sail at 30 degrees off the wind, or go faster dead downwind than on a reach,
 * would not be an approximation of sailing.
 */
class PolarDiagramTest {

    private static final double KNOTS = 0.514444;
    private static final PolarDiagram POLAR =
            PolarDiagram.fromClasspath("polars/class40.csv");

    @Test
    @DisplayName("interpolation passes exactly through the tabulated nodes")
    void passesThroughNodes() {
        // 90 degrees at 12 knots is tabulated at 9.0 knots.
        assertEquals(9.0, POLAR.boatSpeedKnots(Math.toRadians(90), 12 * KNOTS), 1e-6);
        // 120 degrees at 20 knots is tabulated at 15.2.
        assertEquals(15.2, POLAR.boatSpeedKnots(Math.toRadians(120), 20 * KNOTS), 1e-6);
        // A corner of the table: dead downwind in 4 knots, which is barely steerage.
        assertEquals(1.9, POLAR.boatSpeedKnots(Math.toRadians(180), 4 * KNOTS), 1e-6);
    }

    @Test
    @DisplayName("there is a no-go zone and the boat cannot sail in it")
    void noGoZoneIsRespected() {
        for (double twa = 0; twa <= 33; twa += 3) {
            double speed = POLAR.boatSpeedKnots(Math.toRadians(twa), 12 * KNOTS);
            assertEquals(0.0, speed, 1e-9, "boat should not sail at " + twa + " degrees");
        }
        assertTrue(POLAR.boatSpeedKnots(Math.toRadians(45), 12 * KNOTS) > 5.0,
                "but it must go properly once sheeted in");
    }

    @Test
    @DisplayName("speed never goes negative, at any angle or wind")
    void neverNegative() {
        for (double twa = 0; twa <= 180; twa += 0.5) {
            for (double tws = 0; tws <= 40; tws += 0.5) {
                double v = POLAR.boatSpeed(Math.toRadians(twa), tws * KNOTS);
                assertTrue(v >= 0.0,
                        "negative speed " + v + " at TWA " + twa + " TWS " + tws);
            }
        }
    }

    @Test
    @DisplayName("the polar is symmetric about the centreline")
    void symmetricAboutCentreline() {
        for (double twa = 5; twa <= 175; twa += 5) {
            assertEquals(
                    POLAR.boatSpeed(Math.toRadians(twa), 14 * KNOTS),
                    POLAR.boatSpeed(Math.toRadians(-twa), 14 * KNOTS),
                    1e-12, "port and starboard must be identical at " + twa);
        }
    }

    @Test
    @DisplayName("more wind is never slower")
    void moreWindIsNeverSlower() {
        for (double twa = 40; twa <= 180; twa += 5) {
            double previous = -1;
            for (double tws = 4; tws <= 25; tws += 0.5) {
                double v = POLAR.boatSpeedKnots(Math.toRadians(twa), tws * KNOTS);
                assertTrue(v >= previous - 1e-6,
                        "speed fell from " + previous + " to " + v
                                + " at TWA " + twa + " as wind rose to " + tws + " kt");
                previous = v;
            }
        }
    }

    @Test
    @DisplayName("reaching is faster than running, as it is on the water")
    void reachingBeatsRunning() {
        for (double tws : new double[] {8, 12, 16, 20}) {
            double reach = POLAR.boatSpeedKnots(Math.toRadians(110), tws * KNOTS);
            double run = POLAR.boatSpeedKnots(Math.toRadians(180), tws * KNOTS);
            assertTrue(reach > run * 1.15,
                    "at " + tws + " kt, reaching " + reach + " should clearly beat running " + run);
        }
    }

    @Test
    @DisplayName("best upwind VMG lands where a boat like this actually points")
    void upwindVmgOptimumIsPlausible() {
        for (double tws : new double[] {8, 12, 16, 20}) {
            double best = Math.toDegrees(POLAR.bestUpwindAngle(tws * KNOTS));
            assertTrue(best > 38 && best < 55,
                    "best upwind angle at " + tws + " kt came out at " + best + " degrees");
        }
    }

    @Test
    @DisplayName("the boat sails deeper downwind as the breeze builds")
    void downwindAngleDeepensWithBreeze() {
        double light = Math.toDegrees(POLAR.bestDownwindAngle(8 * KNOTS));
        double heavy = Math.toDegrees(POLAR.bestDownwindAngle(25 * KNOTS));
        assertTrue(light > 120 && light < 180, "light-air running angle " + light);
        assertTrue(heavy > 120 && heavy < 180, "heavy-air running angle " + heavy);
        // A planing boat soaks lower once it is up and going.
        assertTrue(heavy >= light - 1e-9,
                "expected to sail deeper in breeze: " + heavy + " vs " + light);
    }

    @Test
    @DisplayName("upwind VMG is positive and downwind VMG is negative")
    void vmgSignsAreRight() {
        assertTrue(POLAR.velocityMadeGood(Math.toRadians(45), 12 * KNOTS) > 0);
        assertTrue(POLAR.velocityMadeGood(Math.toRadians(150), 12 * KNOTS) < 0);
    }

    @Test
    @DisplayName("wind beyond the table is clamped, not extrapolated")
    void windIsClampedNotExtrapolated() {
        double top = POLAR.boatSpeedKnots(Math.toRadians(120), 30 * KNOTS);
        double beyond = POLAR.boatSpeedKnots(Math.toRadians(120), 80 * KNOTS);
        assertEquals(top, beyond, 1e-9, "a hurricane must not invent speed");

        double bottom = POLAR.boatSpeedKnots(Math.toRadians(120), 4 * KNOTS);
        double below = POLAR.boatSpeedKnots(Math.toRadians(120), 0.5 * KNOTS);
        assertEquals(bottom, below, 1e-9);
    }

    @Test
    @DisplayName("the speed curve has no steps in it")
    void curveIsSmooth() {
        // Bilinear interpolation would put a kink at every tabulated angle. Sweep
        // finely and check the second difference never spikes, which is what a kink
        // is: the helm must not feel corners in the groove.
        double tws = 12 * KNOTS;
        double step = Math.toRadians(0.25);
        double worst = 0;
        double worstAt = 0;
        for (double twa = Math.toRadians(42); twa < Math.toRadians(178); twa += step) {
            double a = POLAR.boatSpeed(twa - step, tws);
            double b = POLAR.boatSpeed(twa, tws);
            double c = POLAR.boatSpeed(twa + step, tws);
            double secondDifference = Math.abs(a - 2 * b + c);
            if (secondDifference > worst) {
                worst = secondDifference;
                worstAt = Math.toDegrees(twa);
            }
        }
        assertTrue(worst < 0.02,
                "curvature spike of " + worst + " m/s at " + worstAt + " degrees suggests a kink");
    }

    @Test
    @DisplayName("a malformed table is rejected rather than silently half-loaded")
    void malformedTableIsRejected() {
        String ragged = "twa\\tws,6,10\n40,4.0,5.0\n60,4.5\n";
        assertThrows(IllegalArgumentException.class, () ->
                PolarDiagram.parse("ragged", new ByteArrayInputStream(
                        ragged.getBytes(StandardCharsets.UTF_8))));

        String unsorted = "twa\\tws,10,6\n40,4.0,5.0\n60,4.5,5.5\n";
        assertThrows(IllegalArgumentException.class, () ->
                PolarDiagram.parse("unsorted", new ByteArrayInputStream(
                        unsorted.getBytes(StandardCharsets.UTF_8))));
    }
}
