package com.bluemeridian.render.gl;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer;
import com.badlogic.gdx.utils.Disposable;

/**
 * A single-attachment floating point render target.
 *
 * <p>Two formats are used and the distinction matters. The FFT ping-pong buffers
 * are RGBA32F with nearest filtering, because the transform indexes exact texels
 * and full float precision keeps the round trip clean. The textures the surface
 * shader samples are RGBA16F with linear filtering and repeat wrapping, because
 * linear filtering of 16-bit float is core in GLES 3.0 while linear filtering of
 * 32-bit float is an optional extension that a good number of phones do not
 * have.
 */
public final class RenderTarget implements Disposable {

    private final FrameBuffer frameBuffer;
    private final int width;
    private final int height;

    private RenderTarget(FrameBuffer frameBuffer, int width, int height) {
        this.frameBuffer = frameBuffer;
        this.width = width;
        this.height = height;
    }

    /** Full precision, point sampled: for FFT intermediates. */
    public static RenderTarget float32(int width, int height) {
        FrameBuffer fb = new GLFrameBuffer.FrameBufferBuilder(width, height)
                .addFloatAttachment(GL30.GL_RGBA32F, GL30.GL_RGBA, GL20.GL_FLOAT, false)
                .build();
        Texture t = fb.getColorBufferTexture();
        t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        t.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
        return new RenderTarget(fb, width, height);
    }

    /** Half precision, linearly filtered and tiling: for textures the surface samples. */
    public static RenderTarget float16Tiling(int width, int height) {
        FrameBuffer fb = new GLFrameBuffer.FrameBufferBuilder(width, height)
                .addFloatAttachment(GL30.GL_RGBA16F, GL30.GL_RGBA, GL20.GL_FLOAT, false)
                .build();
        Texture t = fb.getColorBufferTexture();
        t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        t.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
        return new RenderTarget(fb, width, height);
    }

    /** Half precision, linearly filtered, clamped: for screen-sized HDR buffers. */
    public static RenderTarget float16Screen(int width, int height, boolean withDepth) {
        GLFrameBuffer.FrameBufferBuilder b = new GLFrameBuffer.FrameBufferBuilder(width, height);
        b.addFloatAttachment(GL30.GL_RGBA16F, GL30.GL_RGBA, GL20.GL_FLOAT, false);
        if (withDepth) {
            b.addDepthRenderBuffer(GL20.GL_DEPTH_COMPONENT16);
        }
        FrameBuffer fb = b.build();
        Texture t = fb.getColorBufferTexture();
        t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        t.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        return new RenderTarget(fb, width, height);
    }

    public Texture texture() {
        return frameBuffer.getColorBufferTexture();
    }

    public FrameBuffer frameBuffer() {
        return frameBuffer;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public void begin() {
        frameBuffer.begin();
    }

    public void end() {
        frameBuffer.end();
    }

    @Override
    public void dispose() {
        frameBuffer.dispose();
    }
}
