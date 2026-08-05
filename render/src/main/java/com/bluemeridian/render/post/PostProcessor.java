package com.bluemeridian.render.post;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.Disposable;
import com.bluemeridian.render.gl.FullscreenQuad;
import com.bluemeridian.render.gl.RenderTarget;
import com.bluemeridian.render.gl.ShaderSources;

/**
 * HDR resolve: threshold, blur, tone map.
 *
 * <p>The scene is rendered into a half-float target because a sunlit sea spans
 * far more than 8 bits - the specular track off a wave face is orders of
 * magnitude brighter than the trough beside it, and clipping that in the
 * framebuffer throws away exactly the contrast that makes water read as wet.
 *
 * <p>Auto-exposure is a slow follower rather than a per-frame normalisation.
 * Instant exposure looks wrong on water for a specific reason: a wave lifting the
 * sun's reflection into frame would darken the whole scene for one frame, which
 * the eye reads as a flicker. A time constant of a couple of seconds matches how
 * an eye adapts when you look up from the deck.
 */
public final class PostProcessor implements Disposable {

    private static final int BLOOM_DIVISOR = 4;

    private final ShaderProgram thresholdProgram;
    private final ShaderProgram blurProgram;
    private final ShaderProgram tonemapProgram;
    private final FullscreenQuad quad;

    private RenderTarget scene;
    private RenderTarget bloomA;
    private RenderTarget bloomB;
    private int width;
    private int height;

    private float exposure = 0.15f;
    private float bloomStrength = 0.22f;
    private float bloomThreshold = 1.45f;
    private float vignette = 0.55f;
    private boolean bloomEnabled = true;

    public PostProcessor(int width, int height) {
        this.thresholdProgram = ShaderSources.program("fullscreen.vert", "bloom_threshold.frag");
        this.blurProgram = ShaderSources.program("fullscreen.vert", "bloom_blur.frag");
        this.tonemapProgram = ShaderSources.program("fullscreen.vert", "post_tonemap.frag");
        this.quad = new FullscreenQuad();
        resize(width, height);
    }

    public void resize(int width, int height) {
        if (this.width == width && this.height == height && scene != null) {
            return;
        }
        disposeTargets();
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        scene = RenderTarget.float16Screen(this.width, this.height, true);
        int bloomWidth = Math.max(1, this.width / BLOOM_DIVISOR);
        int bloomHeight = Math.max(1, this.height / BLOOM_DIVISOR);
        bloomA = RenderTarget.float16Screen(bloomWidth, bloomHeight, false);
        bloomB = RenderTarget.float16Screen(bloomWidth, bloomHeight, false);
    }

    public void setExposure(float exposure) {
        this.exposure = exposure;
    }

    public float exposure() {
        return exposure;
    }

    public void setBloom(boolean enabled, float strength, float threshold) {
        this.bloomEnabled = enabled;
        this.bloomStrength = strength;
        this.bloomThreshold = threshold;
    }

    public void setVignette(float vignette) {
        this.vignette = vignette;
    }

    /** Begins capturing the scene into the HDR target. */
    public void beginScene() {
        scene.begin();
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
    }

    public void endScene() {
        scene.end();
    }

    /** Resolves the captured scene to the default framebuffer. */
    public void resolveToScreen() {
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDisable(GL20.GL_BLEND);

        if (bloomEnabled) {
            bloomA.begin();
            thresholdProgram.bind();
            scene.texture().bind(0);
            thresholdProgram.setUniformi("u_source", 0);
            thresholdProgram.setUniformf("u_threshold", bloomThreshold);
            thresholdProgram.setUniformf("u_softKnee", 0.6f);
            thresholdProgram.setUniformf("u_exposure", exposure);
            quad.render(thresholdProgram);
            bloomA.end();

            blurPass(bloomA, bloomB, 1f / bloomA.width(), 0f);
            blurPass(bloomB, bloomA, 0f, 1f / bloomA.height());
        }

        Gdx.gl.glViewport(0, 0, width, height);
        tonemapProgram.bind();
        scene.texture().bind(0);
        tonemapProgram.setUniformi("u_hdr", 0);
        (bloomEnabled ? bloomA : scene).texture().bind(1);
        tonemapProgram.setUniformi("u_bloom", 1);
        tonemapProgram.setUniformf("u_exposure", exposure);
        // When bloom is off the "bloom" sampler points at the unexposed scene, so its
        // contribution must be exactly zero rather than merely small.
        tonemapProgram.setUniformf("u_bloomStrength", bloomEnabled ? bloomStrength : 0f);
        tonemapProgram.setUniformf("u_vignette", vignette);
        quad.render(tonemapProgram);

        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
    }

    private void blurPass(RenderTarget source, RenderTarget target, float stepX, float stepY) {
        target.begin();
        blurProgram.bind();
        source.texture().bind(0);
        blurProgram.setUniformi("u_source", 0);
        blurProgram.setUniformf("u_texelStep", stepX, stepY);
        quad.render(blurProgram);
        target.end();
    }

    public RenderTarget sceneTarget() {
        return scene;
    }

    private void disposeTargets() {
        if (scene != null) {
            scene.dispose();
            bloomA.dispose();
            bloomB.dispose();
            scene = null;
        }
    }

    @Override
    public void dispose() {
        disposeTargets();
        thresholdProgram.dispose();
        blurProgram.dispose();
        tonemapProgram.dispose();
        quad.dispose();
    }
}
