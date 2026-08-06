package com.bluemeridian.render.scene;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.utils.Disposable;
import com.bluemeridian.core.ocean.CascadeSettings;
import com.bluemeridian.core.ocean.SeaState;
import com.bluemeridian.core.sailing.SailingBoat;
import com.bluemeridian.render.RenderQuality;
import com.bluemeridian.render.boat.BoatRenderer;
import com.bluemeridian.render.ocean.GpuOceanSimulation;
import com.bluemeridian.render.ocean.OceanRenderer;
import com.bluemeridian.render.post.AutoExposure;
import com.bluemeridian.render.post.PostProcessor;
import com.bluemeridian.render.sky.SkyRenderer;
import com.bluemeridian.render.sky.SunLight;

/**
 * Everything needed to draw an ocean under a sky, assembled.
 *
 * <p>This is the seam between the engine and whatever is driving it: the desktop
 * shader-iteration launcher, the Android game, and the offscreen reference
 * renderer in CI all construct one of these and differ only in where the camera
 * comes from and where the frame goes.
 */
public final class OceanScene implements Disposable {

    private final RenderQuality quality;
    private final GpuOceanSimulation simulation;
    private final OceanRenderer oceanRenderer;
    private final SkyRenderer skyRenderer;
    private final PostProcessor postProcessor;
    private final SunLight sun = new SunLight();
    private final AutoExposure autoExposure = new AutoExposure();
    private boolean autoExposureEnabled = true;

    /** Built on first use: a scene may be nothing but sea and sky. */
    private BoatRenderer boatRenderer;
    private SailingBoat boat;

    private SeaState seaState;
    private float maximumDisplacement;
    private float time;

    public OceanScene(SeaState seaState, RenderQuality quality, int width, int height) {
        this.quality = quality;
        this.seaState = seaState;
        CascadeSettings cascades = quality.cascades();
        this.simulation = new GpuOceanSimulation(seaState, cascades);
        this.oceanRenderer = new OceanRenderer(quality.gridColumns(), quality.gridRows());
        this.skyRenderer = new SkyRenderer();
        this.postProcessor = new PostProcessor(width, height);
        updateDerivedParameters();
    }

    private void updateDerivedParameters() {
        // The projected grid needs to know how high water can reach so it can
        // include the band of screen just above the flat-water horizon where a
        // crest is still visible. Hs is the average of the highest third, and the
        // tallest crest in a field runs to roughly Hs, so Hs alone is the right
        // scale with a little margin.
        maximumDisplacement = (float) (seaState.significantWaveHeight() * 1.2);
        skyRenderer.setTurbidity(oceanRenderer.turbidity());

        // Whitecap coverage rises steeply with wind: a Force 4 sea has the odd
        // breaking crest, a Force 9 sea is streaked white from horizon to horizon.
        // The Jacobian says *where* the water is folding; the wind says how much
        // foam that folding is worth.
        // Equilibrium coverage works out as (1 - J) * gain * tau, so the gain is
        // chosen to put a Force 9 sea near saturation on breaking crests while a
        // Force 3 sea stays essentially clean.
        float foamGain = (float) Math.max(0.1,
                Math.min(3.0, 0.35 + 0.13 * (seaState.windSpeed - 4.0)));
        simulation.setFoam(1.0f, foamGain, 1.1f);
        autoExposure.update(sun.elevation(), oceanRenderer.turbidity(), 0f);
        autoExposure.snap();
    }

    /**
     * Puts a boat in the scene, or removes it with {@code null}.
     *
     * <p>The boat is drawn where the sailing model says it is; this class never
     * advances it. Whoever owns the simulation steps it, at its own fixed rate,
     * which is the only way client and server can agree about where a boat is.
     */
    public void setBoat(SailingBoat boat) {
        this.boat = boat;
        if (boat != null && boatRenderer == null) {
            boatRenderer = new BoatRenderer();
        }
    }

    public SailingBoat boat() {
        return boat;
    }

    public SunLight sun() {
        return sun;
    }

    public AutoExposure autoExposure() {
        return autoExposure;
    }

    /** Turn off to drive the exposure by hand, for photo mode or for tuning. */
    public void setAutoExposureEnabled(boolean enabled) {
        this.autoExposureEnabled = enabled;
    }

    public GpuOceanSimulation simulation() {
        return simulation;
    }

    public OceanRenderer oceanRenderer() {
        return oceanRenderer;
    }

    public SkyRenderer skyRenderer() {
        return skyRenderer;
    }

    public PostProcessor postProcessor() {
        return postProcessor;
    }

    public RenderQuality quality() {
        return quality;
    }

    public SeaState seaState() {
        return seaState;
    }

    public float time() {
        return time;
    }

    public void setTime(float time) {
        this.time = time;
    }

    public void resize(int width, int height) {
        postProcessor.resize(width, height);
    }

    /**
     * Advances the wave field and draws one frame to the default framebuffer.
     *
     * @param deltaTime seconds since the previous frame; drives the foam decay
     */
    public void render(Camera camera, float deltaTime) {
        time += deltaTime;
        renderAt(camera, time, deltaTime);
    }

    /**
     * Draws one frame at an explicit simulation time.
     *
     * <p>Used by the reference renderer, which must produce the same image every
     * run and therefore cannot depend on accumulated frame times.
     */
    public void renderAt(Camera camera, float simulationTime, float deltaTime) {
        if (autoExposureEnabled) {
            autoExposure.update(sun.elevation(), oceanRenderer.turbidity(), deltaTime);
            postProcessor.setExposure(autoExposure.exposure());
        }
        simulation.update(simulationTime, deltaTime);

        postProcessor.beginScene();
        skyRenderer.render(camera, sun);
        oceanRenderer.render(camera, simulation, sun, seaState, maximumDisplacement);
        if (boat != null) {
            // After the water, so the hull tests against the depth the sea wrote and
            // anything below the surface is hidden by it.
            boatRenderer.render(camera, boat, sun, oceanRenderer.turbidity());
        }
        postProcessor.endScene();

        postProcessor.resolveToScreen();
    }

    @Override
    public void dispose() {
        simulation.dispose();
        oceanRenderer.dispose();
        skyRenderer.dispose();
        postProcessor.dispose();
        if (boatRenderer != null) {
            boatRenderer.dispose();
        }
    }
}
