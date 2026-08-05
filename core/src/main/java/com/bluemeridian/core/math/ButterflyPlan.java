package com.bluemeridian.core.math;

/**
 * Precomputed Cooley-Tukey butterfly schedule for a size-N 1D inverse FFT.
 *
 * <p>The plan is laid out exactly as the GPU consumes it: an RGBA float texture
 * {@code log2(N)} wide and {@code N} tall, where texel {@code (stage, lane)} holds
 * {@code (twiddle.re, twiddle.im, indexA, indexB)}. Because texture rows are the
 * slow axis, the backing array is ordered lane-major - {@code [lane][stage]} - so
 * that it can be uploaded verbatim. Stage 0 carries bit-reversed indices so the
 * reordering is folded into the first pass instead of costing a separate one.
 *
 * <p>Every lane performs the same operation, {@code out[x] = in[a] + w * in[b]},
 * with no branch. That works because the twiddle exponent is derived from the
 * lane index {@code x} rather than from the butterfly's base index: a lane in
 * the lower half of a butterfly pair lands exactly half a turn further around
 * the unit circle, so its stored twiddle already carries the minus sign of the
 * subtracting wing.
 *
 * <p>Convention: the twiddle is {@code exp(+2*pi*i*k/N)}, i.e. this plan
 * synthesises a signal from a spectrum ({@code h(x) = sum_k H(k) e^{ikx}}) with
 * no {@code 1/N} normalisation, because the ocean amplitude is already carried
 * by the initial spectrum.
 *
 * <p>The point of generating the table here rather than in the shader is that it
 * also drives {@link FastFourierTransform} on the CPU. The CPU path is unit
 * tested against a naive DFT, so the GLSL is a transliteration of arithmetic
 * that has already been proven correct.
 */
public final class ButterflyPlan {

    /** Floats per table entry: twiddle real, twiddle imaginary, index A, index B. */
    public static final int STRIDE = 4;

    private final int size;
    private final int stages;
    private final float[] table;

    public ButterflyPlan(int size) {
        if (!Mth.isPowerOfTwo(size) || size < 2) {
            throw new IllegalArgumentException("FFT size must be a power of two >= 2, got " + size);
        }
        this.size = size;
        this.stages = Mth.log2(size);
        this.table = new float[stages * size * STRIDE];
        build();
    }

    private void build() {
        for (int stage = 0; stage < stages; stage++) {
            int half = 1 << stage;          // distance between the two inputs of a butterfly
            int span = half << 1;           // length of the sub-transform being combined
            for (int x = 0; x < size; x++) {
                int k = (x * (size / span)) % size;
                double angle = Mth.TAU * k / (double) size;

                boolean topWing = (x % span) < half;
                int indexA = topWing ? x : x - half;
                int indexB = indexA + half;

                if (stage == 0) {
                    // Fold the bit-reversal permutation into the first stage's gather.
                    indexA = Mth.reverseBits(indexA, stages);
                    indexB = Mth.reverseBits(indexB, stages);
                }

                int o = (x * stages + stage) * STRIDE;
                table[o] = (float) Math.cos(angle);
                table[o + 1] = (float) Math.sin(angle);
                table[o + 2] = indexA;
                table[o + 3] = indexB;
            }
        }
    }

    public int size() {
        return size;
    }

    public int stages() {
        return stages;
    }

    public float twiddleRe(int stage, int x) {
        return table[(x * stages + stage) * STRIDE];
    }

    public float twiddleIm(int stage, int x) {
        return table[(x * stages + stage) * STRIDE + 1];
    }

    public int indexA(int stage, int x) {
        return (int) table[(x * stages + stage) * STRIDE + 2];
    }

    public int indexB(int stage, int x) {
        return (int) table[(x * stages + stage) * STRIDE + 3];
    }

    /**
     * The raw table, ordered {@code [lane][stage][rgba]}, ready to upload directly
     * as a {@code log2(N)} wide by {@code N} tall RGBA32F texture. The returned
     * array is the live backing store and must not be modified.
     */
    public float[] table() {
        return table;
    }
}
