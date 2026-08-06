package com.bluemeridian.core.sailing;

import com.bluemeridian.core.math.Mth;

/**
 * True wind, boat velocity, and the apparent wind that results.
 *
 * <p>Every tactical decision in sailing comes from this one subtraction. A boat
 * beating to windward at 8 knots into 12 knots of true wind feels close to 19
 * knots over the deck, drawn 20 degrees forward of where the true wind is; the
 * same boat running downwind at 8 knots feels 4. That is why upwind legs are wet
 * and loud and downwind legs are quiet, why a boat that accelerates gets a header
 * it then has to respond to, and why apparent wind boats can sail faster than the
 * wind that drives them.
 *
 * <p><b>Angle conventions</b>, which are the usual source of sign errors here:
 * <ul>
 *   <li>Directions in the world are radians in the XZ plane, from +X toward +Z.
 *   <li>{@code windToward} is the direction the wind is <em>blowing toward</em>,
 *       matching {@link com.bluemeridian.core.ocean.SeaState}. Sailors and
 *       forecasts name the direction wind comes <em>from</em>; that conversion
 *       happens at the weather boundary and never below it.
 *   <li>Wind <em>angles</em> (TWA, AWA) are measured at the boat, from the bow, in
 *       [-180, 180]. Zero is head to wind, 180 is dead downwind. Positive is wind
 *       from the starboard side, so a positive angle means starboard tack.
 * </ul>
 */
public final class ApparentWind {

    /** Apparent wind speed, m/s. */
    public final double speed;
    /** Apparent wind angle at the bow, radians in [-PI, PI]. */
    public final double angle;
    /** True wind angle at the bow, radians in [-PI, PI]. */
    public final double trueAngle;

    private ApparentWind(double speed, double angle, double trueAngle) {
        this.speed = speed;
        this.angle = angle;
        this.trueAngle = trueAngle;
    }

    /**
     * Resolves the apparent wind for a boat moving through a true wind field.
     *
     * @param trueWindSpeed true wind speed, m/s
     * @param windToward    direction the true wind blows toward, radians
     * @param boatSpeed     speed over ground, m/s
     * @param boatHeading   direction the bow points, radians
     */
    public static ApparentWind of(double trueWindSpeed, double windToward,
            double boatSpeed, double boatHeading) {
        // Vector the wind travels along, and the vector the boat travels along.
        double wx = trueWindSpeed * Math.cos(windToward);
        double wz = trueWindSpeed * Math.sin(windToward);
        double bx = boatSpeed * Math.cos(boatHeading);
        double bz = boatSpeed * Math.sin(boatHeading);

        // Apparent wind is the true wind seen from a moving frame.
        double ax = wx - bx;
        double az = wz - bz;
        double apparentSpeed = Math.hypot(ax, az);

        // The angle is measured to the direction the wind arrives *from*, which is
        // the reverse of the direction it travels.
        double apparentAngle = apparentSpeed < 1e-9
                ? 0.0
                : signedAngleFromBow(-ax, -az, boatHeading);
        double trueAngle = trueWindSpeed < 1e-9
                ? 0.0
                : signedAngleFromBow(-wx, -wz, boatHeading);

        return new ApparentWind(apparentSpeed, apparentAngle, trueAngle);
    }

    /**
     * Signed angle from the bow to a direction, positive to starboard.
     *
     * <p>With headings measured from +X toward +Z, turning to starboard decreases
     * the heading angle, so the sign is negated to keep "positive means starboard
     * tack" true for a sailor reading the number.
     */
    private static double signedAngleFromBow(double x, double z, double heading) {
        double direction = Math.atan2(z, x);
        return -Mth.wrapPi(direction - heading);
    }

    /** Magnitude of the apparent wind angle, radians in [0, PI]. Handy for polars. */
    public double angleMagnitude() {
        return Math.abs(angle);
    }

    /** Magnitude of the true wind angle, radians in [0, PI]. */
    public double trueAngleMagnitude() {
        return Math.abs(trueAngle);
    }

    /** True when the wind crosses the starboard side first, which carries right of way. */
    public boolean isStarboardTack() {
        return trueAngle > 0.0;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT,
                "ApparentWind[AWS=%.2f m/s, AWA=%.1f deg, TWA=%.1f deg, %s tack]",
                speed, Math.toDegrees(angle), Math.toDegrees(trueAngle),
                isStarboardTack() ? "starboard" : "port");
    }
}
