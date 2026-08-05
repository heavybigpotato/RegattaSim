package com.bluemeridian.render.gl;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.GdxRuntimeException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Loads GLSL from the classpath, resolves {@code #pragma include} directives and
 * prepends the right version header for the platform.
 *
 * <p>Shaders are written once, without a {@code #version} line, and targeted at
 * GLSL ES 3.00. Desktop gets {@code #version 330 core}, which accepts the same
 * body: {@code precision} statements and precision qualifiers have been legal
 * and ignored in desktop GLSL since 1.30. That means the ocean shaders that are
 * iterated on in seconds on a laptop are byte-for-byte the ones the phone runs,
 * which is the only way a "looks right on desktop" claim means anything.
 */
public final class ShaderSources {

    private static final String ROOT = "shaders/";

    private ShaderSources() {
    }

    /** Version header plus common defines, chosen from the running GL context. */
    public static String header() {
        boolean es = Gdx.app != null && Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android;
        StringBuilder sb = new StringBuilder();
        sb.append(es ? "#version 300 es\n" : "#version 330 core\n");
        sb.append("precision highp float;\n");
        sb.append("precision highp int;\n");
        sb.append("precision highp sampler2D;\n");
        return sb.toString();
    }

    /** Reads a shader file and resolves its includes. */
    public static String load(String name) {
        return resolve(name, new LinkedHashSet<>());
    }

    private static String resolve(String name, Set<String> visiting) {
        if (!visiting.add(name)) {
            throw new GdxRuntimeException("circular shader include involving " + name);
        }
        com.badlogic.gdx.files.FileHandle handle = Gdx.files.classpath(ROOT + name);
        if (!handle.exists()) {
            throw new GdxRuntimeException("shader not found on classpath: " + ROOT + name);
        }
        StringBuilder out = new StringBuilder();
        for (String line : handle.readString("UTF-8").split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#pragma include")) {
                int first = trimmed.indexOf('"');
                int last = trimmed.lastIndexOf('"');
                if (first < 0 || last <= first) {
                    throw new GdxRuntimeException(
                            "malformed include in " + name + ": " + trimmed);
                }
                out.append(resolve(trimmed.substring(first + 1, last), visiting)).append('\n');
            } else {
                out.append(line).append('\n');
            }
        }
        visiting.remove(name);
        return out.toString();
    }

    /**
     * Compiles a vertex/fragment pair, failing loudly. A silently broken shader
     * produces a black ocean and hours of confusion, so this never returns an
     * unusable program.
     */
    public static ShaderProgram program(String vertexName, String fragmentName) {
        // A uniform that a shader does not actually read is removed by the GLSL
        // compiler, and libGDX's default is to throw when one is then set. That
        // turns an unremarkable optimisation into a crash, so it is disabled here
        // once, at the single point where every program in the engine is built.
        ShaderProgram.pedantic = false;
        String head = header();
        ShaderProgram program = new ShaderProgram(head + load(vertexName), head + load(fragmentName));
        if (!program.isCompiled()) {
            throw new GdxRuntimeException(
                    "failed to compile " + vertexName + " + " + fragmentName + ":\n" + program.getLog());
        }
        return program;
    }
}
