// The sailing model, transliterated from `core`.
//
// Same arrangement as spectrum.js: the Java is the authority, this exists so a
// browser can sail the same boat, and tools/web-parity-check.js evaluates both
// and compares them so the two cannot drift apart unnoticed.
//
// The polar CSV is not copied here - it is carried verbatim from
// core/src/main/resources/polars by :core:generateWebPolars and parsed by the
// same rules.

import { POLAR_CSV } from './polars.js';

const KNOTS_TO_MS = 0.514444;
const TAU = Math.PI * 2;

export function wrapPi(radians) {
  let r = (radians + Math.PI) % TAU;
  if (r < 0) r += TAU;
  return r - Math.PI;
}

// --- apparent wind ----------------------------------------------------------

/**
 * True wind minus boat velocity. Conventions match ApparentWind.java: directions
 * are radians in the XZ plane from +X toward +Z, `windToward` is the direction
 * the wind blows toward, and wind angles are measured at the bow with positive
 * meaning starboard tack.
 */
export function apparentWind(trueWindSpeed, windToward, boatSpeed, boatHeading) {
  const wx = trueWindSpeed * Math.cos(windToward);
  const wz = trueWindSpeed * Math.sin(windToward);
  const bx = boatSpeed * Math.cos(boatHeading);
  const bz = boatSpeed * Math.sin(boatHeading);

  const ax = wx - bx;
  const az = wz - bz;
  const speed = Math.hypot(ax, az);

  const fromBow = (x, z) => -wrapPi(Math.atan2(z, x) - boatHeading);
  return {
    speed,
    angle: speed < 1e-9 ? 0 : fromBow(-ax, -az),
    trueAngle: trueWindSpeed < 1e-9 ? 0 : fromBow(-wx, -wz),
  };
}

// --- polar ------------------------------------------------------------------

function catmullRom(p0, p1, p2, p3, t) {
  const t2 = t * t;
  const t3 = t2 * t;
  return 0.5 * ((2 * p1)
    + (-p0 + p2) * t
    + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2
    + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
}

const clampIndex = (v, lo, hi) => (v < lo ? lo : (v > hi ? hi : v));

function segment(axis, value) {
  let i = 0;
  while (i < axis.length - 2 && value >= axis[i + 1]) i++;
  return i;
}

/** Parses the CSV format used by PolarDiagram.java. */
export function parsePolar(name, text) {
  const angles = [];
  const rows = [];
  let speeds = null;

  for (const raw of text.split('\n')) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const parts = line.split(',');
    if (speeds === null) {
      speeds = parts.slice(1).map((v) => parseFloat(v) * KNOTS_TO_MS);
      continue;
    }
    if (parts.length !== speeds.length + 1) {
      throw new Error(`${name}: row "${parts[0]}" has the wrong number of cells`);
    }
    angles.push((parseFloat(parts[0]) * Math.PI) / 180);
    rows.push(parts.slice(1).map((v) => parseFloat(v) * KNOTS_TO_MS));
  }

  return {
    name,
    angles,
    speeds,
    /** Target boat speed, m/s, bicubic in both axes. */
    boatSpeed(trueWindAngle, trueWindSpeed) {
      let angle = Math.min(Math.abs(trueWindAngle), Math.PI);
      // Clamped, never extrapolated: a Catmull-Rom past its last node diverges
      // and would invent a boat doing thirty knots in a hurricane.
      const wind = Math.max(speeds[0], Math.min(speeds[speeds.length - 1], trueWindSpeed));

      const ai = segment(angles, angle);
      const si = segment(speeds, wind);
      const at = (angle - angles[ai]) / (angles[ai + 1] - angles[ai]);
      const st = (wind - speeds[si]) / (speeds[si + 1] - speeds[si]);

      const column = [];
      for (let k = 0; k < 4; k++) {
        const row = rows[clampIndex(ai - 1 + k, 0, angles.length - 1)];
        column.push(catmullRom(
          row[clampIndex(si - 1, 0, speeds.length - 1)],
          row[si],
          row[si + 1],
          row[clampIndex(si + 2, 0, speeds.length - 1)],
          st));
      }
      return Math.max(0, catmullRom(column[0], column[1], column[2], column[3], at));
    },
  };
}

export function loadPolar(file) {
  const text = POLAR_CSV[file];
  if (!text) throw new Error(`polar not bundled: ${file}`);
  return parsePolar(file, text);
}

// --- the boat ---------------------------------------------------------------

export const STEP = 1 / 120;

export const CLASS40 = {
  length: 12.18,
  beam: 4.50,
  accelerationTime: 9.0,
  maximumTurnRate: (22 * Math.PI) / 180,
  maximumHeel: (25 * Math.PI) / 180,
  heelReference: 33.6,
};

/** Time constant for heel to follow the wind pressure, seconds. */
const HEEL_TIME_CONSTANT = 1.6;

/**
 * A hull sailing on a wave surface, matching SailingBoat.java.
 *
 * Speed comes from the polar; the rest is what makes it feel like a boat rather
 * than a number - inertia, a rudder that needs flow over it, and an attitude read
 * off the water by fitting a plane through four points on the hull.
 */
export class Boat {
  constructor(polar, hull = CLASS40) {
    this.polar = polar;
    this.hull = hull;
    this.x = 0;
    this.z = 0;
    this.heading = 0;
    this.speed = 0;
    this.heave = 0;
    this.pitch = 0;
    this.roll = 0;
    this.windHeel = 0;
    this.rudder = 0;
    this.trim = 1;
    this.accumulator = 0;
    this.wind = { speed: 0, angle: 0, trueAngle: 0 };
  }

  setRudder(demand) {
    this.rudder = Math.max(-1, Math.min(1, demand));
  }

  setTrim(quality) {
    this.trim = Math.max(0, Math.min(1, quality));
  }

  /**
   * @param heightAt function (x, z) -> surface elevation, metres
   */
  advance(deltaTime, trueWindSpeed, windToward, heightAt) {
    this.accumulator += Math.min(deltaTime, 0.25);
    while (this.accumulator >= STEP) {
      this.step(trueWindSpeed, windToward);
      this.accumulator -= STEP;
    }
    this.readAttitude(heightAt);
  }

  step(trueWindSpeed, windToward) {
    this.wind = apparentWind(trueWindSpeed, windToward, this.speed, this.heading);

    const target = this.polar.boatSpeed(this.wind.trueAngle, trueWindSpeed)
      * (0.85 + 0.15 * this.trim);
    const rate = 1 - Math.exp(-STEP / this.hull.accelerationTime);
    this.speed += (target - this.speed) * rate;

    // A rudder needs flow over it; with no way on, the helm does nothing, which
    // is what being stuck in irons is. Subtracted because starboard is 90 degrees
    // clockwise from the bow, so a starboard turn decreases the heading.
    const steerage = Math.min(1, this.speed / 1.5);
    this.heading = wrapPi(
      this.heading - this.rudder * this.hull.maximumTurnRate * steerage * STEP);

    this.x += this.speed * Math.cos(this.heading) * STEP;
    this.z += this.speed * Math.sin(this.heading) * STEP;

    // Heel lags the wind that causes it: a boat comes upright through a tack,
    // hangs, then lies down on the other side.
    const rollRate = 1 - Math.exp(-STEP / HEEL_TIME_CONSTANT);
    this.windHeel += (this.targetHeel() - this.windHeel) * rollRate;
  }

  /**
   * The heel the rig is asking for, radians. Only the athwartships component of
   * the apparent wind heels a boat, and past a certain pressure a monohull stops
   * heeling further and starts rounding up - hence the saturation.
   */
  targetHeel() {
    const side = Math.sin(this.wind.angle);
    const pressure = this.wind.speed * this.wind.speed * Math.abs(side);
    const magnitude = this.hull.maximumHeel * Math.tanh(pressure / this.hull.heelReference);
    // Wind from starboard lays the boat over to port, and roll is measured
    // starboard-down, so a starboard-tack heel is negative.
    return -Math.sign(side) * magnitude;
  }

  readAttitude(heightAt) {
    const halfLength = this.hull.length * 0.5;
    const halfBeam = this.hull.beam * 0.5;
    const c = Math.cos(this.heading);
    const s = Math.sin(this.heading);

    const bow = heightAt(this.x + c * halfLength, this.z + s * halfLength);
    const stern = heightAt(this.x - c * halfLength, this.z - s * halfLength);
    const starboard = heightAt(this.x + s * halfBeam, this.z - c * halfBeam);
    const port = heightAt(this.x - s * halfBeam, this.z + c * halfBeam);

    this.heave = 0.25 * (bow + stern + starboard + port);
    this.pitch = Math.atan2(bow - stern, this.hull.length);
    // The wave slope tips the boat about the angle the rig already holds it at.
    this.roll = Math.atan2(starboard - port, this.hull.beam) + this.windHeel;
  }

  get speedKnots() {
    return this.speed / KNOTS_TO_MS;
  }

  polarEfficiency(trueWindSpeed) {
    const target = this.polar.boatSpeed(this.wind.trueAngle, trueWindSpeed);
    return target < 1e-6 ? 0 : Math.min(1, this.speed / target);
  }
}
