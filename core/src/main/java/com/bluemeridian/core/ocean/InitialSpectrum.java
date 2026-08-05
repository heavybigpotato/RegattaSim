package com.bluemeridian.core.ocean;

import com.bluemeridian.core.math.Mth;
import com.bluemeridian.core.ocean.spectrum.DirectionalSpreading;
import com.bluemeridian.core.ocean.spectrum.WaveSpectrum;
import com.bluemeridian.core.util.DeterministicRandom;

/**
 * Builds the time-independent initial spectrum {@code h0(k)} for one cascade.
 *
 * <p>Following Tessendorf, the surface is
 *
 * <pre>
 *   h~(k, t) = h0(k) e^{i w t} + conj(h0(-k)) e^{-i w t}
 *   h(x, t)  = sum over k of h~(k, t) e^{i k.x}
 * </pre>
 *
 * <p>so the only randomness in the entire ocean lives in {@code h0}, drawn once.
 * Each texel therefore stores two complex numbers, {@code h0(k)} and
 * {@code conj(h0(-k))}, and the per-frame work reduces to two complex rotations
 * and an inverse FFT.
 *
 * <p>Amplitude scaling: with {@code h0 = (1/sqrt2)(xr + i*xi) * A} the expected
 * power is {@code A^2}, and summing the two counter-propagating terms doubles it,
 * so {@code A = sqrt(0.5 * S2(k) * dkx * dkz)} makes the surface variance come out
 * at {@code m0 = Hs^2/16}. {@code OceanSpectrumTest} checks that end to end,
 * because an amplitude convention that is wrong by a factor of two produces an
 * ocean that still looks like an ocean and is quietly, unfixably wrong.
 *
 * <p>The wavenumber grid uses the natural FFT ordering (DC at index 0, negative
 * frequencies in the upper half) rather than a centred layout, which removes the
 * {@code (-1)^(x+z)} unshuffle pass that centred layouts need.
 */
public final class InitialSpectrum {

    /** Floats per texel: h0.re, h0.im, conj(h0(-k)).re, conj(h0(-k)).im. */
    public static final int STRIDE = 4;

    private final int resolution;
    private final float patchSize;
    private final float kMin;
    private final float kMax;
    private final double depth;
    private final long seed;

    private final WaveSpectrum windSea;
    private final DirectionalSpreading windSeaSpread;
    private final WaveSpectrum swell;
    private final DirectionalSpreading swellSpread;

    public InitialSpectrum(SeaState sea, CascadeSettings cascades, int cascadeIndex) {
        this(sea, cascades, cascadeIndex, cascadeIndex);
    }

    /**
     * @param bandIndex    which entry of {@code cascades} supplies the patch size and band
     * @param decorrelator salt for the phase field; must equal the renderer's cascade
     *                     index so that a coarser physics grid realises the same ocean
     */
    public InitialSpectrum(SeaState sea, CascadeSettings cascades, int bandIndex, int decorrelator) {
        this.resolution = cascades.resolution;
        this.patchSize = cascades.patchSizes[bandIndex];
        this.kMin = cascades.kMin[bandIndex];
        this.kMax = cascades.kMax[bandIndex];
        this.depth = sea.depth;
        // Mixing the cascade index into the seed decorrelates the cascades. Without
        // this they share phases and the "three independent scales" collapse into
        // one scaled copy, which is visible as a repeating diagonal grain.
        this.seed = DeterministicRandom.mix64(sea.seed + 0x9E3779B9L * (decorrelator + 1));
        this.windSea = sea.windSeaSpectrum();
        this.windSeaSpread = sea.windSeaSpreading();
        this.swell = sea.swellSpectrum();
        this.swellSpread = sea.swellSpreading();
    }

    public int resolution() {
        return resolution;
    }

    /**
     * The directional wavenumber spectrum {@code S2(kx, kz)}, in m^4 (energy per
     * unit area of k-space).
     *
     * <p>Converted from the frequency spectrum by
     * {@code S2 = S(w) * D(theta, w) * (dw/dk) / k}, where the {@code 1/k} comes
     * from the polar-to-Cartesian Jacobian {@code dkx dkz = k dk dtheta}.
     */
    public double directionalSpectrum(double kx, double kz) {
        double k = Math.hypot(kx, kz);
        if (k < 1e-9 || k < kMin || k >= kMax) {
            return 0.0;
        }
        double omega = Dispersion.omega(k, depth);
        if (omega <= 1e-9) {
            return 0.0;
        }
        double dwdk = Dispersion.dOmegaDk(k, depth);
        double theta = Math.atan2(kz, kx);

        double s = windSea.energy(omega) * windSeaSpread.density(theta, omega);
        if (swell != null) {
            s += swell.energy(omega) * swellSpread.density(theta, omega);
        }
        return s * dwdk / k;
    }

    /**
     * Generates the full {@code resolution x resolution} initial spectrum.
     *
     * @return interleaved array of {@code resolution*resolution*4} floats, ready
     *         to upload as an RGBA32F texture
     */
    public float[] generate() {
        int n = resolution;
        float[] out = new float[n * n * STRIDE];
        double[] pair = new double[2];
        double dk = Mth.TAU / patchSize;
        double amplitudeScale = Math.sqrt(0.5 * dk * dk);

        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int o = (z * n + x) * STRIDE;

                double[] h0 = complexH0(x, z, amplitudeScale, pair);
                out[o] = (float) h0[0];
                out[o + 1] = (float) h0[1];

                // conj(h0(-k)): the mirrored grid cell, conjugated.
                int mx = (n - x) % n;
                int mz = (n - z) % n;
                double[] h0Minus = complexH0(mx, mz, amplitudeScale, pair);
                out[o + 2] = (float) h0Minus[0];
                out[o + 3] = (float) -h0Minus[1];
            }
        }
        return out;
    }

    /** Scratch-free-ish evaluation of h0 at an unsigned grid index. */
    private double[] complexH0(int x, int z, double amplitudeScale, double[] pair) {
        int n = resolution;
        double kx = Mth.TAU * signedIndex(x, n) / patchSize;
        double kz = Mth.TAU * signedIndex(z, n) / patchSize;

        double s2 = directionalSpectrum(kx, kz);
        if (s2 <= 0.0) {
            return ZERO;
        }
        double amplitude = amplitudeScale * Math.sqrt(s2);
        DeterministicRandom.gaussianPair(seed, x, z, pair);
        double inv = 1.0 / Math.sqrt(2.0);
        return new double[] {inv * pair[0] * amplitude, inv * pair[1] * amplitude};
    }

    private static final double[] ZERO = {0.0, 0.0};

    /** Maps an FFT bin index to its signed wavenumber index. */
    public static int signedIndex(int index, int n) {
        return index < n / 2 ? index : index - n;
    }

    /**
     * Sum of {@code 2*|h0|^2} over the grid, which is the surface elevation
     * variance this cascade contributes.
     */
    public double varianceOf(float[] spectrum) {
        double sum = 0.0;
        for (int i = 0; i < spectrum.length; i += STRIDE) {
            double re = spectrum[i];
            double im = spectrum[i + 1];
            sum += re * re + im * im;
        }
        return 2.0 * sum;
    }
}
