package com.bluemeridian.core.ocean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bluemeridian.core.math.Gamma;
import com.bluemeridian.core.math.Mth;
import com.bluemeridian.core.ocean.spectrum.CosinePowerSpreading;
import com.bluemeridian.core.ocean.spectrum.JonswapSpectrum;
import com.bluemeridian.core.ocean.spectrum.PiersonMoskowitzSpectrum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks the spectrum chain end to end, because an ocean built on a spectrum
 * that is wrong by a constant factor still looks like an ocean. It is just the
 * wrong sea for the wind, forever, and no amount of shading fixes it.
 */
class OceanSpectrumTest {

    @Test
    @DisplayName("log-gamma reproduces known values")
    void gammaIsCorrect() {
        assertEquals(Math.sqrt(Math.PI), Gamma.gamma(0.5), 1e-9);
        assertEquals(1.0, Gamma.gamma(1.0), 1e-12);
        assertEquals(1.0, Gamma.gamma(2.0), 1e-12);
        assertEquals(24.0, Gamma.gamma(5.0), 1e-9);
        assertEquals(362880.0, Gamma.gamma(10.0), 1e-3);
    }

    @Test
    @DisplayName("Pierson-Moskowitz numeric integration matches its closed form")
    void pmNumericMatchesAnalytic() {
        PiersonMoskowitzSpectrum pm = new PiersonMoskowitzSpectrum(10.0);
        // zerothMoment() is overridden with the analytic value; recompute numerically.
        double wp = pm.peakOmega();
        double lo = wp * 0.02;
        double hi = wp * 20.0;
        int steps = 200_000;
        double step = (hi - lo) / steps;
        double sum = 0.5 * (pm.energy(lo) + pm.energy(hi));
        for (int i = 1; i < steps; i++) {
            sum += pm.energy(lo + i * step);
        }
        double numeric = sum * step;
        assertEquals(pm.zerothMoment(), numeric, pm.zerothMoment() * 0.01);
    }

    @Test
    @DisplayName("a fully developed sea at 10 m/s gives a plausible Hs")
    void piersonMoskowitzIsPhysicallyPlausible() {
        double hs = new PiersonMoskowitzSpectrum(10.0).significantWaveHeight();
        // A fully developed sea in ~20 kt is about 2 to 3 m.
        assertTrue(hs > 2.0 && hs < 3.0, "Hs = " + hs);
    }

    @Test
    @DisplayName("JONSWAP fetch limitation reduces Hs and shortens the period")
    void fetchLimitationBehaves() {
        double wind = 12.86; // 25 kt
        JonswapSpectrum shortFetch = new JonswapSpectrum(wind, 20_000.0);
        JonswapSpectrum longFetch = new JonswapSpectrum(wind, 500_000.0);

        assertTrue(shortFetch.significantWaveHeight() < longFetch.significantWaveHeight(),
                "short fetch Hs " + shortFetch.significantWaveHeight()
                        + " should be below long fetch Hs " + longFetch.significantWaveHeight());
        assertTrue(shortFetch.peakOmega() > longFetch.peakOmega(),
                "short fetch should peak at a higher frequency");
        assertTrue(!shortFetch.isFullyDeveloped() && longFetch.isFullyDeveloped());

        // A developed 25 kt sea runs roughly 3.5 to 5 m, peaky rather than
        // Pierson-Moskowitz smooth, so a little above the fully developed value.
        double hs = longFetch.significantWaveHeight();
        assertTrue(hs > 3.2 && hs < 5.2, "Hs = " + hs);
    }

    @Test
    @DisplayName("the sea stops growing once the fetch reaches full development")
    void fetchIsCappedAtFullDevelopment() {
        double wind = 12.86;
        double atCap = new JonswapSpectrum(wind, JonswapSpectrum.fullDevelopmentFetch(wind))
                .significantWaveHeight();
        double wayPast = new JonswapSpectrum(wind, 5_000_000.0).significantWaveHeight();
        assertEquals(atCap, wayPast, 1e-9, "Hs must plateau past full development");

        // Never more than moderately above the fully developed Pierson-Moskowitz sea:
        // the peak enhancement adds energy, it does not create a different ocean.
        double pm = new PiersonMoskowitzSpectrum(wind).significantWaveHeight();
        assertTrue(wayPast > pm && wayPast < pm * 1.4,
                "JONSWAP " + wayPast + " vs Pierson-Moskowitz " + pm);
    }

    @Test
    @DisplayName("directional spreading integrates to one")
    void spreadingIsNormalised() {
        for (double s : new double[] {0.5, 2.0, 8.0, 40.0}) {
            CosinePowerSpreading d = CosinePowerSpreading.constant(0.7, s);
            int steps = 20_000;
            double sum = 0.0;
            double step = Mth.TAU / steps;
            for (int i = 0; i < steps; i++) {
                sum += d.density(-Math.PI + i * step, 1.0) * step;
            }
            assertEquals(1.0, sum, 2e-3, "exponent s=" + s);
        }
    }

    @Test
    @DisplayName("the wavenumber spectrum integrates back to the frequency moment")
    void wavenumberConversionPreservesEnergy() {
        SeaState sea = SeaState.openOcean(12.86, 0.4, 20240701L);
        // One cascade covering the whole wavenumber axis, so no band-limiting.
        CascadeSettings full = CascadeSettings.rebandedAt(256, 512f, 0f, Float.MAX_VALUE);
        InitialSpectrum spectrum = new InitialSpectrum(sea, full, 0);

        // Integrate S2 over k-space in polar coordinates: int S2 * k dk dtheta.
        int kSteps = 4000;
        int thetaSteps = 720;
        double kLo = 1e-4;
        double kHi = 12.0;
        double dTheta = Mth.TAU / thetaSteps;
        // Logarithmic spacing in k: the peak is at k ~ 0.04 and the tail runs to 12.
        double logLo = Math.log(kLo);
        double logHi = Math.log(kHi);
        double dLog = (logHi - logLo) / kSteps;

        double total = 0.0;
        for (int i = 0; i < kSteps; i++) {
            double k = Math.exp(logLo + (i + 0.5) * dLog);
            double dk = k * dLog;
            for (int j = 0; j < thetaSteps; j++) {
                double theta = -Math.PI + (j + 0.5) * dTheta;
                double s2 = spectrum.directionalSpectrum(k * Math.cos(theta), k * Math.sin(theta));
                total += s2 * k * dk * dTheta;
            }
        }

        double expected = sea.significantWaveHeight() * sea.significantWaveHeight() / 16.0;
        assertEquals(expected, total, expected * 0.03,
                "integrated m0 " + total + " vs expected " + expected);
    }

    @Test
    @DisplayName("h0 amplitudes carry exactly the variance the spectrum prescribes")
    void initialSpectrumVarianceMatchesTheSpectrum() {
        // A single realisation is a random variable: nearly all the energy sits in
        // the few dozen bins around the spectral peak, so one draw scatters by
        // several percent no matter how many bins the grid has. Averaging over
        // independent seeds tests the amplitude convention itself rather than the
        // luck of one phase field.
        int resolution = 64;
        float patch = 512f;
        int realisations = 60;
        SeaState base = SeaState.openOcean(12.86, 0.4, 0L);
        CascadeSettings full = CascadeSettings.rebandedAt(resolution, patch, 0f, Float.MAX_VALUE);

        double meanRealised = 0.0;
        for (int r = 0; r < realisations; r++) {
            InitialSpectrum spectrum = new InitialSpectrum(base.withSeed(1000L + r), full, 0);
            meanRealised += spectrum.varianceOf(spectrum.generate());
        }
        meanRealised /= realisations;

        InitialSpectrum reference = new InitialSpectrum(base, full, 0);
        double dk = Mth.TAU / patch;
        double expected = 0.0;
        for (int z = 0; z < resolution; z++) {
            for (int x = 0; x < resolution; x++) {
                double kx = Mth.TAU * InitialSpectrum.signedIndex(x, resolution) / patch;
                double kz = Mth.TAU * InitialSpectrum.signedIndex(z, resolution) / patch;
                expected += reference.directionalSpectrum(kx, kz) * dk * dk;
            }
        }

        assertEquals(expected, meanRealised, expected * 0.05,
                "mean realised variance " + meanRealised + " vs expected " + expected);
    }

    @Test
    @DisplayName("cascade bands tile the wavenumber axis without gaps or overlap")
    void cascadeBandsTile() {
        CascadeSettings c = CascadeSettings.standard(256);
        assertEquals(3, c.count());
        assertEquals(0f, c.kMin[0]);
        for (int i = 1; i < c.count(); i++) {
            assertEquals(c.kMax[i - 1], c.kMin[i], 0f,
                    "band " + (i - 1) + " must end where band " + i + " begins");
        }
        assertEquals(Float.MAX_VALUE, c.kMax[c.count() - 1]);
    }

    @Test
    @DisplayName("identical seeds give identical oceans, different seeds do not")
    void generationIsDeterministic() {
        CascadeSettings cascades = CascadeSettings.standard(64);
        SeaState a = SeaState.openOcean(10.0, 0.0, 7L);
        float[] first = new InitialSpectrum(a, cascades, 0).generate();
        float[] second = new InitialSpectrum(a, cascades, 0).generate();
        assertTrue(java.util.Arrays.equals(first, second), "same seed must reproduce exactly");

        float[] other = new InitialSpectrum(a.withSeed(8L), cascades, 0).generate();
        assertTrue(!java.util.Arrays.equals(first, other), "a different seed must differ");
    }

    @Test
    @DisplayName("cascades are decorrelated from one another")
    void cascadesUseIndependentPhases() {
        CascadeSettings cascades = CascadeSettings.standard(64);
        SeaState sea = SeaState.openOcean(10.0, 0.0, 11L);
        float[] c0 = new InitialSpectrum(sea, cascades, 0).generate();
        float[] c1 = new InitialSpectrum(sea, cascades, 1).generate();
        assertTrue(!java.util.Arrays.equals(c0, c1),
                "cascades sharing a phase field would collapse into one scaled copy");
    }
}
