package com.bluemeridian.core.ocean;

import com.bluemeridian.core.math.Mth;

/**
 * The cascade decomposition: how the wavenumber axis is split across several
 * independent FFT patches.
 *
 * <p>A single FFT patch cannot cover an ocean. A 512 m patch resolves swell but
 * its finest detail is 4 m across, so the surface goes glassy near the camera; a
 * 16 m patch has beautiful ripples and tiles visibly every 16 m. Three patches
 * at decorrelated scales, each carrying only its own slice of the spectrum, is
 * the smallest arrangement where neither failure mode is visible. Two leaves a
 * gap you can see; four costs a third more bandwidth for detail below a pixel.
 *
 * <p><b>Non-overlap matters.</b> Each cascade is band-limited to
 * {@code [kMin, kMax)} and the bands tile the axis exactly. Overlapping bands
 * would double-count energy and the sea would come out too rough by a factor that
 * changes with wind speed, which is much harder to notice and much worse.
 *
 * <p>The default split places each boundary at a cascade's own Nyquist
 * wavenumber. Because the patch sizes are ratios of 4 and 8 rather than N/2, the
 * lower cascades leave some of their spectral bins empty. That is a deliberate
 * trade: patch sizes that tile the spectrum perfectly would have to be roughly
 * 128x apart, and the intermediate scales are exactly the ones the eye reads as
 * "sea" rather than "water".
 */
public final class CascadeSettings {

    /** FFT resolution per axis, same for every cascade. */
    public final int resolution;
    /** Patch size of each cascade, metres, in descending order. */
    public final float[] patchSizes;
    /** Lower wavenumber bound of each cascade, rad/m. */
    public final float[] kMin;
    /** Upper wavenumber bound of each cascade, rad/m. */
    public final float[] kMax;

    /**
     * A layout that keeps the given wavenumber bands but reports a different
     * resolution. Used to evaluate the physics surface on a coarser grid while
     * still slicing the spectrum exactly where the renderer slices it - if the
     * two disagreed about which cascade owns which waves, the boat would float on
     * a different sea than the one drawn.
     */
    public static CascadeSettings rebandedAt(int resolution, float patchSize, float bandMin,
            float bandMax) {
        CascadeSettings s = new CascadeSettings(resolution, new float[] {patchSize});
        s.kMin[0] = bandMin;
        s.kMax[0] = bandMax;
        return s;
    }

    public CascadeSettings(int resolution, float[] patchSizes) {
        if (!Mth.isPowerOfTwo(resolution) || resolution < 8) {
            throw new IllegalArgumentException(
                    "cascade resolution must be a power of two >= 8, got " + resolution);
        }
        if (patchSizes.length == 0) {
            throw new IllegalArgumentException("at least one cascade is required");
        }
        for (int i = 1; i < patchSizes.length; i++) {
            if (patchSizes[i] >= patchSizes[i - 1]) {
                throw new IllegalArgumentException(
                        "patch sizes must strictly decrease, got " + patchSizes[i - 1]
                                + " then " + patchSizes[i]);
            }
        }
        this.resolution = resolution;
        this.patchSizes = patchSizes.clone();
        this.kMin = new float[patchSizes.length];
        this.kMax = new float[patchSizes.length];

        for (int i = 0; i < patchSizes.length; i++) {
            // Nyquist of this cascade: the shortest wave its grid can represent.
            float nyquist = (float) (Math.PI * resolution / patchSizes[i]);
            kMin[i] = i == 0 ? 0f : kMax[i - 1];
            kMax[i] = i == patchSizes.length - 1 ? Float.MAX_VALUE : nyquist;
        }
    }

    public int count() {
        return patchSizes.length;
    }

    /** Fundamental wavenumber (bin spacing) of a cascade, rad/m. */
    public float deltaK(int cascade) {
        return (float) (Mth.TAU / patchSizes[cascade]);
    }

    /** The three-cascade arrangement from the design brief: 512 m / 128 m / 16 m. */
    public static CascadeSettings standard(int resolution) {
        return new CascadeSettings(resolution, new float[] {512f, 128f, 16f});
    }

    /** Two cascades for the low tiers, where a third costs more than it returns. */
    public static CascadeSettings reduced(int resolution) {
        return new CascadeSettings(resolution, new float[] {512f, 32f});
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("CascadeSettings[N=").append(resolution);
        for (int i = 0; i < patchSizes.length; i++) {
            sb.append(String.format(java.util.Locale.ROOT, ", L%d=%.0fm k=[%.4f,%s)",
                    i, patchSizes[i], kMin[i],
                    kMax[i] == Float.MAX_VALUE ? "inf" : String.format(java.util.Locale.ROOT, "%.4f", kMax[i])));
        }
        return sb.append(']').toString();
    }
}
