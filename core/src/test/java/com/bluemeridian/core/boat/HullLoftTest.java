package com.bluemeridian.core.boat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Geometry generated from curves cannot be checked by looking at a number, so
 * these assert the properties that would make the boat wrong on screen: a hull
 * whose deck is under water, one that is inside out, one that is the wrong size.
 * Each of these has actually happened.
 */
class HullLoftTest {

    private static final HullLoft LOFT = HullLoft.class40();

    private static double[] bounds(BoatMesh mesh, int axis) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int i = axis; i < mesh.positions.length; i += 3) {
            min = Math.min(min, mesh.positions[i]);
            max = Math.max(max, mesh.positions[i]);
        }
        return new double[] {min, max};
    }

    @Test
    @DisplayName("the boat is the size the polar says it is")
    void dimensionsMatchTheHull() {
        BoatMesh hull = LOFT.hull();
        double[] alongship = bounds(hull, 0);
        double[] athwartships = bounds(hull, 2);

        assertEquals(12.18, alongship[1] - alongship[0], 0.02, "overall length");
        assertEquals(4.50, athwartships[1] - athwartships[0], 0.05, "maximum beam");
        assertEquals(0.0, alongship[0] + alongship[1], 0.02,
                "the hull should straddle the origin, so the physics position is amidships");
    }

    @Test
    @DisplayName("there is enough freeboard to keep the sea out")
    void freeboardIsARealBoats() {
        // Sampled at the deck edge, which is the lowest part of the deck. A hull with
        // a dinghy's topsides disappears in any sea worth sailing in - this test
        // exists because that shipped once.
        BoatMesh hull = LOFT.hull();
        double lowestDeck = Double.MAX_VALUE;
        for (int v = 0; v < hull.vertexCount(); v++) {
            if (hull.materials[v] == BoatMesh.DECK) {
                lowestDeck = Math.min(lowestDeck, hull.positions[v * 3 + 1]);
            }
        }
        assertTrue(lowestDeck > 1.0,
                "the lowest deck point is " + lowestDeck + " m above the waterline");
        assertTrue(lowestDeck < 2.0, "and it should not be a ship, got " + lowestDeck);
    }

    @Test
    @DisplayName("the transom is immersed, not a knife edge at the waterline")
    void theSternCarriesItsSectionsAft() {
        BoatMesh hull = LOFT.hull();
        double[] alongship = bounds(hull, 0);
        double sternX = alongship[0];

        double deepestAtStern = 0;
        double widestAtStern = 0;
        for (int v = 0; v < hull.vertexCount(); v++) {
            if (hull.positions[v * 3] > sternX + 0.05) {
                continue;
            }
            deepestAtStern = Math.min(deepestAtStern, hull.positions[v * 3 + 1]);
            widestAtStern = Math.max(widestAtStern, Math.abs(hull.positions[v * 3 + 2]));
        }
        assertTrue(deepestAtStern < -0.3,
                "the transom should be immersed, its lowest point is " + deepestAtStern);
        assertTrue(widestAtStern > 4.50 * 0.5 * 0.85,
                "and nearly full beam, got " + (widestAtStern * 2) + " m across");
    }

    @Test
    @DisplayName("every normal is a unit vector, and none is zero")
    void normalsAreUsable() {
        for (BoatMesh mesh : new BoatMesh[] {LOFT.hull(), LOFT.sails(0.4, 0.11)}) {
            for (int v = 0; v < mesh.vertexCount(); v++) {
                double x = mesh.normals[v * 3];
                double y = mesh.normals[v * 3 + 1];
                double z = mesh.normals[v * 3 + 2];
                double length = Math.sqrt(x * x + y * y + z * z);
                // A degenerate face would give a zero normal, and normalize() of the
                // zero vector is undefined in GLSL - it renders as a black or NaN
                // patch depending on the driver.
                assertEquals(1.0, length, 1e-5, "normal " + v + " has length " + length);
            }
        }
    }

    @Test
    @DisplayName("the hull is wound outward, so back-face culling keeps the near side")
    void windingIsOutward() {
        // Every outward normal must point away from the centreline: on the starboard
        // side (negative z) the normal's z must not be positive, and vice versa.
        // Getting this backwards turns the boat inside out, which reads as a hull
        // with a hole in it rather than as an obvious error.
        BoatMesh hull = LOFT.hull();
        int wrong = 0;
        for (int t = 0; t < hull.triangleCount(); t++) {
            int v = hull.indices[t * 3];
            if (hull.materials[v] != BoatMesh.HULL) {
                continue;
            }
            double z = (hull.positions[hull.indices[t * 3] * 3 + 2]
                    + hull.positions[hull.indices[t * 3 + 1] * 3 + 2]
                    + hull.positions[hull.indices[t * 3 + 2] * 3 + 2]) / 3.0;
            double nz = hull.normals[v * 3 + 2];
            if (Math.abs(z) > 0.5 && z * nz < -1e-6) {
                wrong++;
            }
        }
        assertEquals(0, wrong, wrong + " hull faces point inward");
    }

    @Test
    @DisplayName("the sails swing with the sheet, and the boom goes with them")
    void sailsFollowTheSheet() {
        BoatMesh centred = LOFT.sails(0.0, 0.11);
        BoatMesh eased = LOFT.sails(0.9, 0.11);
        assertEquals(centred.vertexCount(), eased.vertexCount(),
                "easing the sheet must not change the mesh, only where it is");

        double[] centredAcross = bounds(centred, 2);
        double[] easedAcross = bounds(eased, 2);
        assertTrue(easedAcross[1] > centredAcross[1] + 2.0,
                "eased to port the rig should reach well outboard, got "
                        + easedAcross[1] + " m");

        // The boom is rig material aft of the mast; if it had stayed in the hull mesh
        // it would sit on the centreline while the sail swung away from it.
        double boomOutboard = 0;
        for (int v = 0; v < eased.vertexCount(); v++) {
            if (eased.materials[v] == BoatMesh.RIG) {
                boomOutboard = Math.max(boomOutboard, eased.positions[v * 3 + 2]);
            }
        }
        assertTrue(boomOutboard > 2.0,
                "the boom should have swung out with the sail, reached " + boomOutboard);
    }

    @Test
    @DisplayName("the rig stands on the boat, not through it or above it")
    void rigIsAttachedWhereItShouldBe() {
        assertTrue(LOFT.mastBase > 0.8 && LOFT.mastBase < 1.6,
                "the mast heel should be about deck height, got " + LOFT.mastBase);
        assertTrue(LOFT.houndsY < LOFT.mastHeight,
                "the forestay cannot meet the mast above the masthead");
        assertTrue(LOFT.stemX > 0 && LOFT.stemX < 12.18 * 0.5,
                "the stemhead must be on the boat, at " + LOFT.stemX);
        assertTrue(LOFT.boomEnd < LOFT.mastX,
                "the boom runs aft from the mast");
    }
}
