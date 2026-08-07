package com.bluemeridian.core.boat;

import static com.bluemeridian.core.boat.BoatMesh.at;

/**
 * A 40 ft racing boat, generated rather than modelled.
 *
 * <p>There is no glTF loader here on purpose: the asset pipeline is Phase 6 work
 * and this needs a boat now. A hull <em>is</em> a loft between stations - that is
 * literally how they are drawn - so generating one is curve evaluation, and it
 * gives something that reads correctly from every angle without shipping a byte of
 * geometry.
 *
 * <p>The numbers are typical of the type rather than taken from any particular
 * design: overall length, beam, freeboard, canoe body depth, rig height and the
 * usual deck layout. Nothing here is a scan of a real yacht and nothing carries a
 * class's lines.
 *
 * <h2>What makes it read as a boat</h2>
 *
 * <p>Three things, and none of them is polygon count.
 *
 * <p><b>Sections, not panels.</b> Each station carries five points from the
 * centreline to the sheer, so the bottom rolls into the topsides through a turn of
 * the bilge instead of meeting them at a fold. The chine is still there - it is
 * the defining line of the type - but it is a narrow band between two smooth
 * surfaces rather than a single crease, which is what catches a highlight the way
 * a real one does.
 *
 * <p><b>Smoothing groups.</b> Bottom, chine, topsides, deck and each spar are
 * separate groups, so every edge is hard or soft according to what it actually is.
 * Flat-shading everything gives a paper model; smoothing everything rounds off the
 * sheer and the chine and the boat stops being that type of boat.
 *
 * <p><b>Silhouette detail.</b> Stanchions, lifelines, spreaders, shrouds and a
 * pushpit cost almost nothing and are most of what the eye uses at chase-camera
 * distance. A bare hull with a stick in it reads as a toy at any polygon count.
 */
public final class HullLoft {

    /** Stations along the hull. Enough that the sheer reads as a curve, not a chain. */
    private static final int STATIONS = 44;

    /**
     * How much higher the deck is on the centreline than at the sheer, metres.
     *
     * <p>Every boat's deck is arched. It sheds water, it is stiffer than a flat
     * plate for the same weight, and - the reason it is here - a flat deck at
     * maximum beam renders as one large parallelogram that reads as a raft with a
     * mast on it. The crown tells the eye the deck is a surface, not a lid.
     */
    private static final double DECK_CROWN = 0.18;

    /** Height of the toerail above the deck edge, metres. */
    private static final double TOERAIL = 0.075;

    // Smoothing groups. Distinct values are hard edges; shared values are soft.
    private static final int G_BOTTOM = 1;
    private static final int G_CHINE = 2;
    private static final int G_TOPSIDES = 3;
    private static final int G_DECK = 4;
    private static final int G_TOERAIL = 5;
    private static final int G_TRANSOM = 6;
    private static final int G_ROOF = 7;
    private static final int G_COCKPIT = 8;
    private static final int G_MAST = 9;
    private static final int G_BOOM = 10;
    private static final int G_FIN = 11;
    private static final int G_HARDWARE = 12;
    private static final int G_SAIL_MAIN = 13;
    private static final int G_SAIL_JIB = 14;
    private static final int G_WIRE = 15;

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
        // The bowsprit carries the tack forward of the stem, which is what this type
        // does and what gives the sail plan its length.
        this.stemX = length * 0.62;
        this.stemY = sheer(0.0) - 0.55;
        this.houndsY = mastHeight * 0.86;
        this.hull = buildHull();
    }

    /** The hull, deck, coachroof, appendages, standing rig and deck gear. */
    public BoatMesh hull() {
        return hull;
    }

    // --- the curves ---------------------------------------------------------

    private static double beamShape(double t) {
        // Smooth all the way, deliberately. An earlier version clamped the forward
        // curve at the point of maximum beam and tapered aft of it, which is easier
        // to read but leaves a slope discontinuity there - and a hull renders that as
        // a hard crease running down the topsides.
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
     * and the hull could not be seen at all.
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
     * waterline, and no modern racing hull is shaped that way.
     */
    private static double keelDepth(double t) {
        return -0.93 * (1 - Math.exp(-t / 0.22)) * (1 - 0.42 * t);
    }

    /**
     * How sharp the chine is at a station, 0 rounded and 1 hard.
     *
     * <p>Soft forward, hard aft. That is what these hulls do: the chine fades out
     * before it reaches the stem, because a hard corner in the entry slams, and it
     * is crisp aft where it carries the boat when she heels.
     */
    private static double chineSharpness(double t) {
        return Math.min(1.0, Math.max(0.0, (t - 0.18) / 0.42));
    }

    /**
     * One transverse section, as five points from the centreline out to the sheer.
     *
     * <p>Returned as a flat array of (y, z) pairs so a station is one allocation:
     * keel, garboard, chine lower, chine upper, sheer.
     */
    private double[] section(double t) {
        double hw = halfWidth(t);
        double keel = keelDepth(t);
        double sharp = chineSharpness(t);

        // The chine sits well outboard and just under the waterline, so the bottom is
        // nearly flat and the topsides nearly upright. Its band opens up as the chine
        // hardens; forward, where it is soft, the two points nearly coincide and the
        // section rolls through instead.
        double band = 0.02 + 0.10 * sharp;
        double chineZ = hw * 0.86;
        double chineY = keel * 0.2;

        return new double[] {
            keel, 0,                                        // centreline
            keel * 0.72, chineZ * 0.55,                     // garboard
            chineY - band * 0.35, chineZ,                   // chine, lower lip
            chineY + band * 0.65, chineZ * (1 - 0.012 * sharp), // chine, upper lip
            sheer(t), hw,                                   // deck edge
        };
    }

    private double stationX(int i) {
        return length * (0.5 - i / (double) STATIONS);
    }

    /** Deck height at a distance {@code z} from the centreline, following the crown. */
    private static double deckAt(double sheerHeight, double halfWidthHere, double z) {
        double across = halfWidthHere > 1e-6 ? z / halfWidthHere : 0;
        return sheerHeight + DECK_CROWN * (1 - across * across);
    }

    // --- the loft -----------------------------------------------------------

    private BoatMesh buildHull() {
        BoatMesh.Builder mesh = new BoatMesh.Builder();
        shell(mesh);
        deck(mesh);
        transom(mesh);
        coachroof(mesh);
        cockpit(mesh);
        appendages(mesh);
        rig(mesh);
        deckGear(mesh);
        return mesh.build();
    }

    /**
     * The outer skin, in three bands: bottom, chine, topsides.
     *
     * <p>Each band is its own smoothing group, so the two chine lips are hard edges
     * and everything between them is smooth. Both sides are lofted in the same pass
     * with the winding mirrored, which is what keeps the normals outboard.
     */
    private void shell(BoatMesh.Builder mesh) {
        // Surface coordinates run in metres: u along the hull from the stem, v
        // around the section from the keel. Girth is accumulated as the section is
        // walked so a procedural detail keeps its size wherever it lands.
        for (int band = 0; band < 3; band++) {
            mesh.material(band == 0 ? BoatMesh.BOTTOM : BoatMesh.TOPSIDES);
            mesh.smoothing(band == 0 ? G_BOTTOM : band == 1 ? G_CHINE : G_TOPSIDES);
            for (int i = 0; i < STATIONS; i++) {
                double ta = i / (double) STATIONS;
                double tb = (i + 1) / (double) STATIONS;
                double[] a = section(ta);
                double[] b = section(tb);
                double xa = stationX(i);
                double xb = stationX(i + 1);
                for (int side = -1; side <= 1; side += 2) {
                    // Bands are two adjacent section points; the chine band is the
                    // middle one, which is why it is narrow.
                    int lo = band == 0 ? 0 : band + 1;
                    int hi = band == 0 ? 2 : band + 2;
                    double[] al = point(xa, a, lo, side, ta);
                    double[] ah = point(xa, a, hi, side, ta);
                    double[] bl = point(xb, b, lo, side, tb);
                    double[] bh = point(xb, b, hi, side, tb);
                    if (band == 0) {
                        // The bottom is two quads, not one, so the garboard curve is
                        // actually in the mesh rather than chorded across.
                        double[] am = point(xa, a, 1, side, ta);
                        double[] bm = point(xb, b, 1, side, tb);
                        wind(mesh, side, al, bl, bm, am);
                        wind(mesh, side, am, bm, bh, ah);
                    } else {
                        wind(mesh, side, al, bl, bh, ah);
                    }
                }
            }
        }
    }

    /** One section point, on the given side, with its surface coordinate. */
    private double[] point(double x, double[] s, int index, int side, double t) {
        double y = s[index * 2];
        double z = s[index * 2 + 1] * side;
        // Girth from the keel to this point, walked along the section.
        double girth = 0;
        for (int k = 1; k <= index; k++) {
            girth += Math.hypot(s[k * 2] - s[(k - 1) * 2], s[k * 2 + 1] - s[(k - 1) * 2 + 1]);
        }
        return at(x, y, z, length * (0.5 - t) * -1, girth);
    }

    /**
     * Adds a quad with the winding that puts its normal outboard on the given side.
     *
     * <p>Starboard is -Z, so its panels are wound bow-to-stern along the lower edge;
     * port repeats them the other way round, which flips the normal.
     */
    private static void wind(BoatMesh.Builder mesh, int side,
            double[] a, double[] b, double[] c, double[] d) {
        if (side < 0) {
            mesh.quad(a, b, c, d);
        } else {
            mesh.quad(d, c, b, a);
        }
    }

    /** The deck, crowned, with a toerail standing above its edge. */
    private void deck(BoatMesh.Builder mesh) {
        mesh.material(BoatMesh.DECK).smoothing(G_DECK);
        int across = 6;
        for (int i = 0; i < STATIONS; i++) {
            double ta = i / (double) STATIONS;
            double tb = (i + 1) / (double) STATIONS;
            double xa = stationX(i);
            double xb = stationX(i + 1);
            double ha = halfWidth(ta);
            double hb = halfWidth(tb);
            double sa = sheer(ta);
            double sb = sheer(tb);
            for (int k = 0; k < across; k++) {
                // Runs from the port edge across to starboard, so one loop covers the
                // whole deck and the crown is sampled rather than chorded.
                double f0 = 1 - 2.0 * k / across;
                double f1 = 1 - 2.0 * (k + 1) / across;
                mesh.quad(
                        deckPoint(xa, ha, sa, f0), deckPoint(xb, hb, sb, f0),
                        deckPoint(xb, hb, sb, f1), deckPoint(xa, ha, sa, f1));
            }
        }

        // Toerail: a low wall around the deck edge. It is two quads per station and
        // it is what stops the deck ending in a knife edge against the sky.
        mesh.smoothing(G_TOERAIL);
        for (int i = 0; i < STATIONS; i++) {
            double ta = i / (double) STATIONS;
            double tb = (i + 1) / (double) STATIONS;
            double xa = stationX(i);
            double xb = stationX(i + 1);
            double ha = halfWidth(ta);
            double hb = halfWidth(tb);
            double sa = sheer(ta);
            double sb = sheer(tb);
            for (int side = -1; side <= 1; side += 2) {
                double[] outerA = at(xa, sa, ha * side, xa, 0);
                double[] outerB = at(xb, sb, hb * side, xb, 0);
                double[] topA = at(xa, sa + TOERAIL, ha * side * 0.995, xa, TOERAIL);
                double[] topB = at(xb, sb + TOERAIL, hb * side * 0.995, xb, TOERAIL);
                double[] innerA = at(xa, sa + TOERAIL * 0.8, ha * side * 0.955, xa, TOERAIL * 2);
                double[] innerB = at(xb, sb + TOERAIL * 0.8, hb * side * 0.955, xb, TOERAIL * 2);
                wind(mesh, side, outerA, outerB, topB, topA);
                wind(mesh, side, topA, topB, innerB, innerA);
            }
        }
    }

    private double[] deckPoint(double x, double halfWidthHere, double sheerHeight,
            double fraction) {
        double z = halfWidthHere * fraction;
        return at(x, deckAt(sheerHeight, halfWidthHere, z), z, x, z);
    }

    /**
     * A flat plate closing the stern, which on this type is nearly the full beam and
     * is a large part of how the boat reads from astern. Wound to face aft, -X.
     */
    private void transom(BoatMesh.Builder mesh) {
        mesh.material(BoatMesh.TOPSIDES).smoothing(G_TRANSOM);
        double[] s = section(1.0);
        double x = stationX(STATIONS);
        double hw = halfWidth(1.0);
        double crownY = deckAt(sheer(1.0), hw, 0);

        // Walked as a fan from the centreline out to each deck corner, following the
        // same five section points the shell uses so the edges meet exactly.
        for (int side = -1; side <= 1; side += 2) {
            double[] centre = at(x, s[0], 0, 0, 0);
            for (int k = 0; k < 4; k++) {
                double[] lo = at(x, s[k * 2], s[k * 2 + 1] * side, s[k * 2 + 1] * side, s[k * 2]);
                double[] hi = at(x, s[(k + 1) * 2], s[(k + 1) * 2 + 1] * side,
                        s[(k + 1) * 2 + 1] * side, s[(k + 1) * 2]);
                if (side < 0) {
                    mesh.triangle(centre, lo, hi);
                } else {
                    mesh.triangle(centre, hi, lo);
                }
            }
            // Close the top: deck edge up to the crown on the centreline.
            double[] edge = at(x, s[8], s[9] * side, s[9] * side, s[8]);
            double[] crown = at(x, crownY, 0, 0, crownY);
            if (side < 0) {
                mesh.triangle(centre, edge, crown);
            } else {
                mesh.triangle(centre, crown, edge);
            }
        }
    }

    /**
     * A low trunk over the accommodation, with windows.
     *
     * <p>A bare deck plate reads as a barge from any angle. The trunk breaks the
     * plane, and it is also what the eye measures the sheerline against - without
     * something in the middle of the boat there is nothing to judge the curve of the
     * deck edge by. The windows are a separate material because a dark glass band is
     * the single most recognisable thing about a modern coachroof.
     */
    private void coachroof(BoatMesh.Builder mesh) {
        int first = (int) Math.round(STATIONS * 0.30);
        int last = (int) Math.round(STATIONS * 0.62);

        mesh.material(BoatMesh.DECK).smoothing(G_ROOF);
        for (int i = first; i < last; i++) {
            double[] a = roof(i, first, last);
            double[] b = roof(i + 1, first, last);
            for (int side = -1; side <= 1; side += 2) {
                // Side, in two bands so the window can be its own material.
                double glassLow = a[2] + (a[3] - a[2]) * 0.35;
                double glassLowB = b[2] + (b[3] - b[2]) * 0.35;
                double glassHigh = a[2] + (a[3] - a[2]) * 0.82;
                double glassHighB = b[2] + (b[3] - b[2]) * 0.82;
                mesh.material(BoatMesh.DECK);
                wind(mesh, side,
                        at(a[0], a[2], a[1] * side), at(b[0], b[2], b[1] * side),
                        at(b[0], glassLowB, b[1] * side), at(a[0], glassLow, a[1] * side));
                mesh.material(BoatMesh.WINDOW);
                wind(mesh, side,
                        at(a[0], glassLow, a[1] * side), at(b[0], glassLowB, b[1] * side),
                        at(b[0], glassHighB, b[1] * side), at(a[0], glassHigh, a[1] * side));
                mesh.material(BoatMesh.DECK);
                wind(mesh, side,
                        at(a[0], glassHigh, a[1] * side), at(b[0], glassHighB, b[1] * side),
                        at(b[0], b[3], b[1] * side), at(a[0], a[3], a[1] * side));
            }
            // Top, crowned like the deck so it does not read as a flat lid either.
            mesh.material(BoatMesh.DECK);
            for (int k = 0; k < 4; k++) {
                double f0 = 1 - 2.0 * k / 4;
                double f1 = 1 - 2.0 * (k + 1) / 4;
                mesh.quad(
                        roofTop(a, f0), roofTop(b, f0), roofTop(b, f1), roofTop(a, f1));
            }
        }

        double[] front = roof(first, first, last);
        double[] back = roof(last, first, last);
        mesh.material(BoatMesh.DECK);
        mesh.quad(
                at(front[0], front[2], front[1]), at(front[0], front[3], front[1]),
                at(front[0], front[3], -front[1]), at(front[0], front[2], -front[1]));
        // The aft face is the companionway bulkhead, facing the cockpit.
        mesh.material(BoatMesh.SPAR);
        mesh.quad(
                at(back[0], back[2], -back[1]), at(back[0], back[3], -back[1]),
                at(back[0], back[3], back[1]), at(back[0], back[2], back[1]));
    }

    /** One coachroof section as x, halfWidth, base, top. */
    private double[] roof(int i, int first, int last) {
        double t = i / (double) STATIONS;
        double f = (i - first) / (double) (last - first);
        double hw = halfWidth(t) * (0.50 + 0.14 * f);
        // Seated on the crowned deck at its own half-width, or it would float clear
        // of the deck on one side and sink into it on the other.
        double base = deckAt(sheer(t), halfWidth(t), hw);
        // Wedge-shaped, low forward and highest at the companionway, which is where
        // the crew needs the headroom.
        return new double[] {stationX(i), hw, base, base + 0.14 + 0.40 * f};
    }

    private static double[] roofTop(double[] r, double fraction) {
        double z = r[1] * fraction;
        double crown = 0.05 * (1 - fraction * fraction);
        return at(r[0], r[3] + crown, z, r[0], z);
    }

    /**
     * A recessed well aft, with a sole and coamings.
     *
     * <p>The cockpit is where the crew is, and even empty it is the feature that
     * says the deck is something people stand on rather than a lid. It is cut as a
     * simple box: the deck above already covers the whole beam, so this is drawn
     * inside it and the sole sits below.
     */
    private void cockpit(BoatMesh.Builder mesh) {
        int first = (int) Math.round(STATIONS * 0.64);
        int last = (int) Math.round(STATIONS * 0.93);
        double soleDrop = 0.52;

        mesh.material(BoatMesh.DECK).smoothing(G_COCKPIT);
        for (int i = first; i < last; i++) {
            double ta = i / (double) STATIONS;
            double tb = (i + 1) / (double) STATIONS;
            double xa = stationX(i);
            double xb = stationX(i + 1);
            double wa = halfWidth(ta) * 0.62;
            double wb = halfWidth(tb) * 0.62;
            double da = deckAt(sheer(ta), halfWidth(ta), wa);
            double db = deckAt(sheer(tb), halfWidth(tb), wb);

            // Sole.
            mesh.quad(
                    at(xa, da - soleDrop, wa, xa, wa), at(xb, db - soleDrop, wb, xb, wb),
                    at(xb, db - soleDrop, -wb, xb, -wb), at(xa, da - soleDrop, -wa, xa, -wa));
            // Coamings, both sides, facing inboard.
            for (int side = -1; side <= 1; side += 2) {
                wind(mesh, -side,
                        at(xa, da - soleDrop, wa * side), at(xb, db - soleDrop, wb * side),
                        at(xb, db, wb * side), at(xa, da, wa * side));
            }
        }

        // Forward bulkhead of the well, facing aft.
        double t = first / (double) STATIONS;
        double x = stationX(first);
        double w = halfWidth(t) * 0.62;
        double d = deckAt(sheer(t), halfWidth(t), w);
        mesh.quad(
                at(x, d - soleDrop, -w), at(x, d - soleDrop, w),
                at(x, d, w), at(x, d, -w));
    }

    /** Keel fin, bulb and rudder. */
    private void appendages(BoatMesh.Builder mesh) {
        mesh.material(BoatMesh.BOTTOM).smoothing(G_FIN);

        // Fin: a foil section, thin, swept slightly aft.
        double finX = -0.3;
        double finTop = -0.45;
        double finBottom = -2.85;
        foil(mesh, finX, finTop, finBottom, 0.78, 0.42, 0.075, 0.035);

        // Bulb: the lead at the bottom, which is a large part of the silhouette from
        // ahead and completely absent without it.
        double bulbY = finBottom - 0.1;
        int rings = 8;
        int around = 10;
        for (int r = 0; r < rings; r++) {
            double f0 = r / (double) rings;
            double f1 = (r + 1) / (double) rings;
            for (int k = 0; k < around; k++) {
                double a0 = k * 2 * Math.PI / around;
                double a1 = (k + 1) * 2 * Math.PI / around;
                mesh.quad(
                        bulbPoint(finX, bulbY, f0, a0), bulbPoint(finX, bulbY, f1, a0),
                        bulbPoint(finX, bulbY, f1, a1), bulbPoint(finX, bulbY, f0, a1));
            }
        }

        // Rudder, right aft and deeper than it looks from above.
        double rudderX = -length * 0.44;
        foil(mesh, rudderX, -0.35, -2.35, 0.36, 0.24, 0.05, 0.03);
    }

    /**
     * A symmetrical foil: a thin vertical blade with a rounded leading edge.
     *
     * <p>Four points around the section rather than two flat faces, so the leading
     * edge catches light instead of vanishing into a hairline.
     */
    private void foil(BoatMesh.Builder mesh, double x, double top, double bottom,
            double chordTop, double chordBottom, double thickTop, double thickBottom) {
        int spans = 6;
        for (int s = 0; s < spans; s++) {
            double f0 = s / (double) spans;
            double f1 = (s + 1) / (double) spans;
            for (int side = -1; side <= 1; side += 2) {
                double[] leadA = foilPoint(x, top, bottom, chordTop, chordBottom,
                        thickTop, thickBottom, f0, 0, side);
                double[] leadB = foilPoint(x, top, bottom, chordTop, chordBottom,
                        thickTop, thickBottom, f1, 0, side);
                double[] midA = foilPoint(x, top, bottom, chordTop, chordBottom,
                        thickTop, thickBottom, f0, 0.35, side);
                double[] midB = foilPoint(x, top, bottom, chordTop, chordBottom,
                        thickTop, thickBottom, f1, 0.35, side);
                double[] tailA = foilPoint(x, top, bottom, chordTop, chordBottom,
                        thickTop, thickBottom, f0, 1, side);
                double[] tailB = foilPoint(x, top, bottom, chordTop, chordBottom,
                        thickTop, thickBottom, f1, 1, side);
                wind(mesh, side, leadA, leadB, midB, midA);
                wind(mesh, side, midA, midB, tailB, tailA);
            }
        }
    }

    private double[] foilPoint(double x, double top, double bottom, double chordTop,
            double chordBottom, double thickTop, double thickBottom,
            double span, double along, int side) {
        double chord = chordTop + (chordBottom - chordTop) * span;
        double thick = thickTop + (thickBottom - thickTop) * span;
        double y = top + (bottom - top) * span;
        // Sweep: the tip trails the root, which is what every fin does.
        double sweep = -0.22 * span * chordTop;
        double px = x + sweep + chord * (0.5 - along);
        // A rough NACA-ish thickness distribution: fat a third back, fine at the tail.
        double halfThick = thick * Math.sin(Math.PI * Math.pow(Math.max(along, 1e-4), 0.55));
        return at(px, y, halfThick * side, span, along);
    }

    private double[] bulbPoint(double x, double y, double along, double around) {
        double lengthOfBulb = 1.55;
        double radius = 0.115 * Math.sin(Math.PI * Math.pow(along, 0.62));
        double px = x + lengthOfBulb * (0.42 - along);
        return at(px, y + Math.sin(around) * radius, Math.cos(around) * radius,
                along, around);
    }

    // --- rig ----------------------------------------------------------------

    /** Mast, spreaders, standing rigging and the bowsprit. */
    private void rig(BoatMesh.Builder mesh) {
        mesh.material(BoatMesh.SPAR).smoothing(G_MAST);

        // Mast: sixteen sides and a teardrop section, deeper fore-and-aft than it is
        // wide, tapered above the hounds. Round and smooth, so it takes a highlight
        // down its length instead of showing six flat strips.
        int around = 16;
        int spans = 10;
        for (int s = 0; s < spans; s++) {
            double f0 = s / (double) spans;
            double f1 = (s + 1) / (double) spans;
            for (int k = 0; k < around; k++) {
                double a0 = k * 2 * Math.PI / around;
                double a1 = (k + 1) * 2 * Math.PI / around;
                mesh.quad(
                        mastPoint(f0, a0), mastPoint(f1, a0),
                        mastPoint(f1, a1), mastPoint(f0, a1));
            }
        }

        // Spreaders, two sets, swept aft.
        mesh.smoothing(G_HARDWARE);
        double[] spreaderHeights = {0.42, 0.68};
        for (double h : spreaderHeights) {
            double y = mastBase + (mastHeight - mastBase) * h;
            double span = 1.05 * (1 - 0.25 * h);
            for (int side = -1; side <= 1; side += 2) {
                double tipZ = span * side;
                double tipX = mastX - 0.34 * span;
                bar(mesh, at(mastX, y, 0.05 * side), at(tipX, y + 0.06, tipZ), 0.035);
            }
        }

        // Standing rigging. Thin, dark, and worth every triangle: the shrouds and
        // the backstay are most of what tells the eye there is a rig here at all.
        mesh.material(BoatMesh.WIRE).smoothing(G_WIRE);
        double chainplateX = mastX - 0.15;
        for (int side = -1; side <= 1; side += 2) {
            double deckZ = halfWidth(0.5 - chainplateX / length) * 0.86;
            double deckY = sheer(0.5 - chainplateX / length);
            // Cap shroud, over both spreader tips to the hounds.
            double y1 = mastBase + (mastHeight - mastBase) * spreaderHeights[0];
            double y2 = mastBase + (mastHeight - mastBase) * spreaderHeights[1];
            double span1 = 1.05 * (1 - 0.25 * spreaderHeights[0]);
            double span2 = 1.05 * (1 - 0.25 * spreaderHeights[1]);
            bar(mesh, at(chainplateX, deckY, deckZ * side),
                    at(mastX - 0.34 * span1, y1 + 0.06, span1 * side), 0.018);
            bar(mesh, at(mastX - 0.34 * span1, y1 + 0.06, span1 * side),
                    at(mastX - 0.34 * span2, y2 + 0.06, span2 * side), 0.016);
            bar(mesh, at(mastX - 0.34 * span2, y2 + 0.06, span2 * side),
                    at(mastX, houndsY + 0.6, 0.03 * side), 0.014);
            // Lower shroud, straight to the first spreader root.
            bar(mesh, at(chainplateX - 0.5, deckY, deckZ * side * 0.98),
                    at(mastX, y1, 0.06 * side), 0.016);
        }
        // Backstay, masthead to transom.
        bar(mesh, at(mastX, mastHeight - 0.15, 0),
                at(stationX(STATIONS) + 0.1, sheer(1.0), 0), 0.016);
        // Forestay, stemhead to hounds. The headsail's luff sits on this line and
        // without it the jib looks like it is hanging in mid air.
        bar(mesh, at(stemX, stemY, 0), at(mastX, houndsY, 0), 0.018);

        // Bowsprit, carrying the tack forward of the stem.
        mesh.material(BoatMesh.SPAR).smoothing(G_HARDWARE);
        bar(mesh, at(length * 0.48, sheer(0.0) - 0.12, 0), at(stemX, stemY, 0), 0.055);
    }

    private double[] mastPoint(double span, double around) {
        double y = mastBase + (mastHeight - mastBase) * span;
        // Taper starts at the hounds; below that a modern section is near enough
        // constant, and tapering the whole length makes the mast look like a spike.
        double taper = span < 0.86 ? 1.0 : 1.0 - 0.45 * ((span - 0.86) / 0.14);
        double foreAft = 0.135 * taper;
        double athwart = 0.098 * taper;
        return at(mastX + Math.cos(around) * foreAft, y, Math.sin(around) * athwart,
                span, around);
    }

    /**
     * A square-section bar between two points, for spars and rigging.
     *
     * <p>Four sides rather than a cylinder: at the width these are drawn, the extra
     * faces of a round section are below a pixel, and a wire that is four triangles
     * instead of forty is a wire that can be afforded a dozen times over.
     */
    private static void bar(BoatMesh.Builder mesh, double[] from, double[] to,
            double radius) {
        double dx = to[0] - from[0];
        double dy = to[1] - from[1];
        double dz = to[2] - from[2];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) {
            return;
        }
        dx /= len;
        dy /= len;
        dz /= len;

        // Any two directions perpendicular to the bar will do; pick the one least
        // parallel to it so the cross product never collapses.
        double[] helper = Math.abs(dy) < 0.9 ? new double[] {0, 1, 0} : new double[] {1, 0, 0};
        double ax = dy * helper[2] - dz * helper[1];
        double ay = dz * helper[0] - dx * helper[2];
        double az = dx * helper[1] - dy * helper[0];
        double al = Math.sqrt(ax * ax + ay * ay + az * az);
        ax /= al;
        ay /= al;
        az /= al;
        double bx = dy * az - dz * ay;
        double by = dz * ax - dx * az;
        double bz = dx * ay - dy * ax;

        for (int k = 0; k < 4; k++) {
            double a0 = k * Math.PI / 2;
            double a1 = (k + 1) * Math.PI / 2;
            double c0 = Math.cos(a0) * radius;
            double s0 = Math.sin(a0) * radius;
            double c1 = Math.cos(a1) * radius;
            double s1 = Math.sin(a1) * radius;
            mesh.quad(
                    at(from[0] + ax * c0 + bx * s0, from[1] + ay * c0 + by * s0,
                            from[2] + az * c0 + bz * s0, 0, a0),
                    at(to[0] + ax * c0 + bx * s0, to[1] + ay * c0 + by * s0,
                            to[2] + az * c0 + bz * s0, len, a0),
                    at(to[0] + ax * c1 + bx * s1, to[1] + ay * c1 + by * s1,
                            to[2] + az * c1 + bz * s1, len, a1),
                    at(from[0] + ax * c1 + bx * s1, from[1] + ay * c1 + by * s1,
                            from[2] + az * c1 + bz * s1, 0, a1));
        }
    }

    /**
     * Stanchions, lifelines, pushpit and winches.
     *
     * <p>All silhouette. At any distance where the boat is worth looking at, the
     * line of stanchions and the wire between them does more for recognition than
     * another thousand triangles in the topsides would.
     */
    private void deckGear(BoatMesh.Builder mesh) {
        double height = 0.62;
        int count = 7;
        double[] xs = new double[count];
        double[] ys = new double[count];
        double[] zs = new double[count];

        mesh.material(BoatMesh.SPAR).smoothing(G_HARDWARE);
        for (int i = 0; i < count; i++) {
            double t = 0.12 + 0.72 * i / (double) (count - 1);
            xs[i] = length * (0.5 - t);
            ys[i] = sheer(t) + TOERAIL;
            zs[i] = halfWidth(t) * 0.95;
            for (int side = -1; side <= 1; side += 2) {
                bar(mesh, at(xs[i], ys[i], zs[i] * side),
                        at(xs[i], ys[i] + height, zs[i] * side * 0.98), 0.022);
            }
        }

        // Two lifelines between the stanchions, plus a run forward to the stem.
        mesh.material(BoatMesh.WIRE).smoothing(G_WIRE);
        for (double level : new double[] {0.55, 1.0}) {
            for (int side = -1; side <= 1; side += 2) {
                for (int i = 0; i < count - 1; i++) {
                    bar(mesh,
                            at(xs[i], ys[i] + height * level, zs[i] * side * 0.98),
                            at(xs[i + 1], ys[i + 1] + height * level, zs[i + 1] * side * 0.98),
                            0.012);
                }
                bar(mesh,
                        at(xs[0], ys[0] + height * level, zs[0] * side * 0.98),
                        at(length * 0.485, sheer(0.0) + height * level * 0.75, 0.02 * side),
                        0.012);
            }
        }

        // Pushpit: the rail around the stern, closing the line of stanchions.
        mesh.material(BoatMesh.SPAR).smoothing(G_HARDWARE);
        double sternX = stationX(STATIONS);
        double sternY = sheer(1.0) + TOERAIL;
        double sternZ = halfWidth(1.0) * 0.92;
        for (int side = -1; side <= 1; side += 2) {
            bar(mesh, at(sternX + 0.15, sternY, sternZ * side),
                    at(sternX + 0.15, sternY + height, sternZ * side), 0.024);
            bar(mesh, at(sternX + 0.15, sternY + height, sternZ * side),
                    at(xs[count - 1], ys[count - 1] + height, zs[count - 1] * side * 0.98),
                    0.02);
        }
        bar(mesh, at(sternX + 0.15, sternY + height, sternZ),
                at(sternX + 0.15, sternY + height, -sternZ), 0.02);

        // Primary winches, on the cockpit coaming where they belong.
        double winchT = 0.70;
        double winchX = length * (0.5 - winchT);
        double winchZ = halfWidth(winchT) * 0.66;
        double winchY = deckAt(sheer(winchT), halfWidth(winchT), winchZ);
        for (int side = -1; side <= 1; side += 2) {
            drum(mesh, winchX, winchY, winchZ * side, 0.115, 0.2);
            drum(mesh, winchX - 0.9, winchY, winchZ * side * 0.95, 0.09, 0.16);
        }
    }

    /** A winch drum: a short waisted cylinder. */
    private static void drum(BoatMesh.Builder mesh, double x, double y, double z,
            double radius, double height) {
        int around = 10;
        for (int k = 0; k < around; k++) {
            double a0 = k * 2 * Math.PI / around;
            double a1 = (k + 1) * 2 * Math.PI / around;
            double[] b0 = at(x + Math.cos(a0) * radius, y, z + Math.sin(a0) * radius, a0, 0);
            double[] b1 = at(x + Math.cos(a1) * radius, y, z + Math.sin(a1) * radius, a1, 0);
            double waist = radius * 0.82;
            double[] m0 = at(x + Math.cos(a0) * waist, y + height * 0.5,
                    z + Math.sin(a0) * waist, a0, height * 0.5);
            double[] m1 = at(x + Math.cos(a1) * waist, y + height * 0.5,
                    z + Math.sin(a1) * waist, a1, height * 0.5);
            double[] t0 = at(x + Math.cos(a0) * radius, y + height,
                    z + Math.sin(a0) * radius, a0, height);
            double[] t1 = at(x + Math.cos(a1) * radius, y + height,
                    z + Math.sin(a1) * radius, a1, height);
            mesh.quad(b0, b1, m1, m0);
            mesh.quad(m0, m1, t1, t0);
            mesh.triangle(t0, t1, at(x, y + height, z, 0, height));
        }
    }

    // --- sails --------------------------------------------------------------

    private static final int SAIL_ROWS = 18;
    private static final int SAIL_COLS = 14;

    /** An edge of a sail, as a function of height fraction, returning x and y. */
    private interface Edge {
        double[] at(double v);
    }

    /**
     * Lofts a cambered sail.
     *
     * <p>A sail is a surface between two edges - the luff, fixed to a spar or a
     * stay, and the leech, which is not - so it is described by where those two run
     * and how deep the section between them is. The camber is a circular arc,
     * deepest around a third of the way aft, which is where a sail's draft sits.
     *
     * <p>Twist is the thing that makes it a sail rather than a wing: the head is
     * always eased relative to the foot, because the apparent wind aloft is freer.
     * A sail without it looks like sheet metal.
     */
    private static void loftSail(BoatMesh.Builder mesh, Edge luff, Edge leech,
            double angle, double draft, double twist) {
        for (int r = 0; r < SAIL_ROWS; r++) {
            for (int c = 0; c < SAIL_COLS; c++) {
                double v0 = r / (double) SAIL_ROWS;
                double v1 = (r + 1) / (double) SAIL_ROWS;
                double u0 = c / (double) SAIL_COLS;
                double u1 = (c + 1) / (double) SAIL_COLS;
                mesh.quad(
                        sailPoint(luff, leech, u0, v0, angle, draft, twist),
                        sailPoint(luff, leech, u1, v0, angle, draft, twist),
                        sailPoint(luff, leech, u1, v1, angle, draft, twist),
                        sailPoint(luff, leech, u0, v1, angle, draft, twist));
            }
        }
    }

    private static double[] sailPoint(Edge luff, Edge leech, double u, double v,
            double angle, double draft, double twist) {
        double[] l = luff.at(v);
        double[] t = leech.at(v);
        double chord = l[0] - t[0];
        double along = u * chord;
        // Draft moves aft and shallows as it climbs, which is what a trimmed sail
        // does and what makes the leech fall open at the head.
        double depth = draft * (1 - 0.35 * v);
        double camber = depth * chord * Math.sin(Math.PI * Math.pow(u, 0.8 + 0.25 * v));
        double swing = angle * (1 + twist * v);
        double cos = Math.cos(swing);
        double sin = Math.sin(swing);
        return at(
                l[0] - along * cos + camber * sin,
                l[1] + (t[1] - l[1]) * u,
                along * sin + camber * cos,
                along, v);
    }

    /**
     * Builds the sail plan - mainsail, headsail and boom - as one mesh, rebuilt when
     * the trim changes.
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

        mesh.material(BoatMesh.SAIL).smoothing(G_SAIL_MAIN);
        Edge mainLuff = v -> new double[] {mastX, gooseneck + v * (mastHeight - gooseneck)};
        Edge mainLeech = v -> {
            // A modern main keeps a lot of area high up - the leech falls away far
            // less than a classic triangular sail's, which is the square-top look.
            double chord = (mastX - boomEnd) * (1 - v * 0.58 + 0.06 * Math.sin(Math.PI * v));
            return new double[] {mastX - chord, gooseneck + v * (mastHeight - gooseneck)};
        };
        loftSail(mesh, mainLuff, mainLeech, sheetAngle, draft, 0.55);

        mesh.material(BoatMesh.SPAR).smoothing(G_BOOM);
        boom(mesh, sheetAngle, gooseneck);

        mesh.material(BoatMesh.SAIL).smoothing(G_SAIL_JIB);
        double jibAngle = sheetAngle * 0.55;
        double clewX = mastX + 0.9;
        double clewY = mastBase + 3.6;
        Edge jibLuff = v -> new double[] {
            stemX + (mastX - stemX) * v, stemY + (houndsY - stemY) * v};
        Edge jibLeech = v -> new double[] {
            clewX + (mastX - clewX) * v, clewY + (houndsY - clewY) * v};
        loftSail(mesh, jibLuff, jibLeech, jibAngle, draft * 0.85, 0.42);

        return mesh.build();
    }

    /** A spar from the gooseneck aft along the foot, swung to the sheet angle. */
    private void boom(BoatMesh.Builder mesh, double sheetAngle, double gooseneck) {
        double boomLength = mastX - boomEnd;
        double cos = Math.cos(sheetAngle);
        double sin = Math.sin(sheetAngle);
        double[] root = new double[] {mastX, gooseneck - 0.13, 0};
        double[] tip = new double[] {
            mastX - boomLength * cos, gooseneck - 0.02, boomLength * sin};
        bar(mesh, at(root[0], root[1], root[2]), at(tip[0], tip[1], tip[2]), 0.075);
        // Vang, from the boom down to the mast heel.
        bar(mesh, at(mastX - boomLength * 0.22 * cos, gooseneck - 0.16,
                        boomLength * 0.22 * sin),
                at(mastX, mastBase + 0.15, 0), 0.035);
    }
}
