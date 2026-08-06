package com.bluemeridian.core.ocean;

import com.bluemeridian.core.math.FastFourierTransform;
import com.bluemeridian.core.math.Mth;

/**
 * The wave field evaluated on the CPU, at a resolution chosen for physics rather
 * than for looks.
 *
 * <p>This exists because the boat has to float on the same water the player sees,
 * and the authoritative server has to agree about it without owning a GPU. It
 * runs the identical spectrum, dispersion and butterfly schedule as the renderer,
 * at a coarser grid: the buoyancy of a 60-foot hull does not care about 20 cm
 * capillary chop, so the fine cascades are simply omitted and the cost drops by
 * more than an order of magnitude.
 *
 * <p>Sampling a choppy surface is not a lookup. Horizontal displacement makes the
 * surface parametric - the grid point at {@code (u,v)} ends up at
 * {@code (u + Dx, v + Dz)} - so finding the height above a fixed world position
 * means inverting that map. A few fixed-point iterations converge quickly as long
 * as the choppiness stays below the self-intersection limit, which is the same
 * limit the renderer respects.
 */
public final class CpuOceanSurface implements WaveSurface {

    private final int resolution;
    private final int cascadeCount;
    private final float[] patchSizes;
    private final double choppiness;
    private final double repeatPeriod;
    private final double depth;

    /** Per cascade: interleaved (h0.re, h0.im, conj(h0(-k)).re, conj(h0(-k)).im). */
    private final float[][] initialSpectra;
    /** Per cascade: angular frequency for every bin, precomputed once. */
    private final float[][] omega;

    /** Per cascade spatial fields, indexed [cascade][z*n + x]. */
    private final float[][] heightField;
    private final float[][] displacementXField;
    private final float[][] displacementZField;

    private final FastFourierTransform fft;
    private final float[] scratchA;
    private final float[] scratchB;

    private double currentTime = Double.NaN;

    /**
     * @param sea             sea state to realise
     * @param cascades        cascade layout; only the first {@code cascadeLimit} are evaluated
     * @param physicsResolution FFT grid for physics, typically 64
     * @param cascadeLimit    how many cascades to include, from the largest down
     */
    public CpuOceanSurface(SeaState sea, CascadeSettings cascades, int physicsResolution,
            int cascadeLimit) {
        if (cascadeLimit < 1 || cascadeLimit > cascades.count()) {
            throw new IllegalArgumentException("cascadeLimit out of range: " + cascadeLimit);
        }
        this.resolution = physicsResolution;
        this.cascadeCount = cascadeLimit;
        this.choppiness = sea.choppiness;
        this.repeatPeriod = sea.repeatPeriod;
        this.depth = sea.depth;
        this.fft = new FastFourierTransform(physicsResolution);

        this.patchSizes = new float[cascadeLimit];
        this.initialSpectra = new float[cascadeLimit][];
        this.omega = new float[cascadeLimit][];
        this.heightField = new float[cascadeLimit][];
        this.displacementXField = new float[cascadeLimit][];
        this.displacementZField = new float[cascadeLimit][];

        for (int c = 0; c < cascadeLimit; c++) {
            patchSizes[c] = cascades.patchSizes[c];
            // Band limits must come from the *render* cascade layout so both agree
            // on which slice of the spectrum belongs to which cascade.
            CascadeSettings band = CascadeSettings.rebandedAt(physicsResolution,
                    cascades.patchSizes[c], cascades.kMin[c], cascades.kMax[c]);
            InitialSpectrum gen = new InitialSpectrum(sea, band, 0, c);
            initialSpectra[c] = gen.generate();
            omega[c] = precomputeOmega(patchSizes[c]);
            heightField[c] = new float[resolution * resolution];
            displacementXField[c] = new float[resolution * resolution];
            displacementZField[c] = new float[resolution * resolution];
        }

        this.scratchA = new float[resolution * resolution * 2];
        this.scratchB = new float[resolution * resolution * 2];
    }

    private float[] precomputeOmega(float patchSize) {
        int n = resolution;
        float[] w = new float[n * n];
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                double kx = Mth.TAU * InitialSpectrum.signedIndex(x, n) / patchSize;
                double kz = Mth.TAU * InitialSpectrum.signedIndex(z, n) / patchSize;
                double k = Math.hypot(kx, kz);
                w[z * n + x] = (float) Dispersion.quantiseForLoop(
                        Dispersion.omega(k, depth), repeatPeriod);
            }
        }
        return w;
    }

    public int resolution() {
        return resolution;
    }

    public int cascadeCount() {
        return cascadeCount;
    }

    /** Rebuilds the spatial fields for the given time. Idempotent for a repeated time. */
    public void update(double timeSeconds) {
        if (timeSeconds == currentTime) {
            return;
        }
        currentTime = timeSeconds;
        for (int c = 0; c < cascadeCount; c++) {
            evolveCascade(c, timeSeconds);
        }
    }

    private void evolveCascade(int c, double t) {
        int n = resolution;
        float[] h0 = initialSpectra[c];
        float[] w = omega[c];
        float patch = patchSizes[c];

        // scratchA carries (Dx + i*Dz), scratchB carries (h + i*0).
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int idx = z * n + x;
                int o4 = idx * 4;
                int o2 = idx * 2;

                double phase = w[idx] * t;
                double cos = Math.cos(phase);
                double sin = Math.sin(phase);

                // h~(k,t) = h0 * e^{i w t} + conj(h0(-k)) * e^{-i w t}
                double a = h0[o4], b = h0[o4 + 1];
                double c2 = h0[o4 + 2], d = h0[o4 + 3];
                double hRe = a * cos - b * sin + c2 * cos + d * sin;
                double hIm = a * sin + b * cos - c2 * sin + d * cos;

                scratchB[o2] = (float) hRe;
                scratchB[o2 + 1] = (float) hIm;

                double kx = Mth.TAU * InitialSpectrum.signedIndex(x, n) / patch;
                double kz = Mth.TAU * InitialSpectrum.signedIndex(z, n) / patch;
                double k = Math.hypot(kx, kz);
                if (k < 1e-9) {
                    scratchA[o2] = 0f;
                    scratchA[o2 + 1] = 0f;
                    continue;
                }
                // D = -i * (k/|k|) * h~, packed as Dx + i*Dz.
                // Multiplying by -i maps (re,im) -> (im,-re).
                double nx = kx / k;
                double nz = kz / k;
                double dxRe = hIm * nx;
                double dxIm = -hRe * nx;
                double dzRe = hIm * nz;
                double dzIm = -hRe * nz;
                // Pack two Hermitian signals into one complex transform: the real
                // part of the result is Dx, the imaginary part is Dz.
                scratchA[o2] = (float) (dxRe - dzIm);
                scratchA[o2 + 1] = (float) (dxIm + dzRe);
            }
        }

        fft.inverse2d(scratchA);
        fft.inverse2d(scratchB);

        float[] hf = heightField[c];
        float[] dxf = displacementXField[c];
        float[] dzf = displacementZField[c];
        for (int i = 0; i < n * n; i++) {
            hf[i] = scratchB[i * 2];
            dxf[i] = (float) (scratchA[i * 2] * choppiness);
            dzf[i] = (float) (scratchA[i * 2 + 1] * choppiness);
        }
    }

    /** Bilinear lookup with wrapping, in the cascade's own patch space. */
    private float sampleField(float[] field, int cascade, double worldX, double worldZ) {
        int n = resolution;
        double patch = patchSizes[cascade];
        double fx = worldX / patch * n;
        double fz = worldZ / patch * n;
        int x0 = (int) Math.floor(fx);
        int z0 = (int) Math.floor(fz);
        double tx = fx - x0;
        double tz = fz - z0;
        int xi0 = Math.floorMod(x0, n);
        int zi0 = Math.floorMod(z0, n);
        int xi1 = (xi0 + 1) % n;
        int zi1 = (zi0 + 1) % n;

        float v00 = field[zi0 * n + xi0];
        float v10 = field[zi0 * n + xi1];
        float v01 = field[zi1 * n + xi0];
        float v11 = field[zi1 * n + xi1];
        double a = v00 + (v10 - v00) * tx;
        double b = v01 + (v11 - v01) * tx;
        return (float) (a + (b - a) * tz);
    }

    /**
     * Displacement of the grid point whose undisplaced position is
     * {@code (u, v)}.
     *
     * @param out receives {@code (Dx, height, Dz)}
     */
    public void displacementAtGridPoint(double u, double v, float[] out) {
        double dx = 0.0;
        double h = 0.0;
        double dz = 0.0;
        for (int c = 0; c < cascadeCount; c++) {
            dx += sampleField(displacementXField[c], c, u, v);
            h += sampleField(heightField[c], c, u, v);
            dz += sampleField(displacementZField[c], c, u, v);
        }
        out[0] = (float) dx;
        out[1] = (float) h;
        out[2] = (float) dz;
    }

    /** Number of fixed-point iterations used to invert the horizontal displacement. */
    private static final int INVERSION_ITERATIONS = 4;

    /**
     * Surface elevation directly above the given world position.
     *
     * <p>Inverts the horizontal displacement by fixed-point iteration: the grid
     * point that lands at {@code (x,z)} is found by repeatedly stepping back along
     * the displacement it produces.
     */
    @Override
    public float heightAt(double worldX, double worldZ) {
        float[] tmp = new float[3];
        double u = worldX;
        double v = worldZ;
        for (int i = 0; i < INVERSION_ITERATIONS; i++) {
            displacementAtGridPoint(u, v, tmp);
            u = worldX - tmp[0];
            v = worldZ - tmp[2];
        }
        displacementAtGridPoint(u, v, tmp);
        return tmp[1];
    }

    /**
     * Upward surface normal at a world position, by central differences of the
     * elevation.
     *
     * @param out receives a normalised {@code (x, y, z)} in world axes
     */
    public void normalAt(double worldX, double worldZ, float[] out) {
        // One tenth of the finest cascade's cell size: fine enough to follow the
        // crest, coarse enough not to amplify interpolation noise.
        double eps = patchSizes[cascadeCount - 1] / resolution * 0.5;
        double hL = heightAt(worldX - eps, worldZ);
        double hR = heightAt(worldX + eps, worldZ);
        double hD = heightAt(worldX, worldZ - eps);
        double hU = heightAt(worldX, worldZ + eps);
        double dhdx = (hR - hL) / (2.0 * eps);
        double dhdz = (hU - hD) / (2.0 * eps);
        double len = Math.sqrt(dhdx * dhdx + 1.0 + dhdz * dhdz);
        out[0] = (float) (-dhdx / len);
        out[1] = (float) (1.0 / len);
        out[2] = (float) (-dhdz / len);
    }

    /** Root-mean-square surface elevation across the whole grid, for diagnostics and tests. */
    public double rmsElevation() {
        double sum = 0.0;
        int count = 0;
        for (int c = 0; c < cascadeCount; c++) {
            for (float v : heightField[c]) {
                sum += v * v;
                count++;
            }
        }
        // Cascades are independent, so their variances add; dividing by the number
        // of cascades would average them instead.
        return Math.sqrt(sum / (count / (double) cascadeCount));
    }
}
