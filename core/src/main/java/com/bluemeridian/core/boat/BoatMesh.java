package com.bluemeridian.core.boat;

import java.util.Arrays;

/**
 * A flat-shaded triangle mesh in boat-local coordinates.
 *
 * <p>Deliberately plain: three parallel float arrays and an index array, which is
 * exactly what a vertex buffer wants and nothing more. There is no scene graph, no
 * material objects and no node hierarchy, because a boat generated from curves has
 * no hierarchy to describe - and inventing one here would mean writing a loader
 * for a format nothing reads.
 *
 * <p>Axes match the physics: {@code +X} is the bow, {@code +Y} is up, {@code +Z} is
 * port. That last one follows from starboard being 90 degrees clockwise from the
 * bow, which is what {@link com.bluemeridian.core.sailing.SailingBoat} samples when
 * it reads roll off the wave surface.
 */
public final class BoatMesh {

    /** Material index carried per vertex; the fragment shader branches on it. */
    public static final float HULL = 0f;
    public static final float DECK = 1f;
    public static final float RIG = 2f;
    public static final float SAIL = 3f;

    /** Interleaved x, y, z per vertex. */
    public final float[] positions;
    /** Interleaved x, y, z per vertex, unit length. */
    public final float[] normals;
    /** One material index per vertex. */
    public final float[] materials;
    public final int[] indices;

    BoatMesh(float[] positions, float[] normals, float[] materials, int[] indices) {
        this.positions = positions;
        this.normals = normals;
        this.materials = materials;
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
     * winding or dropping a face all change the total. Compared with a relative
     * tolerance rather than exactly, because the point is to catch a different mesh,
     * not to test floating-point associativity across two languages.
     */
    public double checksum() {
        double sum = 0;
        // Widened before multiplying, not after. Java would otherwise evaluate
        // float * int in float and only promote on the way into the sum, which is
        // arithmetic JavaScript cannot reproduce - its typed arrays read out as
        // doubles - and the two totals then disagree in the seventh figure for no
        // reason anyone would find by looking at the geometry.
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

    /** Accumulates triangles, computing a face normal for each. */
    static final class Builder {

        private float[] positions = new float[3072];
        private float[] normals = new float[3072];
        private float[] materials = new float[1024];
        private int[] indices = new int[1024];
        private int vertices;
        private int indexCount;

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

        /** Adds a flat-shaded triangle. Flat shading suits a hard-chined racing hull. */
        void triangle(double[] a, double[] b, double[] c, float material) {
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
            nx /= length;
            ny /= length;
            nz /= length;

            int i0 = vertex(a, nx, ny, nz, material);
            int i1 = vertex(b, nx, ny, nz, material);
            int i2 = vertex(c, nx, ny, nz, material);
            index(i0);
            index(i1);
            index(i2);
        }

        void quad(double[] a, double[] b, double[] c, double[] d, float material) {
            triangle(a, b, c, material);
            triangle(a, c, d, material);
        }

        private int vertex(double[] p, double nx, double ny, double nz, float material) {
            if (vertices * 3 + 3 > positions.length) {
                positions = Arrays.copyOf(positions, positions.length * 2);
                normals = Arrays.copyOf(normals, normals.length * 2);
            }
            if (vertices + 1 > materials.length) {
                materials = Arrays.copyOf(materials, materials.length * 2);
            }
            int base = vertices * 3;
            positions[base] = (float) p[0];
            positions[base + 1] = (float) p[1];
            positions[base + 2] = (float) p[2];
            normals[base] = (float) nx;
            normals[base + 1] = (float) ny;
            normals[base + 2] = (float) nz;
            materials[vertices] = material;
            return vertices++;
        }

        private void index(int value) {
            if (indexCount + 1 > indices.length) {
                indices = Arrays.copyOf(indices, indices.length * 2);
            }
            indices[indexCount++] = value;
        }

        BoatMesh build() {
            return new BoatMesh(
                    Arrays.copyOf(positions, vertices * 3),
                    Arrays.copyOf(normals, vertices * 3),
                    Arrays.copyOf(materials, vertices),
                    Arrays.copyOf(indices, indexCount));
        }
    }
}
