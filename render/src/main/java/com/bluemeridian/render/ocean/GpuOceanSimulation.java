package com.bluemeridian.render.ocean;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.Disposable;
import com.bluemeridian.core.math.ButterflyPlan;
import com.bluemeridian.core.ocean.CascadeSettings;
import com.bluemeridian.core.ocean.SeaState;
import com.bluemeridian.render.gl.DataTexture;
import com.bluemeridian.render.gl.FullscreenQuad;
import com.bluemeridian.render.gl.RenderTarget;
import com.bluemeridian.render.gl.ShaderSources;

/**
 * Runs the FFT ocean on the GPU, one full update per frame.
 *
 * <p>Per cascade the sequence is: evolve the spectrum to the current time
 * (twice, producing four packed complex signals), inverse transform each pair,
 * then assemble a displacement map and a derivative map.
 *
 * <p><b>Why fragment passes rather than compute.</b> The brief calls for a
 * compute-shader FFT with a fragment fallback. This is the fragment path, and it
 * is the one that is implemented first on purpose. libGDX exposes the whole GLES
 * 3.1 compute API - {@code glDispatchCompute}, {@code glBindImageTexture},
 * {@code glMemoryBarrier} are all present on {@code Gdx.gl31} - but it does not
 * expose {@code glTexStorage2D}, and GLES 3.1 requires immutable-format textures
 * for image load/store. The compute path therefore needs a small per-platform
 * bridge (LWJGL's {@code GL42.glTexStorage2D} on desktop,
 * {@code GLES30.glTexStorage2D} on Android) before it can exist at all. That
 * bridge is worth building for the performance, but it is an optimisation on top
 * of a path that has to exist anyway for GLES 3.0 devices. See the README section
 * "Compute-shader FFT" for what remains.
 *
 * <p>The arrangement below keeps the cost identical to a compute implementation's
 * memory traffic: the transform runs on one RGBA32F surface carrying two packed
 * complex signals, so a full pass over four signals costs exactly the same reads
 * and writes either way. What compute would save is the fixed-function overhead
 * of {@code 2 * log2(N)} draw calls per signal pair.
 */
public final class GpuOceanSimulation implements Disposable {

    private final CascadeSettings settings;
    private final OceanCascade[] cascades;
    private final Texture butterfly;
    private final int stages;

    private final ShaderProgram evolveProgram;
    private final ShaderProgram fftProgram;
    private final ShaderProgram displacementProgram;
    private final ShaderProgram derivativesProgram;
    private final FullscreenQuad quad;

    private final RenderTarget scratch;

    private float choppiness;
    /**
     * Jacobian below which foam is deposited.
     *
     * <p>A Jacobian under 1 means the surface is being compressed; under 0 it has
     * folded over, which is literally a breaking crest. Thresholding at true
     * folding sounds like the principled choice and produces almost no foam at all,
     * because at a realistic choppiness the surface only inverts on the steepest
     * few crests in a field. Real whitecapping starts well before that, as the
     * crest steepens and entrains air, so the threshold sits at the onset of
     * compression and the amount deposited scales with how hard the water is being
     * squeezed.
     */
    private float foamThreshold = 1.0f;
    private float foamGain = 0.55f;
    /** Time constant of the foam decay, seconds. Foam is essentially gone after ~4 tau. */
    private float foamTimeConstant = 1.1f;

    public GpuOceanSimulation(SeaState sea, CascadeSettings settings) {
        this.settings = settings;
        this.choppiness = (float) sea.choppiness;
        this.cascades = new OceanCascade[settings.count()];
        for (int i = 0; i < settings.count(); i++) {
            cascades[i] = new OceanCascade(sea, settings, i);
        }

        ButterflyPlan plan = new ButterflyPlan(settings.resolution);
        this.stages = plan.stages();
        // The plan is a log2(N) x N table: stages across, lanes down.
        this.butterfly = DataTexture.rgba32f(stages, settings.resolution, plan.table());

        this.evolveProgram = ShaderSources.program("fullscreen.vert", "ocean_evolve.frag");
        this.fftProgram = ShaderSources.program("fullscreen.vert", "ocean_fft.frag");
        this.displacementProgram =
                ShaderSources.program("fullscreen.vert", "ocean_assemble_displacement.frag");
        this.derivativesProgram =
                ShaderSources.program("fullscreen.vert", "ocean_assemble_derivatives.frag");
        this.quad = new FullscreenQuad();

        this.scratch = RenderTarget.float32(settings.resolution, settings.resolution);
    }

    public CascadeSettings settings() {
        return settings;
    }

    public OceanCascade cascade(int index) {
        return cascades[index];
    }

    public int cascadeCount() {
        return cascades.length;
    }

    public float choppiness() {
        return choppiness;
    }

    public void setChoppiness(float choppiness) {
        this.choppiness = choppiness;
    }

    public void setFoam(float threshold, float gain, float timeConstant) {
        this.foamThreshold = threshold;
        this.foamGain = gain;
        this.foamTimeConstant = timeConstant;
    }

    /**
     * Advances every cascade to the given time.
     *
     * @param timeSeconds simulation time; the surface repeats exactly every
     *                    {@code SeaState.repeatPeriod} seconds
     * @param deltaTime   frame time, used only for the foam decay
     */
    public void update(float timeSeconds, float deltaTime) {
        // Depth and blending are irrelevant to data passes and would corrupt them.
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDisable(GL20.GL_BLEND);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);

        for (OceanCascade cascade : cascades) {
            evolve(cascade, timeSeconds, 0, cascade.spatialA());
            evolve(cascade, timeSeconds, 1, cascade.spatialB());
            transform(cascade.spatialA());
            transform(cascade.spatialB());
            assembleDisplacement(cascade);
            assembleDerivatives(cascade, deltaTime);
            cascade.swapDerivatives();
        }
    }

    private void evolve(OceanCascade cascade, float time, int outputSet, RenderTarget target) {
        target.begin();
        evolveProgram.bind();
        cascade.initialSpectrumTexture().bind(0);
        evolveProgram.setUniformi("u_h0", 0);
        cascade.waveDataTexture().bind(1);
        evolveProgram.setUniformi("u_waveData", 1);
        evolveProgram.setUniformf("u_time", time);
        evolveProgram.setUniformi("u_outputSet", outputSet);
        quad.render(evolveProgram);
        target.end();
    }

    /**
     * In-place 2D inverse transform of a packed surface.
     *
     * <p>Ping-pongs between {@code surface} and the shared scratch target. The
     * pass count is {@code 2 * log2(N)}, which is even, so the result always lands
     * back in {@code surface} and no copy is needed.
     */
    private void transform(RenderTarget surface) {
        RenderTarget source = surface;
        RenderTarget destination = scratch;

        for (int axis = 0; axis < 2; axis++) {
            for (int stage = 0; stage < stages; stage++) {
                destination.begin();
                fftProgram.bind();
                source.texture().bind(0);
                fftProgram.setUniformi("u_source", 0);
                butterfly.bind(1);
                fftProgram.setUniformi("u_butterfly", 1);
                fftProgram.setUniformi("u_stage", stage);
                fftProgram.setUniformi("u_horizontal", axis == 0 ? 1 : 0);
                quad.render(fftProgram);
                destination.end();

                RenderTarget swap = source;
                source = destination;
                destination = swap;
            }
        }
        if (source != surface) {
            throw new IllegalStateException(
                    "FFT pass count must be even so the result lands in the original surface");
        }
    }

    private void assembleDisplacement(OceanCascade cascade) {
        RenderTarget target = cascade.displacement();
        target.begin();
        displacementProgram.bind();
        cascade.spatialA().texture().bind(0);
        displacementProgram.setUniformi("u_spatialA", 0);
        displacementProgram.setUniformf("u_choppiness", choppiness);
        quad.render(displacementProgram);
        target.end();
    }

    private void assembleDerivatives(OceanCascade cascade, float deltaTime) {
        RenderTarget target = cascade.currentDerivatives();
        target.begin();
        derivativesProgram.bind();
        cascade.spatialA().texture().bind(0);
        derivativesProgram.setUniformi("u_spatialA", 0);
        cascade.spatialB().texture().bind(1);
        derivativesProgram.setUniformi("u_spatialB", 1);
        cascade.previousDerivatives().texture().bind(2);
        derivativesProgram.setUniformi("u_previousFoam", 2);
        derivativesProgram.setUniformf("u_choppiness", choppiness);
        derivativesProgram.setUniformf("u_foamDecay",
                (float) Math.exp(-deltaTime / Math.max(1e-3f, foamTimeConstant)));
        derivativesProgram.setUniformf("u_foamThreshold", foamThreshold);
        derivativesProgram.setUniformf("u_foamInjection", foamGain * deltaTime);
        quad.render(derivativesProgram);
        target.end();
    }

    @Override
    public void dispose() {
        for (OceanCascade cascade : cascades) {
            cascade.dispose();
        }
        butterfly.dispose();
        scratch.dispose();
        evolveProgram.dispose();
        fftProgram.dispose();
        displacementProgram.dispose();
        derivativesProgram.dispose();
        quad.dispose();
    }
}
