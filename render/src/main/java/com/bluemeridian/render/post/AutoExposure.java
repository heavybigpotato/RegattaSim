package com.bluemeridian.render.post;

import com.bluemeridian.core.env.PreethamSky;

/**
 * Chooses the exposure from the sky rather than from the image.
 *
 * <p>The usual approach reduces the rendered frame to an average luminance and
 * follows it. On an ocean that misbehaves in a specific way: the sea is a mirror,
 * so a swell lifting the sun's reflection into frame can double the average
 * brightness for a few frames, and an image-driven exposure answers by darkening
 * the whole scene. The result reads as a flicker every time a wave passes.
 *
 * <p>Because the sky model is analytic and lives in {@code core}, the exposure can
 * instead be computed directly from the sun's elevation and the atmospheric
 * turbidity: the same numbers the shader is about to use. That is stable by
 * construction, costs nothing per frame, and is fully deterministic, which is
 * what lets the reference scenes be reproducible.
 *
 * <p>A time constant is still applied, because the sun does move and the eye does
 * adapt. Two seconds matches the pace of looking up from the deck into the sky.
 */
public final class AutoExposure {

    /**
     * Target exposed value for a mid-grey sky. Chosen so a clear daytime sky lands
     * on the shoulder of the ACES curve rather than clipped against its top.
     */
    private static final float KEY = 0.5f;

    /** Seconds for the exposure to close most of the gap to its target. */
    private float timeConstant = 2.0f;

    private float exposure = -1f;
    private float target = -1f;

    /** Recomputes the target from the sky and eases the current exposure toward it. */
    public void update(float sunElevationRadians, float turbidity, float deltaTime) {
        double mean = PreethamSky.meanDomeLuminance(sunElevationRadians, turbidity);
        // Floor the divisor: deep twilight tends to zero, and an exposure that grows
        // without bound would amplify nothing but noise.
        target = (float) (KEY / Math.max(mean, 0.02));

        if (exposure < 0f) {
            exposure = target;
            return;
        }
        float rate = 1f - (float) Math.exp(-deltaTime / Math.max(1e-3f, timeConstant));
        exposure += (target - exposure) * rate;
    }

    /** Jumps straight to the target, for the first frame and for offscreen rendering. */
    public void snap() {
        exposure = target;
    }

    public float exposure() {
        return exposure < 0f ? 1f : exposure;
    }

    public float target() {
        return target;
    }

    public void setTimeConstant(float seconds) {
        this.timeConstant = seconds;
    }
}
