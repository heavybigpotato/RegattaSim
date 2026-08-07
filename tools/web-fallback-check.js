// Runs the WebGL showcase with 32-bit float render targets taken away.
//
// This exists because of a real failure, on the only kind of device this build was
// written for. WebGL 2 makes no float format colour-renderable by itself: RGBA32F
// needs EXT_color_buffer_float, and RGBA16F needs that *or*
// EXT_color_buffer_half_float. Desktop GPUs hand you both, so code that assumes
// both passes every test on a desktop and shows an error page on a phone - which is
// exactly what happened.
//
// The check hides EXT_color_buffer_float from the page and asserts the ocean still
// runs, that the FFT still produces waves, and that the targets really did fall back
// to 16-bit rather than the extension being quietly re-acquired somewhere.
//
// Usage: node tools/web-fallback-check.js <url> [output.png] [frames]
// Set CHROMIUM_PATH to use a browser that is already installed.
import { chromium } from 'playwright';

(async () => {
  const url = process.argv[2];
  const out = process.argv[3];
  const frames = Number(process.argv[4] || 120);

  const launch = {
    args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'],
  };
  if (process.env.CHROMIUM_PATH) launch.executablePath = process.env.CHROMIUM_PATH;

  const browser = await chromium.launch(launch);
  const page = await browser.newPage({ viewport: { width: 800, height: 520 } });

  const problems = [];
  page.on('console', (m) => { if (m.type() === 'error') problems.push('console: ' + m.text()); });
  page.on('pageerror', (e) => problems.push('pageerror: ' + e.message));

  // Installed before any page script runs, so the page never sees the extension.
  // Hooking getExtension rather than the context is deliberate: it leaves WebGL 2
  // itself intact, which is the situation on the phones this is standing in for.
  await page.addInitScript(() => {
    const original = WebGL2RenderingContext.prototype.getExtension;
    WebGL2RenderingContext.prototype.getExtension = function (name) {
      if (name === 'EXT_color_buffer_float') return null;
      return original.call(this, name);
    };
  });

  await page.goto(url, { waitUntil: 'load' });

  const blocked = await page.evaluate(() =>
    getComputedStyle(document.getElementById('message')).display !== 'none'
      ? document.getElementById('messageText').textContent
      : null);
  if (blocked) {
    console.error('FAIL: the page refused to run without EXT_color_buffer_float: ' + blocked);
    await browser.close();
    process.exit(1);
  }

  await page.evaluate(async (n) => {
    const ocean = window.__ocean;
    ocean.time = 40;
    for (let i = 0; i < n; i++) {
      window.__advance(1 / 30);
      if (i % 10 === 0) await new Promise((r) => setTimeout(r, 0));
    }
  }, frames);

  const stats = await page.evaluate(() => {
    const o = window.__ocean;
    const gl = o.gl;
    // Read a corner of the largest cascade's displacement map. If the transform ran
    // at all this is a real wave field; if it silently produced nothing it is zero,
    // and the page would still render a plausible flat blue sea.
    const c = o.cascades[0];
    const px = new Float32Array(4 * 4 * 4);
    gl.bindFramebuffer(gl.FRAMEBUFFER, c.displacement.fbo);
    gl.readPixels(0, 0, 4, 4, gl.RGBA, gl.FLOAT, px);
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    let largest = 0;
    for (const v of px) largest = Math.max(largest, Math.abs(v));
    return {
      glError: gl.getError(),
      float32Available: o.formats.float32,
      halfAvailable: o.formats.half,
      // RGBA16F is 0x881A; RGBA32F is 0x8814.
      fftTargetFormat: '0x' + c.spatialA.colour.toString(16),
      sceneTargetFormat: '0x' + o.scene.colour.toString(16),
      largestDisplacement: largest,
      significantWaveHeight: o.sea.significantWaveHeight,
      boatKnots: window.__boat.speedKnots,
    };
  });
  console.log(JSON.stringify(stats, null, 2));

  if (stats.float32Available) {
    problems.push('the extension was not actually hidden, so this proved nothing');
  }
  if (stats.fftTargetFormat !== '0x881a') {
    problems.push('FFT target is ' + stats.fftTargetFormat + ', expected RGBA16F (0x881a)');
  }
  if (stats.glError !== 0) problems.push('glError 0x' + stats.glError.toString(16));
  if (!(stats.largestDisplacement > 0.05)) {
    problems.push('the transform produced no waves: largest displacement '
      + stats.largestDisplacement);
  }
  if (!(stats.boatKnots > 1)) problems.push('the boat is doing ' + stats.boatKnots + ' kt');

  if (out) await page.screenshot({ path: out, timeout: 120000, animations: 'disabled' });
  await browser.close();

  if (problems.length) {
    console.error('FAIL:\n  ' + problems.join('\n  '));
    process.exit(1);
  }
  console.log('PASS: the ocean runs on half-float-only hardware'
    + (out ? ' -> ' + out : ''));
})();
