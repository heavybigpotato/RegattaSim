package com.bluemeridian.android;

import android.app.ActivityManager;
import android.content.Context;
import android.opengl.GLES20;
import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Vector3;
import com.bluemeridian.core.ocean.SeaState;
import com.bluemeridian.render.RenderQuality;
import com.bluemeridian.render.scene.OceanScene;

/**
 * The ocean on a phone.
 *
 * <p>Phase 1 has no boat and no race yet, so this is the sea itself: drag to look
 * around, pinch to change the wind. It is the acceptance test for the phase made
 * runnable - the thing you install from GitHub Releases to see whether the water
 * holds up on the device it has to hold up on.
 */
public final class AndroidOceanView extends ApplicationAdapter {

    private static final double KNOTS = 0.514444;

    /** Held only to query device memory when choosing a starting quality tier. */
    private final Context context;

    private PerspectiveCamera camera;
    private OceanScene scene;

    private float heading = (float) Math.toRadians(110);
    private float pitch = (float) Math.toRadians(-6);
    private double windKnots = 22.0;

    private int lastX;
    private int lastY;
    private boolean dragging;
    private float lastPinchDistance;

    public AndroidOceanView(Context context) {
        this.context = context;
    }

    @Override
    public void create() {
        camera = new PerspectiveCamera(62f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.near = 0.15f;
        camera.far = 60_000f;
        camera.position.set(0f, 4.5f, 0f);
        build(detectQuality());
    }

    /**
     * Picks a starting tier from the device's memory and GLES version.
     *
     * <p>A starting point only. The brief is explicit that the player may push
     * every setting to maximum on a phone that cannot hold it, and that is their
     * call to make - this only decides what happens before they have made it.
     */
    private RenderQuality detectQuality() {
        // Total RAM is a crude proxy for GPU class, but it is the one signal that
        // is cheap, universally available, and correlates well enough in practice:
        // 8 GB phones have flagship GPUs and 3 GB phones do not.
        long megabytes = 3072;
        ActivityManager activityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) {
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(info);
            megabytes = info.totalMem / (1024L * 1024L);
        }
        Gdx.app.log("BlueMeridian", "device RAM " + megabytes + " MB, GL renderer "
                + GLES20.glGetString(GLES20.GL_RENDERER));

        if (megabytes >= 7000) {
            return RenderQuality.ULTRA;
        }
        if (megabytes >= 5000) {
            return RenderQuality.HIGH;
        }
        if (megabytes >= 3500) {
            return RenderQuality.MEDIUM;
        }
        return RenderQuality.LOW;
    }

    private void build(RenderQuality quality) {
        if (scene != null) {
            scene.dispose();
        }
        SeaState sea = SeaState.openOcean(windKnots * KNOTS, Math.toRadians(35), 20_260_805L);
        scene = new OceanScene(sea, quality, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        scene.sun().set(Math.toRadians(24), Math.toRadians(120));
        scene.autoExposure().update((float) Math.toRadians(24),
                scene.oceanRenderer().turbidity(), 0f);
        scene.autoExposure().snap();
    }

    @Override
    public void resize(int width, int height) {
        if (width == 0 || height == 0) {
            return;
        }
        camera.viewportWidth = width;
        camera.viewportHeight = height;
        camera.update();
        scene.resize(width, height);
    }

    @Override
    public void render() {
        handleTouch();

        camera.direction.set(
                (float) (Math.cos(pitch) * Math.cos(heading)),
                (float) Math.sin(pitch),
                (float) (Math.cos(pitch) * Math.sin(heading)));
        camera.up.set(Vector3.Y);
        camera.update();

        scene.render(camera, Math.min(Gdx.graphics.getDeltaTime(), 0.1f));
    }

    private void handleTouch() {
        boolean pinching = Gdx.input.isTouched(0) && Gdx.input.isTouched(1);
        if (pinching) {
            float dx = Gdx.input.getX(0) - Gdx.input.getX(1);
            float dy = Gdx.input.getY(0) - Gdx.input.getY(1);
            float distance = (float) Math.hypot(dx, dy);
            if (lastPinchDistance > 0f) {
                double change = (distance - lastPinchDistance) * 0.02;
                double updated = Math.max(4.0, Math.min(55.0, windKnots + change));
                // Rebuilding the spectrum is not free, so only do it once the wind
                // has actually moved by a knot.
                if (Math.abs(updated - windKnots) >= 1.0) {
                    windKnots = updated;
                    build(scene.quality());
                }
            }
            lastPinchDistance = distance;
            dragging = false;
            return;
        }
        lastPinchDistance = 0f;

        if (Gdx.input.isTouched()) {
            int x = Gdx.input.getX();
            int y = Gdx.input.getY();
            if (dragging) {
                heading += (x - lastX) * 0.004f;
                pitch -= (y - lastY) * 0.004f;
                pitch = Math.max(-1.4f, Math.min(1.4f, pitch));
            }
            lastX = x;
            lastY = y;
            dragging = true;
        } else {
            dragging = false;
        }
    }

    @Override
    public void dispose() {
        if (scene != null) {
            scene.dispose();
        }
    }
}
