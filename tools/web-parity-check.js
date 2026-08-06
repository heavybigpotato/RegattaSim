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

if (failures.length) {
  console.error(`FAIL: the web build has drifted from core in ${failures.length} place(s):`);
  for (const f of failures) console.error('  ' + f);
  process.exit(1);
}
console.log('PASS: docs/ocean/spectrum.js reproduces core exactly');
