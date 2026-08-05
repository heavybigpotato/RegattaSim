package com.bluemeridian.core.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The FFT is the load-bearing arithmetic of the whole renderer, and the GLSL
 * version is a transliteration of this one, so it is checked against a naive DFT
 * rather than against itself.
 */
class FastFourierTransformTest {

    /** Direct evaluation of {@code h[j] = sum_n H[n] e^{+2 pi i n j / N}}. */
    private static float[] naiveInverse1d(float[] data, int n) {
        float[] out = new float[n * 2];
        for (int j = 0; j < n; j++) {
            double re = 0;
            double im = 0;
            for (int k = 0; k < n; k++) {
                double a = 2.0 * Math.PI * k * j / n;
                double c = Math.cos(a);
                double s = Math.sin(a);
                re += data[k * 2] * c - data[k * 2 + 1] * s;
                im += data[k * 2] * s + data[k * 2 + 1] * c;
            }
            out[j * 2] = (float) re;
            out[j * 2 + 1] = (float) im;
        }
        return out;
    }

    /** Separable 2D reference: rows then columns. */
    private static float[] naiveInverse2d(float[] data, int n) {
        float[] tmp = new float[n * n * 2];
        float[] row = new float[n * 2];
        for (int r = 0; r < n; r++) {
            System.arraycopy(data, r * n * 2, row, 0, n * 2);
            float[] t = naiveInverse1d(row, n);
            System.arraycopy(t, 0, tmp, r * n * 2, n * 2);
        }
        float[] out = new float[n * n * 2];
        float[] col = new float[n * 2];
        for (int c = 0; c < n; c++) {
            for (int r = 0; r < n; r++) {
                col[r * 2] = tmp[(r * n + c) * 2];
                col[r * 2 + 1] = tmp[(r * n + c) * 2 + 1];
            }
            float[] t = naiveInverse1d(col, n);
            for (int r = 0; r < n; r++) {
                out[(r * n + c) * 2] = t[r * 2];
                out[(r * n + c) * 2 + 1] = t[r * 2 + 1];
            }
        }
        return out;
    }

    @Test
    @DisplayName("butterfly-driven 2D inverse FFT matches a naive DFT")
    void matchesNaiveDft() {
        for (int n : new int[] {8, 16, 32}) {
            Random rnd = new Random(1234 + n);
            float[] data = new float[n * n * 2];
            for (int i = 0; i < data.length; i++) {
                data[i] = (float) (rnd.nextDouble() * 2.0 - 1.0);
            }
            float[] expected = naiveInverse2d(data, n);
            float[] actual = data.clone();
            new FastFourierTransform(n).inverse2d(actual);

            for (int i = 0; i < expected.length; i++) {
                assertEquals(expected[i], actual[i], 1e-2f,
                        "n=" + n + " element " + i);
            }
        }
    }

    @Test
    @DisplayName("a single non-zero bin produces the corresponding plane wave")
    void singleBinIsAPlaneWave() {
        int n = 16;
        float[] data = new float[n * n * 2];
        // Bin (kx=2, kz=0), amplitude 1, zero phase.
        data[(0 * n + 2) * 2] = 1f;
        new FastFourierTransform(n).inverse2d(data);

        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                double expectedRe = Math.cos(2.0 * Math.PI * 2 * x / n);
                double expectedIm = Math.sin(2.0 * Math.PI * 2 * x / n);
                assertEquals(expectedRe, data[(z * n + x) * 2], 1e-4,
                        "real at " + x + "," + z);
                assertEquals(expectedIm, data[(z * n + x) * 2 + 1], 1e-4,
                        "imag at " + x + "," + z);
            }
        }
    }

    @Test
    @DisplayName("a Hermitian spectrum transforms to a purely real field")
    void hermitianSpectrumIsReal() {
        int n = 16;
        Random rnd = new Random(99);
        float[] data = new float[n * n * 2];
        // Build H(-k) = conj(H(k)) by construction.
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int mz = (n - z) % n;
                int mx = (n - x) % n;
                int i = (z * n + x) * 2;
                int m = (mz * n + mx) * 2;
                if (data[i] != 0f || data[i + 1] != 0f) {
                    continue;
                }
                float re = (float) rnd.nextGaussian();
                float im = (float) rnd.nextGaussian();
                if (i == m) {
                    im = 0f; // self-conjugate bins must be real
                }
                data[i] = re;
                data[i + 1] = im;
                data[m] = re;
                data[m + 1] = -im;
            }
        }
        new FastFourierTransform(n).inverse2d(data);
        for (int i = 1; i < data.length; i += 2) {
            assertTrue(Math.abs(data[i]) < 1e-3f,
                    "imaginary residue " + data[i] + " at " + i);
        }
    }

    @Test
    @DisplayName("butterfly plan indices cover every input exactly once per stage")
    void planIsAPermutation() {
        int n = 32;
        ButterflyPlan plan = new ButterflyPlan(n);
        for (int stage = 0; stage < plan.stages(); stage++) {
            int[] touches = new int[n];
            for (int x = 0; x < n; x++) {
                touches[plan.indexA(stage, x)]++;
                touches[plan.indexB(stage, x)]++;
            }
            for (int i = 0; i < n; i++) {
                assertEquals(2, touches[i],
                        "stage " + stage + " input " + i + " read " + touches[i] + " times");
            }
        }
    }
}
