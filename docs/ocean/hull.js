// The boat's geometry.
//
// Split in two, deliberately.
//
// The **hull** - shell, deck, coachroof, cockpit, appendages, standing rig and
// deck gear - is baked by `./gradlew :core:generateWebBoat` into boat-hull.js from
// HullLoft in `core`. It is six hundred lines of curve evaluation and it is
// entirely static, so transliterating it here would be six hundred lines of
// duplicated geometry kept in step by a checksum: all the cost of a second
// implementation and none of the benefit, because unlike the physics there is
// nothing in a hull's vertices that a server needs to reason about on its own.
//
// The **sails** are built here, because they move: they swing with the sheet and
// take a different draft and twist on each point of sail, so the browser has to be
// able to loft them itself. That code is a transliteration of HullLoft.sails, and
// tools/web-parity-check.js evaluates both and compares them so they cannot drift.
//
// Boat-local axes match the physics: +X is the bow, +Z is port, +Y is up. That last
// one follows from starboard being 90 degrees clockwise from the bow, which is what
// SailingBoat samples for roll.

import { HULL_MESH } from './boat-hull.js';

export const MATERIAL = {
  TOPSIDES: 0, DECK: 1, SPAR: 2, SAIL: 3, BOTTOM: 4, WIRE: 5, WINDOW: 6,
};

/** Rig dimensions, mirroring the fields HullLoft exposes. */
export const RIG = {
  length: 12.18,
  mastX: 12.18 * 0.12,
  mastHeight: 18.5,
  get mastBase() { return sheer(0.5 - this.mastX / this.length) - 0.1; },
  get boomEnd() { return this.mastX - 5.2; },
  get stemX() { return this.length * 0.62; },
  get stemY() { return sheer(0.0) - 0.55; },
  get houndsY() { return this.mastHeight * 0.86; },
};

/**
 * Deck height above the waterline. Only the rig needs it here - the hull's own
 * copy of this curve lives in HullLoft and is baked into the mesh.
 */
function sheer(t) {
  return 1.14 + 0.72 * Math.pow(1 - t, 1.8) + 0.10 * t * t;
}

/** The baked hull, ready to upload. */
export function buildHull() {
  return HULL_MESH;
}

// --- mesh building ----------------------------------------------------------

/**
 * Smallest cross-product length a triangle may have and still be built. Twice the
 * area, so this is a face of half a square millimetre - real faces here are
 * thousands of times larger and the ones this rejects are millions of times
 * smaller, so nothing sits near the line.
 *
 * The test cannot be for exactly zero. Both sail heads taper to a point, and the
 * luff and the leech arrive there by different arithmetic, so the two "same"
 * vertices differ in the last bits of a double: not exactly degenerate, and far too
 * close for a cross product to have a direction. Java and JavaScript disagreed
 * about which way one such face pointed by 9 degrees, which is what found this.
 */
const MINIMUM_FACE = 1e-9;

/** Position quantum for deciding two vertices are the same point, metres. */
const WELD = 1e-4;

/** A point on a surface: position and the surface coordinate at it. */
export const at = (x, y, z, u = 0, v = 0) => [x, y, z, u, v];

/**
 * Accumulates triangles and resolves their normals, matching BoatMesh.Builder.
 *
 * Normals are averaged across faces that share a position *and* a smoothing group,
 * so every edge is hard or soft according to what it actually is: a sail is one
 * smooth surface, and the boom beside it is not part of it.
 */
export class MeshBuilder {
  constructor() {
    this.positions = [];
    this.normals = [];
    this.materials = [];
    this.uvs = [];
    this.groups = [];
    this.indices = [];
    this.currentMaterial = MATERIAL.TOPSIDES;
    this.currentGroup = 0;
  }

  material(material) {
    this.currentMaterial = material;
    return this;
  }

  smoothing(group) {
    this.currentGroup = group;
    return this;
  }

  triangle(a, b, c) {
    const ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
    const vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
    const nx = uy * vz - uz * vy;
    const ny = uz * vx - ux * vz;
    const nz = ux * vy - uy * vx;
    if (Math.sqrt(nx * nx + ny * ny + nz * nz) < MINIMUM_FACE) return;
    // Left unnormalised on purpose. Averaging by the raw cross product weights each
    // face by its area, which is what stops a fan of slivers at a masthead from
    // outvoting the large panels around it.
    this.indices.push(this.vertex(a, nx, ny, nz));
    this.indices.push(this.vertex(b, nx, ny, nz));
    this.indices.push(this.vertex(c, nx, ny, nz));
  }

  quad(a, b, c, d) {
    this.triangle(a, b, c);
    this.triangle(a, c, d);
  }

  vertex(p, nx, ny, nz) {
    this.positions.push(p[0], p[1], p[2]);
    this.normals.push(nx, ny, nz);
    this.uvs.push(p[3], p[4]);
    this.materials.push(this.currentMaterial);
    this.groups.push(this.currentGroup);
    return this.materials.length - 1;
  }

  /**
   * Averages face normals per smoothing group, then collapses vertices that are
   * genuinely identical and remaps the indices onto them.
   */
  build() {
    const count = this.materials.length;
    const q = (v) => Math.round(v / WELD);

    const sums = new Map();
    const keys = new Array(count);
    for (let v = 0; v < count; v++) {
      const key = `${q(this.positions[v * 3])},${q(this.positions[v * 3 + 1])},`
        + `${q(this.positions[v * 3 + 2])},${this.groups[v]}`;
      keys[v] = key;
      let sum = sums.get(key);
      if (!sum) sums.set(key, (sum = [0, 0, 0]));
      sum[0] += this.normals[v * 3];
      sum[1] += this.normals[v * 3 + 1];
      sum[2] += this.normals[v * 3 + 2];
    }

    const positions = [];
    const normals = [];
    const materials = [];
    const uvs = [];
    const unique = new Map();
    const remap = new Int32Array(count);

    for (let v = 0; v < count; v++) {
      const sum = sums.get(keys[v]);
      let x = sum[0], y = sum[1], z = sum[2];
      let length = Math.sqrt(x * x + y * y + z * z);
      if (length < 1e-12) {
        // Two faces of a zero-thickness fin exactly cancelling. Fall back to this
        // vertex's own face normal, which still has a direction.
        x = this.normals[v * 3];
        y = this.normals[v * 3 + 1];
        z = this.normals[v * 3 + 2];
        length = Math.sqrt(x * x + y * y + z * z);
      }
      // Rounded to float32 before deduplication, so the key matches what is uploaded
      // and two vertices that differ only below float precision become one.
      const nx = Math.fround(x / length);
      const ny = Math.fround(y / length);
      const nz = Math.fround(z / length);
      const px = Math.fround(this.positions[v * 3]);
      const py = Math.fround(this.positions[v * 3 + 1]);
      const pz = Math.fround(this.positions[v * 3 + 2]);
      const u = Math.fround(this.uvs[v * 2]);
      const w = Math.fround(this.uvs[v * 2 + 1]);
      const m = this.materials[v];

      const key = `${px},${py},${pz},${nx},${ny},${nz},${m},${u},${w}`;
      const existing = unique.get(key);
      if (existing !== undefined) {
        remap[v] = existing;
        continue;
      }
      const index = materials.length;
      positions.push(px, py, pz);
      normals.push(nx, ny, nz);
      uvs.push(u, w);
      materials.push(m);
      unique.set(key, index);
      remap[v] = index;
    }

    const indices = new Uint32Array(this.indices.length);
    for (let i = 0; i < this.indices.length; i++) indices[i] = remap[this.indices[i]];

    return {
      positions: new Float32Array(positions),
      normals: new Float32Array(normals),
      materials: new Float32Array(materials),
      uvs: new Float32Array(uvs),
      indices,
    };
  }
}

/**
 * A stable summary of a mesh, matching BoatMesh.checksum in `core`.
 *
 * Summing coordinates plainly would let a sign error cancel itself out, so each is
 * weighted by where it sits in the buffer: moving a vertex, flipping a winding or
 * dropping a face all change the total.
 */
export function meshChecksum(mesh) {
  let sum = 0;
  for (let i = 0; i < mesh.positions.length; i++) sum += mesh.positions[i] * ((i % 97) + 1);
  for (let i = 0; i < mesh.normals.length; i++) sum += mesh.normals[i] * ((i % 71) + 1);
  for (let i = 0; i < mesh.indices.length; i++) sum += mesh.indices[i] * ((i % 89) + 1);
  return sum;
}

/**
 * A square-section bar between two points, for spars and rigging.
 *
 * Four sides rather than a cylinder: at the width these are drawn, the extra faces
 * of a round section are below a pixel.
 */
export function bar(mesh, from, to, radius) {
  let dx = to[0] - from[0];
  let dy = to[1] - from[1];
  let dz = to[2] - from[2];
  const len = Math.sqrt(dx * dx + dy * dy + dz * dz);
  if (len < 1e-6) return;
  dx /= len;
  dy /= len;
  dz /= len;

  // Any two directions perpendicular to the bar will do; pick the one least
  // parallel to it so the cross product never collapses.
  const helper = Math.abs(dy) < 0.9 ? [0, 1, 0] : [1, 0, 0];
  let ax = dy * helper[2] - dz * helper[1];
  let ay = dz * helper[0] - dx * helper[2];
  let az = dx * helper[1] - dy * helper[0];
  const al = Math.sqrt(ax * ax + ay * ay + az * az);
  ax /= al;
  ay /= al;
  az /= al;
  const bx = dy * az - dz * ay;
  const by = dz * ax - dx * az;
  const bz = dx * ay - dy * ax;

  for (let k = 0; k < 4; k++) {
    const a0 = (k * Math.PI) / 2;
    const a1 = ((k + 1) * Math.PI) / 2;
    const c0 = Math.cos(a0) * radius;
    const s0 = Math.sin(a0) * radius;
    const c1 = Math.cos(a1) * radius;
    const s1 = Math.sin(a1) * radius;
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

// --- sails ------------------------------------------------------------------

const SAIL_ROWS = 18;
const SAIL_COLS = 14;

// Smoothing groups, matching the constants in HullLoft.
const G_BOOM = 10;
const G_SAIL_MAIN = 13;
const G_SAIL_JIB = 14;

/**
 * Lofts a cambered sail.
 *
 * A sail is a surface between two edges - the luff, fixed to a spar or a stay, and
 * the leech, which is not - so it is described by where those two run and how deep
 * the section between them is. The camber is a circular arc, deepest around a third
 * of the way aft, which is where a sail's draft sits.
 *
 * Twist is what makes it a sail rather than a wing: the head is always eased
 * relative to the foot, because the apparent wind aloft is freer. A sail without it
 * looks like sheet metal.
 */
function loftSail(mesh, luff, leech, angle, draft, twist) {
  for (let r = 0; r < SAIL_ROWS; r++) {
    for (let c = 0; c < SAIL_COLS; c++) {
      const v0 = r / SAIL_ROWS;
      const v1 = (r + 1) / SAIL_ROWS;
      const u0 = c / SAIL_COLS;
      const u1 = (c + 1) / SAIL_COLS;
      mesh.quad(
        sailPoint(luff, leech, u0, v0, angle, draft, twist),
        sailPoint(luff, leech, u1, v0, angle, draft, twist),
        sailPoint(luff, leech, u1, v1, angle, draft, twist),
        sailPoint(luff, leech, u0, v1, angle, draft, twist));
    }
  }
}

function sailPoint(luff, leech, u, v, angle, draft, twist) {
  const l = luff(v);
  const t = leech(v);
  const chord = l[0] - t[0];
  const along = u * chord;
  // Draft moves aft and shallows as it climbs, which is what a trimmed sail does
  // and what makes the leech fall open at the head.
  const depth = draft * (1 - 0.35 * v);
  const camber = depth * chord * Math.sin(Math.PI * Math.pow(u, 0.8 + 0.25 * v));
  const swing = angle * (1 + twist * v);
  const cos = Math.cos(swing);
  const sin = Math.sin(swing);
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
 * Both sails are drawn because a sloop under main alone does not read as a sailing
 * boat: the shape of the rig *is* the two overlapping triangles. The jib is sheeted
 * inside the main, which is what a jib always is - it works in the main's upwash
 * and stalls if it is eased as far.
 *
 * @param sheetAngle boom angle from the centreline, radians, positive to port
 * @param draft      maximum camber as a fraction of chord
 */
export function buildSails(hull, sheetAngle, draft = 0.11) {
  const mesh = new MeshBuilder();
  const rig = RIG;
  const gooseneck = rig.mastBase + 1.3;

  mesh.material(MATERIAL.SAIL).smoothing(G_SAIL_MAIN);
  const mainLuff = (v) => [rig.mastX, gooseneck + v * (rig.mastHeight - gooseneck)];
  const mainLeech = (v) => {
    // A modern main keeps a lot of area high up - the leech falls away far less
    // than a classic triangular sail's, which is the square-top look.
    const chord = (rig.mastX - rig.boomEnd) * (1 - v * 0.58 + 0.06 * Math.sin(Math.PI * v));
    return [rig.mastX - chord, gooseneck + v * (rig.mastHeight - gooseneck)];
  };
  loftSail(mesh, mainLuff, mainLeech, sheetAngle, draft, 0.55);

  mesh.material(MATERIAL.SPAR).smoothing(G_BOOM);
  const boomLength = rig.mastX - rig.boomEnd;
  const cos = Math.cos(sheetAngle);
  const sin = Math.sin(sheetAngle);
  bar(mesh,
    at(rig.mastX, gooseneck - 0.13, 0),
    at(rig.mastX - boomLength * cos, gooseneck - 0.02, boomLength * sin), 0.075);
  // Vang, from the boom down to the mast heel.
  bar(mesh,
    at(rig.mastX - boomLength * 0.22 * cos, gooseneck - 0.16, boomLength * 0.22 * sin),
    at(rig.mastX, rig.mastBase + 0.15, 0), 0.035);

  mesh.material(MATERIAL.SAIL).smoothing(G_SAIL_JIB);
  const jibAngle = sheetAngle * 0.55;
  const clewX = rig.mastX + 0.9;
  const clewY = rig.mastBase + 3.6;
  const jibLuff = (v) => [
    rig.stemX + (rig.mastX - rig.stemX) * v,
    rig.stemY + (rig.houndsY - rig.stemY) * v,
  ];
  const jibLeech = (v) => [
    clewX + (rig.mastX - clewX) * v,
    clewY + (rig.houndsY - clewY) * v,
  ];
  loftSail(mesh, jibLuff, jibLeech, jibAngle, draft * 0.85, 0.42);

  return mesh.build();
}
