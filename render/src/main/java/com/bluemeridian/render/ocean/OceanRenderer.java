package com.bluemeridian.render.ocean;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.Disposable;
import com.bluemeridian.core.ocean.SeaState;
import com.bluemeridian.render.gl.ShaderSources;
import com.bluemeridian.render.sky.SunLight;

/**
 * Draws the sea surface.
 *
 * <p>Owns the projected grid and the surface shader, and binds the cascade
 * textures the FFT simulation produced. Nothing here computes waves; it is purely
 * the act of putting them on screen.
 */
public final class OceanRenderer implements Disposable {

    private final ShaderProgram program;
    private final ProjectedGrid grid;

    /** Deep-water body colour: what you see looking down into open ocean. */
    private float deepR = 0.004f;
    private float deepG = 0.016f;
    private float deepB = 0.031f;

    /** Colour of sunlight that has travelled through a wave crest. */
    private float scatterR = 0.043f;
    private float scatterG = 0.16f;
    private float scatterB = 0.128f;

    /** Per-metre extinction of the water column, roughly Jerlov type I. */
    private float extinctionR = 0.45f;
    private float extinctionG = 0.08f;
    private float extinctionB = 0.035f;

    private float turbidity = 2.6f;
    private float foamScale = 0.35f;
    private float normalDetailFade = 12f;
    private float displacementFadeStart = 1800f;
    private float displacementFadeEnd = 6000f;

    public OceanRenderer(int gridColumns, int gridRows) {
        this.program = ShaderSources.program("ocean_surface.vert", "ocean_surface.frag");
        this.grid = new ProjectedGrid(gridColumns, gridRows);
    }

    public ProjectedGrid grid() {
        return grid;
    }

    public void setTurbidity(float turbidity) {
        this.turbidity = turbidity;
    }

    public float turbidity() {
        return turbidity;
    }

    public void setWaterColour(float deepR, float deepG, float deepB,
            float scatterR, float scatterG, float scatterB) {
        this.deepR = deepR;
        this.deepG = deepG;
        this.deepB = deepB;
        this.scatterR = scatterR;
        this.scatterG = scatterG;
        this.scatterB = scatterB;
    }

    public void setExtinction(float r, float g, float b) {
        this.extinctionR = r;
        this.extinctionG = g;
        this.extinctionB = b;
    }

    public void setFoamScale(float foamScale) {
        this.foamScale = foamScale;
    }

    public void setDisplacementFade(float start, float end) {
        this.displacementFadeStart = start;
        this.displacementFadeEnd = end;
    }

    /**
     * Renders the surface into whatever target is currently bound.
     *
     * @param maximumDisplacement tallest expected crest, metres; used to decide how
     *                            much of the screen can contain water
     */
    public void render(Camera camera, GpuOceanSimulation simulation, SunLight sun, SeaState sea,
            float maximumDisplacement) {
        if (!grid.update(camera, maximumDisplacement)) {
            return;
        }

        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        // The surface is a single closed sheet seen from one side, but a crest can
        // fold enough to show its back face, so culling stays off.
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        Gdx.gl.glDisable(GL20.GL_BLEND);

        program.bind();
        program.setUniformMatrix("u_viewProjection", camera.combined);
        program.setUniformMatrix("u_inverseViewProjection", grid.inverseViewProjection());
        program.setUniformf("u_ndcMin", grid.ndcMinX(), grid.ndcMinY());
        program.setUniformf("u_ndcMax", grid.ndcMaxX(), grid.ndcMaxY());
        program.setUniformf("u_cameraPosition",
                camera.position.x, camera.position.y, camera.position.z);
        program.setUniformf("u_horizonDistance", ProjectedGrid.HORIZON_DISTANCE);

        program.setUniformf("u_sunDirection",
                sun.direction().x, sun.direction().y, sun.direction().z);
        program.setUniformf("u_sunColour", sun.red(), sun.green(), sun.blue());
        program.setUniformf("u_turbidity", turbidity);

        int count = simulation.cascadeCount();
        program.setUniformi("u_cascadeCount", count);
        program.setUniformf("u_choppiness", simulation.choppiness());

        float[] patch = new float[3];
        for (int i = 0; i < 3; i++) {
            // Unused slots still need a non-zero divisor: the shader computes all
            // three UVs before branching, and dividing by zero on some drivers
            // produces NaN that survives the branch.
            patch[i] = i < count ? simulation.cascade(i).patchSize() : 1f;
        }
        program.setUniformf("u_patchSizes", patch[0], patch[1], patch[2]);

        for (int i = 0; i < 3; i++) {
            int slot = Math.min(i, count - 1);
            simulation.cascade(slot).displacement().texture().bind(i);
            program.setUniformi("u_displacement" + i, i);
            simulation.cascade(slot).latestDerivatives().texture().bind(3 + i);
            program.setUniformi("u_derivatives" + i, 3 + i);
        }

        program.setUniformf("u_deepColour", deepR, deepG, deepB);
        program.setUniformf("u_scatterColour", scatterR, scatterG, scatterB);
        program.setUniformf("u_extinction", extinctionR, extinctionG, extinctionB);
        program.setUniformf("u_waterDepth", (float) Math.min(sea.depth, 200.0));
        program.setUniformf("u_foamScale", foamScale);
        program.setUniformf("u_normalDetailFade", normalDetailFade);
        program.setUniformf("u_displacementFadeStart", displacementFadeStart);
        program.setUniformf("u_displacementFadeEnd", displacementFadeEnd);

        grid.render(program);

        // Leave texture unit 0 active: libGDX's own draw paths assume it.
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
    }

    @Override
    public void dispose() {
        program.dispose();
        grid.dispose();
    }
}
