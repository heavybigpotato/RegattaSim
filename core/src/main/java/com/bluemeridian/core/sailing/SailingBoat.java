package com.bluemeridian.core.sailing;

import com.bluemeridian.core.math.Mth;
import com.bluemeridian.core.ocean.WaveSurface;

/**
 * A hull sailing on a wave surface.
 *
 * <p>Speed comes from the polar rather than from a force balance. That is a real
 * choice and worth defending: a force model has to be tuned until it reproduces a
 * polar anyway, and until it does, it is wrong in ways nobody can measure. Driving
 * the boat from the polar directly means the performance is exactly what the table
 * says, which is the one thing about a sailing simulator that can be checked
 * against reality.
 *
 * <p>What the polar does not give is <em>how it feels</em>, and that is what the
 * rest of this class is. A polar is a steady state; a boat takes time to reach it,
 * cannot turn instantly, loses speed through a tack, and rises and falls over
 * every wave. Those are the differences between a number and a boat.
 *
 * <p>Attitude is read from the water rather than integrated. Four points around
 * the hull are sampled on the surface and a plane is fitted through them: the mean
 * gives heave, the fore-and-aft difference gives pitch, the athwartships
 * difference gives roll. This deliberately skips rigid-body dynamics, and the
 * reason is scale - a 12 metre hull spans a good fraction of the waves it sits in,
 * so it follows the surface far more than it oscillates about it. Buoyancy
 * integration is Phase 5 work, when hulls start slamming.
 *
 * <p>The step is fixed at 120 Hz per the brief, decoupled from the frame rate.
 */
public final class SailingBoat {

    /** Physics step, seconds. Fixed so client and server integrate identically. */
    public static final double STEP = 1.0 / 120.0;

    private final PolarDiagram polar;
    private final HullShape hull;

    // Position on the water plane, metres.
    private double x;
    private double z;
    /** Direction the bow points, radians in the XZ plane. */
    private double heading;
    /** Speed through the water, m/s. */
    private double speed;

    // Attitude, read from the surface each step.
    private double heave;
    private double pitch;
    private double roll;
    /** Heel from the rig, radians, separate from the roll the waves impose. */
    private double windHeel;

    /** Helm demand in [-1, 1]; negative turns to port. */
    private double rudder;
    /** Trim quality in [0, 1]; 1 is perfectly set sails. */
    private double trim = 1.0;

    private double accumulator;
    private ApparentWind wind =
            ApparentWind.of(1e-9, 0.0, 0.0, 0.0);

    public SailingBoat(PolarDiagram polar, HullShape hull) {
        this.polar = polar;
        this.hull = hull;
    }

    /** Hull dimensions and how quickly the boat responds. */
    public static final class HullShape {

        public final double length;
        public final double beam;
        /**
         * Time constant for approaching polar speed, seconds. A 40 ft boat takes
         * the better part of a minute to settle onto its number after a tack.
         */
        public final double accelerationTime;
        /** Maximum rate of turn at full helm and full speed, radians per second. */
        public final double maximumTurnRate;
        /**
         * Heel the boat settles at when the rig is fully pressed, radians.
         *
         * <p>Beyond this a monohull is not sailing faster, it is sailing sideways:
         * the keel loses its bite, the rudder ventilates and the boat rounds up.
         * Racing crews live just under this number.
         */
        public final double maximumHeel;
        /**
         * Wind pressure, in m^2/s^2, at which the boat reaches half its maximum
         * heel. Sets stiffness: a heavier or beamier boat needs a larger number.
         */
        public final double heelReference;

        public HullShape(double length, double beam, double accelerationTime,
                double maximumTurnRate, double maximumHeel, double heelReference) {
            this.length = length;
            this.beam = beam;
            this.accelerationTime = accelerationTime;
            this.maximumTurnRate = maximumTurnRate;
            this.maximumHeel = maximumHeel;
            this.heelReference = heelReference;
        }

        /** A 40 ft offshore monohull, matching the shipped polar. */
        public static HullShape class40() {
            return new HullShape(12.18, 4.50, 9.0, Math.toRadians(22),
                    Math.toRadians(25), 33.6);
        }
    }

    // --- inputs -------------------------------------------------------------

    /** Sets helm demand, clamped to [-1, 1]. Negative is to port. */
    public void setRudder(double demand) {
        this.rudder = Mth.clamp((float) demand, -1f, 1f);
    }

    /**
     * Sets trim quality in [0, 1].
     *
     * <p>The brief asks for a 0.85 to 1.00 band on the polar, so badly set sails
     * cost about fifteen percent rather than stopping the boat. That is the right
     * magnitude: a poorly trimmed boat is beaten, not becalmed.
     */
    public void setTrim(double quality) {
        this.trim = Mth.clamp((float) quality, 0f, 1f);
    }

    public void setPosition(double worldX, double worldZ, double headingRadians) {
        this.x = worldX;
        this.z = worldZ;
        this.heading = headingRadians;
    }

    // --- simulation ---------------------------------------------------------

    /**
     * Advances the boat by a frame's worth of time, in fixed steps.
     *
     * <p>Leftover time is carried, so a variable frame rate never changes the
     * trajectory. Anything beyond a quarter second is dropped rather than
     * simulated: after a stall or a backgrounded tab, catching up faithfully would
     * mean hundreds of steps in one frame and a worse stall.
     */
    public void advance(double deltaTime, double trueWindSpeed, double windToward,
            WaveSurface surface, double time) {
        accumulator += Math.min(deltaTime, 0.25);
        while (accumulator >= STEP) {
            step(trueWindSpeed, windToward);
            accumulator -= STEP;
        }
        readAttitude(surface, Math.min(deltaTime, 0.25));
    }

    private void step(double trueWindSpeed, double windToward) {
        wind = ApparentWind.of(trueWindSpeed, windToward, speed, heading);

        // The polar is indexed by true wind angle, and the trim multiplier is the
        // only thing allowed to scale it.
        double target = polar.boatSpeed(wind.trueAngle, trueWindSpeed)
                * (0.85 + 0.15 * trim);

        // Exponential approach, which is the right shape: a boat closes most of the
        // gap to its target quickly and the last knot takes far longer.
        double rate = 1.0 - Math.exp(-STEP / hull.accelerationTime);
        speed += (target - speed) * rate;

        // A rudder needs flow over it. Head to wind with no way on, the helm does
        // nothing at all, which is exactly why a boat gets stuck in irons.
        double steerage = Math.min(1.0, speed / 1.5);
        // Subtracted, not added. Starboard is 90 degrees clockwise from the bow,
        // which in this frame - headings running from +X toward +Z - means turning
        // to starboard *decreases* the heading. Adding the demand would have made
        // negative helm turn to starboard, contradicting both this class's own
        // documentation and the tack convention ApparentWind uses.
        heading = Mth.wrapPi(heading - rudder * hull.maximumTurnRate * steerage * STEP);

        x += speed * Math.cos(heading) * STEP;
        z += speed * Math.sin(heading) * STEP;

        // Heel lags the wind that causes it. A boat carries its heel through a
        // lull and takes a moment to stand up in a tack, and that lag is most of
        // what a tack looks like from outside: the boat comes upright, hangs, then
        // lies down on the other side.
        double rollRate = 1.0 - Math.exp(-STEP / HEEL_TIME_CONSTANT);
        windHeel += (targetHeel() - windHeel) * rollRate;
    }

    /** Time constant for heel to follow the wind pressure, seconds. */
    private static final double HEEL_TIME_CONSTANT = 1.6;

    /**
     * The heel the rig is asking for right now, radians.
     *
     * <p>Only the athwartships component of the apparent wind heels a boat, which
     * is why running dead downwind is upright and why a beam reach in the same
     * breeze has the rail under. The saturation matters as much as the slope: past
     * a certain pressure a monohull stops heeling further and starts rounding up,
     * so the response has to flatten rather than keep going.
     */
    private double targetHeel() {
        double side = Math.sin(wind.angle);
        double pressure = wind.speed * wind.speed * Math.abs(side);
        double magnitude = hull.maximumHeel * Math.tanh(pressure / hull.heelReference);
        // Wind from starboard lays the boat over to port, and roll is measured
        // starboard-down, so a starboard-tack heel is negative.
        return -Math.signum(side) * magnitude;
    }

    /**
     * Time constant for pitch and roll to follow the wave slope, seconds.
     *
     * <p>A hull does not snap to the surface under it. A 40 footer has a natural
     * roll period of three or four seconds and a pitch period not far off, so it
     * lags the slope it is sitting on and never quite reaches it before the wave has
     * moved on. Reading the plane fit straight out - which this did - is fine in a
     * two metre sea and violent in a twelve metre one: the boat snaps through forty
     * degrees between frames and reads as broken rather than as pressed.
     */
    private static final double ATTITUDE_TIME_CONSTANT = 0.7;

    /**
     * Beyond this the boat is not sailing, it is capsizing, and Phase 5 owns that.
     * Until then the angle is held here rather than allowed to run to whatever a
     * steep wave face asks for.
     */
    private static final double MAXIMUM_ATTITUDE = Math.toRadians(65);

    /**
     * Fits a plane through four points on the hull and reads heave, pitch and roll
     * from it.
     *
     * @param deltaTime frame time, seconds, for the lag on pitch and roll
     */
    private void readAttitude(WaveSurface surface, double deltaTime) {
        double halfLength = hull.length * 0.5;
        double halfBeam = hull.beam * 0.5;
        double cos = Math.cos(heading);
        double sin = Math.sin(heading);

        double bow = surface.heightAt(x + cos * halfLength, z + sin * halfLength);
        double stern = surface.heightAt(x - cos * halfLength, z - sin * halfLength);
        // Starboard is 90 degrees to the right of the bow, which with headings
        // measured from +X toward +Z means rotating by -90.
        double starboard = surface.heightAt(x + sin * halfBeam, z - cos * halfBeam);
        double port = surface.heightAt(x - sin * halfBeam, z + cos * halfBeam);

        // Heave is not lagged: a hull really does follow the surface up and down,
        // and damping it would have the boat swimming through crests.
        heave = 0.25 * (bow + stern + starboard + port);

        // Bow up is positive pitch. Starboard down is positive roll, and the wave
        // slope tips the boat about the angle the rig is already holding it at, so
        // the two add.
        double targetPitch = clampAttitude(Math.atan2(bow - stern, hull.length));
        double targetRoll = clampAttitude(
                Math.atan2(starboard - port, hull.beam) + windHeel);

        double rate = 1.0 - Math.exp(-deltaTime / ATTITUDE_TIME_CONSTANT);
        pitch += (targetPitch - pitch) * rate;
        roll += (targetRoll - roll) * rate;
    }

    private static double clampAttitude(double angle) {
        return Math.max(-MAXIMUM_ATTITUDE, Math.min(MAXIMUM_ATTITUDE, angle));
    }

    // --- state --------------------------------------------------------------

    public double x() {
        return x;
    }

    public double z() {
        return z;
    }

    /** Vertical position of the hull's centre, metres above mean water. */
    public double heave() {
        return heave;
    }

    public double heading() {
        return heading;
    }

    /** Speed through the water, m/s. */
    public double speed() {
        return speed;
    }

    public double speedKnots() {
        return speed / 0.514444;
    }

    /** Bow-up angle, radians. */
    public double pitch() {
        return pitch;
    }

    /** Starboard-down angle, radians: wave slope plus the heel from the rig. */
    public double roll() {
        return roll;
    }

    /** The part of the roll the sails are responsible for, radians. */
    public double windHeel() {
        return windHeel;
    }

    public ApparentWind wind() {
        return wind;
    }

    public double trim() {
        return trim;
    }

    public double rudder() {
        return rudder;
    }

    /**
     * How close the boat is to its polar, in [0, 1]. This is the number a racing
     * sailor actually watches, and the one an autopilot steers to.
     */
    public double polarEfficiency(double trueWindSpeed) {
        double target = polar.boatSpeed(wind.trueAngle, trueWindSpeed);
        return target < 1e-6 ? 0.0 : Math.min(1.0, speed / target);
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT,
                "SailingBoat[%.1f kt, hdg %.0f, TWA %.0f, heave %.2f m, pitch %.1f, heel %.1f]",
                speedKnots(), Math.toDegrees(Mth.wrap360(Math.toDegrees(heading))),
                Math.toDegrees(wind.trueAngle), heave,
                Math.toDegrees(pitch), Math.toDegrees(roll));
    }
}
