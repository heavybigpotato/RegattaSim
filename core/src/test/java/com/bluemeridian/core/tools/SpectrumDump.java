package com.bluemeridian.core.tools;

import com.bluemeridian.core.boat.BoatMesh;
import com.bluemeridian.core.boat.HullLoft;
import com.bluemeridian.core.env.PreethamSky;
import com.bluemeridian.core.math.ButterflyPlan;
import com.bluemeridian.core.ocean.CascadeSettings;
import com.bluemeridian.core.ocean.Dispersion;
import com.bluemeridian.core.ocean.InitialSpectrum;
import com.bluemeridian.core.ocean.SeaState;
import com.bluemeridian.core.ocean.WaveSurface;
import com.bluemeridian.core.sailing.ApparentWind;
import com.bluemeridian.core.sailing.PolarDiagram;
import com.bluemeridian.core.sailing.SailingBoat;
import java.io.PrintStream;
import java.util.Locale;

/**
 * Prints the authoritative ocean numbers as JSON, for the web build to be checked
 * against.
 *
 * <p>{@code docs/ocean/spectrum.js} is a transliteration of this module so that a
 * browser can show the same sea. Transliterations drift silently: a sign lost in
 * the dispersion relation or a mis-ported hash still produces a plausible ocean,
 * just not <em>this</em> ocean, and the web preview would stop being evidence
 * about the renderer it claims to preview.
 *
 * <p>The h0 samples matter most. They exercise the deterministic hash end to end,
 * and JavaScript has no 64-bit integers outside BigInt, so that is exactly where a
 * transliteration is most likely to go wrong.
 */
public final class SpectrumDump {

    /** Fixed sea state, chosen so both sides describe the same ocean. */
    public static SeaState referenceSeaState() {
        return new SeaState(
                11.0,                      // wind speed, m/s
                0.6,                       // wind direction, rad
                300_000.0,                 // fetch, m
                3.3,                       // JONSWAP peak enhancement
                4000.0,                    // depth, m
                2.2,                       // swell Hs, m
                11.0,                      // swell Tp, s
                1.3,                       // swell direction, rad
                0.07,                      // swell narrowness
                24.0,                      // swell spreading exponent
                1.0,                       // choppiness
                200.0,                     // repeat period, s
                20260805L);                // seed
    }

    private SpectrumDump() {
    }

    public static void main(String[] args) {
        SeaState sea = referenceSeaState();
        CascadeSettings cascades = CascadeSettings.standard(128);
        InitialSpectrum spectrum = new InitialSpectrum(sea, cascades, 0);
        float[] h0 = spectrum.generate();
        ButterflyPlan plan = new ButterflyPlan(128);

        PrintStream out = System.out;
        out.println("{");
        out.printf(Locale.ROOT, "  \"significantWaveHeight\": %.12f,%n", sea.significantWaveHeight());
        out.printf(Locale.ROOT, "  \"windSeaPeakOmega\": %.12f,%n", sea.windSeaSpectrum().peakOmega());
        out.printf(Locale.ROOT, "  \"cascadeKMax0\": %.12f,%n", cascades.kMax[0]);

        // Dispersion across a wide range of wavenumbers, including the shallow
        // regime where tanh matters.
        out.print("  \"omega\": [");
        double[] ks = {0.01, 0.05, 0.2, 1.0, 5.0, 30.0};
        for (int i = 0; i < ks.length; i++) {
            out.printf(Locale.ROOT, "%s%.12f", i == 0 ? "" : ", ",
                    Dispersion.quantiseForLoop(Dispersion.omega(ks[i], sea.depth), sea.repeatPeriod));
        }
        out.println("],");

        // The directional spectrum, which folds in JONSWAP, the swell, the
        // spreading and the polar Jacobian.
        out.print("  \"directionalSpectrum\": [");
        double[][] kv = {{0.02, 0.01}, {0.05, -0.03}, {0.2, 0.15}, {-0.4, 0.1}};
        for (int i = 0; i < kv.length; i++) {
            out.printf(Locale.ROOT, "%s%.12e", i == 0 ? "" : ", ",
                    spectrum.directionalSpectrum(kv[i][0], kv[i][1]));
        }
        out.println("],");

        // Individual h0 texels: these exercise the 64-bit hash.
        out.print("  \"h0\": [");
        int[][] texels = {{0, 0}, {1, 0}, {3, 5}, {17, 42}, {64, 64}, {127, 127}};
        for (int i = 0; i < texels.length; i++) {
            int o = (texels[i][1] * 128 + texels[i][0]) * 4;
            out.printf(Locale.ROOT, "%s[%.9e, %.9e, %.9e, %.9e]", i == 0 ? "" : ", ",
                    h0[o], h0[o + 1], h0[o + 2], h0[o + 3]);
        }
        out.println("],");

        out.printf(Locale.ROOT, "  \"h0Variance\": %.12e,%n", spectrum.varianceOf(h0));

        // A few butterfly entries, so a transposed or mis-indexed plan is caught.
        out.print("  \"butterfly\": [");
        int[][] lanes = {{0, 0}, {0, 1}, {3, 5}, {6, 127}};
        for (int i = 0; i < lanes.length; i++) {
            int stage = lanes[i][0];
            int lane = lanes[i][1];
            out.printf(Locale.ROOT, "%s[%.9f, %.9f, %d, %d]", i == 0 ? "" : ", ",
                    plan.twiddleRe(stage, lane), plan.twiddleIm(stage, lane),
                    plan.indexA(stage, lane), plan.indexB(stage, lane));
        }
        out.println("],");

        // --- sailing -------------------------------------------------------
        PolarDiagram polar = PolarDiagram.fromClasspath("polars/class40.csv");
        out.print("  \"polar\": [");
        double[][] polarCases = {{45, 12}, {90, 8}, {135, 20}, {60, 17.5}, {170, 6.5}, {20, 12}};
        for (int i = 0; i < polarCases.length; i++) {
            out.printf(Locale.ROOT, "%s%.12f", i == 0 ? "" : ", ",
                    polar.boatSpeed(Math.toRadians(polarCases[i][0]),
                            polarCases[i][1] * 0.514444));
        }
        out.println("],");

        out.print("  \"apparentWind\": [");
        double[][] windCases = {{12, 0, 8, Math.PI - Math.toRadians(45)},
                                {10, 0.7, 6, 1.9}, {8, 0, 8, 0}, {14, 2.2, 3, -1.1}};
        for (int i = 0; i < windCases.length; i++) {
            ApparentWind aw = ApparentWind.of(windCases[i][0] * 0.514444, windCases[i][1],
                    windCases[i][2] * 0.514444, windCases[i][3]);
            out.printf(Locale.ROOT, "%s[%.12f, %.12f, %.12f]", i == 0 ? "" : ", ",
                    aw.speed, aw.angle, aw.trueAngle);
        }
        out.println("],");

        // A full trajectory: the boat is integrated for two minutes with helm on,
        // over a known swell, so the whole loop is compared rather than one step.
        SailingBoat boat = new SailingBoat(polar, SailingBoat.HullShape.class40());
        boat.setPosition(0, 0, Math.PI - Math.toRadians(50));
        boat.setRudder(0.25);
        boat.setTrim(0.8);
        WaveSurface swell = WaveSurface.sine(1.2, 40.0, 0.3, 0.0);
        for (int i = 0; i < 2400; i++) {
            boat.advance(0.05, 13.0 * 0.514444, 0.0, swell, i * 0.05);
        }
        out.printf(Locale.ROOT,
                "  \"boat\": [%.10f, %.10f, %.10f, %.10f, %.10f, %.10f, %.10f, %.10f],%n",
                boat.x(), boat.z(), boat.heading(), boat.speed(),
                boat.heave(), boat.pitch(), boat.roll(), boat.windHeel());

        // The boat's geometry, so the browser's transliterated loft cannot quietly
        // become a different boat. Counts catch a dropped or duplicated face; the
        // checksum catches a moved vertex or a flipped winding.
        HullLoft loft = HullLoft.class40();
        BoatMesh hullMesh = loft.hull();
        BoatMesh sailMesh = loft.sails(0.4, 0.11);
        out.printf(Locale.ROOT, "  \"hullMesh\": [%d, %d, %.6f],%n",
                hullMesh.vertexCount(), hullMesh.indices.length, hullMesh.checksum());
        out.printf(Locale.ROOT, "  \"sailMesh\": [%d, %d, %.6f],%n",
                sailMesh.vertexCount(), sailMesh.indices.length, sailMesh.checksum());

        out.print("  \"meanDomeLuminance\": [");
        double[] elevations = {-0.05, 0.05, 0.3, 0.9, 1.4};
        for (int i = 0; i < elevations.length; i++) {
            out.printf(Locale.ROOT, "%s%.12f", i == 0 ? "" : ", ",
                    PreethamSky.meanDomeLuminance(elevations[i], 2.6));
        }
        out.println("]");
        out.println("}");
    }
}
