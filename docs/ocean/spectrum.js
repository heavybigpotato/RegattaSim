// The ocean's CPU-side mathematics, transliterated from the `core` module.
//
// This is a deliberate duplication and it is worth being clear about why. The
// Java in `core` is the authority: it is unit tested against a naive DFT, checked
// for spectral energy conservation, and shared with the authoritative server. This
// file exists so a phone with no App Store account can see that same ocean in a
// browser. It is checked against the Java by `tools/web-parity-check.js`, which
// evaluates both for one sea state and compares them value by value: significant
// wave height, peak frequency, dispersion, the h0 field itself (and therefore the
// 64-bit hash), the butterfly plan, and sky luminance. That check runs in CI.
//
// It earned its place on its first run, by catching a discrepancy that turned out
// to be in the Java rather than here: GRAVITY, TAU and PI were declared float but
// used only in double expressions, so `core` had been carrying a relative error of
// 1.6e-8 through every wavenumber and frequency.
//
// Everything here reads as its Java counterpart does. Where it differs, it is
// because JavaScript has no 64-bit integers outside BigInt, which only affects the
// hashing.

export const GRAVITY = 9.80665;
export const TAU = Math.PI * 2;

// --- deterministic random ---------------------------------------------------
// SplitMix64's finalising mix, in BigInt so the bit pattern matches Java's long
// arithmetic exactly. Only ever run at initialisation.

const M64 = (1n << 64n) - 1n;

function mix64(z) {
  z = (z + 0x9E3779B97F4A7C15n) & M64;
  z = ((z ^ (z >> 30n)) * 0xBF58476D1CE4E5B9n) & M64;
  z = ((z ^ (z >> 27n)) * 0x94D049BB133111EBn) & M64;
  return (z ^ (z >> 31n)) & M64;
}

function hash2(seed, x, y) {
  // Java's int*long multiply sign-extends the int; BigInt.asIntN reproduces that.
  let h = BigInt.asUintN(64, seed);
  h = mix64(h ^ BigInt.asUintN(64, BigInt(x) * 0x9E3779B97F4A7C15n));
  h = mix64(h ^ BigInt.asUintN(64, BigInt(y) * 0xC2B2AE3D27D4EB4Fn));
  return h;
}

function toUnit(hash) {
  return Number(hash >> 11n) * Math.pow(2, -53);
}

/** Two standard normal deviates for a seed and grid coordinate, via Box-Muller. */
export function gaussianPair(seed, x, y, out) {
  const h = hash2(seed, x, y);
  let u1 = toUnit(h);
  const u2 = toUnit(mix64(h ^ 0x5851F42D4C957F2Dn));
  if (u1 < 1e-12) u1 = 1e-12;
  const r = Math.sqrt(-2 * Math.log(u1));
  const a = TAU * u2;
  out[0] = r * Math.cos(a);
  out[1] = r * Math.sin(a);
}

// --- log-gamma, for the directional spreading normalisation ------------------

const LANCZOS = [
  0.99999999999980993, 676.5203681218851, -1259.1392167224028,
  771.32342877765313, -176.61502916214059, 12.507343278686905,
  -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7,
];

export function logGamma(x) {
  if (x < 0.5) {
    return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - logGamma(1 - x);
  }
  const z = x - 1;
  let a = LANCZOS[0];
  for (let i = 1; i < LANCZOS.length; i++) a += LANCZOS[i] / (z + i);
  const t = z + 7.5;
  return Math.log(Math.sqrt(TAU)) + (z + 0.5) * Math.log(t) - t + Math.log(a);
}

// --- spectra ----------------------------------------------------------------

/** Fetch beyond which the sea stops growing for a given wind, metres. */
export function fullDevelopmentFetch(windSpeed) {
  return (windSpeed * windSpeed) / 5.754e-4;
}

/**
 * JONSWAP, with the fetch capped at full development. Applied past that point the
 * fetch relations keep lowering the peak frequency and, since energy goes as
 * wp^-4, raise the sea above anything the wind can physically build.
 */
export function jonswap(windSpeed, fetch, gamma) {
  const capped = Math.min(fetch, fullDevelopmentFetch(windSpeed));
  const chi = (GRAVITY * capped) / (windSpeed * windSpeed);
  const alpha = 0.076 * Math.pow(chi, -0.22);
  const peakOmega = 22 * Math.cbrt((GRAVITY * GRAVITY) / (windSpeed * capped));
  return {
    peakOmega,
    energy(omega) {
      if (omega <= 1e-6) return 0;
      const g2 = GRAVITY * GRAVITY;
      const base =
        ((alpha * g2) / Math.pow(omega, 5)) *
        Math.exp(-1.25 * Math.pow(peakOmega / omega, 4));
      const sigma = omega <= peakOmega ? 0.07 : 0.09;
      const d = omega - peakOmega;
      const r = Math.exp(-(d * d) / (2 * sigma * sigma * peakOmega * peakOmega));
      return base * Math.pow(gamma, r);
    },
  };
}

/** Narrow-band swell carrying a prescribed significant height. */
export function gaussianSwell(significantHeight, peakPeriod, narrowness) {
  const peakOmega = TAU / peakPeriod;
  const sigma = narrowness * peakOmega;
  const m0 = (significantHeight * significantHeight) / 16;
  return {
    peakOmega,
    energy(omega) {
      if (omega <= 1e-6) return 0;
      const d = omega - peakOmega;
      return (m0 / (sigma * Math.sqrt(TAU))) * Math.exp(-(d * d) / (2 * sigma * sigma));
    },
  };
}

function wrapPi(a) {
  let r = (a + Math.PI) % TAU;
  if (r < 0) r += TAU;
  return r - Math.PI;
}

/** Longuet-Higgins cos^{2s}(theta/2) spreading, analytically normalised. */
export function cosinePowerSpreading(meanDirection, peakOmega, exponentPeak, frequencyDependent) {
  const clamp = (v) => Math.max(0.05, Math.min(200, v));
  const sp = clamp(exponentPeak);
  return {
    density(theta, omega) {
      let s = sp;
      if (frequencyDependent && omega > 0) {
        const ratio = omega / peakOmega;
        s = clamp(ratio <= 1 ? sp * Math.pow(ratio, 5) : sp * Math.pow(ratio, -2.5));
      }
      const c = Math.cos(0.5 * wrapPi(theta - meanDirection));
      if (c <= 0) return 0;
      const n = Math.exp(logGamma(s + 1) - logGamma(s + 0.5)) / (2 * Math.sqrt(Math.PI));
      return n * Math.pow(c, 2 * s);
    },
  };
}

/** Wind-sea spreading with the Mitsuyasu frequency dependence. */
export function windSeaSpreading(direction, peakOmega, windSpeed) {
  const sp = 11.5 * Math.pow(GRAVITY / (peakOmega * windSpeed), 2.5);
  return cosinePowerSpreading(direction, peakOmega, sp, true);
}

// --- dispersion -------------------------------------------------------------

const DEEP_KD = 20;

export function omegaOf(k, depth) {
  const kd = k * depth;
  const t = kd >= DEEP_KD ? 1 : Math.tanh(kd);
  return Math.sqrt(GRAVITY * k * t);
}

export function dOmegaDk(k, depth) {
  const w = omegaOf(k, depth);
  if (w <= 0) return 0;
  const kd = k * depth;
  if (kd >= DEEP_KD) return GRAVITY / (2 * w);
  const t = Math.tanh(kd);
  return (GRAVITY * (t + kd * (1 - t * t))) / (2 * w);
}

/** Quantises omega so the surface repeats exactly after repeatPeriod seconds. */
export function quantiseForLoop(omega, repeatPeriod) {
  if (repeatPeriod <= 0) return omega;
  const base = TAU / repeatPeriod;
  return Math.floor(omega / base) * base;
}

// --- sea state --------------------------------------------------------------

export function seaState(options) {
  const s = Object.assign(
    {
      windSpeed: 11,
      windDirection: 0.6,
      fetch: 300000,
      gamma: 3.3,
      depth: 4000,
      swellHeight: 2.2,
      swellPeriod: 11,
      swellDirection: 1.3,
      swellNarrowness: 0.07,
      swellSpreadExponent: 24,
      choppiness: 1.0,
      repeatPeriod: 200,
      seed: 20260805n,
    },
    options
  );
  s.windSea = jonswap(s.windSpeed, s.fetch, s.gamma);
  s.windSeaSpread = windSeaSpreading(s.windDirection, s.windSea.peakOmega, s.windSpeed);
  s.swell = s.swellHeight > 0 ? gaussianSwell(s.swellHeight, s.swellPeriod, s.swellNarrowness) : null;
  s.swellSpread = cosinePowerSpreading(s.swellDirection, 1, s.swellSpreadExponent, false);

  // Numerically integrated zeroth moment, matching WaveSpectrum.zerothMoment().
  const wp = s.windSea.peakOmega;
  const lo = Math.max(1e-3, wp * 0.02);
  const hi = wp * 12;
  const steps = 4096;
  const step = (hi - lo) / steps;
  let sum = 0.5 * (s.windSea.energy(lo) + s.windSea.energy(hi));
  for (let i = 1; i < steps; i++) sum += s.windSea.energy(lo + i * step);
  let m0 = sum * step;
  if (s.swellHeight > 0) m0 += (s.swellHeight * s.swellHeight) / 16;
  s.significantWaveHeight = 4 * Math.sqrt(Math.max(0, m0));
  return s;
}

// --- cascades ---------------------------------------------------------------

export function cascadeSettings(resolution, patchSizes) {
  const kMin = new Float32Array(patchSizes.length);
  const kMax = new Float32Array(patchSizes.length);
  for (let i = 0; i < patchSizes.length; i++) {
    const nyquist = (Math.PI * resolution) / patchSizes[i];
    kMin[i] = i === 0 ? 0 : kMax[i - 1];
    kMax[i] = i === patchSizes.length - 1 ? Number.MAX_VALUE : nyquist;
  }
  return { resolution, patchSizes, kMin, kMax };
}

export function signedIndex(index, n) {
  return index < n / 2 ? index : index - n;
}

/** The directional wavenumber spectrum S2(kx, kz), in m^4. */
function directionalSpectrum(sea, kx, kz, kMin, kMax) {
  const k = Math.hypot(kx, kz);
  if (k < 1e-9 || k < kMin || k >= kMax) return 0;
  const omega = omegaOf(k, sea.depth);
  if (omega <= 1e-9) return 0;
  const theta = Math.atan2(kz, kx);
  let s = sea.windSea.energy(omega) * sea.windSeaSpread.density(theta, omega);
  if (sea.swell) s += sea.swell.energy(omega) * sea.swellSpread.density(theta, omega);
  return (s * dOmegaDk(k, sea.depth)) / k;
}

/**
 * The initial spectrum h0 for one cascade, as (h0.re, h0.im, conj(h0(-k)).re,
 * conj(h0(-k)).im) per texel.
 *
 * @param index        which entry of `cascades` supplies the patch size and band
 * @param decorrelator salt for the phase field; defaults to `index` and must
 *                     equal the *renderer's* cascade index, so that a coarser
 *                     physics grid realises the same ocean rather than a
 *                     different one
 */
export function initialSpectrum(sea, cascades, index, decorrelator = index) {
  const n = cascades.resolution;
  const patch = cascades.patchSizes[index];
  const kMin = cascades.kMin[index];
  const kMax = cascades.kMax[index];
  // Mixing the cascade index into the seed decorrelates the cascades. Without it
  // they share phases and the three independent scales collapse into one scaled
  // copy, visible as a repeating diagonal grain.
  const seed = mix64(BigInt.asUintN(64, sea.seed + 0x9E3779B9n * BigInt(decorrelator + 1)));

  const dk = TAU / patch;
  const amplitudeScale = Math.sqrt(0.5 * dk * dk);
  const out = new Float32Array(n * n * 4);
  const pair = [0, 0];

  const h0At = (x, z) => {
    const kx = (TAU * signedIndex(x, n)) / patch;
    const kz = (TAU * signedIndex(z, n)) / patch;
    const s2 = directionalSpectrum(sea, kx, kz, kMin, kMax);
    if (s2 <= 0) return [0, 0];
    const amplitude = amplitudeScale * Math.sqrt(s2);
    gaussianPair(seed, x, z, pair);
    const inv = 1 / Math.sqrt(2);
    return [inv * pair[0] * amplitude, inv * pair[1] * amplitude];
  };

  for (let z = 0; z < n; z++) {
    for (let x = 0; x < n; x++) {
      const o = (z * n + x) * 4;
      const a = h0At(x, z);
      out[o] = a[0];
      out[o + 1] = a[1];
      const b = h0At((n - x) % n, (n - z) % n);
      out[o + 2] = b[0];
      out[o + 3] = -b[1];
    }
  }
  return out;
}

/** (kx, kz, |k|, omega) per bin, precomputed so the shader only rotates phases. */
export function waveData(sea, cascades, index) {
  const n = cascades.resolution;
  const patch = cascades.patchSizes[index];
  const out = new Float32Array(n * n * 4);
  for (let z = 0; z < n; z++) {
    for (let x = 0; x < n; x++) {
      const kx = (TAU * signedIndex(x, n)) / patch;
      const kz = (TAU * signedIndex(z, n)) / patch;
      const k = Math.hypot(kx, kz);
      const o = (z * n + x) * 4;
      out[o] = kx;
      out[o + 1] = kz;
      out[o + 2] = k;
      out[o + 3] = quantiseForLoop(omegaOf(k, sea.depth), sea.repeatPeriod);
    }
  }
  return out;
}

// --- butterfly plan ---------------------------------------------------------

function reverseBits(value, bits) {
  let r = 0;
  for (let i = 0; i < bits; i++) r = (r << 1) | ((value >>> i) & 1);
  return r;
}

/**
 * The Cooley-Tukey schedule, ordered lane-major so it uploads directly as a
 * log2(N) wide by N tall texture.
 */
export function butterflyPlan(size) {
  const stages = Math.log2(size) | 0;
  const table = new Float32Array(stages * size * 4);
  for (let stage = 0; stage < stages; stage++) {
    const half = 1 << stage;
    const span = half << 1;
    for (let x = 0; x < size; x++) {
      const k = (x * (size / span)) % size;
      const angle = (TAU * k) / size;
      const topWing = x % span < half;
      let indexA = topWing ? x : x - half;
      let indexB = indexA + half;
      if (stage === 0) {
        indexA = reverseBits(indexA, stages);
        indexB = reverseBits(indexB, stages);
      }
      const o = (x * stages + stage) * 4;
      table[o] = Math.cos(angle);
      table[o + 1] = Math.sin(angle);
      table[o + 2] = indexA;
      table[o + 3] = indexB;
    }
  }
  return { size, stages, table };
}

// --- sky luminance, for exposure -------------------------------------------

const VALIDITY_ELEVATION_LIMIT = (10 * Math.PI) / 180;
const TWILIGHT_FALLOFF = 17.2;

function zenithLuminanceRaw(sunZenith, turbidity) {
  const chi = (4 / 9 - turbidity / 120) * (Math.PI - 2 * sunZenith);
  return (4.0453 * turbidity - 4.971) * Math.tan(chi) - 0.2155 * turbidity + 0.1208;
}

/** Always positive, always decreasing as the sun sets. See PreethamSky in core. */
function usableZenithLuminance(sunZenith, turbidity) {
  const elevation = Math.PI / 2 - sunZenith;
  if (elevation >= VALIDITY_ELEVATION_LIMIT) return zenithLuminanceRaw(sunZenith, turbidity);
  const atLimit = zenithLuminanceRaw(Math.PI / 2 - VALIDITY_ELEVATION_LIMIT, turbidity);
  return atLimit * Math.exp((elevation - VALIDITY_ELEVATION_LIMIT) * TWILIGHT_FALLOFF);
}

function perez(cosTheta, gamma, c) {
  const cosGamma = Math.cos(gamma);
  return (
    (1 + c[0] * Math.exp(c[1] / Math.max(cosTheta, 0.01))) *
    (1 + c[2] * Math.exp(c[3] * gamma) + c[4] * cosGamma * cosGamma)
  );
}

/** Cosine-weighted mean sky luminance, kcd/m^2, excluding the solar aureole. */
export function meanDomeLuminance(sunElevation, turbidity) {
  const sunZenith = Math.PI / 2 - sunElevation;
  const c = [
    0.1787 * turbidity - 1.463,
    -0.3554 * turbidity + 0.4275,
    -0.0227 * turbidity + 5.3251,
    0.1206 * turbidity - 2.5771,
    -0.067 * turbidity + 0.3703,
  ];
  const zenith = usableZenithLuminance(sunZenith, turbidity);
  const f0 = perez(1, sunZenith, c);
  let sum = 0;
  let weight = 0;
  for (let i = 0; i < 12; i++) {
    const theta = ((i + 0.5) / 12) * (Math.PI / 2);
    const cosTheta = Math.cos(theta);
    const sinTheta = Math.sin(theta);
    for (let j = 0; j < 24; j++) {
      const phi = ((j + 0.5) / 24) * TAU;
      const cosGamma = sinTheta * Math.cos(phi) * Math.sin(sunZenith) + cosTheta * Math.cos(sunZenith);
      const gamma = Math.acos(Math.max(-1, Math.min(1, cosGamma)));
      if (gamma < (6 * Math.PI) / 180) continue;
      const w = cosTheta * sinTheta;
      sum += Math.max(0, (zenith * perez(cosTheta, gamma, c)) / f0) * w;
      weight += w;
    }
  }
  return weight > 0 ? sum / weight : 0;
}

// --- CPU wave field ---------------------------------------------------------

/**
 * The wave height evaluated on the CPU, mirroring CpuOceanSurface in `core`.
 *
 * The boat has to float on the water that is drawn, and reading the GPU's
 * displacement maps back every frame would stall the pipeline - the one thing a
 * mobile GPU least forgives. So the same spectrum is transformed again on the
 * CPU at a coarse resolution. That sounds wasteful and is not: a 32-point
 * transform is a few thousand operations, while a readback costs a full
 * synchronisation.
 *
 * One deliberate simplification: the number of cascades is capped, because the
 * finest one carries chop measured in centimetres over metres and a hull this
 * long averages it out. What is *not* simplified is the horizontal displacement.
 * A choppy surface is parametric - the grid point at (u,v) is drawn at
 * (u + Dx, v + Dz) - so the height above a fixed world position is only found by
 * inverting that map. Skipping the inversion samples the wrong place on the wave,
 * and on a steep sea that is the difference between a boat on the water and a
 * boat inside it.
 */
export class WaveField {
  /**
   * @param resolution   FFT grid for physics
   * @param cascadeLimit how many cascades to evaluate, from the largest down
   */
  constructor(sea, cascades, resolution = 32, cascadeLimit = 2) {
    const count = Math.max(1, Math.min(cascadeLimit, cascades.patchSizes.length));
    this.n = resolution;
    this.cascadeCount = count;
    this.choppiness = sea.choppiness;
    this.patchSizes = [];
    this.h0 = [];
    this.wave = [];
    this.heightField = [];
    this.displacementX = [];
    this.displacementZ = [];

    for (let c = 0; c < count; c++) {
      const patch = cascades.patchSizes[c];
      // Band limits come from the render cascade layout, so a coarser physics
      // grid still realises the same ocean rather than a different one.
      const band = {
        resolution,
        patchSizes: [patch],
        kMin: [cascades.kMin[c]],
        kMax: [cascades.kMax[c]],
      };
      this.patchSizes.push(patch);
      // The fourth argument is the decorrelator: it must be the *render* cascade
      // index, not 0, or every cascade here would share one phase field.
      this.h0.push(initialSpectrum(sea, band, 0, c));
      this.wave.push(waveData(sea, band, 0));
      this.heightField.push(new Float32Array(resolution * resolution));
      this.displacementX.push(new Float32Array(resolution * resolution));
      this.displacementZ.push(new Float32Array(resolution * resolution));
    }

    this.plan = butterflyPlan(resolution);
    this.spectrumH = new Float32Array(resolution * resolution * 2);
    this.spectrumD = new Float32Array(resolution * resolution * 2);
    this.scratch = new Float32Array(resolution * resolution * 2);
    this.sample = [0, 0, 0];
    this.time = NaN;
  }

  /** Rebuilds the spatial fields for a given time. Idempotent for a repeated time. */
  update(time) {
    if (time === this.time) return;
    this.time = time;
    for (let c = 0; c < this.cascadeCount; c++) this.evolve(c, time);
  }

  evolve(c, time) {
    const n = this.n;
    const h0 = this.h0[c];
    const wave = this.wave[c];
    const h = this.spectrumH;
    const d = this.spectrumD;

    for (let i = 0; i < n * n; i++) {
      const o4 = i * 4;
      const o2 = i * 2;
      const phase = wave[o4 + 3] * time;
      const cos = Math.cos(phase);
      const sin = Math.sin(phase);
      const a = h0[o4], b = h0[o4 + 1];
      const p = h0[o4 + 2], q = h0[o4 + 3];
      // h~(k,t) = h0 e^{iwt} + conj(h0(-k)) e^{-iwt}
      const hRe = a * cos - b * sin + p * cos + q * sin;
      const hIm = a * sin + b * cos - p * sin + q * cos;
      h[o2] = hRe;
      h[o2 + 1] = hIm;

      const kx = wave[o4];
      const kz = wave[o4 + 1];
      const k = wave[o4 + 2];
      if (k < 1e-9) {
        d[o2] = 0;
        d[o2 + 1] = 0;
        continue;
      }
      // D = -i (k/|k|) h~, and multiplying by -i maps (re, im) -> (im, -re).
      const nx = kx / k;
      const nz = kz / k;
      const dxRe = hIm * nx;
      const dxIm = -hRe * nx;
      const dzRe = hIm * nz;
      const dzIm = -hRe * nz;
      // Two Hermitian signals packed into one transform: the real part of the
      // result comes out as Dx and the imaginary part as Dz.
      d[o2] = dxRe - dzIm;
      d[o2 + 1] = dxIm + dzRe;
    }

    this.inverse2d(h);
    this.inverse2d(d);

    const hf = this.heightField[c];
    const dxf = this.displacementX[c];
    const dzf = this.displacementZ[c];
    for (let i = 0; i < n * n; i++) {
      hf[i] = h[i * 2];
      dxf[i] = d[i * 2] * this.choppiness;
      dzf[i] = d[i * 2 + 1] * this.choppiness;
    }
  }

  /** Table-driven inverse transform, the same butterfly schedule the GPU runs. */
  inverse2d(data) {
    const n = this.n;
    const { stages, table } = this.plan;
    const scratch = this.scratch;

    for (let axis = 0; axis < 2; axis++) {
      for (let stage = 0; stage < stages; stage++) {
        for (let row = 0; row < n; row++) {
          for (let col = 0; col < n; col++) {
            const lane = axis === 0 ? col : row;
            const o = (lane * stages + stage) * 4;
            const wr = table[o];
            const wi = table[o + 1];
            const ia = table[o + 2];
            const ib = table[o + 3];
            const aIndex = (axis === 0 ? row * n + ia : ia * n + col) * 2;
            const bIndex = (axis === 0 ? row * n + ib : ib * n + col) * 2;
            const br = data[bIndex];
            const bi = data[bIndex + 1];
            const out = (row * n + col) * 2;
            scratch[out] = data[aIndex] + (wr * br - wi * bi);
            scratch[out + 1] = data[aIndex + 1] + (wr * bi + wi * br);
          }
        }
        data.set(scratch);
      }
    }
  }

  /** Bilinear lookup with wrapping, in one cascade's own patch space. */
  sampleField(field, cascade, worldX, worldZ) {
    const n = this.n;
    const patch = this.patchSizes[cascade];
    const fx = (worldX / patch) * n;
    const fz = (worldZ / patch) * n;
    const x0 = Math.floor(fx);
    const z0 = Math.floor(fz);
    const tx = fx - x0;
    const tz = fz - z0;
    const wrap = (v) => ((v % n) + n) % n;
    const xi0 = wrap(x0);
    const zi0 = wrap(z0);
    const xi1 = (xi0 + 1) % n;
    const zi1 = (zi0 + 1) % n;

    const v00 = field[zi0 * n + xi0];
    const v10 = field[zi0 * n + xi1];
    const v01 = field[zi1 * n + xi0];
    const v11 = field[zi1 * n + xi1];
    const a = v00 + (v10 - v00) * tx;
    const b = v01 + (v11 - v01) * tx;
    return a + (b - a) * tz;
  }

  /** Displacement of the grid point whose undisplaced position is (u, v). */
  displacementAtGridPoint(u, v, out) {
    let dx = 0;
    let h = 0;
    let dz = 0;
    for (let c = 0; c < this.cascadeCount; c++) {
      dx += this.sampleField(this.displacementX[c], c, u, v);
      h += this.sampleField(this.heightField[c], c, u, v);
      dz += this.sampleField(this.displacementZ[c], c, u, v);
    }
    out[0] = dx;
    out[1] = h;
    out[2] = dz;
    return out;
  }

  /**
   * Surface elevation directly above a world position, metres above mean water.
   *
   * Four fixed-point steps back along the displacement, the same count
   * CpuOceanSurface uses; it converges quickly while the choppiness stays below
   * the self-intersection limit the renderer also respects.
   */
  heightAt(worldX, worldZ) {
    const out = this.sample;
    let u = worldX;
    let v = worldZ;
    for (let i = 0; i < 4; i++) {
      this.displacementAtGridPoint(u, v, out);
      u = worldX - out[0];
      v = worldZ - out[2];
    }
    return this.displacementAtGridPoint(u, v, out)[1];
  }

  /** Root-mean-square surface elevation across the grid, for diagnostics and tests. */
  rmsElevation() {
    let sum = 0;
    let count = 0;
    for (let c = 0; c < this.cascadeCount; c++) {
      for (const v of this.heightField[c]) {
        sum += v * v;
        count++;
      }
    }
    // Cascades are independent, so their variances add.
    return Math.sqrt(sum / (count / this.cascadeCount));
  }
}
