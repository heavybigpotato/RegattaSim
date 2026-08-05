package com.bluemeridian.render.gl;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.Disposable;

/**
 * Two triangles covering clip space, used to drive every full-target pass.
 *
 * <p>Positions are already in normalised device coordinates, so the vertex
 * shader is a pass-through and no matrices are involved.
 */
public final class FullscreenQuad implements Disposable {

    private final Mesh mesh;

    public FullscreenQuad() {
        mesh = new Mesh(true, 4, 6,
                new VertexAttribute(VertexAttributes.Usage.Position, 2, ShaderProgram.POSITION_ATTRIBUTE),
                new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2,
                        ShaderProgram.TEXCOORD_ATTRIBUTE + "0"));
        mesh.setVertices(new float[] {
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                1f, 1f, 1f, 1f,
                -1f, 1f, 0f, 1f,
        });
        mesh.setIndices(new short[] {0, 1, 2, 2, 3, 0});
    }

    public void render(ShaderProgram program) {
        mesh.render(program, GL20.GL_TRIANGLES);
    }

    @Override
    public void dispose() {
        mesh.dispose();
    }
}
