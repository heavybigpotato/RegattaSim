package com.bluemeridian.render.sky;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;
import com.bluemeridian.render.gl.FullscreenQuad;
import com.bluemeridian.render.gl.ShaderSources;

/**
 * Fullscreen analytic sky.
 *
 * <p>No dome mesh and no cubemap: each pixel reconstructs its own world-space
 * view ray from the inverse view-projection and evaluates the sky model directly.
 * That keeps the sun's edge sharp at any field of view and means the same
 * function can be called from the water shader for reflections, so sea and sky
 * can never disagree about what colour the sky is.
 */
public final class SkyRenderer implements Disposable {

    private final ShaderProgram program;
    private final FullscreenQuad quad;
    private final Matrix4 inverseViewProjection = new Matrix4();

    private float turbidity = 2.6f;

    public SkyRenderer() {
        this.program = ShaderSources.program("sky.vert", "sky.frag");
        this.quad = new FullscreenQuad();
    }

    public void setTurbidity(float turbidity) {
        this.turbidity = turbidity;
    }

    public void render(Camera camera, SunLight sun) {
        inverseViewProjection.set(camera.combined).inv();

        // The sky is behind everything; it writes no depth and tests none.
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(false);
        Gdx.gl.glDisable(GL20.GL_BLEND);

        program.bind();
        program.setUniformMatrix("u_inverseViewProjection", inverseViewProjection);
        // No camera position: the sky is at infinity, so only the ray direction matters.
        program.setUniformf("u_sunDirection",
                sun.direction().x, sun.direction().y, sun.direction().z);
        program.setUniformf("u_sunColour", sun.red(), sun.green(), sun.blue());
        program.setUniformf("u_turbidity", turbidity);
        quad.render(program);

        Gdx.gl.glDepthMask(true);
    }

    @Override
    public void dispose() {
        program.dispose();
        quad.dispose();
    }
}
