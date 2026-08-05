package com.bluemeridian.desktop;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.bluemeridian.core.math.FastFourierTransform;
import com.bluemeridian.core.math.Mth;
import com.bluemeridian.core.ocean.CascadeSettings;
import com.bluemeridian.core.ocean.Dispersion;
import com.bluemeridian.core.ocean.InitialSpectrum;
import com.bluemeridian.core.ocean.SeaState;
import com.bluemeridian.render.gl.RenderTarget;
import com.bluemeridian.render.ocean.GpuOceanSimulation;
import com.bluemeridian.render.ocean.OceanCascade;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Checks that the GPU produces the same wave field as the CPU.
 *
 * <p>This is the test that makes the rest of the renderer trustworthy. The GLSL
 * transform is a transliteration of {@link FastFourierTransform}, which is itself
 * checked against a naive DFT in {@code core}'s unit tests. Transliterations
 * drift: an index computed with the wrong parity, a twiddle conjugated the wrong
 * way, a texture fetched with the wrong filter. All of those produce an ocean
 * that still moves and still looks vaguely like water, so none of them would be
 * caught by looking at it.
 *
 * <p>Here the same sea state is evolved on both sides to the same instant, the
 * GPU's displacement map is read back, and the two are compared texel by texel.
 * It runs headless, so it belongs in CI.
 *
 * <p>Exits non-zero when the two disagree.
 */
public final class GpuFftVerifyTool {

    private static final int RESOLUTION = 64;
    private static final float PATCH = 512f;
    private static final float TIME = 7.25f;
    /**
     * Tolerance as a fraction of the field's RMS. The GPU works in 32-bit float
     * through an 8-stage transform and stores the result as 16-bit float, which is
     * the dominant error term; 2% of RMS is comfortably above that and far below
     * anything a real bug would produce.
     */
    private static final double TOLERANCE_FRACTION = 0.02;

    private GpuFftVerifyTool() {
    }

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL32, 4, 3);
        config.setWindowedMode(256, 256);
        config.setInitialVisible(false);
        config.disableAudio(true);

        Verifier verifier = new Verifier();
        new Lwjgl3Application(verifier, config);
        System.exit(verifier.passed ? 0 : 1);
    }

    private static final class Verifier extends ApplicationAdapter {

        boolean passed;

        @Override
        public void create() {
            try {
                passed = verify();
            } catch (RuntimeException e) {
                e.printStackTrace();
                passed = false;
            }
            Gdx.app.exit();
        }

        private boolean verify() {
            System.out.println("GL_RENDERER = " + Gdx.gl.glGetString(GL20.GL_RENDERER));

            // A single cascade covering the whole spectrum, so the comparison is not
            // confused by band limits.
            SeaState sea = SeaState.openOcean(12.86, 0.4, 987_654L);
            CascadeSettings cascades =
                    CascadeSettings.rebandedAt(RESOLUTION, PATCH, 0f, Float.MAX_VALUE);

            GpuOceanSimulation simulation = new GpuOceanSimulation(sea, cascades);
            try {
                simulation.update(TIME, 1f / 60f);

                OceanCascade cascade = simulation.cascade(0);
                float[] gpu = readBack(cascade.displacement());
                float[] expected = cpuReference(sea, cascades);

                return compare(expected, gpu);
            } finally {
                simulation.dispose();
            }
        }

        /**
         * Evolves and transforms the same spectrum on the CPU, producing the same
         * {@code (Dx*lambda, h, Dz*lambda)} the GPU's displacement pass writes.
         */
        private float[] cpuReference(SeaState sea, CascadeSettings cascades) {
            int n = RESOLUTION;
            float[] h0 = new InitialSpectrum(sea, cascades, 0).generate();
            FastFourierTransform fft = new FastFourierTransform(n);

            float[] horizontal = new float[n * n * 2];
            float[] height = new float[n * n * 2];

            for (int z = 0; z < n; z++) {
                for (int x = 0; x < n; x++) {
                    int index = z * n + x;
                    double kx = Mth.TAU * InitialSpectrum.signedIndex(x, n) / PATCH;
                    double kz = Mth.TAU * InitialSpectrum.signedIndex(z, n) / PATCH;
                    double k = Math.hypot(kx, kz);
                    double omega = Dispersion.quantiseForLoop(
                            Dispersion.omega(k, sea.depth), sea.repeatPeriod);

                    double phase = omega * TIME;
                    double cos = Math.cos(phase);
                    double sin = Math.sin(phase);

                    int o4 = index * 4;
                    double a = h0[o4];
                    double b = h0[o4 + 1];
                    double c = h0[o4 + 2];
                    double d = h0[o4 + 3];
                    double hRe = a * cos - b * sin + c * cos + d * sin;
                    double hIm = a * sin + b * cos - c * sin + d * cos;

                    int o2 = index * 2;
                    height[o2] = (float) hRe;
                    height[o2 + 1] = (float) hIm;

                    if (k < 1e-8) {
                        continue;
                    }
                    double nx = kx / k;
                    double nz = kz / k;
                    // Dx + i*Dz with D = -i (k/|k|) h~
                    double dxRe = hIm * nx;
                    double dxIm = -hRe * nx;
                    double dzRe = hIm * nz;
                    double dzIm = -hRe * nz;
                    horizontal[o2] = (float) (dxRe - dzIm);
                    horizontal[o2 + 1] = (float) (dxIm + dzRe);
                }
            }

            fft.inverse2d(horizontal);
            fft.inverse2d(height);

            float[] out = new float[n * n * 4];
            for (int i = 0; i < n * n; i++) {
                out[i * 4] = (float) (horizontal[i * 2] * sea.choppiness);
                out[i * 4 + 1] = height[i * 2];
                out[i * 4 + 2] = (float) (horizontal[i * 2 + 1] * sea.choppiness);
            }
            return out;
        }

        private float[] readBack(RenderTarget target) {
            int n = target.width();
            ByteBuffer bytes = ByteBuffer.allocateDirect(n * n * 4 * Float.BYTES)
                    .order(ByteOrder.nativeOrder());
            target.begin();
            Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 1);
            Gdx.gl.glReadPixels(0, 0, n, n, GL30.GL_RGBA, GL20.GL_FLOAT, bytes);
            target.end();

            FloatBuffer floats = bytes.asFloatBuffer();
            float[] out = new float[n * n * 4];
            floats.get(out);
            return out;
        }

        private boolean compare(float[] expected, float[] actual) {
            int n = RESOLUTION;
            double sumSquares = 0.0;
            for (int i = 0; i < n * n; i++) {
                for (int c = 0; c < 3; c++) {
                    double v = expected[i * 4 + c];
                    sumSquares += v * v;
                }
            }
            double rms = Math.sqrt(sumSquares / (n * n * 3));
            double tolerance = rms * TOLERANCE_FRACTION;

            double worst = 0.0;
            int worstIndex = -1;
            int worstChannel = -1;
            for (int i = 0; i < n * n; i++) {
                for (int c = 0; c < 3; c++) {
                    double diff = Math.abs(expected[i * 4 + c] - actual[i * 4 + c]);
                    if (diff > worst) {
                        worst = diff;
                        worstIndex = i;
                        worstChannel = c;
                    }
                }
            }

            System.out.printf(java.util.Locale.ROOT,
                    "field RMS      = %.6f m%n"
                    + "tolerance      = %.6f m (%.1f%% of RMS)%n"
                    + "worst error    = %.6f m at texel %d,%d channel %s%n",
                    rms, tolerance, TOLERANCE_FRACTION * 100.0, worst,
                    worstIndex % n, worstIndex / n,
                    worstChannel == 0 ? "Dx" : worstChannel == 1 ? "height" : "Dz");

            if (rms < 1e-4) {
                System.err.println("FAIL: the CPU reference field is empty, nothing was compared");
                return false;
            }
            if (worst > tolerance) {
                System.err.println("FAIL: GPU and CPU wave fields diverge");
                return false;
            }
            System.out.println("PASS: GPU FFT matches the CPU reference");
            return true;
        }
    }
}
