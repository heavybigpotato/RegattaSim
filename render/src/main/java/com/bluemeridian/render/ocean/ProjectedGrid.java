package com.bluemeridian.render.ocean;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;

/**
 * A grid that is uniform on screen rather than in the world.
 *
 * <p>The mesh is a fixed lattice of {@code (u,v)} pairs in {@code [0,1]^2}, built
 * once. Each frame the shader turns a lattice point into a world position by
 * un-projecting it through the inverse view-projection and intersecting the
 * resulting ray with the water plane. Vertices therefore land evenly across the
 * screen: dense in the foreground where a wave is metres across, sparse at the
 * horizon where a wave is a pixel.
 *
 * <p>Compared with a camera-centred clipmap this has no LOD rings, so no seams,
 * no T-junctions, no stitching, and no popping as the camera moves. What it gives
 * up is world-space vertex stability - vertices slide across the wave field as the
 * camera turns - which is why the fine surface detail comes from the derivative
 * maps rather than from geometry.
 *
 * <p>This class computes the visible band of the screen. Pointing the camera at
 * the sky should not spend 80,000 vertices on it, so the upper bound of the
 * lattice is bisected against the top of the wave slab: the highest crest can be
 * seen a little beyond the flat-water horizon, and the search accounts for that.
 */
public final class ProjectedGrid implements Disposable {

    /** Distance at which a ray that never meets the water is planted, metres. */
    public static final float HORIZON_DISTANCE = 40_000f;

    /** Lower bound on overscan, so the mesh always covers a little past the frame. */
    private static final float MINIMUM_OVERSCAN = 0.06f;
    /**
     * Upper bound on overscan. Past this, so much of the lattice is off-screen that
     * the on-screen density collapses; better a small gap than a coarse ocean.
     */
    private static final float MAXIMUM_OVERSCAN = 0.85f;

    private final Mesh mesh;
    private final int columns;
    private final int rows;

    private final Matrix4 inverseViewProjection = new Matrix4();
    private final Vector3 nearPoint = new Vector3();
    private final Vector3 farPoint = new Vector3();
    private final Vector3 cameraPosition = new Vector3();
    private final Vector3 centreDirection = new Vector3();
    private final Vector3 edgeDirection = new Vector3();

    private float ndcMinX = -1f;
    private float ndcMinY = -1f;
    private float ndcMaxX = 1f;
    private float ndcMaxY = 1f;
    private float overscan = MINIMUM_OVERSCAN;
    private boolean waterVisible;

    /**
     * @param columns lattice cells across the screen
     * @param rows    lattice cells down the screen
     */
    public ProjectedGrid(int columns, int rows) {
        long vertexCount = (long) (columns + 1) * (rows + 1);
        if (vertexCount > 65535L) {
            // libGDX meshes index with shorts.
            throw new IllegalArgumentException(
                    "projected grid needs " + vertexCount + " vertices, the limit is 65535");
        }
        this.columns = columns;
        this.rows = rows;

        int vertices = (columns + 1) * (rows + 1);
        int indexCount = columns * rows * 6;
        mesh = new Mesh(true, vertices, indexCount,
                new VertexAttribute(VertexAttributes.Usage.Position, 2, "a_position"));

        float[] vertexData = new float[vertices * 2];
        int v = 0;
        for (int y = 0; y <= rows; y++) {
            for (int x = 0; x <= columns; x++) {
                vertexData[v++] = x / (float) columns;
                vertexData[v++] = y / (float) rows;
            }
        }
        mesh.setVertices(vertexData);

        short[] indices = new short[indexCount];
        int i = 0;
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < columns; x++) {
                int base = y * (columns + 1) + x;
                int right = base + 1;
                int below = base + columns + 1;
                int belowRight = below + 1;
                indices[i++] = (short) base;
                indices[i++] = (short) below;
                indices[i++] = (short) right;
                indices[i++] = (short) right;
                indices[i++] = (short) below;
                indices[i++] = (short) belowRight;
            }
        }
        mesh.setIndices(indices);
    }

    public int columns() {
        return columns;
    }

    public int rows() {
        return rows;
    }

    public boolean isWaterVisible() {
        return waterVisible;
    }

    public Matrix4 inverseViewProjection() {
        return inverseViewProjection;
    }

    public float ndcMinX() {
        return ndcMinX;
    }

    public float ndcMinY() {
        return ndcMinY;
    }

    public float ndcMaxX() {
        return ndcMaxX;
    }

    public float ndcMaxY() {
        return ndcMaxY;
    }

    /** How far past the frame edge the lattice reached this frame, in NDC. */
    public float overscan() {
        return overscan;
    }

    /**
     * Recomputes the visible band for this frame.
     *
     * @param camera              the rendering camera
     * @param maximumDisplacement the tallest the surface can rise above mean water,
     *                            metres; only sets the margin above the horizon
     * @return false when no water is on screen, in which case the surface pass can
     *         be skipped entirely
     */
    public boolean update(Camera camera, float maximumDisplacement) {
        inverseViewProjection.set(camera.combined).inv();
        cameraPosition.set(camera.position);

        // A lattice vertex is placed by intersecting its ray with flat water and is
        // only then displaced, and displacement has a horizontal component. At the
        // bottom of the frame the water is a few metres away, so a metre of sideways
        // shift is a large *angle*: the edge of the mesh swings inward and leaves a
        // wedge of empty frame. The lattice is therefore extended past the frustum
        // by however much that shift is worth in NDC, which is a ratio of two
        // angles rather than a guess.
        overscan = computeOverscan(maximumDisplacement);
        ndcMinX = -1f - overscan;
        ndcMaxX = 1f + overscan;
        ndcMinY = -1f - overscan;

        // Find the highest point on screen that still sees water, by bisecting on
        // the NDC vertical axis. Three columns are enough - the horizon is a
        // straight line in screen space for a level camera and only bows slightly
        // when rolled, and a small margin absorbs the rest.
        float highest = -1f;
        boolean anyHit = false;
        for (float ndcX : new float[] {-1f, 0f, 1f}) {   // probe the true frustum, not the overscan
            if (!hitsWater(ndcX, -1f)) {
                continue;
            }
            anyHit = true;
            float lo = -1f;
            float hi = 1f;
            if (hitsWater(ndcX, 1f)) {
                lo = 1f;
            } else {
                for (int iteration = 0; iteration < 24; iteration++) {
                    float mid = 0.5f * (lo + hi);
                    if (hitsWater(ndcX, mid)) {
                        lo = mid;
                    } else {
                        hi = mid;
                    }
                }
            }
            highest = Math.max(highest, lo);
        }

        waterVisible = anyHit;
        if (!anyHit) {
            return false;
        }
        // A margin above the horizon so the lattice does not end in a hard straight
        // cut. Vertices past the horizon are planted at the horizon distance and
        // collapse into that sliver, so the margin costs a few rows and guarantees
        // the surface reaches the skyline. It is scaled by wave height because a big
        // sea needs the mesh to extend further before it can be trusted to be flat.
        float margin = 0.02f + 0.002f * Math.min(maximumDisplacement, 20f);
        ndcMaxY = Math.min(1f, highest + margin);
        return true;
    }

    /**
     * Overscan needed so that horizontal displacement cannot expose the frame edge.
     *
     * <p>Compares the angle the nearest visible water subtends when shifted
     * sideways by the wave displacement against the camera's own half angle. The
     * vertical bound is used as the horizontal scale: Tessendorf displacement is
     * comparable in both, and a factor of two either way is absorbed by the clamp.
     */
    private float computeOverscan(float maximumDisplacement) {
        nearPoint.set(0f, -1f, -1f).prj(inverseViewProjection);
        farPoint.set(0f, -1f, 1f).prj(inverseViewProjection);
        float dirX = farPoint.x - nearPoint.x;
        float dirY = farPoint.y - nearPoint.y;
        float dirZ = farPoint.z - nearPoint.z;
        if (Math.abs(dirY) < 1e-6f) {
            return MINIMUM_OVERSCAN;
        }
        float t = -nearPoint.y / dirY;
        if (t < 0f) {
            return MINIMUM_OVERSCAN;
        }
        // Distance from the camera to where the bottom of the frame meets the water.
        float bottomX = nearPoint.x + dirX * t;
        float bottomZ = nearPoint.z + dirZ * t;
        float nearDistance = (float) Math.hypot(bottomX - cameraPosition.x,
                bottomZ - cameraPosition.z);
        if (nearDistance < 1e-3f) {
            return MAXIMUM_OVERSCAN;
        }

        // Half the camera's vertical angle, measured from the rays themselves so
        // this works for any projection.
        centreDirection.set(0f, 0f, 1f).prj(inverseViewProjection)
                .sub(cameraPosition).nor();
        edgeDirection.set(dirX, dirY, dirZ).nor();
        float halfAngle = (float) Math.acos(
                Math.max(-1.0, Math.min(1.0, centreDirection.dot(edgeDirection))));
        if (halfAngle < 1e-4f) {
            return MINIMUM_OVERSCAN;
        }

        float shift = (float) Math.atan2(maximumDisplacement, nearDistance);
        float needed = shift / halfAngle;
        return Math.max(MINIMUM_OVERSCAN, Math.min(MAXIMUM_OVERSCAN, needed));
    }

    /**
     * True when the ray through this NDC point meets the mean water plane in front
     * of the camera and within the horizon distance.
     *
     * <p>The test is against {@code y = 0}, the same plane the vertex shader
     * intersects, and not against the top of the wave slab. Testing the slab top
     * fails in the case that matters most: with the camera at deck height in a big
     * sea it sits <em>below</em> the tallest crest, so no downward ray ever reaches
     * that plane and the search concludes there is no water on screen at all.
     */
    private boolean hitsWater(float ndcX, float ndcY) {
        nearPoint.set(ndcX, ndcY, -1f).prj(inverseViewProjection);
        farPoint.set(ndcX, ndcY, 1f).prj(inverseViewProjection);
        float dirY = farPoint.y - nearPoint.y;
        if (Math.abs(dirY) < 1e-6f) {
            return false;
        }
        float t = -nearPoint.y / dirY;
        if (t < 0f) {
            return false;
        }
        float dx = (farPoint.x - nearPoint.x) * t;
        float dz = (farPoint.z - nearPoint.z) * t;
        return dx * dx + dz * dz < HORIZON_DISTANCE * HORIZON_DISTANCE;
    }

    public void render(ShaderProgram program) {
        mesh.render(program, GL20.GL_TRIANGLES);
    }

    @Override
    public void dispose() {
        mesh.dispose();
    }
}
