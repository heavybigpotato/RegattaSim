package com.bluemeridian.core.sailing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The apparent wind is one vector subtraction, and it is still the single easiest
 * place in a sailing simulator to be quietly wrong: a sign flip produces a boat
 * that sails, tacks and gybes perfectly well while being the mirror image of a
 * real one. These cases pin the behaviours a sailor would notice immediately.
 */
class ApparentWindTest {

    private static final double KNOTS = 0.514444;
    private static final double EPS = 1e-9;

    /** Wind blowing toward +X, i.e. a wind out of the west if +X is east. */
    private static final double WIND_TOWARD_EAST = 0.0;

    @Test
    @DisplayName("a stationary boat feels the true wind exactly")
    void stationaryBoatFeelsTrueWind() {
        // Bow pointing into the wind: the wind comes from -X, boat heads -X.
        ApparentWind aw = ApparentWind.of(10.0, WIND_TOWARD_EAST, 0.0, Math.PI);
        assertEquals(10.0, aw.speed, EPS);
        assertEquals(0.0, Math.toDegrees(aw.angle), 1e-6, "head to wind is 0 degrees");
        assertEquals(0.0, Math.toDegrees(aw.trueAngle), 1e-6);
    }

    @Test
    @DisplayName("running dead downwind at wind speed leaves no wind on deck")
    void runningAtWindSpeedCancelsTheWind() {
        // Boat travelling with the wind, at the same speed.
        ApparentWind aw = ApparentWind.of(8.0, WIND_TOWARD_EAST, 8.0, 0.0);
        assertEquals(0.0, aw.speed, 1e-9, "apparent wind must vanish");
        assertEquals(180.0, Math.abs(Math.toDegrees(aw.trueAngle)), 1e-6, "dead downwind");
    }

    @Test
    @DisplayName("running slower than the wind leaves the difference")
    void runningSlowerLeavesTheDifference() {
        ApparentWind aw = ApparentWind.of(10.0, WIND_TOWARD_EAST, 6.0, 0.0);
        assertEquals(4.0, aw.speed, 1e-9);
        assertEquals(180.0, Math.abs(Math.toDegrees(aw.angle)), 1e-6);
    }

    @Test
    @DisplayName("beating adds the boat's speed and draws the wind forward")
    void beatingIncreasesAndDrawsForward() {
        // 12 kt true, boat close-hauled at 45 degrees making 8 kt.
        double tws = 12 * KNOTS;
        double boat = 8 * KNOTS;
        // Wind comes from -X; sailing 45 degrees off it.
        double heading = Math.PI - Math.toRadians(45);
        ApparentWind aw = ApparentWind.of(tws, WIND_TOWARD_EAST, boat, heading);

        assertEquals(45.0, Math.abs(Math.toDegrees(aw.trueAngle)), 1e-6);
        assertTrue(aw.speed > tws,
                "apparent wind should exceed true wind upwind: " + aw.speed + " vs " + tws);
        assertTrue(aw.angleMagnitude() < aw.trueAngleMagnitude(),
                "apparent wind must draw forward of true: AWA "
                        + Math.toDegrees(aw.angleMagnitude()) + " vs TWA "
                        + Math.toDegrees(aw.trueAngleMagnitude()));

        // Closed form: components along and across the boat's axis.
        double alongAxis = tws * Math.cos(Math.toRadians(45)) + boat;
        double acrossAxis = tws * Math.sin(Math.toRadians(45));
        assertEquals(Math.hypot(alongAxis, acrossAxis), aw.speed, 1e-9);
    }

    @Test
    @DisplayName("reaching draws the wind forward without a following sea of maths")
    void reachingDrawsForward() {
        double tws = 10.0;
        double boat = 7.0;
        // Beam reach: wind on the beam, 90 degrees off the bow.
        double heading = Math.PI / 2;
        ApparentWind aw = ApparentWind.of(tws, WIND_TOWARD_EAST, boat, heading);
        assertEquals(90.0, Math.abs(Math.toDegrees(aw.trueAngle)), 1e-6);
        assertTrue(aw.angleMagnitude() < Math.toRadians(90),
                "on a beam reach the apparent wind moves ahead of the beam, got "
                        + Math.toDegrees(aw.angleMagnitude()));
        assertEquals(Math.hypot(tws, boat), aw.speed, 1e-9);
    }

    @Test
    @DisplayName("tack is read from which side the wind crosses")
    void tackIsCorrect() {
        // Wind toward +X, so it arrives from -X. Heading north-ish of that axis puts
        // the wind on one side; south-ish puts it on the other.
        ApparentWind a = ApparentWind.of(10, WIND_TOWARD_EAST, 5, Math.PI - 0.6);
        ApparentWind b = ApparentWind.of(10, WIND_TOWARD_EAST, 5, Math.PI + 0.6);
        assertTrue(a.isStarboardTack() != b.isStarboardTack(),
                "mirrored headings must be on opposite tacks");
        assertEquals(Math.abs(a.trueAngle), Math.abs(b.trueAngle), 1e-9,
                "and they must be the same angle off the wind");
    }

    @Test
    @DisplayName("the angle is measured from the bow whatever the compass says")
    void headingIsIrrelevantToTheAngle() {
        // The same geometry, rotated around the compass, must give the same answer.
        double reference = -1.0;
        for (double rotation = 0; rotation < Math.PI * 2; rotation += 0.37) {
            ApparentWind aw = ApparentWind.of(11.0, rotation, 6.0,
                    rotation + Math.PI - Math.toRadians(50));
            if (reference < 0) {
                reference = aw.speed;
            }
            assertEquals(reference, aw.speed, 1e-9, "speed must not depend on compass rotation");
            assertEquals(50.0, Math.abs(Math.toDegrees(aw.trueAngle)), 1e-6);
        }
    }

    @Test
    @DisplayName("accelerating in steady wind heads the boat, as it does on the water")
    void accelerationHeadsTheBoat() {
        double tws = 12 * KNOTS;
        double heading = Math.PI - Math.toRadians(50);
        double slow = ApparentWind.of(tws, WIND_TOWARD_EAST, 3 * KNOTS, heading).angleMagnitude();
        double fast = ApparentWind.of(tws, WIND_TOWARD_EAST, 9 * KNOTS, heading).angleMagnitude();
        assertTrue(fast < slow,
                "going faster must pull the apparent wind forward: "
                        + Math.toDegrees(fast) + " vs " + Math.toDegrees(slow));
    }

    @Test
    @DisplayName("with no wind at all the boat still reports a sane apparent wind")
    void noWindIsHandled() {
        ApparentWind aw = ApparentWind.of(1e-12, 0.4, 5.0, 1.1);
        assertEquals(5.0, aw.speed, 1e-6, "the boat makes its own wind");
        assertEquals(0.0, Math.toDegrees(aw.trueAngle), 1e-9, "no true wind means no true angle");
    }
}
