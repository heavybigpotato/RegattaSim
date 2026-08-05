package com.bluemeridian.desktop;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Vector3;
import com.bluemeridian.core.ocean.SeaState;
import com.bluemeridian.render.RenderQuality;
import com.bluemeridian.render.scene.OceanScene;

/**
 * Interactive ocean, for tuning by eye.
 *
 * <p>Deliberately keyboard-only and chrome-free. Anything drawn over the sea
 * changes how the sea looks, and the whole point of this window is to judge the
 * water honestly. The current state is printed to the console instead.
 *
 * <pre>
 *   W A S D / R F   move, rise and dive        mouse drag   look
 *   [ ]             wind down / up             , .          sun lower / higher
 *   - =             choppiness                 1 2 3 4      quality tier
 *   B               bloom on/off               P            print state
 * </pre>
 */
public final class OceanSandbox extends ApplicationAdapter {

    private static final double KNOTS = 0.514444;

    private PerspectiveCamera camera;
    private OceanScene scene;

    private RenderQuality quality = RenderQuality.ULTRA;
    private double windKnots = 22.0;
    private double windDirection = Math.toRadians(35);
    private double sunElevation = Math.toRadians(24);
    private double sunAzimuth = Math.toRadians(120);
    private float choppiness = 1.0f;
    private boolean bloom = true;

    private float heading = (float) Math.toRadians(110);
    private float pitch = (float) Math.toRadians(-6);
    private int lastMouseX;
    private int lastMouseY;
    private boolean dragging;

    @Override
    public void create() {
        camera = new PerspectiveCamera(60f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.near = 0.15f;
        camera.far = 60_000f;
        camera.position.set(0f, 5f, 0f);
        rebuild();
        System.out.println(OceanSandbox.class.getSimpleName()
                + ": W A S D R F move, drag to look, [ ] wind, , . sun, - = chop, 1-4 quality,"
                + " B bloom, P print");
    }

    private void rebuild() {
        if (scene != null) {
            scene.dispose();
        }
        SeaState sea = SeaState.openOcean(windKnots * KNOTS, windDirection, 20_260_805L);
        scene = new OceanScene(sea, quality,
                Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        scene.sun().set(sunElevation, sunAzimuth);
        scene.simulation().setChoppiness(choppiness);
        scene.postProcessor().setBloom(bloom, 0.22f, 1.45f);
        scene.autoExposure().update((float) sunElevation, scene.oceanRenderer().turbidity(), 0f);
        scene.autoExposure().snap();
        printState();
    }

    private void printState() {
        System.out.printf(java.util.Locale.ROOT,
                "wind %.0f kt @ %.0f deg | sun %.0f deg | chop %.2f | Hs %.2f m | %s"
                + " | %d FFT draws/frame%n",
                windKnots, Math.toDegrees(windDirection), Math.toDegrees(sunElevation),
                choppiness, scene.seaState().significantWaveHeight(), quality,
                quality.fftDrawCallsPerFrame());
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
        float delta = Math.min(Gdx.graphics.getDeltaTime(), 0.1f);
        handleInput(delta);

        camera.direction.set(
                (float) (Math.cos(pitch) * Math.cos(heading)),
                (float) Math.sin(pitch),
                (float) (Math.cos(pitch) * Math.sin(heading)));
        camera.up.set(Vector3.Y);
        camera.update();

        scene.render(camera, delta);
    }

    private void handleInput(float delta) {
        // Move faster in a big sea, where the interesting features are further apart.
        float speed = 12f * delta * (Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) ? 6f : 1f);
        Vector3 forward = new Vector3(camera.direction).nor();
        Vector3 right = new Vector3(forward).crs(Vector3.Y).nor();

        if (Gdx.input.isKeyPressed(Input.Keys.W)) {
            camera.position.mulAdd(forward, speed);
        }
        if (Gdx.input.isKeyPressed(Input.Keys.S)) {
            camera.position.mulAdd(forward, -speed);
        }
        if (Gdx.input.isKeyPressed(Input.Keys.A)) {
            camera.position.mulAdd(right, -speed);
        }
        if (Gdx.input.isKeyPressed(Input.Keys.D)) {
            camera.position.mulAdd(right, speed);
        }
        if (Gdx.input.isKeyPressed(Input.Keys.R)) {
            camera.position.y += speed;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.F)) {
            camera.position.y -= speed;
        }
        // The camera may go under, but not through the sea floor of the scene.
        camera.position.y = Math.max(-8f, camera.position.y);

        if (Gdx.input.isTouched()) {
            int x = Gdx.input.getX();
            int y = Gdx.input.getY();
            if (dragging) {
                heading += (x - lastMouseX) * 0.004f;
                pitch -= (y - lastMouseY) * 0.004f;
                pitch = Math.max(-1.5f, Math.min(1.5f, pitch));
            }
            lastMouseX = x;
            lastMouseY = y;
            dragging = true;
        } else {
            dragging = false;
        }

        if (Gdx.input.isKeyJustPressed(Input.Keys.LEFT_BRACKET)) {
            windKnots = Math.max(3.0, windKnots - 3.0);
            rebuild();
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.RIGHT_BRACKET)) {
            windKnots = Math.min(60.0, windKnots + 3.0);
            rebuild();
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.COMMA)) {
            sunElevation = Math.max(Math.toRadians(-6), sunElevation - Math.toRadians(4));
            scene.sun().set(sunElevation, sunAzimuth);
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.PERIOD)) {
            sunElevation = Math.min(Math.toRadians(88), sunElevation + Math.toRadians(4));
            scene.sun().set(sunElevation, sunAzimuth);
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.MINUS)) {
            choppiness = Math.max(0f, choppiness - 0.1f);
            scene.simulation().setChoppiness(choppiness);
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.EQUALS)) {
            choppiness = Math.min(1.4f, choppiness + 0.1f);
            scene.simulation().setChoppiness(choppiness);
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.B)) {
            bloom = !bloom;
            scene.postProcessor().setBloom(bloom, 0.22f, 1.45f);
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.P)) {
            printState();
            System.out.printf(java.util.Locale.ROOT, "  camera %.1f, %.1f, %.1f | %.0f fps%n",
                    camera.position.x, camera.position.y, camera.position.z,
                    (double) Gdx.graphics.getFramesPerSecond());
        }
        for (int i = 0; i < RenderQuality.values().length; i++) {
            if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_1 + i)) {
                quality = RenderQuality.values()[i];
                rebuild();
            }
        }
    }

    @Override
    public void dispose() {
        if (scene != null) {
            scene.dispose();
        }
    }
}
