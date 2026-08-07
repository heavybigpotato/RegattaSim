package com.bluemeridian.core.boat;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * A triangle mesh in boat-local coordinates.
 *
 * <p>Deliberately plain: parallel float arrays and an index array, which is what a
 * vertex buffer wants and nothing more. There is no scene graph, no material
 * objects and no node hierarchy, because a boat generated from curves has none to
 * describe - and inventing one would mean writing a loader for a format nothing
 * reads.
 *
 * <p>Axes match the physics: {@code +X} is the bow, {@code +Y} is up, {@code +Z} is
 * port. That last one follows from starboard being 90 degrees clockwise from the
 * bow, which is what {@link com.bluemeridian.core.sailing.SailingBoat} samples when
 * it reads roll off the wave surface.
 *
 * <h2>Smoothing groups</h2>
 *
 * <p>Every face is built with a smoothing group, and normals are averaged across
 * faces that share a position <em>and</em> a group. This is the difference between
 * a boat and a paper model: a hull's topsides are a developable surface and must
 * shade smoothly along their length, while the chine, the sheer and the transom
 * edge are real creases and must stay sharp. One flat-shaded pass gives every panel
 * a visible facet; one fully smoothed pass rounds off the very lines that make the
 * type recognisable. Groups let each edge be whichever it actually is.
 */
public final class BoatMesh {

    /** Material index carried per vertex; the fragment shader branches on it. */
    public static final float TOPSIDES = 0f;
    public static final float DECK = 1f;
    public static final float SPAR = 2f;
    public static final float SAIL = 3f;
    public static final float BOTTOM = 4f;
    public static final float WIRE = 5f;
    public static final float WINDOW = 6f;

    /** Interleaved x, y, z per vertex. */
    public final float[] positions;
    /** Interleaved x, y, z per vertex, unit length. */
    public final float[] normals;
    /** One material index per vertex. */
    public final float[] materials;
    /**
     * Interleaved u, v per vertex, in metres along the surface.
     *
     * <p>Metres rather than normalised, so a procedural detail - a plank seam, a
     * sail panel, a non-skid pattern - has the same physical size wherever it lands
     * and does not stretch where the surface does.
     */
    public final float[] uvs;
    public final int[] indices;

    BoatMesh(float[] positions, float[] normals, float[] materials, float[] uvs,
            int[] indices) {
        this.positions = positions;
        this.normals = normals;
        this.materials = materials;
        this.uvs = uvs;
        this.indices = indices;
    }

    public int vertexCount() {
        return positions.length / 3;
    }

    public int triangleCount() {
        return indices.length / 3;
    }

    /**
     * A stable summary of the geometry, for tests and for the web parity check.
     *
     * <p>Summing coordinates plainly would let a sign error cancel itself out, so
     * each is weighted by where it sits in the buffer: moving a vertex, flipping a
     * winding or dropping a face all change the total.
     *
     * <p>Widened before multiplying, not after. Java would otherwise evaluate
     * {@code float * int} in float and only promote on the way into the sum, which
     * is arithmetic JavaScript cannot reproduce - its typed arrays read out as
     * doubles - and the two totals then disagree in the seventh figure for no reason
     * anyone would find by looking at the geometry.
     */
    public double checksum() {
        double sum = 0;
        for (int i = 0; i < positions.length; i++) {
            sum += (double) positions[i] * ((i % 97) + 1);
        }
        for (int i = 0; i < normals.length; i++) {
            sum += (double) normals[i] * ((i % 71) + 1);
        }
        for (int i = 0; i < indices.length; i++) {
            sum += (double) indices[i] * ((i % 89) + 1);
        }
        return sum;
    }

    /** A point on a surface: position and the surface coordinate at it. */
    static double[] at(double x, double y, double z, double u, double v) {
        return new double[] {x, y, z, u, v};
    }

    /** A point with no meaningful surface coordinate. */
    static double[] at(double x, double y, double z) {
        return new double[] {x, y, z, 0, 0};
    }

    /**
     * Accumulates triangles and resolves their normals.
     *
     * <p>Material and smoothing group are builder state rather than arguments,
     * because they change far less often than faces are added and threading them
     * through every call buries the geometry in bookkeeping.
     */
    static final class Builder {

        /**
         * Smallest cross-product length a triangle may have and still be built.
         *
         * <p>Twice the area, so this is a face of half a square millimetre. Real
         * faces here are thousands of times larger and the ones this rejects are
         * millions of times smaller, so nothing sits near the line.
         *
         * <p>The test cannot be for exactly zero. Both sail heads taper to a point,
         * and the luff and the leech arrive there by different arithmetic, so the
         * two "same" vertices differ in the last bits of a double - enough that the
         * face is not exactly degenerate, and far too little for its normal to mean
         * anything. Java and JavaScript disagreed about which way one such face
         * pointed by 9 degrees, which is what found this.
         */
        private static final double MINIMUM_FACE = 1e-9;

        /** Position quantum for deciding two vertices are the same point, metres. */
        private static final double WELD = 1e-4;

        private float[] positions = new float[6144];
        private float[] normals = new float[6144];
        private float[] materials = new float[2048];
        private float[] uvs = new float[4096];
        private int[] groups = new int[2048];
        private int[] indices = new int[2048];
        private int vertices;
        private int indexCount;

        private float material = TOPSIDES;
        private int group;

        Builder material(float material) {
            this.material = material;
            return this;
        }

        /**
         * Starts a new smoothing group. Faces in different groups never share a
         * normal, so a group boundary is a hard edge.
         */
        Builder smoothing(int group) {
            this.group = group;
            return this;
        }

        /** Adds a triangle. Its normal is resolved later, per smoothing group. */
        void triangle(double[] a, double[] b, double[] c) {
            double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
            double vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
            double nx = uy * vz - uz * vy;
            double ny = uz * vx - ux * vz;
            double nz = ux * vy - uy * vx;
            double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length < MINIMUM_FACE) {
                // No area, so no normal worth having. Dropping it beats emitting
                // three vertices whose normal is whatever zero divides into.
                return;
            }
            // Left unnormalised on purpose. Averaging by the raw cross product
            // weights each face by its area, which is what stops a fan of slivers at
            // a bow or a masthead from outvoting the large panels around it.
            index(vertex(a, nx, ny, nz));
            index(vertex(b, nx, ny, nz));
            index(vertex(c, nx, ny, nz));
        }

        void quad(double[] a, double[] b, double[] c, double[] d) {
            triangle(a, b, c);
            triangle(a, c, d);
        }

        /** Adds a closed strip between two rows of points, as a ladder of quads. */
        void strip(double[][] lower, double[][] upper) {
            for (int i = 0; i < lower.length - 1; i++) {
                quad(lower[i], lower[i + 1], upper[i + 1], upper[i]);
            }
        }

        private int vertex(double[] p, double nx, double ny, double nz) {
            if (vertices * 3 + 3 > positions.length) {
                positions = Arrays.copyOf(positions, positions.length * 2);
                normals = Arrays.copyOf(normals, normals.length * 2);
            }
            if (vertices * 2 + 2 > uvs.length) {
                uvs = Arrays.copyOf(uvs, uvs.length * 2);
            }
            if (vertices + 1 > materials.length) {
                materials = Arrays.copyOf(materials, materials.length * 2);
                groups = Arrays.copyOf(groups, groups.length * 2);
            }
            int base = vertices * 3;
            positions[base] = (float) p[0];
            positions[base + 1] = (float) p[1];
            positions[base + 2] = (float) p[2];
            normals[base] = (float) nx;
            normals[base + 1] = (float) ny;
            normals[base + 2] = (float) nz;
            uvs[vertices * 2] = (float) p[3];
            uvs[vertices * 2 + 1] = (float) p[4];
            materials[vertices] = material;
            groups[vertices] = group;
            return vertices++;
        }

        private void index(int value) {
            if (indexCount + 1 > indices.length) {
                indices = Arrays.copyOf(indices, indices.length * 2);
            }
            indices[indexCount++] = value;
        }

        /**
         * Averages face normals across vertices that share a position and a
         * smoothing group, then normalises.
         *
         * <p>Vertices are keyed on their position rounded to a tenth of a
         * millimetre. Exact equality would not do: the same corner is reached by
         * different arithmetic from either side of a panel, and the two answers
         * differ in the last bits - which is precisely the case a weld has to
         * recognise.
         */
        BoatMesh build() {
            Map<WeldKey, float[]> accumulated = new HashMap<>();
            WeldKey[] keys = new WeldKey[vertices];
            for (int v = 0; v < vertices; v++) {
                WeldKey key = weldKey(v);
                keys[v] = key;
                float[] sum = accumulated.computeIfAbsent(key, k -> new float[3]);
                sum[0] += normals[v * 3];
                sum[1] += normals[v * 3 + 1];
                sum[2] += normals[v * 3 + 2];
            }

            for (int v = 0; v < vertices; v++) {
                float[] sum = accumulated.get(keys[v]);
                double x = sum[0];
                double y = sum[1];
                double z = sum[2];
                double length = Math.sqrt(x * x + y * y + z * z);
                if (length < 1e-12) {
                    // Two faces of a zero-thickness fin exactly cancelling. Fall back
                    // to this vertex's own face normal, which still has a direction.
                    x = normals[v * 3];
                    y = normals[v * 3 + 1];
                    z = normals[v * 3 + 2];
                    length = Math.sqrt(x * x + y * y + z * z);
                }
                normals[v * 3] = (float) (x / length);
                normals[v * 3 + 1] = (float) (y / length);
                normals[v * 3 + 2] = (float) (z / length);
            }

            return index();
        }

        /**
         * Collapses vertices that are genuinely the same into one, and remaps the
         * index buffer onto them.
         *
         * <p>Every triangle is added with three fresh vertices, because that is the
         * only way to know a face's own normal before the smoothing pass has run.
         * Once it has, a corner shared by four panels of one smoothing group holds
         * four identical vertices - same position, same averaged normal, same
         * material, same surface coordinate - and there is no reason to send four
         * copies to the GPU. On this hull it is the difference between 9600 vertices
         * and about a third of that, which on a phone is bandwidth that buys nothing.
         */
        private BoatMesh index() {
            Map<VertexKey, Integer> unique = new HashMap<>();
            int[] remap = new int[vertices];
            float[] p = new float[vertices * 3];
            float[] n = new float[vertices * 3];
            float[] m = new float[vertices];
            float[] uv = new float[vertices * 2];
            int kept = 0;

            for (int v = 0; v < vertices; v++) {
                VertexKey key = new VertexKey(
                        positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2],
                        normals[v * 3], normals[v * 3 + 1], normals[v * 3 + 2],
                        materials[v], uvs[v * 2], uvs[v * 2 + 1]);
                Integer existing = unique.get(key);
                if (existing != null) {
                    remap[v] = existing;
                    continue;
                }
                p[kept * 3] = positions[v * 3];
                p[kept * 3 + 1] = positions[v * 3 + 1];
                p[kept * 3 + 2] = positions[v * 3 + 2];
                n[kept * 3] = normals[v * 3];
                n[kept * 3 + 1] = normals[v * 3 + 1];
                n[kept * 3 + 2] = normals[v * 3 + 2];
                m[kept] = materials[v];
                uv[kept * 2] = uvs[v * 2];
                uv[kept * 2 + 1] = uvs[v * 2 + 1];
                unique.put(key, kept);
                remap[v] = kept;
                kept++;
            }

            int[] remapped = new int[indexCount];
            for (int i = 0; i < indexCount; i++) {
                remapped[i] = remap[indices[i]];
            }

            return new BoatMesh(
                    Arrays.copyOf(p, kept * 3),
                    Arrays.copyOf(n, kept * 3),
                    Arrays.copyOf(m, kept),
                    Arrays.copyOf(uv, kept * 2),
                    remapped);
        }

        /** Everything a vertex carries; two that match on all of it are one vertex. */
        private static final class VertexKey {

            private final float[] values;
            private final int hash;

            VertexKey(float... values) {
                this.values = values;
                this.hash = Arrays.hashCode(values);
            }

            @Override
            public boolean equals(Object other) {
                return other instanceof VertexKey
                        && Arrays.equals(values, ((VertexKey) other).values);
            }

            @Override
            public int hashCode() {
                return hash;
            }
        }

        private WeldKey weldKey(int v) {
            return new WeldKey(
                    Math.round(positions[v * 3] / WELD),
                    Math.round(positions[v * 3 + 1] / WELD),
                    Math.round(positions[v * 3 + 2] / WELD),
                    groups[v]);
        }

        /** A quantised position plus a smoothing group: what makes two corners one. */
        private static final class WeldKey {

            private final long x;
            private final long y;
            private final long z;
            private final int group;

            WeldKey(long x, long y, long z, int group) {
                this.x = x;
                this.y = y;
                this.z = z;
                this.group = group;
            }

            @Override
            public boolean equals(Object other) {
                if (!(other instanceof WeldKey)) {
                    return false;
                }
                WeldKey o = (WeldKey) other;
                return x == o.x && y == o.y && z == o.z && group == o.group;
            }

            @Override
            public int hashCode() {
                int h = (int) (x ^ (x >>> 32));
                h = h * 31 + (int) (y ^ (y >>> 32));
                h = h * 31 + (int) (z ^ (z >>> 32));
                return h * 31 + group;
            }
        }
    }
}
