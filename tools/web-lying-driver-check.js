// Runs the WebGL showcase on a device whose extension list is wrong.
//
// This stands in for a real report. An iPhone refused to start with "neither
// EXT_color_buffer_float nor EXT_color_buffer_half_float is available", and the
// diagnostic panel on that same failure page listed both as present. The two
// disagreed about one device seconds apart, so whatever the driver was doing, the
// extension list could not be the thing the page decided on.
//
// It is now decided by allocating a float framebuffer and seeing whether it comes
// back complete, which is the capability actually needed rather than a claim about
// it. This check hides both extensions while leaving the formats genuinely
// renderable - the exact shape of that report - and asserts the ocean runs anyway.
//
// Usage: node tools/web-lying-driver-check.js <url> [output.png] [frames]
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

  // Both float extensions denied, everything else left alone.
  //
  // The formats stay renderable underneath, because the extensions are enabled for
  // the context the moment anything asks for them - and the harness itself asks,
  // once, before the page gets a chance to be told no. That is what makes this a
  // lying driver rather than a device that genuinely cannot do it: the capability
  // is there and the list denies it.
  await page.addInitScript(() => {
    const proto = WebGL2RenderingContext.prototype;
    const getExtension = proto.getExtension;
    const denied = new Set(['EXT_color_buffer_float', 'EXT_color_buffer_half_float']);
    proto.getExtension = function (name) {
      const result = getExtension.call(this, name);
      if (denied.has(name)) {
        if (!this.__enabled) {
          // Enable it for real, once, then never admit to it again.
          this.__enabled = true;
          for (const n of denied) getExtension.call(this, n);
        }
        return null;
      }
      return result;
    };
  });

  await page.goto(url, { waitUntil: 'load' });

  const blocked = await page.evaluate(() =>
    getComputedStyle(document.getElementById('message')).display !== 'none'
      ? document.getElementById('messageText').textContent
      : null);
  if (blocked) {
    console.error('FAIL: the page believed the extension list over the hardware: '
      + blocked);
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
    const c = o.cascades[0];
    const px = new Float32Array(4 * 4 * 4);
    gl.bindFramebuffer(gl.FRAMEBUFFER, c.displacement.fbo);
    gl.readPixels(0, 0, 4, 4, gl.RGBA, gl.FLOAT, px);
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    let largest = 0;
    for (const v of px) largest = Math.max(largest, Math.abs(v));
    return {
      glError: gl.getError(),
      contextLost: gl.isContextLost(),
      // Both must read false: the page found the capability by trying it, not by
      // being told about it.
      float32Reported: o.formats.float32,
      halfReported: o.formats.half,
      largestDisplacement: largest,
      boatKnots: window.__boat.speedKnots,
    };
  });
  console.log(JSON.stringify(stats, null, 2));

  if (stats.contextLost) problems.push('the context was lost');
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
  console.log('PASS: the ocean tries the format rather than believing the list'
    + (out ? ' -> ' + out : ''));
})();
