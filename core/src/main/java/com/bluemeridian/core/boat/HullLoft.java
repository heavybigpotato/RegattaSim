package com.bluemeridian.core.boat;

/**
 * A 40 ft racing boat, generated rather than modelled.
 *
 * <p>There is no glTF loader here on purpose: the asset pipeline is Phase 6 work
 * and this needs a boat now. A hull <em>is</em> a loft between stations - that is
 * literally how they are drawn - so generating one is a few lines of curve
 * evaluation, and it gives something that reads correctly from every angle without
 * shipping a single byte of geometry.
 *
 * <p>The numbers are typical of the type rather than taken from any particular
 * design: overall length, beam, freeboard, canoe body depth, rig height. Nothing
 * here is a scan of a real yacht and nothing here carries a class's lines.
 *
 * <p>Two meshes come out, because they have different lifetimes. The hull is built
 * once. The sails move - they swing with the sheet and take a different draft on
 * each point of sail - so they are rebuilt when the trim changes, and the boom goes
 * with them: left in the hull it sat on the centreline while the mainsail swung
 * away from it.
 */
public final class HullLoft {

    private static final int STATIONS = 22;

    /**
     * How much higher the deck is on the centreline than at the sheer, metres.
     *
     * <p>Every boat's deck is arched. It sheds water, it is stiffer than a flat
     * plate for the same weight, and - the reason it is here - a flat deck at
     * maximum beam renders as one large parallelogram that reads as a raft with a
     * mast on it. The crown is what tells the eye the deck is a surface, not a lid.
     */
    private static final double DECK_CROWN = 0.18;

    private final double length;
    private final double halfBeam;
    /**
     * Scales the beam curve so the widest station is exactly the beam asked for.
     *
     * <p>Without this the shape function peaks below 1 - it did, at 0.916 - and the
     * drawn boat came out 8% narrower than the hull the physics was sampling.
     * Computed from the same discrete stations the loft uses, so the maximum is the
     * one that actually gets built rather than the curve's analytic peak.
     */
    private final double beamScale;

    /** Fore-and-aft position of the mast, metres from midships, positive forward. */
    public final double mastX;
    /** Height of the mast heel above the designed waterline, metres. */
    public final double mastBase;
    /** Height of the masthead above the designed waterline, metres. */
    public final double mastHeight;
    /** Fore-and-aft position of the boom's aft end when sheeted on the centreline. */
    public final double boomEnd;
    /** Stemhead, where the headsail tacks down. */
    public final double stemX;
    public final double stemY;
    /** Height of the hounds, where the forestay meets the mast. */
    public final double houndsY;

    private final BoatMesh hull;

    public static HullLoft class40() {
        return new HullLoft(12.18, 4.50);
    }

    public HullLoft(double length, double beam) {
        this.length = length;
        this.halfBeam = beam * 0.5;

        double widest = 0;
        for (int i = 0; i <= STATIONS; i++) {
            widest = Math.max(widest, beamShape(i / (double) STATIONS));
        }
        this.beamScale = 1.0 / widest;

        this.mastX = length * 0.12;
        this.mastHeight = 18.5;
        this.mastBase = sheer(0.5 - mastX / length) - 0.1;
        this.boomEnd = mastX - 5.2;
        this.stemX = length * 0.47;
        this.stemY = sheer(0.5 - stemX / length);
        this.houndsY = mastHeight * 0.86;
        this.hull = buildHull();
    }

    /** The hull, deck, coachroof, appendages and standing rig. Built once. */
    public BoatMesh hull() {
        return hull;
    }

    // --- the curves ---------------------------------------------------------

    /**
     * Half-beam at a station, 0 at the bow and nearly full at the transom.
     *
     * <p>A boat of this type carries its beam a long way aft - maximum around two
     * thirds back, and a transom still at nine tenths of it - which is what lets it
     * plane. A cruising hull would taper to a fine stern instead, and the difference
     * is most of what makes the two recognisable from astern.
     *
     * <p>Smooth all the way, deliberately. An earlier version clamped the forward
     * curve at the point of maximum beam and tapered aft of it, which is easier to
     * read but leaves a slope discontinuity there - and a flat-shaded hull renders
     * that as a hard crease running down the topsides.
     */
    private static double beamShape(double t) {
        return Math.sin(Math.PI * 0.5 * Math.pow(t, 0.8)) * (1 - 0.1 * Math.pow(t, 2.5));
    }

    private double halfWidth(double t) {
        return halfBeam * beamShape(t) * beamScale;
    }

    /**
     * Deck height above the waterline: high at the bow, lowest amidships.
     *
     * <p>About 1.85 m at the stem, 1.35 amidships, 1.25 at the transom. These matter
     * more than they look: an earlier version gave the boat 0.7 m of topsides, which
     * is a dinghy's, and in a four metre sea the deck was under water in every frame
     * and the hull could not be seen at all. Freeboard is what keeps the sea out, and
     * getting it wrong shows immediately.
     */
    private static double sheer(double t) {
        return 1.14 + 0.72 * Math.pow(1 - t, 1.8) + 0.10 * t * t;
    }

    /**
     * Canoe body depth below the waterline: zero at the stem, deepest by a third of
     * the way aft, and still immersed at the transom.
     *
     * <p>The transom is the point. Returning to zero depth aft - which a single sine
     * over the length does - draws a boat whose stern comes to a knife edge at the
     * waterline, and no modern racing hull is shaped that way; they carry their
     * sections aft to a wide, shallow, immersed transom.
     */
    private static double keelDepth(double t) {
        return -0.93 * (1 - Math.exp(-t / 0.22)) * (1 - 0.42 * t);
    }

    /**
     * One transverse section.
     *
     * <p>Four points, not two: centreline, chine and deck edge. A hard chine is the
     * defining line of this type, and a section lofted straight from the keel to the
     * sheer draws a V - which from astern is a pyramid rather than a boat.
     */
    private double[] station(int i) {
        double t = i / (double) STATIONS;
        double hw = halfWidth(t);
        double keel = keelDepth(t);
        // x, halfWidth, deck, keel, chineZ, chineY.
        // The turn of the bilge sits well outboard and just under the waterline, so
        // the bottom is nearly flat and the topsides nearly upright.
        return new double[] {length * (0.5 - t), hw, sheer(t), keel, hw * 0.86, keel * 0.2};
    }

    /** Deck height at a distance {@code z} from the centreline, following the crown. */
    private static double deckAt(double[] s, double z) {
        double across = s[1] > 1e-6 ? z / s[1] : 0;
        return s[2] + DECK_CROWN * (1 - across * across);
    }

    private static double[] point(double x, double y, double z) {
        return new double[] {x, y, z};
    }

    // --- the loft -----------------------------------------------------------

    private BoatMesh buildHull() {
        BoatMesh.Builder mesh = new BoatMesh.Builder();

        // Station 0 is the bow, station STATIONS the transom. x runs from +L/2 to
        // -L/2 so the bow sits forward of the origin.
        for (int i = 0; i < STATIONS; i++) {
            double[] a = station(i);
            double[] b = station(i + 1);

            // Starboard is -Z, so its panels are wound bow-to-stern along the lower
            // edge; port repeats them the other way round, which flips the normals
            // outboard.
            for (int side = -1; side <= 1; side += 2) {
                double[] aChine = point(a[0], a[5], side * a[4]);
                double[] bChine = point(b[0], b[5], side * b[4]);
                double[] aKeel = point(a[0], a[3], 0);
                double[] bKeel = point(b[0], b[3], 0);
                double[] aDeck = point(a[0], a[2], side * a[1]);
                double[] bDeck = point(b[0], b[2], side * b[1]);
                if (side < 0) {
                    mesh.quad(aKeel, bKeel, bChine, aChine, BoatMesh.HULL);
                    mesh.quad(aChine, bChine, bDeck, aDeck, BoatMesh.HULL);
                } else {
                    mesh.quad(aChine, bChine, bKeel, aKeel, BoatMesh.HULL);
                    mesh.quad(aDeck, bDeck, bChine, aChine, BoatMesh.HULL);
                }
            }

            // Deck, in two halves so the crown has a ridge to run along.
            mesh.quad(
                    point(a[0], deckAt(a, 0), 0), point(a[0], a[2], -a[1]),
                    point(b[0], b[2], -b[1]), point(b[0], deckAt(b, 0), 0), BoatMesh.DECK);
            mesh.quad(
                    point(a[0], deckAt(a, 0), 0), point(b[0], deckAt(b, 0), 0),
                    point(b[0], b[2], b[1]), point(a[0], a[2], a[1]), BoatMesh.DECK);
        }

        transom(mesh);
        coachroof(mesh);
        appendages(mesh);
        rig(mesh);
        return mesh.build();
    }

    /**
     * A flat plate closing the stern, which on this type is nearly the full beam and
     * is a large part of how the boat reads from astern. Wound to face aft, along -X.
     */
    private void transom(BoatMesh.Builder mesh) {
        double[] s = station(STATIONS);
        double[] keel = point(s[0], s[3], 0);
        double[] chineStarboard = point(s[0], s[5], -s[4]);
        double[] chinePort = point(s[0], s[5], s[4]);
        double[] deckStarboard = point(s[0], s[2], -s[1]);
        double[] deckPort = point(s[0], s[2], s[1]);
        double[] crown = point(s[0], deckAt(s, 0), 0);
        // Split down the centreline so the top edge follows the deck's crown; the
        // two halves share the keel-to-crown line and together close the outline.
        mesh.quad(chinePort, deckPort, crown, keel, BoatMesh.HULL);
        mesh.quad(keel, crown, deckStarboard, chineStarboard, BoatMesh.HULL);
    }

    /**
     * A low trunk over the accommodation.
     *
     * <p>A bare deck plate reads as a barge from any angle. The trunk breaks the
     * plane, and it is also what the eye measures the sheerline against - without
     * something in the middle of the boat there is nothing to judge the curve of the
     * deck edge by.
     */
    private void coachroof(BoatMesh.Builder mesh) {
        int first = (int) Math.round(STATIONS * 0.28);
        int last = (int) Math.round(STATIONS * 0.74);

        for (int i = first; i < last; i++) {
            double[] a = roof(i, first, last);
            double[] b = roof(i + 1, first, last);
            // Starboard side, then port wound the other way, same as the topsides.
            mesh.quad(
                    point(a[0], a[2], -a[1]), point(b[0], b[2], -b[1]),
                    point(b[0], b[3], -b[1]), point(a[0], a[3], -a[1]), BoatMesh.DECK);
            mesh.quad(
                    point(a[0], a[3], a[1]), point(b[0], b[3], b[1]),
                    point(b[0], b[2], b[1]), point(a[0], a[2], a[1]), BoatMesh.DECK);
            mesh.quad(
                    point(a[0], a[3], a[1]), point(a[0], a[3], -a[1]),
                    point(b[0], b[3], -b[1]), point(b[0], b[3], b[1]), BoatMesh.DECK);
        }

        double[] front = roof(first, first, last);
        double[] back = roof(last, first, last);
        mesh.quad(
                point(front[0], front[2], front[1]), point(front[0], front[3], front[1]),
                point(front[0], front[3], -front[1]), point(front[0], front[2], -front[1]),
                BoatMesh.DECK);
        // The aft face is the companionway bulkhead, so it faces the cockpit.
        mesh.quad(
                point(back[0], back[2], -back[1]), point(back[0], back[3], -back[1]),
                point(back[0], back[3], back[1]), point(back[0], back[2], back[1]),
                BoatMesh.RIG);
    }

    /** One coachroof section as x, halfWidth, base, top. */
    private double[] roof(int i, int first, int last) {
        double[] s = station(i);
        double f = (i - first) / (double) (last - first);
        double hw = s[1] * (0.52 + 0.13 * f);
        // Seated on the crowned deck at its own half-width, or it would float clear
        // of the deck on one side and sink into it on the other.
        double base = deckAt(s, hw);
        // Wedge-shaped, low forward and highest at the companionway, which is where
        // the crew needs the headroom.
        return new double[] {s[0], hw, base, base + 0.16 + 0.42 * f};
    }

    /** Keel fin and rudder, as thin plates - they are only ever seen edge-on. */
    private void appendages(BoatMesh.Builder mesh) {
        double finTop = -0.5;
        double finBottom = -3.0;
        double finX = -0.3;
        double finChord = 0.75;
        mesh.quad(
                point(finX + finChord, finTop, 0.06), point(finX - finChord, finTop, 0.06),
                point(finX - finChord * 0.45, finBottom, 0.06),
                point(finX + finChord * 0.45, finBottom, 0.06), BoatMesh.RIG);
        mesh.quad(
                point(finX - finChord, finTop, -0.06), point(finX + finChord, finTop, -0.06),
                point(finX + finChord * 0.45, finBottom, -0.06),
                point(finX - finChord * 0.45, finBottom, -0.06), BoatMesh.RIG);

        double rudderX = -length * 0.44;
        mesh.quad(
                point(rudderX + 0.35, -0.4, 0.04), point(rudderX - 0.35, -0.4, 0.04),
                point(rudderX - 0.25, -2.1, 0.04), point(rudderX + 0.25, -2.1, 0.04),
                BoatMesh.RIG);
        mesh.quad(
                point(rudderX - 0.35, -0.4, -0.04), point(rudderX + 0.35, -0.4, -0.04),
                point(rudderX + 0.25, -2.1, -0.04), point(rudderX - 0.25, -2.1, -0.04),
                BoatMesh.RIG);
    }

    /** Mast and forestay. The boom is built with the sails, because it swings. */
    private void rig(BoatMesh.Builder mesh) {
        double mastRadius = 0.14;
        int sides = 6;
        for (int s = 0; s < sides; s++) {
            double a0 = (s / (double) sides) * Math.PI * 2;
            double a1 = ((s + 1) / (double) sides) * Math.PI * 2;
            // Tapered: a spar is thinner at the head than at the partners.
            mesh.quad(
                    point(mastX + Math.cos(a0) * mastRadius, mastBase,
                            Math.sin(a0) * mastRadius),
                    point(mastX + Math.cos(a1) * mastRadius, mastBase,
                            Math.sin(a1) * mastRadius),
                    point(mastX + Math.cos(a1) * mastRadius * 0.4, mastHeight,
                            Math.sin(a1) * mastRadius * 0.4),
                    point(mastX + Math.cos(a0) * mastRadius * 0.4, mastHeight,
                            Math.sin(a0) * mastRadius * 0.4),
                    BoatMesh.RIG);
        }

        // Forestay, stemhead to hounds. One thin quad, and it earns its place: it is
        // the line the headsail's luff sits on, and without it the jib looks like it
        // is hanging in mid air.
        mesh.quad(
                point(stemX, stemY, 0.035), point(mastX, houndsY, 0.035),
                point(mastX, houndsY, -0.035), point(stemX, stemY, -0.035), BoatMesh.RIG);
    }

    // --- sails --------------------------------------------------------------

    private static final int SAIL_ROWS = 10;
    private static final int SAIL_COLS = 8;

    /** An edge of a sail, as a function of height fraction, returning x and y. */
    private interface Edge {
        double[] at(double v);
    }

    /**
     * Lofts a cambered sail.
     *
     * <p>A sail is a surface between two edges - the luff, fixed to a spar or a stay,
     * and the leech, which is not - so it is described by where those two run and how
     * deep the section between them is. The camber is a circular arc, deepest around
     * a third of the way aft, which is where a sail's draft actually sits.
     */
    private static void loftSail(BoatMesh.Builder mesh, Edge luff, Edge leech,
            double angle, double draft) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        for (int r = 0; r < SAIL_ROWS; r++) {
            for (int c = 0; c < SAIL_COLS; c++) {
                double v0 = r / (double) SAIL_ROWS;
                double v1 = (r + 1) / (double) SAIL_ROWS;
                double u0 = c / (double) SAIL_COLS;
                double u1 = (c + 1) / (double) SAIL_COLS;
                mesh.quad(
                        sailPoint(luff, leech, u0, v0, cos, sin, draft),
                        sailPoint(luff, leech, u1, v0, cos, sin, draft),
                        sailPoint(luff, leech, u1, v1, cos, sin, draft),
                        sailPoint(luff, leech, u0, v1, cos, sin, draft),
                        BoatMesh.SAIL);
            }
        }
    }

    private static double[] sailPoint(Edge luff, Edge leech, double u, double v,
            double cos, double sin, double draft) {
        double[] l = luff.at(v);
        double[] t = leech.at(v);
        double chord = l[0] - t[0];
        double along = u * chord;
        double camber = draft * chord * Math.sin(Math.PI * Math.pow(u, 0.8));
        return new double[] {
            l[0] - along * cos + camber * sin,
            l[1] + (t[1] - l[1]) * u,
            along * sin + camber * cos,
        };
    }

    /**
     * Builds the sail plan - mainsail, headsail and boom - as one mesh.
     *
     * <p>Both sails are drawn because a sloop under main alone does not read as a
     * sailing boat: the shape of the rig <em>is</em> the two overlapping triangles,
     * and a boat close-hauled with a bare foretriangle looks like it has lost
     * something. The jib is sheeted inside the main, which is what a jib always is -
     * it works in the main's upwash and stalls if it is eased as far.
     *
     * @param sheetAngle boom angle from the centreline, radians, positive to port
     * @param draft      maximum camber as a fraction of chord
     */
    public BoatMesh sails(double sheetAngle, double draft) {
        BoatMesh.Builder mesh = new BoatMesh.Builder();
        double gooseneck = mastBase + 1.3;

        // Mainsail: luff up the mast from the gooseneck, leech from the boom end to
        // the head. A modern main keeps a lot of area high up - the leech falls away
        // far less than a classic triangular sail's, which is the square-top look.
        Edge mainLuff = v -> new double[] {mastX, gooseneck + v * (mastHeight - gooseneck)};
        Edge mainLeech = v -> {
            double chord = (mastX - boomEnd) * (1 - v * 0.62);
            return new double[] {mastX - chord, gooseneck + v * (mastHeight - gooseneck)};
        };
        loftSail(mesh, mainLuff, mainLeech, sheetAngle, draft);

        boom(mesh, sheetAngle, gooseneck);

        // Headsail: luff along the forestay, leech from the clew up to the same head.
        double jibAngle = sheetAngle * 0.55;
        double clewX = mastX + 0.9;
        double clewY = mastBase + 3.6;
        Edge jibLuff = v -> new double[] {
            stemX + (mastX - stemX) * v, stemY + (houndsY - stemY) * v};
        Edge jibLeech = v -> new double[] {
            clewX + (mastX - clewX) * v, clewY + (houndsY - clewY) * v};
        loftSail(mesh, jibLuff, jibLeech, jibAngle, draft * 0.85);

        return mesh.build();
    }

    /** A spar from the gooseneck aft along the foot, swung to the sheet angle. */
    private void boom(BoatMesh.Builder mesh, double sheetAngle, double gooseneck) {
        double boomLength = mastX - boomEnd;
        double cos = Math.cos(sheetAngle);
        double sin = Math.sin(sheetAngle);
        mesh.quad(
                boomPoint(0, 0.08, 0, cos, sin, gooseneck),
                boomPoint(boomLength, 0.08, 0.15, cos, sin, gooseneck),
                boomPoint(boomLength, -0.08, 0.15, cos, sin, gooseneck),
                boomPoint(0, -0.08, 0, cos, sin, gooseneck), BoatMesh.RIG);
        mesh.quad(
                boomPoint(0, -0.08, -0.14, cos, sin, gooseneck),
                boomPoint(boomLength, -0.08, 0.01, cos, sin, gooseneck),
                boomPoint(boomLength, 0.08, 0.01, cos, sin, gooseneck),
                boomPoint(0, 0.08, -0.14, cos, sin, gooseneck), BoatMesh.RIG);
    }

    private double[] boomPoint(double along, double side, double lift,
            double cos, double sin, double gooseneck) {
        return new double[] {
            mastX - along * cos - side * sin,
            gooseneck - 0.16 + lift,
            along * sin - side * cos,
        };
    }
}
