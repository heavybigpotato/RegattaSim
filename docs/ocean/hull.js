// A 40 ft racing hull, generated rather than modelled.
//
// There is no glTF loader here on purpose: the asset pipeline is Phase 6 work and
// this needs a boat now. A hull is a loft between stations, which is how they are
// actually drawn, so generating one is a few lines of curve evaluation and gives
// something that reads correctly from every angle without shipping a single byte
// of geometry.
//
// Boat-local axes match the physics: +X is the bow, +Z is port, +Y is up. That
// last one follows from starboard being 90 degrees clockwise from the bow, which
// is what SailingBoat samples for roll.

const MATERIAL = { HULL: 0, DECK: 1, RIG: 2, SAIL: 3 };

/**
 * How much higher the deck is on the centreline than at the sheer, metres.
 *
 * Every boat's deck is arched. It sheds water, it is stiffer than a flat plate
 * for the same weight, and - the reason it is here - a flat deck at maximum beam
 * renders as one large parallelogram that reads as a raft with a mast on it. The
 * crown is what tells the eye the deck is a surface rather than a lid.
 */
const DECK_CROWN = 0.18;

/**
 * Half-beam at a station, 0 at the bow and nearly full at the transom.
 *
 * A Class40 carries its beam a long way aft - maximum around two thirds back,
 * and a transom still at nine tenths of it - which is what lets the boat plane.
 * A cruising hull would taper to a fine stern instead, and the difference is
 * most of what makes these two types recognisable from astern.
 */
function halfWidth(t, halfBeam) {
  // Smooth all the way, deliberately. An earlier version clamped the forward
  // curve at the point of maximum beam and tapered aft of it, which is easy to
  // read but leaves a slope discontinuity there - and a flat-shaded hull renders
  // that as a hard crease running right down the topsides.
  return halfBeam * Math.sin(Math.PI * 0.5 * Math.pow(t, 0.8)) * (1 - 0.1 * Math.pow(t, 2.5));
}

/**
 * Deck height above the waterline: high at the bow, lowest amidships.
 *
 * These are real freeboards - about 1.85 m at the stem, 1.35 amidships, 1.25 at
 * the transom - and they matter more than they look. The first version of this
 * function gave the boat 0.7 m of topsides, which is a dinghy's, and in a four
 * metre sea the deck was under water in every frame: the hull simply could not
 * be seen. A boat's freeboard is what keeps the sea out, and getting it wrong is
 * visible immediately.
 */
function sheer(t) {
  return 1.14 + 0.72 * Math.pow(1 - t, 1.8) + 0.10 * t * t;
}

/**
 * Canoe body depth below the waterline: zero at the stem, deepest by a third of
 * the way aft, and still immersed at the transom.
 *
 * The transom is the point. Returning to zero depth aft - which a single sine
 * over the length does - draws a boat whose stern comes to a knife edge at the
 * waterline, and no modern racing hull is shaped that way; they carry their
 * sections aft to a wide, shallow, immersed transom.
 */
function keelDepth(t) {
  return -0.93 * (1 - Math.exp(-t / 0.22)) * (1 - 0.42 * t);
}

function push(arrays, x, y, z, nx, ny, nz, material) {
  arrays.positions.push(x, y, z);
  arrays.normals.push(nx, ny, nz);
  arrays.materials.push(material);
  return arrays.positions.length / 3 - 1;
}

function faceNormal(a, b, c) {
  const ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
  const vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
  const nx = uy * vz - uz * vy;
  const ny = uz * vx - ux * vz;
  const nz = ux * vy - uy * vx;
  const l = Math.hypot(nx, ny, nz) || 1;
  return [nx / l, ny / l, nz / l];
}

/** Adds a flat-shaded triangle. Flat shading suits a hard-chined racing hull. */
function triangle(arrays, a, b, c, material) {
  const n = faceNormal(a, b, c);
  const i0 = push(arrays, a[0], a[1], a[2], n[0], n[1], n[2], material);
  const i1 = push(arrays, b[0], b[1], b[2], n[0], n[1], n[2], material);
  const i2 = push(arrays, c[0], c[1], c[2], n[0], n[1], n[2], material);
  arrays.indices.push(i0, i1, i2);
}

function quad(arrays, a, b, c, d, material) {
  triangle(arrays, a, b, c, material);
  triangle(arrays, a, c, d, material);
}

/**
 * Builds the boat.
 *
 * @param length overall length, metres
 * @param beam   maximum beam, metres
 */
export function buildHull(length = 12.18, beam = 4.5) {
  const arrays = { positions: [], normals: [], materials: [], indices: [] };
  const halfBeam = beam * 0.5;
  const stations = 22;

  // Station 0 is the bow, station `stations` the transom. x runs from +L/2 to
  // -L/2 so the bow sits forward of the origin.
  //
  // Each station carries four points, not two: centreline, chine, deck edge -
  // because a hard chine is the defining line of this type. A section lofted
  // straight from the keel to the sheer draws a V, and a V seen from astern is a
  // pyramid rather than a boat.
  const station = (i) => {
    const t = i / stations;
    const hw = halfWidth(t, halfBeam);
    const keel = keelDepth(t);
    return {
      x: length * (0.5 - t),
      hw,
      deck: sheer(t),
      keel,
      // The turn of the bilge: well outboard and just under the waterline, so
      // the bottom is nearly flat and the topsides nearly upright.
      chineZ: hw * 0.86,
      chineY: keel * 0.2,
    };
  };

  /** Deck height at a distance `z` from the centreline, following the crown. */
  const deckAt = (s, z) => {
    const across = s.hw > 1e-6 ? z / s.hw : 0;
    return s.deck + DECK_CROWN * (1 - across * across);
  };

  for (let i = 0; i < stations; i++) {
    const a = station(i);
    const b = station(i + 1);

    // Starboard is -Z, so its panels are wound bow-to-stern along the lower edge;
    // port repeats them the other way round, which flips the normals outboard.
    for (const side of [-1, 1]) {
      const lower = side < 0
        ? [[a.x, a.keel, 0], [b.x, b.keel, 0], [b.x, b.chineY, side * b.chineZ],
           [a.x, a.chineY, side * a.chineZ]]
        : [[a.x, a.chineY, side * a.chineZ], [b.x, b.chineY, side * b.chineZ],
           [b.x, b.keel, 0], [a.x, a.keel, 0]];
      const upper = side < 0
        ? [[a.x, a.chineY, side * a.chineZ], [b.x, b.chineY, side * b.chineZ],
           [b.x, b.deck, side * b.hw], [a.x, a.deck, side * a.hw]]
        : [[a.x, a.deck, side * a.hw], [b.x, b.deck, side * b.hw],
           [b.x, b.chineY, side * b.chineZ], [a.x, a.chineY, side * a.chineZ]];
      quad(arrays, lower[0], lower[1], lower[2], lower[3], MATERIAL.HULL);
      quad(arrays, upper[0], upper[1], upper[2], upper[3], MATERIAL.HULL);
    }

    // Deck, in two halves so the crown has a ridge to run along.
    quad(arrays,
      [a.x, deckAt(a, 0), 0], [a.x, a.deck, -a.hw],
      [b.x, b.deck, -b.hw], [b.x, deckAt(b, 0), 0], MATERIAL.DECK);
    quad(arrays,
      [a.x, deckAt(a, 0), 0], [b.x, deckAt(b, 0), 0],
      [b.x, b.deck, b.hw], [a.x, a.deck, a.hw], MATERIAL.DECK);
  }

  // Transom: a flat plate closing the stern, which on this type is nearly the
  // full beam and is a large part of how the boat reads from astern. Wound to
  // face aft, along -X.
  const stern = station(stations);
  const keelPoint = [stern.x, stern.keel, 0];
  const chineStarboard = [stern.x, stern.chineY, -stern.chineZ];
  const chinePort = [stern.x, stern.chineY, stern.chineZ];
  const deckStarboard = [stern.x, stern.deck, -stern.hw];
  const deckPort = [stern.x, stern.deck, stern.hw];
  const crown = [stern.x, deckAt(stern, 0), 0];
  // Split down the centreline so the top edge follows the deck's crown; the two
  // halves share the keel-to-crown line and together close the whole outline.
  quad(arrays, chinePort, deckPort, crown, keelPoint, MATERIAL.HULL);
  quad(arrays, keelPoint, crown, deckStarboard, chineStarboard, MATERIAL.HULL);

  // --- coachroof ------------------------------------------------------------
  // A bare deck plate reads as a barge from any angle. A low trunk is what
  // breaks the plane, and it is also the thing the eye measures the sheerline
  // against - without it there is nothing in the middle of the boat to judge
  // the curve of the deck edge by.
  const roofFirst = Math.round(stations * 0.28);
  const roofLast = Math.round(stations * 0.74);
  const roof = (i) => {
    const s = station(i);
    const f = (i - roofFirst) / (roofLast - roofFirst);
    const hw = s.hw * (0.52 + 0.13 * f);
    // Seated on the crowned deck at its own half-width, or it would float clear
    // of the deck on one side and sink into it on the other.
    const base = deckAt(s, hw);
    return {
      x: s.x,
      hw,
      base,
      // Wedge-shaped, low at the forward end and highest at the companionway,
      // which is where the crew needs the headroom.
      top: base + 0.16 + 0.42 * f,
    };
  };

  for (let i = roofFirst; i < roofLast; i++) {
    const a = roof(i);
    const b = roof(i + 1);
    // Starboard side, then port wound the other way, same as the topsides.
    quad(arrays,
      [a.x, a.base, -a.hw], [b.x, b.base, -b.hw],
      [b.x, b.top, -b.hw], [a.x, a.top, -a.hw], MATERIAL.DECK);
    quad(arrays,
      [a.x, a.top, a.hw], [b.x, b.top, b.hw],
      [b.x, b.base, b.hw], [a.x, a.base, a.hw], MATERIAL.DECK);
    quad(arrays,
      [a.x, a.top, a.hw], [a.x, a.top, -a.hw],
      [b.x, b.top, -b.hw], [b.x, b.top, b.hw], MATERIAL.DECK);
  }

  const roofFront = roof(roofFirst);
  const roofBack = roof(roofLast);
  quad(arrays,
    [roofFront.x, roofFront.base, roofFront.hw], [roofFront.x, roofFront.top, roofFront.hw],
    [roofFront.x, roofFront.top, -roofFront.hw], [roofFront.x, roofFront.base, -roofFront.hw],
    MATERIAL.DECK);
  // The aft face is the companionway bulkhead, so it faces the cockpit.
  quad(arrays,
    [roofBack.x, roofBack.base, -roofBack.hw], [roofBack.x, roofBack.top, -roofBack.hw],
    [roofBack.x, roofBack.top, roofBack.hw], [roofBack.x, roofBack.base, roofBack.hw],
    MATERIAL.RIG);

  // --- keel and rudder ------------------------------------------------------
  const finTop = -0.5;
  const finBottom = -3.0;
  const finX = -0.3;
  const finChord = 0.75;
  quad(arrays,
    [finX + finChord, finTop, 0.06], [finX - finChord, finTop, 0.06],
    [finX - finChord * 0.45, finBottom, 0.06], [finX + finChord * 0.45, finBottom, 0.06],
    MATERIAL.RIG);
  quad(arrays,
    [finX - finChord, finTop, -0.06], [finX + finChord, finTop, -0.06],
    [finX + finChord * 0.45, finBottom, -0.06], [finX - finChord * 0.45, finBottom, -0.06],
    MATERIAL.RIG);

  const rudderX = -length * 0.44;
  quad(arrays,
    [rudderX + 0.35, -0.4, 0.04], [rudderX - 0.35, -0.4, 0.04],
    [rudderX - 0.25, -2.1, 0.04], [rudderX + 0.25, -2.1, 0.04], MATERIAL.RIG);
  quad(arrays,
    [rudderX - 0.35, -0.4, -0.04], [rudderX + 0.35, -0.4, -0.04],
    [rudderX + 0.25, -2.1, -0.04], [rudderX - 0.25, -2.1, -0.04], MATERIAL.RIG);

  // --- rig ------------------------------------------------------------------
  const mastX = length * 0.12;
  const mastHeight = 18.5;
  const mastBase = sheer(0.5 - mastX / length) - 0.1;
  const mastRadius = 0.14;
  const sides = 6;
  for (let s = 0; s < sides; s++) {
    const a0 = (s / sides) * Math.PI * 2;
    const a1 = ((s + 1) / sides) * Math.PI * 2;
    // Tapered: a spar is thinner at the head than at the partners.
    quad(arrays,
      [mastX + Math.cos(a0) * mastRadius, mastBase, mastX * 0 + Math.sin(a0) * mastRadius],
      [mastX + Math.cos(a1) * mastRadius, mastBase, Math.sin(a1) * mastRadius],
      [mastX + Math.cos(a1) * mastRadius * 0.4, mastHeight, Math.sin(a1) * mastRadius * 0.4],
      [mastX + Math.cos(a0) * mastRadius * 0.4, mastHeight, Math.sin(a0) * mastRadius * 0.4],
      MATERIAL.RIG);
  }

  // The boom is not built here. It swings with the sheet, so it belongs with the
  // sails, which are rebuilt when the trim changes - left in the hull mesh it sat
  // on the centreline while the mainsail swung away from it.
  const boomEnd = mastX - 5.2;

  // Forestay, from the stemhead to the hounds. It is one thin quad and it earns
  // its place: it is the line the headsail's luff sits on, and without it the jib
  // looks like it is hanging in mid air.
  const stemX = length * 0.47;
  const stemY = sheer(0.5 - stemX / length);
  const houndsY = mastHeight * 0.86;
  quad(arrays,
    [stemX, stemY, 0.035], [mastX, houndsY, 0.035],
    [mastX, houndsY, -0.035], [stemX, stemY, -0.035], MATERIAL.RIG);

  return {
    positions: new Float32Array(arrays.positions),
    normals: new Float32Array(arrays.normals),
    materials: new Float32Array(arrays.materials),
    indices: new Uint32Array(arrays.indices),
    mastX,
    mastBase,
    mastHeight,
    boomEnd,
    stemX,
    stemY,
    houndsY,
  };
}

const SAIL_ROWS = 10;
const SAIL_COLS = 8;

/**
 * Lofts a cambered sail into `arrays`.
 *
 * A sail is a surface between two edges - the luff, which is fixed to a spar or a
 * stay, and the leech, which is not - so it is described by where those two edges
 * run and how deep the section between them is. The camber is a circular arc,
 * deepest around a third of the way aft, which is where a sail's draft actually
 * sits.
 *
 * @param luff  (u=0) edge as a function of height fraction, returning [x, y]
 * @param leech (u=1) edge as a function of height fraction, returning [x, y]
 * @param angle rotation from the centreline, radians, positive to port
 * @param draft maximum camber as a fraction of chord
 */
function loftSail(arrays, luff, leech, angle, draft) {
  const point = (u, v) => {
    const [luffX, luffY] = luff(v);
    const [leechX, leechY] = leech(v);
    const chord = luffX - leechX;
    const along = u * chord;
    const camber = draft * chord * Math.sin(Math.PI * Math.pow(u, 0.8));
    return [
      luffX - along * Math.cos(angle) + camber * Math.sin(angle),
      luffY + (leechY - luffY) * u,
      along * Math.sin(angle) + camber * Math.cos(angle),
    ];
  };

  for (let r = 0; r < SAIL_ROWS; r++) {
    for (let c = 0; c < SAIL_COLS; c++) {
      const v0 = r / SAIL_ROWS;
      const v1 = (r + 1) / SAIL_ROWS;
      const u0 = c / SAIL_COLS;
      const u1 = (c + 1) / SAIL_COLS;
      quad(arrays, point(u0, v0), point(u1, v0), point(u1, v1), point(u0, v1), MATERIAL.SAIL);
    }
  }
}

/**
 * Builds the sail plan - mainsail and headsail - as one mesh, rebuilt when the
 * trim changes.
 *
 * The sails are generated separately from the hull because they move: they swing
 * with the sheets, take a different draft on each point of sail, and will need to
 * flog when they are let out too far.
 *
 * Both sails are drawn because a sloop under main alone does not read as a
 * sailing boat - the whole shape of the rig is the two overlapping triangles, and
 * a boat close-hauled with a bare foretriangle looks like it has lost something.
 * The jib is sheeted inside the main, which is what a jib always is: it works in
 * the main's upwash and stalls if it is eased as far.
 *
 * @param sheetAngle boom angle from the centreline, radians, positive to port
 * @param draft      maximum camber as a fraction of chord
 */
export function buildSails(hull, sheetAngle, draft = 0.11) {
  const arrays = { positions: [], normals: [], materials: [], indices: [] };

  // Mainsail: luff up the mast from the gooseneck, leech from the boom end to
  // the head, with roach taken out as it climbs.
  const gooseneck = hull.mastBase + 1.3;
  const mainLuff = (v) => [hull.mastX, gooseneck + v * (hull.mastHeight - gooseneck)];
  const mainLeech = (v) => {
    // A modern main keeps a lot of area high up - the leech falls away far less
    // than a classic triangular sail's, which is the square-top look.
    const chord = (hull.mastX - hull.boomEnd) * (1 - v * 0.62);
    return [hull.mastX - chord, gooseneck + v * (hull.mastHeight - gooseneck)];
  };
  loftSail(arrays, mainLuff, mainLeech, sheetAngle, draft);

  // Boom, swung to the same angle: a spar from the gooseneck aft along the foot.
  const boomLength = hull.mastX - hull.boomEnd;
  const boomPoint = (along, side, lift) => [
    hull.mastX - along * Math.cos(sheetAngle) - side * Math.sin(sheetAngle),
    gooseneck - 0.16 + lift,
    along * Math.sin(sheetAngle) - side * Math.cos(sheetAngle),
  ];
  quad(arrays,
    boomPoint(0, 0.08, 0), boomPoint(boomLength, 0.08, 0.15),
    boomPoint(boomLength, -0.08, 0.15), boomPoint(0, -0.08, 0), MATERIAL.RIG);
  quad(arrays,
    boomPoint(0, -0.08, -0.14), boomPoint(boomLength, -0.08, 0.01),
    boomPoint(boomLength, 0.08, 0.01), boomPoint(0, 0.08, -0.14), MATERIAL.RIG);

  // Headsail: luff along the forestay, leech from the clew up to the same head.
  const jibAngle = sheetAngle * 0.55;
  const clewX = hull.mastX + 0.9;
  const clewY = hull.mastBase + 3.6;
  const jibLuff = (v) => [
    hull.stemX + (hull.mastX - hull.stemX) * v,
    hull.stemY + (hull.houndsY - hull.stemY) * v,
  ];
  const jibLeech = (v) => [
    clewX + (hull.mastX - clewX) * v,
    clewY + (hull.houndsY - clewY) * v,
  ];
  loftSail(arrays, jibLuff, jibLeech, jibAngle, draft * 0.85);

  return {
    positions: new Float32Array(arrays.positions),
    normals: new Float32Array(arrays.normals),
    materials: new Float32Array(arrays.materials),
    indices: new Uint32Array(arrays.indices),
  };
}
