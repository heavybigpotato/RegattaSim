package com.bluemeridian.core.math;

/**
 * Table-driven 2D inverse FFT over interleaved complex data, executing the same
 * butterfly schedule the GPU runs.
 *
 * <p>Data layout is {@code [ (row * n + col) * 2 + (0:re, 1:im) ]}, matching the
 * RG channel pair of a texture row. Transforms are performed in place using one
 * scratch buffer, rows first then columns, which is the separable decomposition
 * the GPU passes use.
 *
 * <p>This is the authoritative implementation for the server and for buoyancy
 * queries. It is not the fastest possible FFT; it is the one whose arithmetic
 * is identical to the shader's, which matters more.
 */
public final class FastFourierTransform {

    private final ButterflyPlan plan;
    private final int n;
    private final float[] scratch;

    public FastFourierTransform(int n) {
        this.plan = new ButterflyPlan(n);
        this.n = n;
        this.scratch = new float[n * n * 2];
    }

    public int size() {
        return n;
    }

    public ButterflyPlan plan() {
        return plan;
    }

    /**
     * Runs the 2D inverse transform in place.
     *
     * @param data interleaved complex field of {@code n*n} elements ({@code 2*n*n} floats)
     */
    public void inverse2d(float[] data) {
        if (data.length != n * n * 2) {
            throw new IllegalArgumentException(
                    "expected " + (n * n * 2) + " floats, got " + data.length);
        }
        for (int stage = 0; stage < plan.stages(); stage++) {
            horizontalPass(data, scratch, stage);
            System.arraycopy(scratch, 0, data, 0, data.length);
        }
        for (int stage = 0; stage < plan.stages(); stage++) {
            verticalPass(data, scratch, stage);
            System.arraycopy(scratch, 0, data, 0, data.length);
        }
    }

    private void horizontalPass(float[] src, float[] dst, int stage) {
        for (int row = 0; row < n; row++) {
            int rowBase = row * n * 2;
            for (int x = 0; x < n; x++) {
                int a = rowBase + plan.indexA(stage, x) * 2;
                int b = rowBase + plan.indexB(stage, x) * 2;
                float wr = plan.twiddleRe(stage, x);
                float wi = plan.twiddleIm(stage, x);
                float br = src[b];
                float bi = src[b + 1];
                int o = rowBase + x * 2;
                dst[o] = src[a] + (wr * br - wi * bi);
                dst[o + 1] = src[a + 1] + (wr * bi + wi * br);
            }
        }
    }

    private void verticalPass(float[] src, float[] dst, int stage) {
        for (int col = 0; col < n; col++) {
            for (int y = 0; y < n; y++) {
                int a = (plan.indexA(stage, y) * n + col) * 2;
                int b = (plan.indexB(stage, y) * n + col) * 2;
                float wr = plan.twiddleRe(stage, y);
                float wi = plan.twiddleIm(stage, y);
                float br = src[b];
                float bi = src[b + 1];
                int o = (y * n + col) * 2;
                dst[o] = src[a] + (wr * br - wi * bi);
                dst[o + 1] = src[a + 1] + (wr * bi + wi * br);
            }
        }
    }
}
