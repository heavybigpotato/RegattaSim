package com.bluemeridian.desktop;

import com.bluemeridian.core.ocean.SeaState;
import com.bluemeridian.core.ocean.spectrum.JonswapSpectrum;

/**
 * The fixed set of sea states the renderer is judged against.
 *
 * <p>These exist so that "the ocean still looks right" is a thing a machine can
 * check. Each scene pins a sea state, a camera, a sun position and a simulation
 * time, so the same six images come out of every build and a shader change that
 * quietly ruins backlit crests shows up as a diff rather than as a bug report
 * three weeks later.
 *
 * <p>Scene 2 is the one the design brief's acceptance test describes: 25 knots,
 * a crossed sea, and a low sun.
 */
public final class ReferenceScenes {

    /** Knots to metres per second. */
    private static final double KNOTS = 0.514444;

    public static final class Scene {
        public final String name;
        public final SeaState sea;
        /** Camera height above mean water, metres. */
        public final float cameraHeight;
        /** Camera heading in radians, from +X toward +Z. */
        public final float cameraHeading;
        /** Camera pitch in radians; negative looks down. */
        public final float cameraPitch;
        /** Sun elevation above the horizon, radians. */
        public final float sunElevation;
        /** Sun azimuth, radians. */
        public final float sunAzimuth;
        public final float turbidity;
        public final float exposure;
        /** Simulation time to render at; fixed so the image is reproducible. */
        public final float time;
        /**
         * When set, a boat is sailed through the scene and the camera trails it.
         *
         * <p>{@code cameraHeight} then means height above the boat rather than above
         * mean water, and {@code cameraHeading} means yaw off dead astern.
         */
        public final boolean withBoat;
        /** Initial heading of the boat, radians. Only meaningful with a boat. */
        public final float boatHeading;

        Scene(String name, SeaState sea, float cameraHeight, float cameraHeading,
                float cameraPitch, float sunElevation, float sunAzimuth, float turbidity,
                float exposure, float time) {
            this(name, sea, cameraHeight, cameraHeading, cameraPitch, sunElevation,
                    sunAzimuth, turbidity, exposure, time, false, 0f);
        }

        Scene(String name, SeaState sea, float cameraHeight, float cameraHeading,
                float cameraPitch, float sunElevation, float sunAzimuth, float turbidity,
                float exposure, float time, boolean withBoat, float boatHeading) {
            this.name = name;
            this.sea = sea;
            this.cameraHeight = cameraHeight;
            this.cameraHeading = cameraHeading;
            this.cameraPitch = cameraPitch;
            this.sunElevation = sunElevation;
            this.sunAzimuth = sunAzimuth;
            this.turbidity = turbidity;
            this.exposure = exposure;
            this.time = time;
            this.withBoat = withBoat;
            this.boatHeading = boatHeading;
        }
    }

    private ReferenceScenes() {
    }

    public static Scene[] all() {
        return new Scene[] {
                // A quiet morning: small sea, low sun, the water almost a mirror.
                new Scene("01-calm-dawn",
                        new SeaState(6.0 * KNOTS, Math.toRadians(20), 30_000, 3.3, 2000,
                                0.35, 12.0, Math.toRadians(200), 0.06, 40, 0.75, 200, 91_001L),
                        3.2f, (float) Math.toRadians(200), (float) Math.toRadians(-4),
                        (float) Math.toRadians(6), (float) Math.toRadians(200),
                        2.2f, 0.30f, 43.0f),

                // The acceptance shot: 25 kt, a swell crossing the wind sea at 55
                // degrees, sun low and almost behind.
                new Scene("02-gale-crossed-sea",
                        new SeaState(25.0 * KNOTS, Math.toRadians(0), 400_000, 3.3, 3000,
                                3.1, 13.0, Math.toRadians(55), 0.07, 26, 1.05, 200, 25_025L),
                        4.5f, (float) Math.toRadians(150), (float) Math.toRadians(-3),
                        (float) Math.toRadians(9), (float) Math.toRadians(155),
                        2.9f, 0.34f, 77.0f),

                // Mid-morning trade wind sailing: the everyday condition.
                new Scene("03-trade-wind",
                        SeaState.openOcean(15.0 * KNOTS, Math.toRadians(70), 5150L),
                        6.0f, (float) Math.toRadians(95), (float) Math.toRadians(-7),
                        (float) Math.toRadians(46), (float) Math.toRadians(120),
                        2.4f, 0.38f, 120.0f),

                // Sun almost dead ahead and low: crests should glow green from
                // behind. This is the scene that catches a broken scattering term.
                new Scene("04-backlit-swell",
                        new SeaState(12.0 * KNOTS, Math.toRadians(10), 200_000, 3.3, 4000,
                                2.6, 15.0, Math.toRadians(8), 0.05, 60, 1.0, 200, 60_607L),
                        2.4f, (float) Math.toRadians(8), (float) Math.toRadians(-2),
                        (float) Math.toRadians(7), (float) Math.toRadians(6),
                        2.6f, 0.30f, 200.0f),

                // Short fetch, shallow, no swell: an inshore course on flat water.
                new Scene("05-inshore-flat",
                        SeaState.inshore(11.0 * KNOTS, Math.toRadians(300), 777L),
                        2.0f, (float) Math.toRadians(300), (float) Math.toRadians(-6),
                        (float) Math.toRadians(58), (float) Math.toRadians(250),
                        3.4f, 0.40f, 64.0f),

                // Storm force, hazy air, sun high and diffuse: heavy foam coverage.
                new Scene("06-storm",
                        new SeaState(40.0 * KNOTS, Math.toRadians(240),
                                JonswapSpectrum.fullDevelopmentFetch(40.0 * KNOTS), 3.3, 4000,
                                5.5, 15.0, Math.toRadians(235), 0.09, 20, 1.15, 200, 40_404L),
                        7.5f, (float) Math.toRadians(240), (float) Math.toRadians(-5),
                        (float) Math.toRadians(34), (float) Math.toRadians(280),
                        5.5f, 0.30f, 150.0f),

                // A boat, beating in a working breeze. This is the scene that shows
                // the client's own hull rather than the browser's: same loft, same
                // shaders, same sailing model, drawn by the native renderer. The
                // wind blows toward +X, so it arrives from 180 degrees and a heading
                // of -130 puts it 50 degrees off the starboard bow.
                //
                // The sun is deliberately abeam of the camera rather than behind it.
                // Back light flatters a hull and hides everything this scene is meant
                // to show: side light is what reveals the sections, and it is the only
                // arrangement that puts the rig's shadow across open water where it
                // can be seen instead of underneath the boat.
                new Scene("07-close-hauled",
                        new SeaState(18.0 * KNOTS, Math.toRadians(0), 250_000, 3.3, 3000,
                                1.6, 11.0, Math.toRadians(15), 0.07, 30, 1.0, 200, 40_040L),
                        6.5f, (float) Math.toRadians(32), (float) Math.toRadians(-9),
                        (float) Math.toRadians(19), (float) Math.toRadians(172),
                        2.5f, 0.34f, 96.0f,
                        true, (float) -(Math.PI - Math.toRadians(50))),
        };
    }
}
