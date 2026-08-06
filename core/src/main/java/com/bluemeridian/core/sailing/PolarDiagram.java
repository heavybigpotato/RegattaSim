package com.bluemeridian.core.sailing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A boat's speed as a function of true wind angle and true wind speed.
 *
 * <p>The polar is the source of truth for performance. Everything else in the
 * sailing model - trim quality, sea state losses, damage - multiplies what this
 * table says; nothing overrides it. That is deliberate, because a polar is the one
 * artefact that can be compared against a real boat, and a simulator whose speed
 * comes from somewhere else has no way of being checked.
 *
 * <p><b>The shipped tables are approximations and must never be presented as
 * official.</b> They are built to have the right shape - a no-go zone, an upwind
 * VMG optimum near 42 to 45 degrees, a reaching bulge that moves aft as the breeze
 * builds, a running speed well below beam-reaching speed - but they are not
 * measured and no class association has anything to do with them.
 *
 * <p>Interpolation is bicubic, via Catmull-Rom in each axis. Bilinear would be
 * cheaper and is wrong here for a reason a sailor would feel: a polar's derivative
 * is what steering feedback is made of, and bilinear interpolation has a
 * discontinuous derivative at every node. The boat would accelerate in faint steps
 * as the helm swept through the table's angles, and the groove upwind - the thing
 * you steer by - would have corners in it.
 */
public final class PolarDiagram {

    private static final double KNOTS_TO_MS = 0.514444;

    private final double[] angles;   // true wind angle, radians, ascending
    private final double[] speeds;   // true wind speed, m/s, ascending
    private final double[][] table;  // [angle][speed] -> boat speed, m/s
    private final String name;

    private PolarDiagram(String name, double[] angles, double[] speeds, double[][] table) {
        this.name = name;
        this.angles = angles;
        this.speeds = speeds;
        this.table = table;
    }

    public String name() {
        return name;
    }

    /**
     * Loads a polar from the classpath.
     *
     * @param resource path such as {@code polars/class40.csv}
     */
    public static PolarDiagram fromClasspath(String resource) {
        try (InputStream in = PolarDiagram.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("polar not found on classpath: " + resource);
            }
            return parse(resource, in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read polar " + resource, e);
        }
    }

    /**
     * Parses the CSV format: a header row of true wind speeds in knots, then one
     * row per true wind angle in degrees, cells in knots. Lines starting with
     * {@code #} are comments.
     */
    public static PolarDiagram parse(String name, InputStream in) throws IOException {
        List<double[]> rows = new ArrayList<>();
        List<Double> angleList = new ArrayList<>();
        double[] windSpeeds = null;

        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split(",");
                if (windSpeeds == null) {
                    windSpeeds = new double[parts.length - 1];
                    for (int i = 1; i < parts.length; i++) {
                        windSpeeds[i - 1] = Double.parseDouble(parts[i].trim()) * KNOTS_TO_MS;
                    }
                    continue;
                }
                if (parts.length != windSpeeds.length + 1) {
                    throw new IllegalArgumentException(name + ": row \"" + parts[0] + "\" has "
                            + (parts.length - 1) + " cells, expected " + windSpeeds.length);
                }
                angleList.add(Math.toRadians(Double.parseDouble(parts[0].trim())));
                double[] row = new double[windSpeeds.length];
                for (int i = 0; i < row.length; i++) {
                    row[i] = Double.parseDouble(parts[i + 1].trim()) * KNOTS_TO_MS;
                }
                rows.add(row);
            }
        }

        if (windSpeeds == null || rows.size() < 2) {
            throw new IllegalArgumentException(name + ": needs a header and at least two rows");
        }
        double[] angles = new double[angleList.size()];
        for (int i = 0; i < angles.length; i++) {
            angles[i] = angleList.get(i);
            if (i > 0 && angles[i] <= angles[i - 1]) {
                throw new IllegalArgumentException(name + ": wind angles must ascend");
            }
        }
        for (int i = 1; i < windSpeeds.length; i++) {
            if (windSpeeds[i] <= windSpeeds[i - 1]) {
                throw new IllegalArgumentException(name + ": wind speeds must ascend");
            }
        }
        return new PolarDiagram(name, angles, windSpeeds, rows.toArray(new double[0][]));
    }

    /**
     * Target boat speed, m/s.
     *
     * @param trueWindAngle  angle off the bow, radians; sign is ignored because a
     *                       polar is symmetric about the centreline
     * @param trueWindSpeed  true wind speed, m/s
     */
    public double boatSpeed(double trueWindAngle, double trueWindSpeed) {
        double angle = Math.abs(trueWindAngle);
        if (angle > Math.PI) {
            angle = Math.PI;
        }
        // Outside the tabulated wind range the boat does not gain or lose speed
        // without evidence: the table is clamped rather than extrapolated, because
        // a Catmull-Rom extrapolated past its last node diverges quickly and would
        // invent a boat that sails at thirty knots in a hurricane.
        double wind = Math.max(speeds[0], Math.min(speeds[speeds.length - 1], trueWindSpeed));

        int ai = segment(angles, angle);
        int si = segment(speeds, wind);
        double at = (angle - angles[ai]) / (angles[ai + 1] - angles[ai]);
        double st = (wind - speeds[si]) / (speeds[si + 1] - speeds[si]);

        // Interpolate along wind speed at four surrounding angles, then across them.
        double[] column = new double[4];
        for (int k = 0; k < 4; k++) {
            int row = clamp(ai - 1 + k, 0, angles.length - 1);
            column[k] = catmullRom(
                    table[row][clamp(si - 1, 0, speeds.length - 1)],
                    table[row][si],
                    table[row][si + 1],
                    table[row][clamp(si + 2, 0, speeds.length - 1)],
                    st);
        }
        double result = catmullRom(column[0], column[1], column[2], column[3], at);

        // A cubic through a no-go zone can undershoot below zero; a boat cannot
        // sail backwards under sail alone.
        return Math.max(0.0, result);
    }

    /** Target boat speed in knots, for display. */
    public double boatSpeedKnots(double trueWindAngle, double trueWindSpeedMs) {
        return boatSpeed(trueWindAngle, trueWindSpeedMs) / KNOTS_TO_MS;
    }

    /**
     * Velocity made good toward the wind, m/s. Positive means progress upwind.
     *
     * <p>{@code VMG = boatSpeed * cos(trueWindAngle)} with the angle measured from
     * dead upwind, so beating gives a positive number and running a negative one.
     */
    public double velocityMadeGood(double trueWindAngle, double trueWindSpeed) {
        return boatSpeed(trueWindAngle, trueWindSpeed) * Math.cos(Math.abs(trueWindAngle));
    }

    /**
     * The angle that maximises upwind VMG at a given wind speed, radians.
     *
     * <p>This is what a routing algorithm and an autopilot both need, and what a
     * good helm converges on without being told.
     */
    public double bestUpwindAngle(double trueWindSpeed) {
        return searchBestAngle(trueWindSpeed, Math.toRadians(20), Math.toRadians(90), 1.0);
    }

    /** The angle that maximises downwind VMG at a given wind speed, radians. */
    public double bestDownwindAngle(double trueWindSpeed) {
        return searchBestAngle(trueWindSpeed, Math.toRadians(90), Math.toRadians(180), -1.0);
    }

    private double searchBestAngle(double wind, double from, double to, double sign) {
        // A sampled sweep rather than a solver: the objective is not convex near the
        // no-go zone, and a few hundred evaluations of a table lookup is nothing.
        double best = from;
        double bestValue = -Double.MAX_VALUE;
        int steps = 360;
        for (int i = 0; i <= steps; i++) {
            double angle = from + (to - from) * i / steps;
            double vmg = sign * velocityMadeGood(angle, wind);
            if (vmg > bestValue) {
                bestValue = vmg;
                best = angle;
            }
        }
        return best;
    }

    /** Lowest tabulated wind speed, m/s. */
    public double minimumWindSpeed() {
        return speeds[0];
    }

    /** Highest tabulated wind speed, m/s. */
    public double maximumWindSpeed() {
        return speeds[speeds.length - 1];
    }

    /** Index of the segment containing {@code value}, clamped to the table. */
    private static int segment(double[] axis, double value) {
        int i = 0;
        while (i < axis.length - 2 && value >= axis[i + 1]) {
            i++;
        }
        return i;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * Catmull-Rom through p1 and p2, using p0 and p3 for the tangents. Passes
     * exactly through the nodes and is continuous in the first derivative, which is
     * what keeps the groove smooth.
     */
    private static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5 * ((2.0 * p1)
                + (-p0 + p2) * t
                + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2
                + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "PolarDiagram[%s, %d angles x %d wind speeds]",
                name, angles.length, speeds.length);
    }
}
