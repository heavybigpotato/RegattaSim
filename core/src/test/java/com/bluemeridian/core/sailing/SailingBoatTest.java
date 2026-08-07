package com.bluemeridian.core.sailing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bluemeridian.core.ocean.WaveSurface;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The brief's acceptance test for this phase is that beating and gybing "feel
 * right" and that the boat visibly answers every wave. Feel is not something a
 * test can assert, but the things that would <em>stop</em> it feeling right are:
 * a boat that reaches its polar instantly, one that pivots on the spot, one that
 * steers while stopped, one that ignores the sea it is sitting in.
 */
class SailingBoatTest {

    private static final double KNOTS = 0.514444;
    private static final PolarDiagram POLAR = PolarDiagram.fromClasspath("polars/class40.csv");
    /** Wind blowing toward +X, so it arrives from -X. */
    private static final double WIND_TOWARD = 0.0;

    private static SailingBoat boat() {
        return new SailingBoat(POLAR, SailingBoat.HullShape.class40());
    }

    /** Runs the boat for a while in flat water and returns it. */
    private static SailingBoat sail(SailingBoat b, double seconds, double twsKnots) {
        for (double t = 0; t < seconds; t += 0.05) {
            b.advance(0.05, twsKnots * KNOTS, WIND_TOWARD, WaveSurface.FLAT, t);
        }
        return b;
    }

    @Test
    @DisplayName("the boat settles onto its polar speed, and takes time to do it")
    void convergesToPolarSpeed() {
        SailingBoat b = boat();
        // Close-hauled at 45 degrees on port: the wind blows toward +X so it
        // arrives from 180, and a heading of 135 puts that 45 degrees off the
        // port bow, which ApparentWind reports as a negative angle.
        b.setPosition(0, 0, Math.PI - Math.toRadians(45));

        sail(b, 1.0, 12);
        double after1s = b.speedKnots();
        sail(b, 59.0, 12);
        double after60s = b.speedKnots();

        double target = POLAR.boatSpeedKnots(Math.toRadians(45), 12 * KNOTS);
        assertTrue(after1s < target * 0.3,
                "a boat does not reach its polar in one second: " + after1s + " of " + target);
        assertEquals(target, after60s, target * 0.02,
                "but it should be there after a minute: " + after60s + " vs " + target);
    }

    @Test
    @DisplayName("head to wind, the boat stops")
    void inIronsTheBoatStops() {
        SailingBoat b = boat();
        b.setPosition(0, 0, Math.PI);   // bow straight into the wind
        sail(b, 60, 12);
        assertTrue(b.speedKnots() < 0.5,
                "pointing at the wind should stop the boat, got " + b.speedKnots() + " kt");
    }

    @Test
    @DisplayName("a stopped boat cannot steer, which is what being stuck in irons means")
    void steerageRequiresWayOn() {
        SailingBoat b = boat();
        b.setPosition(0, 0, Math.PI);
        b.setRudder(1.0);
        double before = b.heading();
        // Two seconds of full helm from a standstill, head to wind.
        for (double t = 0; t < 2.0; t += 0.05) {
            b.advance(0.05, 12 * KNOTS, WIND_TOWARD, WaveSurface.FLAT, t);
        }
        // Headings must be compared as angles. This boat starts at exactly PI,
        // which wrapPi represents as -PI, so a raw subtraction reads a stationary
        // boat as having spun through 360 degrees.
        double turned = Math.abs(
                com.bluemeridian.core.math.Mth.wrapPi(b.heading() - before));
        assertTrue(turned < Math.toRadians(2),
                "a stopped boat turned " + Math.toDegrees(turned) + " degrees on full helm");
    }

    @Test
    @DisplayName("with way on, full helm turns the boat at a bounded rate")
    void turnRateIsBounded() {
        SailingBoat b = boat();
        b.setPosition(0, 0, Math.PI / 2);       // beam reach, plenty of speed
        sail(b, 60, 14);
        assertTrue(b.speedKnots() > 5, "needs way on first, got " + b.speedKnots());

        b.setRudder(1.0);
        double before = b.heading();
        for (double t = 0; t < 1.0; t += 0.05) {
            b.advance(0.05, 14 * KNOTS, WIND_TOWARD, WaveSurface.FLAT, t);
        }
        double rate = Math.abs(com.bluemeridian.core.math.Mth.wrapPi(b.heading() - before));
        assertTrue(rate > Math.toRadians(5) && rate < Math.toRadians(30),
                "one second of full helm turned " + Math.toDegrees(rate) + " degrees");
    }

    @Test
    @DisplayName("which tack the boat is on follows from its heading, not from hope")
    void tackFollowsFromHeading() {
        // Worth pinning explicitly, because a heading is not a tack and reading one
        // off the other by eye is how the comments in this file were wrong before.
        // The wind blows toward +X, so it arrives from 180 degrees. Headings run
        // from +X toward +Z and +Z is to port, so a heading *below* 180 puts the
        // wind on the port bow and a heading above it puts the wind to starboard.
        SailingBoat port = boat();
        port.setPosition(0, 0, Math.PI - Math.toRadians(45));
        port.advance(0.05, 12 * KNOTS, WIND_TOWARD, WaveSurface.FLAT, 0);
        assertEquals(-45, Math.toDegrees(port.wind().trueAngle), 1e-9);
        assertTrue(!port.wind().isStarboardTack(), "heading 135 is port tack");

        SailingBoat starboard = boat();
        starboard.setPosition(0, 0, -(Math.PI - Math.toRadians(45)));
        starboard.advance(0.05, 12 * KNOTS, WIND_TOWARD, WaveSurface.FLAT, 0);
        assertEquals(45, Math.toDegrees(starboard.wind().trueAngle), 1e-9);
        assertTrue(starboard.wind().isStarboardTack(), "heading -135 is starboard tack");
    }

    @Test
    @DisplayName("helm sign matches the documented convention: negative is to port")
    void helmSignIsCorrect() {
        // Starboard is 90 degrees clockwise from the bow, and the hull's own
        // starboard sample point is at (sin, -cos) of the heading - so a starboard
        // turn must move the bow toward that side. This caught a real sign error:
        // the helm was inverted, so what looked like a tack was actually a gybe,
        // and the boat came out of it faster instead of slower.
        SailingBoat b = boat();
        b.setPosition(0, 0, Math.PI / 2);
        sail(b, 60, 14);

        double before = b.heading();
        b.setRudder(-1.0);                 // helm to port
        for (double t = 0; t < 2.0; t += 0.05) {
            b.advance(0.05, 14 * KNOTS, WIND_TOWARD, WaveSurface.FLAT, t);
        }
        double delta = com.bluemeridian.core.math.Mth.wrapPi(b.heading() - before);
        assertTrue(delta > 0,
                "port helm must increase the heading in this frame, got "
                        + Math.toDegrees(delta) + " degrees");
    }

    @Test
    @DisplayName("a tack costs speed and the boat has to rebuild it")
    void tackingCostsSpeed() {
        SailingBoat b = boat();
        b.setPosition(0, 0, Math.PI - Math.toRadians(45));
        sail(b, 90, 12);
        double before = b.speedKnots();

        // Put the helm down and swing through the wind onto the other tack. The
        // exit condition watches the tack itself: the true wind angle changes sign
        // as the bow passes through the eye of the wind.
        boolean startedOnStarboard = b.wind().isStarboardTack();
        b.setRudder(-1.0);
        double elapsed = 0;
        while (elapsed < 15.0) {
            b.advance(0.05, 12 * KNOTS, WIND_TOWARD, WaveSurface.FLAT, elapsed);
            elapsed += 0.05;
            if (b.wind().isStarboardTack() != startedOnStarboard
                    && Math.abs(b.wind().trueAngle) > Math.toRadians(43)) {
                break;
            }
        }
        b.setRudder(0);
        assertTrue(b.wind().isStarboardTack() != startedOnStarboard,
                "the boat should have come out on the other tack");
        double justAfter = b.speedKnots();
        assertTrue(justAfter < before,
                "a tack must cost speed: " + justAfter + " vs " + before);

        sail(b, 90, 12);
        assertTrue(b.speedKnots() > justAfter,
                "and the boat must rebuild it afterwards");
    }

    @Test
    @DisplayName("bad trim costs about fifteen percent, not the whole boat")
    void trimScalesThePolar() {
        SailingBoat good = boat();
        good.setPosition(0, 0, Math.PI / 2);
        good.setTrim(1.0);
        sail(good, 120, 12);

        SailingBoat bad = boat();
        bad.setPosition(0, 0, Math.PI / 2);
        bad.setTrim(0.0);
        sail(bad, 120, 12);

        double ratio = bad.speedKnots() / good.speedKnots();
        assertTrue(ratio > 0.83 && ratio < 0.88,
                "badly trimmed boat made " + (ratio * 100) + "% of the well trimmed one");
    }

    @Test
    @DisplayName("the hull sits on the water and answers the waves")
    void attitudeFollowsTheSurface() {
        // A swell running along +X with a wavelength of four boat lengths.
        WaveSurface swell = WaveSurface.sine(1.5, 48.0, 0.0, 0.0);
        SailingBoat b = boat();
        b.setPosition(0, 0, 0);  // bow along +X, straight into the swell's travel

        double minHeave = Double.MAX_VALUE;
        double maxHeave = -Double.MAX_VALUE;
        double maxPitch = 0;
        for (double t = 0; t < 40; t += 0.05) {
            b.advance(0.05, 12 * KNOTS, WIND_TOWARD, swell, t);
            minHeave = Math.min(minHeave, b.heave());
            maxHeave = Math.max(maxHeave, b.heave());
            maxPitch = Math.max(maxPitch, Math.abs(b.pitch()));
        }

        assertTrue(maxHeave - minHeave > 1.5,
                "the boat should rise and fall through the swell, range was "
                        + (maxHeave - minHeave) + " m");
        assertTrue(maxHeave <= 1.51 && minHeave >= -1.51,
                "but never further than the wave is tall");
        assertTrue(maxPitch > Math.toRadians(3),
                "and it should pitch, got " + Math.toDegrees(maxPitch) + " degrees");
    }

    @Test
    @DisplayName("a hull along the crest heels, one across it pitches")
    void pitchAndRollAreNotSwapped() {
        // Swell travelling along +X: the slope is along X, flat along Z.
        WaveSurface swell = WaveSurface.sine(1.0, 30.0, 0.0, 0.0);

        // Sampled at a zero crossing, where the slope is steepest. A crest would
        // have been the worst possible choice: the surface is flat there, so
        // neither pitch nor roll would have anything to register.
        SailingBoat intoIt = boat();
        intoIt.setPosition(0, 0, 0);            // bow along the slope
        intoIt.advance(0.05, 1e-6, WIND_TOWARD, swell, 0);

        SailingBoat acrossIt = boat();
        acrossIt.setPosition(0, 0, Math.PI / 2);    // bow along the crest
        acrossIt.advance(0.05, 1e-6, WIND_TOWARD, swell, 0);

        // Compared against the wave-induced part only: the rig's heel is a constant
        // offset on the roll and would otherwise swamp what this test is measuring.
        double intoItWaveRoll = intoIt.roll() - intoIt.windHeel();
        double acrossItWaveRoll = acrossIt.roll() - acrossIt.windHeel();
        assertTrue(Math.abs(intoIt.pitch()) > Math.abs(intoItWaveRoll) + 0.01,
                "pointing along the slope must pitch, not heel");
        assertTrue(Math.abs(acrossItWaveRoll) > Math.abs(acrossIt.pitch()) + 0.01,
                "pointing along the crest must heel, not pitch");
    }

    @Test
    @DisplayName("flat water means no wave motion, but the boat still heels to its sails")
    void flatWaterLeavesOnlyTheHeel() {
        SailingBoat b = boat();
        b.setPosition(3, -7, 1.1);
        // Long enough for both lags to settle - the heel building against the rig,
        // and the roll following the heel. Five seconds is not: the roll is still
        // half a degree behind, which is the damping working rather than a fault.
        sail(b, 60, 10);
        assertEquals(0.0, b.heave(), 1e-6);
        assertEquals(0.0, b.pitch(), 1e-6);
        // Flat water contributes nothing to the roll, so whatever is left is the rig.
        assertEquals(b.windHeel(), b.roll(), 1e-4);
    }

    @Test
    @DisplayName("the hull lags the wave slope instead of snapping to it")
    void attitudeIsDamped() {
        // A hull has rotational inertia: it never quite reaches the slope it is
        // sitting on before the wave has moved on. Reading the plane fit straight
        // out is fine in a two metre sea and violent in a twelve metre one - the
        // boat snapped through forty degrees between frames and looked broken, which
        // is what a player reported.
        //
        // A short, steep swell is the case that exposes it: the slope under the hull
        // reverses every couple of seconds, so an undamped boat would track it and a
        // damped one cannot.
        WaveSurface steep = WaveSurface.sine(3.0, 26.0, 0.0, 0.0);
        SailingBoat b = boat();
        b.setPosition(0, 0, Math.PI / 2);        // beam on, so the swell rolls her

        double worstRate = 0;
        double previousRoll = b.roll();
        for (double t = 0; t < 30; t += 0.05) {
            b.advance(0.05, 1e-6, WIND_TOWARD, steep, t);
            worstRate = Math.max(worstRate, Math.abs(b.roll() - previousRoll) / 0.05);
            previousRoll = b.roll();
        }

        // The undamped version reached several radians per second here. A real boat
        // of this size rolls at well under one.
        assertTrue(worstRate < 1.0,
                "the boat rolled at " + Math.toDegrees(worstRate) + " deg/s, which is a snap");
        assertTrue(worstRate > 0.02,
                "but it must still answer the sea at all, got "
                        + Math.toDegrees(worstRate) + " deg/s");
    }

    @Test
    @DisplayName("no sea can knock the boat past the angle it would capsize at")
    void attitudeIsBounded() {
        // Phase 5 owns capsize. Until then the angle is held rather than allowed to
        // run to whatever a near-vertical wave face asks for.
        WaveSurface enormous = WaveSurface.sine(9.0, 22.0, 0.4, 0.0);
        SailingBoat b = boat();
        b.setPosition(0, 0, Math.PI / 2);
        for (double t = 0; t < 60; t += 0.05) {
            b.advance(0.05, 30 * KNOTS, WIND_TOWARD, enormous, t);
            assertTrue(Math.abs(b.roll()) <= Math.toRadians(66),
                    "rolled to " + Math.toDegrees(b.roll()) + " degrees");
            assertTrue(Math.abs(b.pitch()) <= Math.toRadians(66),
                    "pitched to " + Math.toDegrees(b.pitch()) + " degrees");
        }
    }

    @Test
    @DisplayName("the boat heels away from the wind, harder as the breeze builds")
    void heelFollowsThePressure() {
        SailingBoat starboardTack = boat();
        starboardTack.setPosition(0, 0, -(Math.PI - Math.toRadians(50)));
        sail(starboardTack, 120, 14);
        // Wind over the starboard side puts the port rail down, and roll is
        // measured starboard-down, so a starboard-tack heel is negative.
        assertTrue(starboardTack.windHeel() < Math.toRadians(-10),
                "close-hauled on starboard in 14 knots should be well heeled, got "
                        + Math.toDegrees(starboardTack.windHeel()));

        SailingBoat portTack = boat();
        portTack.setPosition(0, 0, Math.PI - Math.toRadians(50));
        sail(portTack, 120, 14);
        assertEquals(-starboardTack.windHeel(), portTack.windHeel(), Math.toRadians(0.5),
                "the two tacks must mirror each other");

        SailingBoat breezy = boat();
        breezy.setPosition(0, 0, -(Math.PI - Math.toRadians(50)));
        sail(breezy, 120, 22);
        assertTrue(breezy.windHeel() < starboardTack.windHeel(),
                "more wind must mean more heel");
        assertTrue(breezy.windHeel() > -SailingBoat.HullShape.class40().maximumHeel - 1e-9,
                "but never past the angle the boat rounds up at");
    }

    @Test
    @DisplayName("running dead downwind is upright; the same breeze on the beam is not")
    void onlyTheAthwartshipsComponentHeels() {
        // Wind blows toward +X, so a boat heading +X is running away from it.
        SailingBoat running = boat();
        running.setPosition(0, 0, 0);
        sail(running, 120, 18);
        assertEquals(0.0, running.windHeel(), Math.toRadians(2),
                "dead downwind there is nothing to heel the boat, got "
                        + Math.toDegrees(running.windHeel()));

        SailingBoat reaching = boat();
        reaching.setPosition(0, 0, Math.PI / 2);
        sail(reaching, 120, 18);
        assertTrue(Math.abs(reaching.windHeel()) > Math.toRadians(15),
                "a beam reach in 18 knots should have the boat well over, got "
                        + Math.toDegrees(reaching.windHeel()));
    }

    @Test
    @DisplayName("head to wind the boat stands up, because there is no drive to lean on")
    void inIronsTheBoatIsUpright() {
        SailingBoat b = boat();
        b.setPosition(0, 0, Math.PI);
        sail(b, 90, 16);
        assertEquals(0.0, b.windHeel(), Math.toRadians(2),
                "in irons the boat should be upright, got " + Math.toDegrees(b.windHeel()));
    }

    @Test
    @DisplayName("the trajectory does not depend on the frame rate")
    void integrationIsFrameRateIndependent() {
        SailingBoat smooth = boat();
        smooth.setPosition(0, 0, Math.PI - Math.toRadians(50));
        smooth.setRudder(0.2);
        for (int i = 0; i < 6000; i++) {          // 60 s at 100 fps
            smooth.advance(1.0 / 100, 14 * KNOTS, WIND_TOWARD, WaveSurface.FLAT, i / 100.0);
        }

        SailingBoat stuttering = boat();
        stuttering.setPosition(0, 0, Math.PI - Math.toRadians(50));
        stuttering.setRudder(0.2);
        double t = 0;
        boolean slow = false;
        while (t < 60.0) {                        // 60 s at a lurching 12-60 fps
            double dt = slow ? 1.0 / 12 : 1.0 / 60;
            slow = !slow;
            stuttering.advance(dt, 14 * KNOTS, WIND_TOWARD, WaveSurface.FLAT, t);
            t += dt;
        }

        // Fixed stepping means both boats take the same number of 120 Hz steps, so
        // they must end up in very nearly the same place.
        assertEquals(smooth.x(), stuttering.x(), 1.0, "x drifted with frame rate");
        assertEquals(smooth.z(), stuttering.z(), 1.0, "z drifted with frame rate");
        assertEquals(smooth.speedKnots(), stuttering.speedKnots(), 0.05);
    }

    @Test
    @DisplayName("polar efficiency reads one when the boat is on its number")
    void polarEfficiencyReadsCorrectly() {
        SailingBoat b = boat();
        b.setPosition(0, 0, Math.PI / 2);
        sail(b, 180, 12);
        assertEquals(1.0, b.polarEfficiency(12 * KNOTS), 0.02);
    }
}
