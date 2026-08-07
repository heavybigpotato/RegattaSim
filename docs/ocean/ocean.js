// The WebGL 2 ocean.
//
// This runs the identical pipeline the Android client runs, using the identical
// shaders: evolve the spectrum, inverse FFT it in fragment passes, assemble
// displacement and derivative maps, draw a screen-space projected grid.
//
// It exists because iOS cannot install an Android APK and Apple will not run
// unsigned code, so a browser is the only way onto an iPhone that costs nothing
// and needs no computer. Per the design brief this is the showcase, not the game:
// the native client remains the product, and the brief's ban on an HTML/JS *engine*
// stands.
//
// The reason it is even possible is a decision made much earlier for a different
// reason. The FFT was implemented as fragment ping-pong passes rather than compute
// shaders, to cover GLES 3.0 devices. WebGL 2 has no compute shaders at all, so the
// compute version could not have been ported. The fragment version needs no changes.

import { SHADERS } from './shaders.js';
import * as S from './spectrum.js';
import { buildHull, buildSails } from './hull.js';

const HEADER = '#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\n';

// --- minimal 4x4 matrix maths ----------------------------------------------
// Column-major, matching what WebGL expects for uniformMatrix4fv.

function mat4Identity() {
  return new Float32Array([1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1]);
}

function mat4Multiply(a, b) {
  const o = new Float32Array(16);
  for (let c = 0; c < 4; c++) {
    for (let r = 0; r < 4; r++) {
      let sum = 0;
      for (let k = 0; k < 4; k++) sum += a[k * 4 + r] * b[c * 4 + k];
      o[c * 4 + r] = sum;
    }
  }
  return o;
}

function mat4Perspective(fovYRadians, aspect, near, far) {
  const f = 1 / Math.tan(fovYRadians / 2);
  const o = new Float32Array(16);
  o[0] = f / aspect;
  o[5] = f;
  o[10] = (far + near) / (near - far);
  o[11] = -1;
  o[14] = (2 * far * near) / (near - far);
  return o;
}

/** Orthographic projection, for the shadow camera. */
function mat4Ortho(halfExtent, near, far) {
  const o = mat4Identity();
  o[0] = 1 / halfExtent;
  o[5] = 1 / halfExtent;
  o[10] = -2 / (far - near);
  o[14] = -(far + near) / (far - near);
  return o;
}

function mat4LookAt(eye, dir, up) {
  const f = normalise(dir);
  const s = normalise(cross(f, up));
  const u = cross(s, f);
  const o = mat4Identity();
  o[0] = s[0]; o[4] = s[1]; o[8] = s[2];
  o[1] = u[0]; o[5] = u[1]; o[9] = u[2];
  o[2] = -f[0]; o[6] = -f[1]; o[10] = -f[2];
  o[12] = -dot(s, eye);
  o[13] = -dot(u, eye);
  o[14] = dot(f, eye);
  return o;
}

function mat4Invert(m) {
  const inv = new Float32Array(16);
  inv[0] = m[5]*m[10]*m[15] - m[5]*m[11]*m[14] - m[9]*m[6]*m[15] + m[9]*m[7]*m[14] + m[13]*m[6]*m[11] - m[13]*m[7]*m[10];
  inv[4] = -m[4]*m[10]*m[15] + m[4]*m[11]*m[14] + m[8]*m[6]*m[15] - m[8]*m[7]*m[14] - m[12]*m[6]*m[11] + m[12]*m[7]*m[10];
  inv[8] = m[4]*m[9]*m[15] - m[4]*m[11]*m[13] - m[8]*m[5]*m[15] + m[8]*m[7]*m[13] + m[12]*m[5]*m[11] - m[12]*m[7]*m[9];
  inv[12] = -m[4]*m[9]*m[14] + m[4]*m[10]*m[13] + m[8]*m[5]*m[14] - m[8]*m[6]*m[13] - m[12]*m[5]*m[10] + m[12]*m[6]*m[9];
  inv[1] = -m[1]*m[10]*m[15] + m[1]*m[11]*m[14] + m[9]*m[2]*m[15] - m[9]*m[3]*m[14] - m[13]*m[2]*m[11] + m[13]*m[3]*m[10];
  inv[5] = m[0]*m[10]*m[15] - m[0]*m[11]*m[14] - m[8]*m[2]*m[15] + m[8]*m[3]*m[14] + m[12]*m[2]*m[11] - m[12]*m[3]*m[10];
  inv[9] = -m[0]*m[9]*m[15] + m[0]*m[11]*m[13] + m[8]*m[1]*m[15] - m[8]*m[3]*m[13] - m[12]*m[1]*m[11] + m[12]*m[3]*m[9];
  inv[13] = m[0]*m[9]*m[14] - m[0]*m[10]*m[13] - m[8]*m[1]*m[14] + m[8]*m[2]*m[13] + m[12]*m[1]*m[10] - m[12]*m[2]*m[9];
  inv[2] = m[1]*m[6]*m[15] - m[1]*m[7]*m[14] - m[5]*m[2]*m[15] + m[5]*m[3]*m[14] + m[13]*m[2]*m[7] - m[13]*m[3]*m[6];
  inv[6] = -m[0]*m[6]*m[15] + m[0]*m[7]*m[14] + m[4]*m[2]*m[15] - m[4]*m[3]*m[14] - m[12]*m[2]*m[7] + m[12]*m[3]*m[6];
  inv[10] = m[0]*m[5]*m[15] - m[0]*m[7]*m[13] - m[4]*m[1]*m[15] + m[4]*m[3]*m[13] + m[12]*m[1]*m[7] - m[12]*m[3]*m[5];
  inv[14] = -m[0]*m[5]*m[14] + m[0]*m[6]*m[13] + m[4]*m[1]*m[14] - m[4]*m[2]*m[13] - m[12]*m[1]*m[6] + m[12]*m[2]*m[5];
  inv[3] = -m[1]*m[6]*m[11] + m[1]*m[7]*m[10] + m[5]*m[2]*m[11] - m[5]*m[3]*m[10] - m[9]*m[2]*m[7] + m[9]*m[3]*m[6];
  inv[7] = m[0]*m[6]*m[11] - m[0]*m[7]*m[10] - m[4]*m[2]*m[11] + m[4]*m[3]*m[10] + m[8]*m[2]*m[7] - m[8]*m[3]*m[6];
  inv[11] = -m[0]*m[5]*m[11] + m[0]*m[7]*m[9] + m[4]*m[1]*m[11] - m[4]*m[3]*m[9] - m[8]*m[1]*m[7] + m[8]*m[3]*m[5];
  inv[15] = m[0]*m[5]*m[10] - m[0]*m[6]*m[9] - m[4]*m[1]*m[10] + m[4]*m[2]*m[9] + m[8]*m[1]*m[6] - m[8]*m[2]*m[5];
  let det = m[0]*inv[0] + m[1]*inv[4] + m[2]*inv[8] + m[3]*inv[12];
  if (det === 0) return mat4Identity();
  det = 1 / det;
  for (let i = 0; i < 16; i++) inv[i] *= det;
  return inv;
}

/**
 * How far off dead astern the chase camera sits, radians. Positive swings it
 * toward the port quarter, since headings run from +X toward +Z and +Z is port.
 */
const CHASE_QUARTER = 0.55;

/**
 * Shadow map resolution, and the half-width of the box it covers in metres.
 *
 * One caster, twenty metres tall. At 1024 over a 44 m box that is a texel every
 * four centimetres, which resolves a shroud; the next size up resolves nothing more
 * that can be seen and costs four times the fill.
 */
const SHADOW_SIZE = 1024;
/** Smallest box the shadow camera covers, metres. Holds the boat and the rig. */
const SHADOW_MINIMUM_EXTENT = 22;
/**
 * Largest box, metres. The shadow of a rig at sunset is hundreds of metres long and
 * following it all the way would leave a texel the size of a cockpit; past this the
 * far end simply is not drawn, which is far less noticeable than blocks.
 */
const SHADOW_MAXIMUM_EXTENT = 65;
/** Height of the rig above the waterline, for sizing the shadow's reach. */
const RIG_HEIGHT = 19;

/**
 * How dark a shadow goes. Not 1: a shadow outdoors is never black, it is lit by the
 * whole sky dome and, on the water, by light scattering in from all around it.
 */
const SHADOW_STRENGTH = 0.86;

/**
 * Places the boat: translate to its position, rotate to its heading, then apply
 * the pitch and roll the physics read off the wave surface.
 *
 * Heading rotates about +Y from +X toward +Z. Pitch is bow-up about the
 * port-starboard axis, roll is starboard-down about the fore-and-aft axis - the
 * same senses SailingBoat reports, so a boat climbing a wave face lifts its bow
 * on screen rather than burying it.
 */
function boatModelMatrix(x, y, z, heading, pitch, roll) {
  const ch = Math.cos(heading);
  const sh = Math.sin(heading);
  const cp = Math.cos(pitch);
  const sp = Math.sin(pitch);
  const cr = Math.cos(roll);
  const sr = Math.sin(roll);

  // Local basis: forward is +X, up is +Y, port is +Z.
  // Pitch lifts forward; roll drops the starboard side, which is -Z.
  const fwd = [cp, sp, 0];
  const up = [-sp * cr, cp * cr, -sr];
  const port = [-sp * -sr, cp * -sr, cr];

  // Rotate each local axis into the world by the heading.
  const toWorld = (v) => [v[0] * ch - v[2] * sh, v[1], v[0] * sh + v[2] * ch];
  const F = toWorld(fwd);
  const U = toWorld(up);
  const P = toWorld(port);

  return new Float32Array([
    F[0], F[1], F[2], 0,
    U[0], U[1], U[2], 0,
    P[0], P[1], P[2], 0,
    x, y, z, 1,
  ]);
}

/** Inverse-transpose of the model's upper 3x3, for normals. */
function normalMatrix(m) {
  return new Float32Array([
    m[0], m[1], m[2],
    m[4], m[5], m[6],
    m[8], m[9], m[10],
  ]);
}

const cross = (a, b) => [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]];
const dot = (a, b) => a[0]*b[0] + a[1]*b[1] + a[2]*b[2];
const normalise = (v) => {
  const l = Math.hypot(v[0], v[1], v[2]) || 1;
  return [v[0]/l, v[1]/l, v[2]/l];
};

/** Projects a point through a matrix, with the perspective divide. */
function project(m, x, y, z) {
  const w = m[3]*x + m[7]*y + m[11]*z + m[15];
  const iw = w === 0 ? 1 : 1 / w;
  return [
    (m[0]*x + m[4]*y + m[8]*z + m[12]) * iw,
    (m[1]*x + m[5]*y + m[9]*z + m[13]) * iw,
    (m[2]*x + m[6]*y + m[10]*z + m[14]) * iw,
  ];
}

// --- GL helpers -------------------------------------------------------------

function compile(gl, type, source, name) {
  const s = gl.createShader(type);
  gl.shaderSource(s, HEADER + source);
  gl.compileShader(s);
  if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
    throw new Error(`${name} failed to compile:\n${gl.getShaderInfoLog(s)}`);
  }
  return s;
}

/**
 * Compiles and links a program.
 *
 * Attribute locations are bound *before* linking, and uniform locations cached
 * after. That order is not cosmetic: re-linking a program invalidates every
 * uniform location previously handed out, so binding attributes after the fact
 * and re-linking leaves the cache pointing at nothing and every subsequent
 * gl.uniform* call raises GL_INVALID_OPERATION.
 */
function program(gl, vertName, fragName, attributes = []) {
  const p = gl.createProgram();
  gl.attachShader(p, compile(gl, gl.VERTEX_SHADER, SHADERS[vertName], vertName));
  gl.attachShader(p, compile(gl, gl.FRAGMENT_SHADER, SHADERS[fragName], fragName));
  attributes.forEach((name, index) => gl.bindAttribLocation(p, index, name));
  gl.linkProgram(p);
  if (!gl.getProgramParameter(p, gl.LINK_STATUS)) {
    throw new Error(`${vertName} + ${fragName} failed to link:\n${gl.getProgramInfoLog(p)}`);
  }
  // Cache uniform locations: looking them up per draw is a measurable cost on
  // mobile, and there are a lot of draws here.
  p.u = {};
  const count = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS);
  for (let i = 0; i < count; i++) {
    const name = gl.getActiveUniform(p, i).name.replace(/\[0\]$/, '');
    p.u[name] = gl.getUniformLocation(p, name);
  }
  return p;
}

function dataTexture(gl, width, height, data) {
  const t = gl.createTexture();
  gl.bindTexture(gl.TEXTURE_2D, t);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, width, height, 0, gl.RGBA, gl.FLOAT, data);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.REPEAT);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.REPEAT);
  return t;
}

/**
 * What this device can actually render into.
 *
 * WebGL 2 makes no float format colour-renderable on its own: RGBA32F needs
 * EXT_color_buffer_float, and RGBA16F needs *either* that or
 * EXT_color_buffer_half_float. Desktop GPUs give you both and it is easy to write
 * code that assumes so. A great many iPhones expose only the half-float one, and
 * demanding the other is the difference between an ocean and an error page - which
 * is exactly what this build did, on the only kind of device it was written for.
 */
/**
 * Whether this context will actually render to a texture of the given format, by
 * trying it rather than by asking.
 *
 * getExtension is a question about what the driver advertises. This is the thing
 * the renderer actually needs, and the two have been seen to disagree: an iPhone
 * refused to start with "neither EXT_color_buffer_float nor
 * EXT_color_buffer_half_float is available" while the diagnostic panel on the same
 * page reported both as present. Whatever the cause of that, an allocation that
 * comes back complete is the authority - the extension list is hearsay about it.
 */
function canRender(gl, internalFormat) {
  const tex = gl.createTexture();
  gl.bindTexture(gl.TEXTURE_2D, tex);
  gl.texImage2D(gl.TEXTURE_2D, 0, internalFormat, 4, 4, 0, gl.RGBA, gl.FLOAT, null);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);

  const fbo = gl.createFramebuffer();
  gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
  gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
  const complete = gl.checkFramebufferStatus(gl.FRAMEBUFFER) === gl.FRAMEBUFFER_COMPLETE;

  gl.bindFramebuffer(gl.FRAMEBUFFER, null);
  gl.deleteFramebuffer(fbo);
  gl.deleteTexture(tex);
  return complete;
}

function detectFormats(gl) {
  // Ask first, because the answer is usually right and enabling the extension is
  // what makes the format renderable in the first place. Fall through to trying it
  // only when the answer is no, so a driver that misreports itself costs a four
  // pixel allocation rather than the whole page.
  const float32 = !!gl.getExtension('EXT_color_buffer_float') || canRender(gl, gl.RGBA32F);
  const half = float32
    || !!gl.getExtension('EXT_color_buffer_half_float')
    || canRender(gl, gl.RGBA16F);
  return { float32, half };
}

/**
 * A WebGL 2 context that can render the ocean, retrying once on a fresh canvas.
 *
 * A phone reported neither float extension while the failure page's own diagnostic
 * - which builds a throwaway canvas with default attributes - reported both on the
 * same device, seconds apart. A context belongs to its canvas for the life of the
 * element and getContext hands back the one it already made, attributes and all, so
 * the only way to ask a second time is with a new element. Asking for nothing in
 * particular the second time is deliberate: powerPreference buys nothing on a phone
 * with one GPU, and the attributes are the only difference between the context that
 * failed and the one that worked.
 */
function acquireContext(canvas) {
  const attributes = [
    { antialias: false, alpha: false, powerPreference: 'high-performance' },
    {},
  ];
  let best = null;
  for (let attempt = 0; attempt < attributes.length; attempt++) {
    if (attempt > 0) {
      // A detached canvas has nothing to be replaced in, so one attempt is all
      // there is; cloneNode(false) keeps the id and classes the page styles it by.
      if (!canvas.parentNode) break;
      const replacement = canvas.cloneNode(false);
      canvas.replaceWith(replacement);
      canvas = replacement;
    }
    const gl = canvas.getContext('webgl2', attributes[attempt]);
    if (!gl || gl.isContextLost()) continue;
    const formats = detectFormats(gl);
    best = { gl, canvas, formats };
    if (formats.half) break;
  }
  return best;
}

/**
 * Build marker, shown on the failure page.
 *
 * Bumped whenever something here changes that a phone would experience. The only
 * feedback channel for this page is a screenshot from someone else's device, and
 * without a version in the frame there is no way to tell a fix that did not work
 * from a fix that was never fetched - a browser cache and a broken build look
 * exactly alike.
 */
export const BUILD = '2026-08-07.5';

/** Starting ceiling on the scene target, in pixels. */
const PIXEL_BUDGET = 1.6e6;

/**
 * Floor for that ceiling. Below this the picture is too coarse to be worth showing
 * and the device is not going to run this renderer at all, so the allocation
 * failure is reported rather than shrunk away from any further.
 */
const MINIMUM_PIXEL_BUDGET = 1.2e5;

/** Everything worth knowing when the page cannot start, for the failure message. */
export function describeContext(canvas) {
  const gl = canvas.getContext('webgl2');
  if (!gl) {
    const one = canvas.getContext('webgl') || canvas.getContext('experimental-webgl');
    return `WebGL 2 unavailable; WebGL 1 ${one ? 'is' : 'is not'} available`;
  }
  const info = gl.getExtension('WEBGL_debug_renderer_info');
  const renderer = info
    ? gl.getParameter(info.UNMASKED_RENDERER_WEBGL)
    : gl.getParameter(gl.RENDERER);
  const has = (name) => (gl.getExtension(name) ? 'yes' : 'no');
  const lost = gl.isContextLost();
  // What the driver advertises and what it will actually do are separate questions,
  // and a report that only answered the first one sent us chasing an extension that
  // was present all along. The renderable lines are the ones that decide whether
  // this page can run; a lost context answers no to everything, hence the flag.
  const renderable = (format) => (lost ? 'n/a' : (canRender(gl, format) ? 'yes' : 'no'));
  return [
    `build: ${BUILD}`,
    `renderer: ${renderer}`,
    `context lost: ${lost ? 'yes' : 'no'}`,
    `EXT_color_buffer_float: ${has('EXT_color_buffer_float')}`,
    `EXT_color_buffer_half_float: ${has('EXT_color_buffer_half_float')}`,
    `OES_texture_float_linear: ${has('OES_texture_float_linear')}`,
    `RGBA32F renderable: ${renderable(gl.RGBA32F)}`,
    `RGBA16F renderable: ${renderable(gl.RGBA16F)}`,
    `max texture: ${gl.getParameter(gl.MAX_TEXTURE_SIZE)}`,
    `max renderbuffer: ${gl.getParameter(gl.MAX_RENDERBUFFER_SIZE)}`,
    `drawing buffer: ${gl.drawingBufferWidth}x${gl.drawingBufferHeight}`,
  ].join('\n');
}

/**
 * A render target, built from whichever format combination this device accepts.
 *
 * Probed rather than assumed. Two things vary between devices and neither can be
 * predicted from the extension list alone: which colour formats are renderable,
 * and which depth attachment they will accept alongside. An iPhone returned
 * FRAMEBUFFER_UNSUPPORTED (0x8CDD) for RGBA16F with a 16-bit depth renderbuffer -
 * a combination every desktop driver takes without complaint. So the candidates
 * are tried in order of preference and the first complete one wins.
 *
 * Float32 with nearest sampling is what the FFT intermediates want; float16 with
 * linear sampling and repeat wrapping is what the surface shader reads, because
 * linear filtering of 16-bit float is core in WebGL 2 and of 32-bit float it is not.
 */
function target(gl, width, height, { float32 = false, tiling = false, depth = false } = {}) {
  const formats = gl.__formats || { float32: true, half: true };
  // Preference order. Asking for 32-bit on a device that has not got it falls back
  // to 16, which costs precision in the transform and nothing else - and a slightly
  // noisier ocean is not in the same category of problem as no ocean.
  const colours = [];
  if (float32 && formats.float32) colours.push(gl.RGBA32F);
  if (formats.half) colours.push(gl.RGBA16F);
  if (colours.length === 0) {
    throw new Error('no floating point colour format is renderable on this device');
  }

  const depths = depth
    ? [
      { format: gl.DEPTH_COMPONENT24, attachment: gl.DEPTH_ATTACHMENT },
      { format: gl.DEPTH_COMPONENT16, attachment: gl.DEPTH_ATTACHMENT },
      { format: gl.DEPTH24_STENCIL8, attachment: gl.DEPTH_STENCIL_ATTACHMENT },
    ]
    : [null];

  const attempts = [];
  for (const colour of colours) {
    for (const d of depths) {
      const tex = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, tex);
      gl.texImage2D(gl.TEXTURE_2D, 0, colour, width, height, 0, gl.RGBA, gl.FLOAT, null);
      // 32-bit float is only linearly filterable with an extension; 16-bit always is.
      const filter = colour === gl.RGBA32F ? gl.NEAREST : gl.LINEAR;
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, filter);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, filter);
      const wrap = tiling ? gl.REPEAT : gl.CLAMP_TO_EDGE;
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, wrap);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, wrap);

      const fbo = gl.createFramebuffer();
      gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
      let depthBuffer = null;
      if (d) {
        depthBuffer = gl.createRenderbuffer();
        gl.bindRenderbuffer(gl.RENDERBUFFER, depthBuffer);
        gl.renderbufferStorage(gl.RENDERBUFFER, d.format, width, height);
        gl.framebufferRenderbuffer(gl.FRAMEBUFFER, d.attachment, gl.RENDERBUFFER, depthBuffer);
      }
      const status = gl.checkFramebufferStatus(gl.FRAMEBUFFER);
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
      if (status === gl.FRAMEBUFFER_COMPLETE) {
        return { tex, fbo, width, height, colour, depthBuffer };
      }
      attempts.push(`0x${status.toString(16)}`);
      gl.deleteFramebuffer(fbo);
      gl.deleteTexture(tex);
      if (depthBuffer) gl.deleteRenderbuffer(depthBuffer);
    }
  }
  throw new Error(
    `no usable framebuffer at ${width}x${height} (tried ${attempts.join(', ')})`);
}

// --- the ocean --------------------------------------------------------------

export class Ocean {
  constructor(canvas, options = {}) {
    const acquired = acquireContext(canvas);
    if (!acquired) throw new Error('WebGL 2 is not available in this browser.');
    // acquireContext may have swapped the element to get a usable context, so the
    // canvas the page handed in is not necessarily the one being drawn on.
    const gl = acquired.gl;
    canvas = acquired.canvas;

    // What the device will render into, rather than what would be convenient.
    // EXT_color_buffer_float is the nice one and a great many iPhones do not have
    // it; EXT_color_buffer_half_float is enough to run everything here, at the cost
    // of doing the transform in half precision.
    const formats = acquired.formats;
    if (!formats.half) {
      throw new Error(
        'this device cannot render to a floating point texture, which the wave '
        + 'transform needs (neither EXT_color_buffer_float nor '
        + 'EXT_color_buffer_half_float is available)');
    }
    gl.__formats = formats;
    this.formats = formats;
    // Linear filtering of 32-bit float is an extension too. Without it the
    // displacement maps have to stay 16-bit whatever else is available, which they
    // already are - this is asked for so the flag is accurate, not to gate anything.
    gl.getExtension('OES_texture_float_linear');
    this.gl = gl;
    this.canvas = canvas;

    this.resolution = options.resolution || 128;
    this.patchSizes = options.patchSizes || [512, 128, 16];
    this.gridColumns = options.gridColumns || 192;
    this.gridRows = options.gridRows || 128;

    this.heading = options.heading ?? 1.9;
    this.pitch = options.pitch ?? -0.09;
    this.eyeHeight = options.eyeHeight ?? 4.5;
    this.sunElevation = options.sunElevation ?? 0.30;
    this.sunAzimuth = options.sunAzimuth ?? 2.6;
    this.turbidity = options.turbidity ?? 2.6;

    this.time = options.time ?? 0;
    this.exposure = 1;
    /**
     * Ceiling on the scene target, in pixels. Lowered - and left lowered - if the
     * device turns out not to have the memory for it. See resize().
     */
    this.pixelBudget = options.pixelBudget ?? PIXEL_BUDGET;
    /** Attach a Boat from sailing.js to switch to chase view and draw a hull. */
    this.boat = null;
    /** Extra yaw applied to the chase camera, so the player can look around. */
    this.orbit = 0;

    const quadAttributes = ['a_position', 'a_texCoord0'];
    this.programs = {
      evolve: program(gl, 'fullscreen.vert', 'ocean_evolve.frag', quadAttributes),
      fft: program(gl, 'fullscreen.vert', 'ocean_fft.frag', quadAttributes),
      displacement: program(gl, 'fullscreen.vert', 'ocean_assemble_displacement.frag', quadAttributes),
      derivatives: program(gl, 'fullscreen.vert', 'ocean_assemble_derivatives.frag', quadAttributes),
      surface: program(gl, 'ocean_surface.vert', 'ocean_surface.frag', ['a_position']),
      sky: program(gl, 'sky.vert', 'sky.frag', quadAttributes),
      tonemap: program(gl, 'fullscreen.vert', 'post_tonemap.frag', quadAttributes),
      boat: program(gl, 'boat.vert', 'boat.frag',
        ['a_position', 'a_normal', 'a_material', 'a_uv']),
      shadow: program(gl, 'shadow_depth.vert', 'shadow_depth.frag',
        ['a_position', 'a_normal', 'a_material', 'a_uv']),
    };

    this.buildQuad();
    this.buildGrid();
    this.buildBoat();
    this.setSeaState(options.sea || S.seaState({}));
    this.resize();
  }

  buildQuad() {
    const gl = this.gl;
    this.quad = gl.createVertexArray();
    gl.bindVertexArray(this.quad);
    const vbo = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, vbo);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([
      -1, -1, 0, 0,   1, -1, 1, 0,   1, 1, 1, 1,
      -1, -1, 0, 0,   1, 1, 1, 1,   -1, 1, 0, 1,
    ]), gl.STATIC_DRAW);
    // Attribute 0 is a_position and 1 is a_texCoord0 in every fullscreen program,
    // bound at link time above.
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 16, 0);
    gl.enableVertexAttribArray(1);
    gl.vertexAttribPointer(1, 2, gl.FLOAT, false, 16, 8);
    gl.bindVertexArray(null);
  }

  buildGrid() {
    const gl = this.gl;
    const cols = this.gridColumns;
    const rows = this.gridRows;
    const verts = new Float32Array((cols + 1) * (rows + 1) * 2);
    let v = 0;
    for (let y = 0; y <= rows; y++) {
      for (let x = 0; x <= cols; x++) {
        verts[v++] = x / cols;
        verts[v++] = y / rows;
      }
    }
    // WebGL 2 has 32-bit indices natively, so the 65535-vertex ceiling the
    // libGDX mesh works around does not apply here.
    const indices = new Uint32Array(cols * rows * 6);
    let i = 0;
    for (let y = 0; y < rows; y++) {
      for (let x = 0; x < cols; x++) {
        const base = y * (cols + 1) + x;
        const right = base + 1;
        const below = base + cols + 1;
        indices[i++] = base; indices[i++] = below; indices[i++] = right;
        indices[i++] = right; indices[i++] = below; indices[i++] = below + 1;
      }
    }
    this.gridIndexCount = indices.length;

    this.grid = gl.createVertexArray();
    gl.bindVertexArray(this.grid);
    const vbo = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, vbo);
    gl.bufferData(gl.ARRAY_BUFFER, verts, gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    const ibo = gl.createBuffer();
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, ibo);
    gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, indices, gl.STATIC_DRAW);
    gl.bindVertexArray(null);
  }

  /** Uploads the generated hull and an initial mainsail. */
  buildBoat() {
    const gl = this.gl;
    this.hullGeometry = buildHull();
    this.boatMeshes = {
      hull: this.uploadMesh(this.hullGeometry),
      sail: this.uploadMesh(buildSails(this.hullGeometry, 0.35)),
    };
    this.sailSheetAngle = 0.35;

    // A shadow map is a luxury, and a megapixel of it is the single largest thing
    // this renderer asks a device for after the scene target. On a phone short of
    // memory the request is refused, and taking that as fatal would trade the whole
    // ocean for a shadow. Halve it instead, and sail without one rather than not at
    // all - bindShadow already has a path for that, because the sun going below the
    // horizon needs the same thing.
    this.shadowMap = null;
    this.shadowSize = 0;
    for (let size = SHADOW_SIZE; size >= SHADOW_SIZE / 4; size /= 2) {
      try {
        this.shadowMap = target(gl, size, size, { depth: true });
        this.shadowSize = size;
        break;
      } catch (error) {
        // Try the next size down; the last failure falls out of the loop with no
        // shadow map, which is a picture without shadows rather than no picture.
      }
    }
    this.lightViewProjection = mat4Identity();
  }

  /**
   * Renders the boat into the shadow map from the sun's direction.
   *
   * Must run before anything that samples it and outside any other target's
   * binding. The camera is orthographic and follows the boat, so the map never has
   * to cover more than one boat's worth of world however far she has sailed.
   */
  renderShadowMap(sun) {
    const gl = this.gl;
    const b = this.boat;
    // No map means the device would not give us one; bindShadow turns the lookup
    // off to match.
    if (!this.shadowMap) return;
    // The box has to hold the boat *and* the shadow it throws, and how far that
    // reaches depends entirely on how low the sun is: a rig 19 m tall lays its shadow
    // 37 m away at a 26 degree sun and twice that an hour later. Sizing the box for
    // the boat alone - the obvious thing to do - cuts the shadow off in a straight
    // line across the sea a few lengths to leeward.
    const elevation = Math.max(0.12, sun[1]);
    const reach = (RIG_HEIGHT * Math.sqrt(1 - elevation * elevation)) / elevation;
    const extent = Math.min(SHADOW_MAXIMUM_EXTENT,
      Math.max(SHADOW_MINIMUM_EXTENT, reach * 0.5 + 14));

    // Centred half way along the shadow rather than on the boat, so the box is spent
    // on where the shadow actually is.
    const alongLength = Math.hypot(sun[0], sun[2]) || 1;
    const alongX = -sun[0] / alongLength;
    const alongZ = -sun[2] / alongLength;
    const offset = Math.min(reach, SHADOW_MAXIMUM_EXTENT) * 0.5;
    const target3 = [b.x + alongX * offset, b.heave + 5, b.z + alongZ * offset];
    const eye = [
      target3[0] + sun[0] * extent * 2.2,
      target3[1] + sun[1] * extent * 2.2,
      target3[2] + sun[2] * extent * 2.2,
    ];
    const dir = normalise([
      target3[0] - eye[0], target3[1] - eye[1], target3[2] - eye[2]]);
    // Any up vector will do as long as it is not parallel to the light; with the sun
    // near the zenith, +Y is.
    const up = Math.abs(dir[1]) > 0.99 ? [1, 0, 0] : [0, 1, 0];
    const view = mat4LookAt(eye, dir, up);
    const projection = mat4Ortho(extent, 0.1, extent * 5);
    this.lightViewProjection = mat4Multiply(projection, view);
    this.shadowTexelSize = (2 * extent) / this.shadowSize;

    this.bindTarget(this.shadowMap);
    // Cleared to 1, which reads as "nothing between here and the sun".
    gl.clearColor(1, 1, 1, 1);
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
    gl.enable(gl.DEPTH_TEST);
    gl.depthFunc(gl.LEQUAL);
    gl.depthMask(true);
    gl.disable(gl.CULL_FACE);

    const p = this.programs.shadow;
    gl.useProgram(p);
    gl.uniformMatrix4fv(p.u.u_lightViewProjection, false, this.lightViewProjection);
    gl.uniformMatrix4fv(p.u.u_model, false, boatModelMatrix(
      b.x, b.heave, b.z, b.heading, b.pitch, b.roll));
    for (const mesh of [this.boatMeshes.hull, this.boatMeshes.sail]) {
      gl.bindVertexArray(mesh.vao);
      gl.drawElements(gl.TRIANGLES, mesh.count, mesh.type, 0);
    }
  }

  /** Binds the shadow map and its matrix on a program that includes lib_shadow. */
  bindShadow(p, unit, strength) {
    const gl = this.gl;
    if (!this.shadowMap) strength = 0;
    if (strength > 0) {
      this.bindTexture(unit, this.shadowMap.tex, p, 'u_shadowMap');
    } else {
      // Still has to be bound to something valid even though the lookup returns
      // before it samples.
      this.bindTexture(unit, this.cascades[0].displacement.tex, p, 'u_shadowMap');
    }
    gl.uniformMatrix4fv(p.u.u_lightViewProjection, false, this.lightViewProjection);
    gl.uniform1f(p.u.u_shadowTexel, 1 / (this.shadowSize || SHADOW_SIZE));
    gl.uniform1f(p.u.u_shadowStrength, strength);
  }

  uploadMesh(geometry) {
    const gl = this.gl;
    const vao = gl.createVertexArray();
    gl.bindVertexArray(vao);

    const attribute = (index, data, size) => {
      const buffer = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
      gl.bufferData(gl.ARRAY_BUFFER, data, gl.STATIC_DRAW);
      gl.enableVertexAttribArray(index);
      gl.vertexAttribPointer(index, size, gl.FLOAT, false, 0, 0);
      return buffer;
    };
    attribute(0, geometry.positions, 3);
    attribute(1, geometry.normals, 3);
    attribute(2, geometry.materials, 1);
    attribute(3, geometry.uvs, 2);

    const ibo = gl.createBuffer();
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, ibo);
    gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, geometry.indices, gl.STATIC_DRAW);
    gl.bindVertexArray(null);
    // The baked hull is indexed with 16-bit indices where it fits; the sails are
    // built here and use 32. Asking the driver for the wrong one draws confetti.
    const type = geometry.indices instanceof Uint16Array
      ? gl.UNSIGNED_SHORT : gl.UNSIGNED_INT;
    return { vao, count: geometry.indices.length, type };
  }

  /** Rebuilds the mainsail when the sheet has moved enough to be worth it. */
  setSheetAngle(angle) {
    if (Math.abs(angle - this.sailSheetAngle) < 0.02) return;
    this.sailSheetAngle = angle;
    const gl = this.gl;
    gl.deleteVertexArray(this.boatMeshes.sail.vao);
    this.boatMeshes.sail = this.uploadMesh(buildSails(this.hullGeometry, angle));
  }

  /**
   * Draws the boat, if one has been attached. `boat` is a Boat from sailing.js;
   * the renderer only reads its state and never advances it.
   */
  drawBoat(cam, sun, sunColour) {
    if (!this.boat) return;
    const gl = this.gl;
    const p = this.programs.boat;
    gl.useProgram(p);
    gl.enable(gl.DEPTH_TEST);
    gl.depthFunc(gl.LEQUAL);
    // The sail is a membrane and the hull is closed, but a hull heeled far enough
    // shows its underside, so nothing is culled.
    gl.disable(gl.CULL_FACE);

    const model = boatModelMatrix(
      this.boat.x, this.boat.heave, this.boat.z,
      this.boat.heading, this.boat.pitch, this.boat.roll);

    gl.uniformMatrix4fv(p.u.u_viewProjection, false, cam.combined);
    gl.uniformMatrix4fv(p.u.u_model, false, model);
    gl.uniformMatrix3fv(p.u.u_normalMatrix, false, normalMatrix(model));
    gl.uniform3fv(p.u.u_cameraPosition, cam.eye);
    gl.uniform3fv(p.u.u_sunDirection, sun);
    gl.uniform3fv(p.u.u_sunColour, sunColour);
    gl.uniform1f(p.u.u_turbidity, this.turbidity);
    gl.uniform1f(p.u.u_waterLevel, this.boat.heave);
    gl.uniform1f(p.u.u_boatSpeed, this.boat.speed);
    this.bindShadow(p, 0, SHADOW_STRENGTH);

    for (const mesh of [this.boatMeshes.hull, this.boatMeshes.sail]) {
      gl.bindVertexArray(mesh.vao);
      gl.drawElements(gl.TRIANGLES, mesh.count, mesh.type, 0);
    }
  }

  setSeaState(sea) {
    const gl = this.gl;
    this.sea = sea;
    if (this.cascades) {
      for (const c of this.cascades) {
        gl.deleteTexture(c.h0);
        gl.deleteTexture(c.wave);
      }
    }
    const n = this.resolution;
    const settings = S.cascadeSettings(n, this.patchSizes);
    this.cascadeSettings = settings;
    // The CPU field the boat floats on: same spectrum, same seeds, same band
    // limits as the renderer, on a coarse grid. Two cascades of three - the
    // finest is 16 m of capillary chop that a 12 m hull cannot feel, and it is
    // three quarters of the cost.
    this.waveField = new S.WaveField(sea, settings, 32, 2);

    this.cascades = this.patchSizes.map((patch, i) => ({
      patch,
      h0: dataTexture(gl, n, n, S.initialSpectrum(sea, settings, i)),
      wave: dataTexture(gl, n, n, S.waveData(sea, settings, i)),
      // Full precision if the device has it. On half-float-only hardware these come
      // back as RGBA16F and the transform runs in half precision: about three
      // decimal digits per butterfly stage, which shows up as a slightly noisier
      // wave field and nothing else.
      spatialA: this.cascades?.[i]?.spatialA || target(gl, n, n, { float32: true }),
      spatialB: this.cascades?.[i]?.spatialB || target(gl, n, n, { float32: true }),
      displacement: this.cascades?.[i]?.displacement || target(gl, n, n, { tiling: true }),
      derivatives: this.cascades?.[i]?.derivatives || [
        target(gl, n, n, { tiling: true }), target(gl, n, n, { tiling: true }),
      ],
      derivativeIndex: 0,
    }));

    if (!this.butterfly) {
      const plan = S.butterflyPlan(n);
      this.stages = plan.stages;
      this.butterfly = dataTexture(gl, plan.stages, n, plan.table);
      this.scratch = target(gl, n, n, { float32: true });
    }

    // Whitecap coverage climbs steeply with wind: Force 4 has the odd breaking
    // crest, Force 9 is streaked white horizon to horizon.
    this.foamGain = Math.max(0.1, Math.min(3.0, 0.35 + 0.13 * (sea.windSpeed - 4)));
    this.maximumDisplacement = sea.significantWaveHeight * 1.2;
    this.updateExposure(true);
  }

  updateExposure(snap) {
    const mean = S.meanDomeLuminance(this.sunElevation, this.turbidity);
    const targetExposure = 0.5 / Math.max(mean, 0.02);
    this.exposure = snap ? targetExposure : this.exposure + (targetExposure - this.exposure) * 0.05;
  }

  /**
   * Backing store size for a given pixel budget.
   *
   * A modern phone reports a device pixel ratio of 3, and the ocean is fill-rate
   * bound, so rendering at native density costs nine times the pixels for detail
   * the display cannot resolve on water. There is a ceiling on the total as well as
   * the density: a large phone at ratio 2 is over two million pixels of half-float
   * scene target and depth, which is both the fill rate this renderer is short of
   * and the memory a struggling device has least of. Scaled rather than clamped, so
   * the aspect ratio is untouched.
   */
  backingStore(budget) {
    let ratio = Math.min(window.devicePixelRatio || 1, 2);
    const wanted = this.canvas.clientWidth * this.canvas.clientHeight * ratio * ratio;
    if (wanted > budget) ratio *= Math.sqrt(budget / wanted);
    return [
      Math.max(1, Math.floor(this.canvas.clientWidth * ratio)),
      Math.max(1, Math.floor(this.canvas.clientHeight * ratio)),
    ];
  }

  resize() {
    const gl = this.gl;
    let [width, height] = this.backingStore(this.pixelBudget);
    if (this.canvas.width === width && this.canvas.height === height && this.scene) return;

    if (this.scene) {
      gl.deleteTexture(this.scene.tex);
      gl.deleteFramebuffer(this.scene.fbo);
      // The depth renderbuffer too. Leaving it leaked a couple of megabytes every
      // time the canvas changed size, and on a phone the address bar collapsing and
      // reappearing does that repeatedly - so the page ran for a few seconds, ate
      // its memory budget, and died with an incomplete framebuffer or a lost
      // context. That is the whole of the "it plays and then crashes" report.
      if (this.scene.depthBuffer) gl.deleteRenderbuffer(this.scene.depthBuffer);
      this.scene = null;
    }

    // Give up resolution before giving up entirely. A device short of memory - a
    // phone with a dozen other tabs open, which is the normal state of a phone -
    // refuses the allocation at full size and takes it at half, so the choice here
    // is between a smaller ocean and no ocean. The reduced budget is kept rather
    // than retried at full size on the next resize: whatever made the memory scarce
    // is unlikely to have gone away, and thrashing between the two would be worse
    // than either.
    for (;;) {
      this.canvas.width = width;
      this.canvas.height = height;
      try {
        this.scene = target(gl, width, height, { depth: true });
        return;
      } catch (error) {
        if (this.pixelBudget <= MINIMUM_PIXEL_BUDGET) throw error;
        // Measured against what was just refused, not against the budget. The
        // budget is only a ceiling, so the failure can happen well under it - and
        // halving a ceiling nothing was touching would retry at exactly the size
        // that just failed, forever.
        this.pixelBudget = Math.max(
          MINIMUM_PIXEL_BUDGET, Math.min(this.pixelBudget, width * height) * 0.5);
        [width, height] = this.backingStore(this.pixelBudget);
      }
    }
  }

  drawQuad(p) {
    const gl = this.gl;
    gl.useProgram(p);
    gl.bindVertexArray(this.quad);
    gl.drawArrays(gl.TRIANGLES, 0, 6);
  }

  bindTarget(t) {
    const gl = this.gl;
    gl.bindFramebuffer(gl.FRAMEBUFFER, t.fbo);
    gl.viewport(0, 0, t.width, t.height);
  }

  bindTexture(unit, texture, program, name) {
    const gl = this.gl;
    gl.activeTexture(gl.TEXTURE0 + unit);
    gl.bindTexture(gl.TEXTURE_2D, texture);
    if (program.u[name] !== undefined) gl.uniform1i(program.u[name], unit);
  }

  /** Advances every cascade to the given time. */
  /**
   * Surface elevation the boat floats on. Backed by the CPU field rather than a
   * GPU readback, which would stall the pipeline every frame.
   */
  surfaceHeightAt(worldX, worldZ) {
    return this.waveField ? this.waveField.heightAt(worldX, worldZ) : 0;
  }

  simulate(time, deltaTime) {
    const gl = this.gl;
    // Keep the CPU field in step with the GPU one, so the hull and the drawn
    // surface never disagree about where the water is.
    this.waveField.update(time);
    gl.disable(gl.DEPTH_TEST);
    gl.disable(gl.BLEND);
    gl.disable(gl.CULL_FACE);

    for (const c of this.cascades) {
      this.evolve(c, time, 0, c.spatialA);
      this.evolve(c, time, 1, c.spatialB);
      this.transform(c.spatialA);
      this.transform(c.spatialB);
      this.assembleDisplacement(c);
      this.assembleDerivatives(c, deltaTime);
      c.derivativeIndex = 1 - c.derivativeIndex;
    }
  }

  evolve(cascade, time, outputSet, dst) {
    const gl = this.gl;
    const p = this.programs.evolve;
    this.bindTarget(dst);
    gl.useProgram(p);
    this.bindTexture(0, cascade.h0, p, 'u_h0');
    this.bindTexture(1, cascade.wave, p, 'u_waveData');
    gl.uniform1f(p.u.u_time, time);
    gl.uniform1i(p.u.u_outputSet, outputSet);
    this.drawQuad(p);
  }

  /**
   * In-place 2D inverse transform. The pass count is 2*log2(N), which is even, so
   * the result always lands back in the surface it started in and no copy is
   * needed.
   */
  transform(surface) {
    const gl = this.gl;
    const p = this.programs.fft;
    let src = surface;
    let dst = this.scratch;
    gl.useProgram(p);
    for (let axis = 0; axis < 2; axis++) {
      for (let stage = 0; stage < this.stages; stage++) {
        this.bindTarget(dst);
        this.bindTexture(0, src.tex, p, 'u_source');
        this.bindTexture(1, this.butterfly, p, 'u_butterfly');
        gl.uniform1i(p.u.u_stage, stage);
        gl.uniform1i(p.u.u_horizontal, axis === 0 ? 1 : 0);
        this.drawQuad(p);
        const swap = src; src = dst; dst = swap;
      }
    }
  }

  assembleDisplacement(cascade) {
    const gl = this.gl;
    const p = this.programs.displacement;
    this.bindTarget(cascade.displacement);
    gl.useProgram(p);
    this.bindTexture(0, cascade.spatialA.tex, p, 'u_spatialA');
    gl.uniform1f(p.u.u_choppiness, this.sea.choppiness);
    this.drawQuad(p);
  }

  assembleDerivatives(cascade, deltaTime) {
    const gl = this.gl;
    const p = this.programs.derivatives;
    const dst = cascade.derivatives[cascade.derivativeIndex];
    const prev = cascade.derivatives[1 - cascade.derivativeIndex];
    this.bindTarget(dst);
    gl.useProgram(p);
    this.bindTexture(0, cascade.spatialA.tex, p, 'u_spatialA');
    this.bindTexture(1, cascade.spatialB.tex, p, 'u_spatialB');
    this.bindTexture(2, prev.tex, p, 'u_previousFoam');
    gl.uniform1f(p.u.u_choppiness, this.sea.choppiness);
    gl.uniform1f(p.u.u_foamDecay, Math.exp(-deltaTime / 1.1));
    gl.uniform1f(p.u.u_foamThreshold, 1.0);
    gl.uniform1f(p.u.u_foamInjection, this.foamGain * deltaTime);
    this.drawQuad(p);
  }

  camera() {
    if (this.boat) {
      return this.chaseCamera();
    }
    const eye = [0, this.eyeHeight, 0];
    const dir = [
      Math.cos(this.pitch) * Math.cos(this.heading),
      Math.sin(this.pitch),
      Math.cos(this.pitch) * Math.sin(this.heading),
    ];
    const aspect = this.canvas.width / this.canvas.height;
    const proj = mat4Perspective((60 * Math.PI) / 180, aspect, 0.15, 60000);
    const view = mat4LookAt(eye, dir, [0, 1, 0]);
    const combined = mat4Multiply(proj, view);
    return { eye, dir, combined, inverse: mat4Invert(combined) };
  }

  /**
   * A camera trailing the boat.
   *
   * The heading it follows is smoothed rather than taken directly. A camera
   * welded to the bow makes the sea appear to swing around a stationary boat,
   * which reads as the world moving rather than the boat turning, and is why
   * chase cameras lag: the lag is the sensation of turning.
   */
  chaseCamera() {
    const b = this.boat;
    if (this.smoothedHeading === undefined) this.smoothedHeading = b.heading;
    const error = Math.atan2(Math.sin(b.heading - this.smoothedHeading),
                             Math.cos(b.heading - this.smoothedHeading));
    this.smoothedHeading += error * 0.04;

    // Stand further off in a big sea. At Hs 12 m a boat 26 m away is behind the
    // next crest half the time, and the frame is all water.
    const seaScale = 1 + 0.55 * Math.max(0, this.sea.significantWaveHeight - 2);
    const back = (this.chaseDistance ?? 26) + 0.9 * seaScale;
    const height = (this.chaseHeight ?? 7.5) + 0.7 * seaScale;
    // Off the quarter rather than dead astern. From directly behind, a 12 m hull
    // foreshortens into a wedge and the sail is edge-on; a quarter view shows the
    // length of one and the camber of the other, which is why every photograph of
    // a boat under sail is taken from roughly here.
    const yaw = this.smoothedHeading + this.orbit + CHASE_QUARTER;
    const eye = [
      b.x - Math.cos(yaw) * back,
      b.heave + height,
      b.z - Math.sin(yaw) * back,
    ];

    // Keep the camera out of the water. It follows the boat's heave, and in a big
    // sea the boat can be deep in a trough while the crest behind it - which is
    // where the camera is - stands several metres higher. The eye then ends up
    // under the surface, looking up through it at the bottom of the hull, which is
    // exactly as broken as it sounds and is what a player actually sees first.
    const surfaceAtEye = this.surfaceHeightAt(eye[0], eye[2]);
    eye[1] = Math.max(eye[1], surfaceAtEye + 2.0);

    const target = [b.x, b.heave + 3.5, b.z];
    const dir = normalise([target[0] - eye[0], target[1] - eye[1], target[2] - eye[2]]);

    const aspect = this.canvas.width / this.canvas.height;
    const proj = mat4Perspective((55 * Math.PI) / 180, aspect, 0.2, 60000);
    const view = mat4LookAt(eye, dir, [0, 1, 0]);
    const combined = mat4Multiply(proj, view);
    return { eye, dir, combined, inverse: mat4Invert(combined) };
  }

  /**
   * Finds the band of screen containing water, and how far past the frame edge the
   * lattice has to reach. Mirrors ProjectedGrid on the native side: the test is
   * against the mean water plane, not the top of the wave slab, because a camera at
   * deck height in a big sea sits below the tallest crest and would otherwise
   * conclude there is no water at all.
   */
  projectedGridBounds(cam) {
    const HORIZON = 40000;
    const hits = (ndcX, ndcY) => {
      const near = project(cam.inverse, ndcX, ndcY, -1);
      const far = project(cam.inverse, ndcX, ndcY, 1);
      const dirY = far[1] - near[1];
      if (Math.abs(dirY) < 1e-6) return false;
      const t = -near[1] / dirY;
      if (t < 0) return false;
      const dx = (far[0] - near[0]) * t;
      const dz = (far[2] - near[2]) * t;
      return dx * dx + dz * dz < HORIZON * HORIZON;
    };

    let highest = -1;
    let any = false;
    for (const ndcX of [-1, 0, 1]) {
      if (!hits(ndcX, -1)) continue;
      any = true;
      let lo = -1;
      let hi = 1;
      if (hits(ndcX, 1)) {
        lo = 1;
      } else {
        for (let i = 0; i < 24; i++) {
          const mid = 0.5 * (lo + hi);
          if (hits(ndcX, mid)) lo = mid; else hi = mid;
        }
      }
      highest = Math.max(highest, lo);
    }
    if (!any) return null;

    // Overscan: a vertex is placed on flat water and only then displaced, and at
    // the bottom of frame the water is metres away, so a metre of sideways shift
    // is a large angle. Sized from that geometry rather than guessed.
    let overscan = 0.06;
    const near = project(cam.inverse, 0, -1, -1);
    const far = project(cam.inverse, 0, -1, 1);
    const dirY = far[1] - near[1];
    if (Math.abs(dirY) > 1e-6) {
      const t = -near[1] / dirY;
      if (t > 0) {
        const bx = near[0] + (far[0] - near[0]) * t;
        const bz = near[2] + (far[2] - near[2]) * t;
        const distance = Math.hypot(bx - cam.eye[0], bz - cam.eye[2]);
        if (distance > 1e-3) {
          const centre = normalise(project(cam.inverse, 0, 0, 1).map((v, i) => v - cam.eye[i]));
          const edge = normalise([far[0] - near[0], dirY, far[2] - near[2]]);
          const halfAngle = Math.acos(Math.max(-1, Math.min(1, dot(centre, edge))));
          if (halfAngle > 1e-4) {
            overscan = Math.max(0.06, Math.min(0.85,
              Math.atan2(this.maximumDisplacement, distance) / halfAngle));
          }
        }
      }
    }

    const margin = 0.02 + 0.002 * Math.min(this.maximumDisplacement, 20);
    return {
      min: [-1 - overscan, -1 - overscan],
      max: [1 + overscan, Math.min(1, highest + margin)],
      horizon: HORIZON,
    };
  }

  sunDirection() {
    const c = Math.cos(this.sunElevation);
    return [c * Math.cos(this.sunAzimuth), Math.sin(this.sunElevation), c * Math.sin(this.sunAzimuth)];
  }

  /** Two-term extinction fit, as SunLight does. Hue shifts; brightness is exposure's job. */
  sunColour() {
    const dir = this.sunDirection();
    const airMass = 1 / Math.max(dir[1], 0.05);
    const e = [0.18, 0.34, 0.62].map((k) => Math.exp(-k * airMass));
    const peak = Math.max(...e);
    const intensity = Math.max(0, Math.min(1, (dir[1] + 0.05) * 6));
    return e.map((v) => (v / peak) * intensity);
  }

  renderFrame(deltaTime) {
    const gl = this.gl;
    this.resize();
    this.simulate(this.time, deltaTime);
    this.updateExposure(false);

    const cam = this.camera();
    const sun = this.sunDirection();
    const sunColour = this.sunColour();

    // Shadows first, outside every other target: the pass binds its own
    // framebuffer, and both the water and the boat sample the result.
    if (this.boat) this.renderShadowMap(sun);

    this.bindTarget(this.scene);
    gl.clearColor(0, 0, 0, 1);
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);

    // Sky first, behind everything, writing no depth.
    {
      const p = this.programs.sky;
      gl.useProgram(p);
      gl.disable(gl.DEPTH_TEST);
      gl.depthMask(false);
      gl.uniformMatrix4fv(p.u.u_inverseViewProjection, false, cam.inverse);
      gl.uniform3fv(p.u.u_sunDirection, sun);
      gl.uniform3fv(p.u.u_sunColour, sunColour);
      gl.uniform1f(p.u.u_turbidity, this.turbidity);
      this.drawQuad(p);
      gl.depthMask(true);
    }

    const bounds = this.projectedGridBounds(cam);
    if (bounds) {
      const p = this.programs.surface;
      gl.useProgram(p);
      gl.enable(gl.DEPTH_TEST);
      gl.depthFunc(gl.LEQUAL);
      gl.disable(gl.CULL_FACE);

      gl.uniformMatrix4fv(p.u.u_viewProjection, false, cam.combined);
      gl.uniformMatrix4fv(p.u.u_inverseViewProjection, false, cam.inverse);
      gl.uniform2fv(p.u.u_ndcMin, bounds.min);
      gl.uniform2fv(p.u.u_ndcMax, bounds.max);
      gl.uniform3fv(p.u.u_cameraPosition, cam.eye);
      gl.uniform1f(p.u.u_horizonDistance, bounds.horizon);
      gl.uniform3fv(p.u.u_sunDirection, sun);
      gl.uniform3fv(p.u.u_sunColour, sunColour);
      gl.uniform1f(p.u.u_turbidity, this.turbidity);
      gl.uniform1i(p.u.u_cascadeCount, this.cascades.length);
      gl.uniform1f(p.u.u_choppiness, this.sea.choppiness);
      gl.uniform3fv(p.u.u_patchSizes, new Float32Array([
        this.cascades[0].patch,
        this.cascades[1]?.patch || 1,
        this.cascades[2]?.patch || 1,
      ]));

      for (let i = 0; i < 3; i++) {
        const c = this.cascades[Math.min(i, this.cascades.length - 1)];
        this.bindTexture(i, c.displacement.tex, p, `u_displacement${i}`);
        // The simulation swaps after writing, so the completed map is the one at
        // 1 - derivativeIndex.
        this.bindTexture(3 + i, c.derivatives[1 - c.derivativeIndex].tex, p, `u_derivatives${i}`);
      }

      gl.uniform3fv(p.u.u_deepColour, new Float32Array([0.004, 0.016, 0.031]));
      gl.uniform3fv(p.u.u_scatterColour, new Float32Array([0.043, 0.16, 0.128]));
      gl.uniform3fv(p.u.u_extinction, new Float32Array([0.45, 0.08, 0.035]));
      gl.uniform1f(p.u.u_waterDepth, Math.min(this.sea.depth, 200));
      gl.uniform1f(p.u.u_foamScale, 0.35);
      gl.uniform1f(p.u.u_normalDetailFade, 12);
      gl.uniform1f(p.u.u_displacementFadeStart, 1800);
      gl.uniform1f(p.u.u_displacementFadeEnd, 6000);
      // The boat's shadow on the water: the long dark stripe running away to
      // leeward, which is the cue that puts the hull in the water rather than on it.
      this.bindShadow(p, 6, this.boat ? SHADOW_STRENGTH : 0);

      gl.bindVertexArray(this.grid);
      gl.drawElements(gl.TRIANGLES, this.gridIndexCount, gl.UNSIGNED_INT, 0);
    }

    this.drawBoat(cam, sun, sunColour);

    // Resolve to the canvas.
    {
      const p = this.programs.tonemap;
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
      gl.viewport(0, 0, this.canvas.width, this.canvas.height);
      gl.disable(gl.DEPTH_TEST);
      gl.useProgram(p);
      this.bindTexture(0, this.scene.tex, p, 'u_hdr');
      // Bloom is omitted here: it costs three more full-screen passes and the
      // ocean is already fill-rate bound on a phone. Strength zero makes the
      // sampler's contribution exactly nothing.
      this.bindTexture(1, this.scene.tex, p, 'u_bloom');
      gl.uniform1f(p.u.u_exposure, this.exposure);
      gl.uniform1f(p.u.u_bloomStrength, 0);
      gl.uniform1f(p.u.u_vignette, 0.55);
      this.drawQuad(p);
    }
  }

  /** Advances time and draws. */
  render(deltaTime) {
    this.time += deltaTime;
    this.renderFrame(deltaTime);
  }
}
