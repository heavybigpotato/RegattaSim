package com.bluemeridian.android;

import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;

/**
 * Android entry point.
 *
 * <p>Requests a GLES 3.0 context, which the ocean needs: the FFT ping-pongs
 * through floating point render targets and indexes them with
 * {@code texelFetch}, neither of which exists in GLES 2.0.
 *
 * <p>Depth and stencil buffers are declined on the default framebuffer because
 * the scene is drawn into an HDR target that carries its own depth attachment;
 * asking for them twice costs bandwidth on a tile-based GPU for nothing.
 */
public final class AndroidLauncher extends AndroidApplication {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useGL30 = true;
        config.r = 8;
        config.g = 8;
        config.b = 8;
        config.a = 0;
        config.depth = 0;
        config.stencil = 0;
        // Multisampling is off deliberately: the sea is shaded per pixel and its
        // aliasing comes from specular highlights rather than from polygon edges,
        // so MSAA costs a great deal of bandwidth and fixes almost nothing here.
        config.numSamples = 0;
        config.useAccelerometer = false;
        config.useCompass = false;
        config.useGyroscope = false;

        // The screen must not sleep mid-race.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        goFullscreen();

        initialize(new AndroidOceanView(this), config);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            goFullscreen();
        }
    }

    private void goFullscreen() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}
