// Checks the WebGL build's transliterated maths against the authoritative Java.
//
// docs/ocean/spectrum.js is a hand transliteration of the `core` module so a
// browser can show the same sea. Transliterations drift silently: a sign lost in
// the dispersion relation, a mis-ported hash, a spreading exponent applied on the
// wrong side of the peak — all of those still produce a plausible ocean, just not
// the same one, and the preview would stop being evidence about the renderer it
// claims to preview.
//
// Usage:
//   ./gradlew :core:dumpSpectrum -q > java-spectrum.json
//   node tools/web-parity-check.js java-spectrum.json
import { readFileSync } from 'node:fs';
import {
  seaState, cascadeSettings, initialSpectrum, butterflyPlan,
  omegaOf, quantiseForLoop, meanDomeLuminance,
} from '../docs/ocean/spectrum.js';
import { loadPolar, apparentWind, Boat } from '../docs/ocean/sailing.js';
import { buildHull, buildSails, meshChecksum } from '../docs/ocean/hull.js';

const expected = JSON.parse(readFileSync(process.argv[2] || 'java-spectrum.json', 'utf8'));

// Must mirror SpectrumDump.referenceSeaState() exactly.
const sea = seaState({
  windSpeed: 11.0,
  windDirection: 0.6,
  fetch: 300000,
  gamma: 3.3,
  depth: 4000,
  swellHeight: 2.2,
  swellPeriod: 11.0,
  swellDirection: 1.3,
  swellNarrowness: 0.07,
  swellSpreadExponent: 24.0,
  choppiness: 1.0,
  repeatPeriod: 200,
  seed: 20260805n,
});

const failures = [];

function close(label, actual, want, relative = 1e-6, absolute = 1e-12) {
  const diff = Math.abs(actual - want);
  const tolerance = Math.max(absolute, Math.abs(want) * relative);
  if (!(diff <= tolerance)) {
    failures.push(`${label}: js ${actual} vs java ${want} (difference ${diff.toExponential(3)})`);
  }
}

// The spectrum's zeroth moment is integrated numerically on both sides with the
// same scheme, so it should agree to many digits.
close('significantWaveHeight', sea.significantWaveHeight, expected.significantWaveHeight, 1e-9);
close('windSeaPeakOmega', sea.windSea.peakOmega, expected.windSeaPeakOmega, 1e-12);

const cascades = cascadeSettings(128, [512, 128, 16]);
close('cascadeKMax0', cascades.kMax[0], expected.cascadeKMax0, 1e-6);

[0.01, 0.05, 0.2, 1.0, 5.0, 30.0].forEach((k, i) => {
  close(`omega[${i}]`, quantiseForLoop(omegaOf(k, sea.depth), sea.repeatPeriod),
    expected.omega[i], 1e-12);
});

// directionalSpectrum is not exported, so it is exercised through the h0 field,
// which depends on it. The values below come from the same code path.
const h0 = initialSpectrum(sea, cascades, 0);
const texels = [[0, 0], [1, 0], [3, 5], [17, 42], [64, 64], [127, 127]];
texels.forEach(([x, z], i) => {
  const o = (z * 128 + x) * 4;
  for (let c = 0; c < 4; c++) {
    // Float32 on the Java side, doubles here, so relative agreement to ~1e-6.
    close(`h0[${x},${z}].${'reim'[c] || c}`, h0[o + c], expected.h0[i][c], 1e-5, 1e-15);
  }
});

let variance = 0;
for (let i = 0; i < h0.length; i += 4) {
  variance += h0[i] * h0[i] + h0[i + 1] * h0[i + 1];
}
close('h0Variance', 2 * variance, expected.h0Variance, 1e-5);

const plan = butterflyPlan(128);
[[0, 0], [0, 1], [3, 5], [6, 127]].forEach(([stage, lane], i) => {
  const o = (lane * plan.stages + stage) * 4;
  close(`butterfly[${stage},${lane}].re`, plan.table[o], expected.butterfly[i][0], 1e-6, 1e-7);
  close(`butterfly[${stage},${lane}].im`, plan.table[o + 1], expected.butterfly[i][1], 1e-6, 1e-7);
  close(`butterfly[${stage},${lane}].a`, plan.table[o + 2], expected.butterfly[i][2], 0, 0);
  close(`butterfly[${stage},${lane}].b`, plan.table[o + 3], expected.butterfly[i][3], 0, 0);
});

[-0.05, 0.05, 0.3, 0.9, 1.4].forEach((elevation, i) => {
  close(`meanDomeLuminance[${i}]`, meanDomeLuminance(elevation, 2.6),
    expected.meanDomeLuminance[i], 1e-9);
});

// --- sailing ----------------------------------------------------------------

const KT = 0.514444;
const polar = loadPolar('class40.csv');

[[45, 12], [90, 8], [135, 20], [60, 17.5], [170, 6.5], [20, 12]].forEach(([twa, tws], i) => {
  close(`polar[${twa}deg,${tws}kt]`,
    polar.boatSpeed((twa * Math.PI) / 180, tws * KT), expected.polar[i], 1e-9);
});

[[12, 0, 8, Math.PI - Math.PI / 4], [10, 0.7, 6, 1.9], [8, 0, 8, 0], [14, 2.2, 3, -1.1]]
  .forEach(([tws, toward, bs, hdg], i) => {
    const aw = apparentWind(tws * KT, toward, bs * KT, hdg);
    close(`apparentWind[${i}].speed`, aw.speed, expected.apparentWind[i][0], 1e-9);
    close(`apparentWind[${i}].angle`, aw.angle, expected.apparentWind[i][1], 1e-9, 1e-12);
    close(`apparentWind[${i}].trueAngle`, aw.trueAngle, expected.apparentWind[i][2], 1e-9, 1e-12);
  });

// The whole integration loop, not a single step: two minutes of sailing with helm
// on over a known swell. Any drift in the polar, the apparent wind, the
// accumulator or the attitude fit shows up here as a divergent position.
{
  const boat = new Boat(polar);
  boat.x = 0;
  boat.z = 0;
  boat.heading = Math.PI - (50 * Math.PI) / 180;
  boat.setRudder(0.25);
  boat.setTrim(0.8);
  const k = (2 * Math.PI) / 40;
  const kx = k * Math.cos(0.3);
  const kz = k * Math.sin(0.3);
  const swell = (x, z) => 1.2 * Math.sin(kx * x + kz * z);
  for (let i = 0; i < 2400; i++) {
    boat.advance(0.05, 13.0 * KT, 0.0, swell);
  }
  const labels = ['x', 'z', 'heading', 'speed', 'heave', 'pitch', 'roll', 'windHeel'];
  const actual = [boat.x, boat.z, boat.heading, boat.speed, boat.heave, boat.pitch,
    boat.roll, boat.windHeel];
  actual.forEach((v, i) => close(`boat.${labels[i]}`, v, expected.boat[i], 1e-6, 1e-9));
}

// The boat itself. `core` is the authority and the browser is the transliteration
// here too, so the two must generate the same vertices - otherwise the page stops
// being a preview of the client and becomes a different boat that happens to sail
// the same. Vertex and index counts catch a dropped or duplicated face; the
// weighted checksum catches a moved vertex or a flipped winding.
//
// Compared absolutely, not relatively. The checksum runs to eight figures, so a
// relative tolerance of 1e-6 would permit a swing of 25 - enough to hide a
// millimetre moved on every vertex in the boat, which it did. Both sides compute
// identical doubles and round them to identical floats, so the only slack needed
// is the six decimals the dump prints.
const MESH_TOLERANCE = 1e-4;
{
  const hull = buildHull();
  close('hull.vertices', hull.positions.length / 3, expected.hullMesh[0], 0);
  close('hull.indices', hull.indices.length, expected.hullMesh[1], 0);
  close('hull.checksum', meshChecksum(hull), expected.hullMesh[2], 0, MESH_TOLERANCE);

  const sails = buildSails(hull, 0.4, 0.11);
  close('sails.vertices', sails.positions.length / 3, expected.sailMesh[0], 0);
  close('sails.indices', sails.indices.length, expected.sailMesh[1], 0);
  close('sails.checksum', meshChecksum(sails), expected.sailMesh[2], 0, MESH_TOLERANCE);
}

if (failures.length) {
  console.error(`FAIL: the web build has drifted from core in ${failures.length} place(s):`);
  for (const f of failures) console.error('  ' + f);
  process.exit(1);
}
console.log('PASS: docs/ocean/spectrum.js reproduces core exactly');
