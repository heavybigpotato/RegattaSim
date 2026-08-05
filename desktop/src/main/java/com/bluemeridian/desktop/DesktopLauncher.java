package com.bluemeridian.desktop;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

/**
 * Desktop launcher.
 *
 * <p>This module exists for one reason: a shader change should be visible in
 * seconds. Going through an APK build and install for every tweak to the
 * scattering term would make the ocean take months instead of weeks, and the
 * ocean is the part of this game that has to be right.
 *
 * <p>The shaders and the simulation are byte-for-byte the ones Android runs; only
 * the window and the input handling differ.
 */
public final class DesktopLauncher {

    private DesktopLauncher() {
    }

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        // GL 3.2 core with a 4.3 context: covers the GLES 3.0 feature set the phone
        // build targets, so a shader that compiles here compiles there.
        config.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL32, 4, 3);
        config.setWindowedMode(1600, 900);
        config.setTitle("Blue Meridian - ocean");
        config.useVsync(true);
        new Lwjgl3Application(new OceanSandbox(), config);
    }
}
