package com.bluemeridian.render.boat;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix3;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;
import com.bluemeridian.core.boat.BoatMesh;
import com.bluemeridian.core.boat.HullLoft;
import com.bluemeridian.core.sailing.SailingBoat;
import com.bluemeridian.render.gl.ShaderSources;
import com.bluemeridian.render.sky.SunLight;

/**
 * Draws the boat.
 *
 * <p>The geometry comes from {@code core}, not from an asset: {@link HullLoft}
 * generates it from curves. That means the same boat is drawn by the Android
 * client, the desktop launcher, the reference renderer in CI and the WebGL
 * showcase, from one definition, with nothing to keep in sync by hand and nothing
 * to ship.
 *
 * <p>The hull is uploaded once. The sails are not: they swing with the sheet and
 * take a different draft on each point of sail, so they are rebuilt when the trim
 * moves far enough to be worth the upload. The threshold is what stops a boat
 * being re-lofted sixty times a second while the helm wanders half a degree.
 */
public final class BoatRenderer implements Disposable {

    /** How far the sheet must move before the sails are rebuilt, radians. */
    private static final double SHEET_HYSTERESIS = Math.toRadians(1.0);

    private final HullLoft loft;
    private final ShaderProgram program;
    private final Mesh hull;
    private Mesh sails;

    private final Matrix4 model = new Matrix4();
    private final Matrix3 normalMatrix = new Matrix3();
    private double sheetAngle = Double.NaN;
    private float speed;

    public BoatRenderer() {
        this(HullLoft.class40());
    }

    public BoatRenderer(HullLoft loft) {
        this.loft = loft;
        this.program = ShaderSources.program("boat.vert", "boat.frag");
        this.hull = upload(loft.hull());
        setSheetAngle(0.35);
    }

    /**
     * Sets the boom angle from the centreline, radians, positive to port.
     *
     * <p>Ignored unless it has moved far enough to matter, because rebuilding the
     * sails means regenerating and re-uploading a mesh.
     */
    public void setSheetAngle(double angle) {
        if (!Double.isNaN(sheetAngle) && Math.abs(angle - sheetAngle) < SHEET_HYSTERESIS) {
            return;
        }
        sheetAngle = angle;
        if (sails != null) {
            sails.dispose();
        }
        sails = upload(loft.sails(angle, 0.11));
    }

    /**
     * Sheets the sails from the apparent wind angle.
     *
     * <p>Hard in upwind and eased as the boat bears away. This is not a trim model -
     * it is what keeps the sail from being sheeted through the rig until there is
     * one, and it is deliberately separate from the trim <em>quality</em> the polar
     * uses, which is the player's business.
     *
     * @param apparentWindAngle radians at the bow, positive meaning starboard tack
     */
    public void sheetFor(double apparentWindAngle) {
        double magnitude = Math.min(Math.abs(apparentWindAngle), Math.PI);
        double eased = Math.max(0.18, Math.min(1.35, (magnitude - 0.28) * 0.95));
        setSheetAngle(apparentWindAngle >= 0 ? eased : -eased);
    }

    /** Draws the boat where the sailing model says it is. */
    public void render(Camera camera, SailingBoat boat, SunLight sun, float turbidity) {
        sheetFor(boat.wind().angle);
        this.speed = (float) boat.speed();
        render(camera, boat.x(), boat.heave(), boat.z(),
                boat.heading(), boat.pitch(), boat.roll(), sun, turbidity);
    }

    /**
     * Sets speed through the water, m/s, which drives the bow wave and the wet band
     * along the topsides. A boat lying still throws neither.
     */
    public void setSpeed(double metresPerSecond) {
        this.speed = (float) metresPerSecond;
    }

    /**
     * Draws the boat at an explicit position and attitude.
     *
     * <p>Heading rotates about {@code +Y} from {@code +X} toward {@code +Z}; pitch is
     * bow-up, roll is starboard-down. These are the senses {@link SailingBoat}
     * reports, so a boat climbing a wave face lifts its bow on screen rather than
     * burying it.
     */
    public void render(Camera camera, double x, double y, double z,
            double heading, double pitch, double roll, SunLight sun, float turbidity) {
        buildModelMatrix(x, y, z, heading, pitch, roll);
        normalMatrix.set(model);

        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_BLEND);
        // No culling. The sails are single-sided surfaces seen from either face, and
        // the hull's own faces are all outward anyway, so culling would buy nothing
        // and cost the sails.
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);

        program.bind();
        program.setUniformMatrix("u_viewProjection", camera.combined);
        program.setUniformMatrix("u_model", model);
        program.setUniformMatrix("u_normalMatrix", normalMatrix);
        program.setUniformf("u_cameraPosition",
                camera.position.x, camera.position.y, camera.position.z);
        program.setUniformf("u_sunDirection",
                sun.direction().x, sun.direction().y, sun.direction().z);
        program.setUniformf("u_sunColour", sun.red(), sun.green(), sun.blue());
        program.setUniformf("u_turbidity", turbidity);
        program.setUniformf("u_waterLevel", (float) y);
        program.setUniformf("u_boatSpeed", speed);

        hull.render(program, GL20.GL_TRIANGLES);
        sails.render(program, GL20.GL_TRIANGLES);
    }

    /**
     * Translate to the boat's position, rotate to its heading, then apply the pitch
     * and roll the physics read off the wave surface.
     *
     * <p>Written out rather than composed from three rotations, because the order
     * matters and naming the axes makes it checkable: the local basis is built in
     * boat coordinates and then swung by the heading.
     */
    private void buildModelMatrix(double x, double y, double z,
            double heading, double pitch, double roll) {
        double ch = Math.cos(heading);
        double sh = Math.sin(heading);
        double cp = Math.cos(pitch);
        double sp = Math.sin(pitch);
        double cr = Math.cos(roll);
        double sr = Math.sin(roll);

        // Local basis: forward is +X, up is +Y, port is +Z. Pitch lifts forward;
        // roll drops the starboard side, which is -Z.
        double[] forward = {cp, sp, 0};
        double[] up = {-sp * cr, cp * cr, -sr};
        double[] port = {sp * sr, -cp * sr, cr};

        float[] m = model.val;
        setColumn(m, Matrix4.M00, forward, ch, sh);
        setColumn(m, Matrix4.M01, up, ch, sh);
        setColumn(m, Matrix4.M02, port, ch, sh);
        m[Matrix4.M03] = (float) x;
        m[Matrix4.M13] = (float) y;
        m[Matrix4.M23] = (float) z;
        m[Matrix4.M30] = 0f;
        m[Matrix4.M31] = 0f;
        m[Matrix4.M32] = 0f;
        m[Matrix4.M33] = 1f;
    }

    /** Rotates one local axis into the world by the heading and stores it. */
    private static void setColumn(float[] m, int row0, double[] axis, double ch, double sh) {
        m[row0] = (float) (axis[0] * ch - axis[2] * sh);
        m[row0 + 1] = (float) axis[1];
        m[row0 + 2] = (float) (axis[0] * sh + axis[2] * ch);
    }

    /**
     * Uploads a generated mesh.
     *
     * <p>Position, normal and a single material index per vertex, interleaved. The
     * material is a float attribute rather than a texture lookup because there are
     * four of them and they never change: a hull is white above the boot stripe and
     * dark below it whatever else happens.
     */
    private static Mesh upload(BoatMesh source) {
        int vertexCount = source.vertexCount();
        Mesh mesh = new Mesh(true, vertexCount, source.indices.length,
                new VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"),
                new VertexAttribute(VertexAttributes.Usage.Normal, 3, "a_normal"),
                new VertexAttribute(VertexAttributes.Usage.Generic, 1, "a_material"),
                new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, "a_uv"));

        float[] interleaved = new float[vertexCount * 9];
        for (int v = 0; v < vertexCount; v++) {
            int out = v * 9;
            interleaved[out] = source.positions[v * 3];
            interleaved[out + 1] = source.positions[v * 3 + 1];
            interleaved[out + 2] = source.positions[v * 3 + 2];
            interleaved[out + 3] = source.normals[v * 3];
            interleaved[out + 4] = source.normals[v * 3 + 1];
            interleaved[out + 5] = source.normals[v * 3 + 2];
            interleaved[out + 6] = source.materials[v];
            interleaved[out + 7] = source.uvs[v * 2];
            interleaved[out + 8] = source.uvs[v * 2 + 1];
        }
        mesh.setVertices(interleaved);

        // libGDX indices are shorts. A silent wrap would draw garbage rather than
        // fail, and a detailed hull is no longer obviously under the limit.
        if (vertexCount > Short.MAX_VALUE) {
            mesh.dispose();
            throw new IllegalStateException(
                    "boat mesh has " + vertexCount + " vertices, past the 16-bit index limit");
        }
        short[] indices = new short[source.indices.length];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = (short) source.indices[i];
        }
        mesh.setIndices(indices);
        return mesh;
    }

    @Override
    public void dispose() {
        program.dispose();
        hull.dispose();
        sails.dispose();
    }
}
