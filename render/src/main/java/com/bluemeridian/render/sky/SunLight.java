package com.bluemeridian.render.sky;

import com.badlogic.gdx.math.Vector3;

/**
 * Direction and colour of the sun.
 *
 * <p>The direction is exact: it comes from the solar position algorithm in
 * {@code core}, so a boat off Brest and a boat off Newport see the sun where it
 * actually is, at the same instant, which is the whole point of a world map with
 * real time zones.
 *
 * <p>The colour is not exact and is not claimed to be. It is a two-term
 * extinction fit - blue extinguishing fastest, red slowest, with the path length
 * through the atmosphere growing as the sun drops - which produces the familiar
 * white-to-amber-to-red progression. A spectrally correct solar radiance would
 * need the Preetham solar model integrated against the CIE observer; the
 * difference is not visible once the frame has been through ACES.
 */
public final class SunLight {

    /** Relative extinction per unit air mass for R, G and B. */
    private static final float[] EXTINCTION = {0.18f, 0.34f, 0.62f};

    private final Vector3 direction = new Vector3(0f, 1f, 0f);
    private final float[] colour = {1f, 1f, 1f};
    private float intensity = 1f;

    /**
     * Sets the sun from its elevation and azimuth.
     *
     * @param elevationRadians angle above the horizon
     * @param azimuthRadians   compass angle in the world XZ plane, from +X toward +Z
     */
    public void set(double elevationRadians, double azimuthRadians) {
        double cosElevation = Math.cos(elevationRadians);
        direction.set(
                (float) (cosElevation * Math.cos(azimuthRadians)),
                (float) Math.sin(elevationRadians),
                (float) (cosElevation * Math.sin(azimuthRadians)));
        direction.nor();
        updateColour();
    }

    private void updateColour() {
        // Air mass: 1 at the zenith, rising sharply near the horizon. The 0.05
        // floor stops it diverging when the sun is exactly on the horizon.
        float sinElevation = Math.max(direction.y, 0.0f);
        float airMass = 1f / Math.max(sinElevation, 0.05f);

        float peak = 0f;
        for (int channel = 0; channel < 3; channel++) {
            colour[channel] = (float) Math.exp(-EXTINCTION[channel] * airMass);
            peak = Math.max(peak, colour[channel]);
        }
        // Renormalise so the hue shifts but the sun does not simply go dark; the
        // overall dimming is carried by intensity instead, where auto-exposure can
        // respond to it.
        if (peak > 1e-4f) {
            for (int channel = 0; channel < 3; channel++) {
                colour[channel] /= peak;
            }
        }
        // Below the horizon there is no direct sun at all.
        intensity = Math.max(0f, Math.min(1f, (direction.y + 0.05f) * 6f));
    }

    public Vector3 direction() {
        return direction;
    }

    public float red() {
        return colour[0] * intensity;
    }

    public float green() {
        return colour[1] * intensity;
    }

    public float blue() {
        return colour[2] * intensity;
    }

    /** Elevation above the horizon in radians, negative when set. */
    public float elevation() {
        return (float) Math.asin(Math.max(-1f, Math.min(1f, direction.y)));
    }
}
