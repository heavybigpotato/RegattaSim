package com.bluemeridian.desktop;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Vector3;
import com.bluemeridian.core.ocean.CpuOceanSurface;
import com.bluemeridian.core.sailing.PolarDiagram;
import com.bluemeridian.core.sailing.SailingBoat;
import com.bluemeridian.render.RenderQuality;
import com.bluemeridian.render.scene.OceanScene;
import java.io.File;
import java.nio.ByteBuffer;

/**
 * Renders the reference scenes offscreen and writes them as PNGs.
 *
 * <p>This is the visual regression harness the design brief asks for, and it is
 * also how the ocean gets looked at without a phone in hand. It runs on a
 * software rasteriser under a virtual display, so CI can produce the images on a
 * machine with no GPU at all.
 *
 * <p>Each scene is warmed for a fixed number of frames before capture, because
 * foam accumulates over time: capturing frame one would show a sea that has never
 * broken. The warm-up steps simulation time toward the scene's capture time in
 * even increments, so the foam field is deterministic.
 */
public final class ReferenceSceneTool {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    /**
     * Foam is an accumulating field, so a scene has to be run forward before it is
     * captured or the sea will never have broken. The warm-up must also step at a
     * plausible frame time: foam decays with a time constant of about a second, so
     * warming up in a few enormous strides decays it away faster than breaking
     * crests can deposit it, and a storm comes out glassy.
     */
    private static final float WARMUP_STEP = 1f / 30f;
    private static final int WARMUP_FRAMES = 150;

    private ReferenceSceneTool() {
    }

    public static void main(String[] args) {
        File outputDirectory = new File(args.length > 0 ? args[0] : "build/reference-scenes");
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            System.err.println("cannot create " + outputDirectory);
            System.exit(1);
        }

        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL32, 4, 3);
        config.setWindowedMode(WIDTH, HEIGHT);
        config.setInitialVisible(false);
        config.setResizable(false);
        config.disableAudio(true);
        config.setTitle("Blue Meridian reference scenes");

        Renderer renderer = new Renderer(outputDirectory);
        new Lwjgl3Application(renderer, config);
        System.exit(renderer.failed ? 1 : 0);
    }

    private static final class Renderer extends ApplicationAdapter {

        private final File outputDirectory;
        boolean failed;

        Renderer(File outputDirectory) {
            this.outputDirectory = outputDirectory;
        }

        @Override
        public void create() {
            try {
                for (ReferenceScenes.Scene scene : ReferenceScenes.all()) {
                    renderScene(scene);
                }
                System.out.println("reference scenes written to " + outputDirectory);
            } catch (RuntimeException e) {
                failed = true;
                e.printStackTrace();
            }
            Gdx.app.exit();
        }

        private void renderScene(ReferenceScenes.Scene scene) {
            OceanScene oceanScene =
                    new OceanScene(scene.sea, RenderQuality.ULTRA, WIDTH, HEIGHT);
            try {
                oceanScene.sun().set(scene.sunElevation, scene.sunAzimuth);
                oceanScene.oceanRenderer().setTurbidity(scene.turbidity);
                oceanScene.skyRenderer().setTurbidity(scene.turbidity);
                // Exposure comes from the sky model, so a scene never has to be
                // hand-tuned and the images stay comparable to one another.
                oceanScene.autoExposure().update(scene.sunElevation, scene.turbidity, 0f);
                oceanScene.autoExposure().snap();

                PerspectiveCamera camera = new PerspectiveCamera(58f, WIDTH, HEIGHT);
                camera.near = 0.15f;
                camera.far = 60_000f;

                SailingBoat boat = null;
                CpuOceanSurface surface = null;
                if (scene.withBoat) {
                    // The boat floats on a CPU realisation of the same sea state the
                    // GPU is drawing: same spectrum, same seed, same band limits, at
                    // physics resolution.
                    surface = new CpuOceanSurface(scene.sea,
                            RenderQuality.ULTRA.cascades(), 64, 2);
                    boat = new SailingBoat(
                            PolarDiagram.fromClasspath("polars/class40.csv"),
                            SailingBoat.HullShape.class40());
                    boat.setPosition(0, 0, scene.boatHeading);
                    oceanScene.setBoat(boat);
                }
                placeCamera(camera, scene, boat);

                // Run the five seconds of simulation leading up to the capture time.
                float start = scene.time - WARMUP_FRAMES * WARMUP_STEP;
                for (int frame = 1; frame <= WARMUP_FRAMES; frame++) {
                    float now = start + frame * WARMUP_STEP;
                    if (boat != null) {
                        surface.update(now);
                        boat.advance(WARMUP_STEP, scene.sea.windSpeed,
                                scene.sea.windDirection, surface, now);
                        placeCamera(camera, scene, boat);
                    }
                    oceanScene.renderAt(camera, now, WARMUP_STEP);
                }

                writePng(new File(outputDirectory, scene.name + ".png"));
                com.bluemeridian.render.ocean.ProjectedGrid grid =
                        oceanScene.oceanRenderer().grid();
                System.out.println(String.format(java.util.Locale.ROOT,
                        "  %-22s Hs=%5.2f m  water=%s  ndcY=[%.3f, %.3f]",
                        scene.name, scene.sea.significantWaveHeight(),
                        grid.isWaterVisible(), grid.ndcMinY(), grid.ndcMaxY()));
            } finally {
                oceanScene.dispose();
            }
        }

        /**
         * Points the camera: fixed at the origin for a sea-and-sky scene, trailing
         * the boat for one that has a boat in it.
         *
         * <p>The chase camera sits off the quarter rather than dead astern. From
         * directly behind, a 12 m hull foreshortens into a wedge and the sails are
         * edge-on; off the quarter shows the length of one and the camber of the
         * other, which is where every photograph of a boat under sail is taken from.
         */
        private static void placeCamera(PerspectiveCamera camera,
                ReferenceScenes.Scene scene, SailingBoat boat) {
            if (boat == null) {
                camera.position.set(0f, scene.cameraHeight, 0f);
                camera.direction.set(
                        (float) (Math.cos(scene.cameraPitch) * Math.cos(scene.cameraHeading)),
                        (float) Math.sin(scene.cameraPitch),
                        (float) (Math.cos(scene.cameraPitch) * Math.sin(scene.cameraHeading)));
            } else {
                double back = 24.0;
                double yaw = boat.heading() + scene.cameraHeading;
                camera.position.set(
                        (float) (boat.x() - Math.cos(yaw) * back),
                        (float) (boat.heave() + scene.cameraHeight),
                        (float) (boat.z() - Math.sin(yaw) * back));
                // Aimed at the rig rather than the deck, so the boat sits in the frame
                // rather than at the bottom of it.
                camera.direction.set(
                        (float) boat.x() - camera.position.x,
                        (float) (boat.heave() + 4.0) - camera.position.y,
                        (float) boat.z() - camera.position.z).nor();
            }
            camera.up.set(Vector3.Y);
            camera.update();
        }

        private void writePng(File file) {
            Pixmap pixmap = new Pixmap(WIDTH, HEIGHT, Pixmap.Format.RGBA8888);
            ByteBuffer pixels = pixmap.getPixels();
            Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 1);
            Gdx.gl.glReadPixels(0, 0, WIDTH, HEIGHT, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, pixels);
            pixels.position(0);

            // OpenGL reads bottom-up; PNG is top-down.
            Pixmap flipped = new Pixmap(WIDTH, HEIGHT, Pixmap.Format.RGBA8888);
            ByteBuffer target = flipped.getPixels();
            int rowBytes = WIDTH * 4;
            byte[] row = new byte[rowBytes];
            for (int y = 0; y < HEIGHT; y++) {
                pixels.position((HEIGHT - 1 - y) * rowBytes);
                pixels.get(row, 0, rowBytes);
                target.position(y * rowBytes);
                target.put(row, 0, rowBytes);
            }
            target.position(0);
            pixels.position(0);

            PixmapIO.writePNG(Gdx.files.absolute(file.getAbsolutePath()), flipped);
            pixmap.dispose();
            flipped.dispose();
        }
    }
}
