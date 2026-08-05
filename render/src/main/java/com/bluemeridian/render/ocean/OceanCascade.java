package com.bluemeridian.render.ocean;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.Disposable;
import com.bluemeridian.core.math.Mth;
import com.bluemeridian.core.ocean.CascadeSettings;
import com.bluemeridian.core.ocean.Dispersion;
import com.bluemeridian.core.ocean.InitialSpectrum;
import com.bluemeridian.core.ocean.SeaState;
import com.bluemeridian.render.gl.DataTexture;
import com.bluemeridian.render.gl.RenderTarget;

/**
 * GPU resources for one FFT cascade.
 *
 * <p>Two textures are uploaded once and never change for a given sea state: the
 * initial spectrum {@code h0}, and a table of {@code (kx, kz, |k|, omega)} per
 * bin. The wave vectors and frequencies are precomputed on the CPU rather than
 * derived in the shader on purpose - it costs one texture and guarantees the
 * renderer and the authoritative server are stepping the same dispersion
 * relation, including the quantisation that makes the surface loop.
 *
 * <p>The rest are working buffers: two full-precision surfaces for the transform
 * to ping-pong through, a displacement map, and a double-buffered derivative map
 * (foam has to read what the previous frame wrote).
 */
public final class OceanCascade implements Disposable {

    private final int resolution;
    private final float patchSize;

    private final Texture initialSpectrum;
    private final Texture waveData;

    private final RenderTarget spatialA;
    private final RenderTarget spatialB;
    private final RenderTarget displacement;
    private final RenderTarget[] derivatives = new RenderTarget[2];
    private int derivativeIndex;

    public OceanCascade(SeaState sea, CascadeSettings cascades, int cascadeIndex) {
        this.resolution = cascades.resolution;
        this.patchSize = cascades.patchSizes[cascadeIndex];

        InitialSpectrum generator = new InitialSpectrum(sea, cascades, cascadeIndex);
        this.initialSpectrum = DataTexture.rgba32f(resolution, resolution, generator.generate());
        this.waveData = DataTexture.rgba32f(resolution, resolution,
                buildWaveData(sea, patchSize, resolution));

        this.spatialA = RenderTarget.float32(resolution, resolution);
        this.spatialB = RenderTarget.float32(resolution, resolution);
        this.displacement = RenderTarget.float16Tiling(resolution, resolution);
        this.derivatives[0] = RenderTarget.float16Tiling(resolution, resolution);
        this.derivatives[1] = RenderTarget.float16Tiling(resolution, resolution);
    }

    /** {@code (kx, kz, |k|, omega)} for every bin, in FFT index order. */
    private static float[] buildWaveData(SeaState sea, float patchSize, int n) {
        float[] data = new float[n * n * 4];
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                double kx = Mth.TAU * InitialSpectrum.signedIndex(x, n) / patchSize;
                double kz = Mth.TAU * InitialSpectrum.signedIndex(z, n) / patchSize;
                double k = Math.hypot(kx, kz);
                double omega = Dispersion.quantiseForLoop(
                        Dispersion.omega(k, sea.depth), sea.repeatPeriod);
                int o = (z * n + x) * 4;
                data[o] = (float) kx;
                data[o + 1] = (float) kz;
                data[o + 2] = (float) k;
                data[o + 3] = (float) omega;
            }
        }
        return data;
    }

    public int resolution() {
        return resolution;
    }

    public float patchSize() {
        return patchSize;
    }

    public Texture initialSpectrumTexture() {
        return initialSpectrum;
    }

    public Texture waveDataTexture() {
        return waveData;
    }

    public RenderTarget spatialA() {
        return spatialA;
    }

    public RenderTarget spatialB() {
        return spatialB;
    }

    public RenderTarget displacement() {
        return displacement;
    }

    /** The derivative map written this frame. */
    public RenderTarget currentDerivatives() {
        return derivatives[derivativeIndex];
    }

    /** The derivative map written last frame, which the foam pass reads. */
    public RenderTarget previousDerivatives() {
        return derivatives[1 - derivativeIndex];
    }

    /**
     * The most recently completed derivative map.
     *
     * <p>The simulation swaps after writing, so once a frame's update has run this
     * is the buffer that holds it. Shading must read this one, never
     * {@link #currentDerivatives()}, which by then points at the buffer the next
     * frame will overwrite.
     */
    public RenderTarget latestDerivatives() {
        return previousDerivatives();
    }

    public void swapDerivatives() {
        derivativeIndex = 1 - derivativeIndex;
    }

    @Override
    public void dispose() {
        initialSpectrum.dispose();
        waveData.dispose();
        spatialA.dispose();
        spatialB.dispose();
        displacement.dispose();
        derivatives[0].dispose();
        derivatives[1].dispose();
    }
}
