package com.bluemeridian.render.gl;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.GLOnlyTextureData;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * An RGBA32F texture uploaded from a float array.
 *
 * <p>libGDX's {@code Pixmap} path only handles 8-bit formats, so a float texture
 * is allocated empty through {@code GLOnlyTextureData} and then filled with
 * {@code glTexSubImage2D}. This carries the initial spectrum, the wave vector
 * table and the butterfly schedule onto the GPU: all three are computed once by
 * {@code core} and never change, which is what keeps the CPU and GPU oceans
 * identical.
 */
public final class DataTexture {

    private DataTexture() {
    }

    /**
     * Creates an RGBA32F texture from interleaved RGBA float data.
     *
     * @param width  texture width
     * @param height texture height
     * @param rgba   {@code width * height * 4} floats
     */
    public static Texture rgba32f(int width, int height, float[] rgba) {
        int expected = width * height * 4;
        if (rgba.length != expected) {
            throw new IllegalArgumentException(
                    "expected " + expected + " floats for " + width + "x" + height
                            + ", got " + rgba.length);
        }
        Texture texture = new Texture(new GLOnlyTextureData(width, height, 0,
                GL30.GL_RGBA32F, GL30.GL_RGBA, GL20.GL_FLOAT));
        texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        texture.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);

        ByteBuffer bytes = ByteBuffer.allocateDirect(rgba.length * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        FloatBuffer floats = bytes.asFloatBuffer();
        floats.put(rgba);
        floats.position(0);

        texture.bind();
        Gdx.gl.glTexSubImage2D(GL20.GL_TEXTURE_2D, 0, 0, 0, width, height,
                GL30.GL_RGBA, GL20.GL_FLOAT, floats);
        return texture;
    }
}
