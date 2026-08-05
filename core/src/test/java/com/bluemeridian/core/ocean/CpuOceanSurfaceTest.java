package com.bluemeridian.core.ocean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The CPU surface is what the boat floats on and what the server replays, so it
 * is checked for the three properties that matter there: the right amount of
 * water, the same water twice, and water that loops cleanly.
 */
class CpuOceanSurfaceTest {

    private static SeaState sea(long seed) {
        return SeaState.openOcean(12.86, 0.4, seed);
    }

    @Test
    @DisplayName("realised surface RMS matches the significant wave height it was built from")
    void rmsMatchesSignificantHeight() {
        // One cascade over the whole spectrum, so the grid carries all the energy
        // the spectrum prescribes and Hs = 4 * RMS should hold directly.
        SeaState state = sea(31337L);
        CascadeSettings full = CascadeSettings.rebandedAt(64, 512f, 0f, Float.MAX_VALUE);

        // Average several realisations: a single one scatters, for the same reason
        // the spectrum variance test averages.
        double meanRms = 0.0;
        int runs = 24;
        for (int i = 0; i < runs; i++) {
            CpuOceanSurface surface = new CpuOceanSurface(state.withSeed(500L + i), full, 64, 1);
            surface.update(12.5);
            meanRms += surface.rmsElevation();
        }
        meanRms /= runs;

        double expectedRms = state.significantWaveHeight() / 4.0;
        // The 64-point grid truncates the short-wave tail, so the realised surface
        // is slightly smoother than the continuous spectrum. 20% covers that.
        assertEquals(expectedRms, meanRms, expectedRms * 0.2,
                "RMS " + meanRms + " vs Hs/4 " + expectedRms);
    }

    @Test
    @DisplayName("the same seed produces the same surface")
    void isDeterministic() {
        CascadeSettings cascades = CascadeSettings.standard(64);
        CpuOceanSurface a = new CpuOceanSurface(sea(77L), cascades, 64, 2);
        CpuOceanSurface b = new CpuOceanSurface(sea(77L), cascades, 64, 2);
        a.update(9.75);
        b.update(9.75);
        for (int i = 0; i < 40; i++) {
            double x = i * 7.3;
            double z = i * -3.1;
            assertEquals(a.heightAt(x, z), b.heightAt(x, z), 1e-6,
                    "client and server must agree at (" + x + ", " + z + ")");
        }
    }

    @Test
    @DisplayName("the wave field repeats exactly after the loop period")
    void loopsExactly() {
        SeaState state = sea(4242L);
        CascadeSettings cascades = CascadeSettings.standard(64);
        CpuOceanSurface surface = new CpuOceanSurface(state, cascades, 64, 2);

        surface.update(3.0);
        double[] before = new double[24];
        for (int i = 0; i < before.length; i++) {
            before[i] = surface.heightAt(i * 11.0, i * 5.0);
        }

        surface.update(3.0 + state.repeatPeriod);
        for (int i = 0; i < before.length; i++) {
            assertEquals(before[i], surface.heightAt(i * 11.0, i * 5.0), 1e-3,
                    "sample " + i + " must return after one loop period");
        }
    }

    @Test
    @DisplayName("the surface actually moves between frames")
    void evolvesInTime() {
        CascadeSettings cascades = CascadeSettings.standard(64);
        CpuOceanSurface surface = new CpuOceanSurface(sea(5L), cascades, 64, 2);
        surface.update(0.0);
        double h0 = surface.heightAt(20.0, 15.0);
        surface.update(2.0);
        double h1 = surface.heightAt(20.0, 15.0);
        assertTrue(Math.abs(h1 - h0) > 1e-3,
                "height barely changed over 2 s: " + h0 + " -> " + h1);
    }

    @Test
    @DisplayName("displacement inversion lands back on the queried position")
    void inversionIsConsistent() {
        CascadeSettings cascades = CascadeSettings.standard(64);
        CpuOceanSurface surface = new CpuOceanSurface(sea(19L), cascades, 64, 2);
        surface.update(6.0);

        float[] d = new float[3];
        double worstError = 0.0;
        for (int i = 0; i < 50; i++) {
            double x = i * 3.7 - 90.0;
            double z = i * -2.9 + 40.0;
            // Recover the grid point, then check it displaces back to (x, z).
            double u = x;
            double v = z;
            for (int it = 0; it < 4; it++) {
                surface.displacementAtGridPoint(u, v, d);
                u = x - d[0];
                v = z - d[2];
            }
            surface.displacementAtGridPoint(u, v, d);
            worstError = Math.max(worstError, Math.hypot(u + d[0] - x, v + d[2] - z));
        }
        // Sub-decimetre over a sea with metre-scale horizontal displacement.
        assertTrue(worstError < 0.1, "worst inversion error " + worstError + " m");
    }

    @Test
    @DisplayName("normals point upward and tilt with the slope")
    void normalsAreSane() {
        CascadeSettings cascades = CascadeSettings.standard(64);
        CpuOceanSurface surface = new CpuOceanSurface(sea(23L), cascades, 64, 2);
        surface.update(4.0);

        float[] n = new float[3];
        double maxTilt = 0.0;
        for (int i = 0; i < 60; i++) {
            surface.normalAt(i * 4.1, i * 2.3, n);
            double len = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
            assertEquals(1.0, len, 1e-4, "normal must be unit length");
            assertTrue(n[1] > 0.0, "normal must point up, got y=" + n[1]);
            maxTilt = Math.max(maxTilt, Math.acos(n[1]));
        }
        assertTrue(maxTilt > Math.toRadians(3.0),
                "a 4 m sea should tilt the surface somewhere, max tilt was "
                        + Math.toDegrees(maxTilt) + " deg");
    }
}
