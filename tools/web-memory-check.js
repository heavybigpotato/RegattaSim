// Runs the WebGL showcase on a device that refuses large render targets.
//
// This stands in for a phone under memory pressure, which is the normal state of a
// phone: a dozen tabs open, and the browser declines the allocation the ocean asks
// for. Before this the renderer took one refusal as fatal and put up an error page,
// even though the same device would have handed over a target half the size
// perfectly happily - so the choice on offer was a smaller ocean or no ocean, and
// the code chose no ocean.
//
// The check caps how large a framebuffer the page is allowed to allocate, then
// asserts the ocean still runs, that it really did shrink rather than getting its
// way, and that it did not shrink so far the picture is worthless.
//
// Usage: node tools/web-memory-check.js <url> [output.png] [frames]
// Set CHROMIUM_PATH to use a browser that is already installed.
import { chromium } from 'playwright';

// Small enough that the full-size scene target is refused, comfortably larger than
// the 128x128 cascade targets the transform needs, which must still succeed.
const LIMIT = 200000;

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

  // Refuse any framebuffer above the cap, the way a driver out of memory does.
  //
  // The size is taken from the last texImage2D on the context rather than from the
  // framebuffer, which cannot be interrogated for it. That is exact here because
  // target() allocates the texture and checks the framebuffer in the same breath,
  // with nothing in between.
  await page.addInitScript((limit) => {
    const proto = WebGL2RenderingContext.prototype;
    const texImage2D = proto.texImage2D;
    const checkFramebufferStatus = proto.checkFramebufferStatus;
    proto.texImage2D = function (target, level, internalFormat, width, height, ...rest) {
      // Only the sized overload carries dimensions; the DOM-source one does not.
      if (typeof height === 'number') this.__lastArea = width * height;
      return texImage2D.call(this, target, level, internalFormat, width, height, ...rest);
    };
    proto.checkFramebufferStatus = function (t) {
      const status = checkFramebufferStatus.call(this, t);
      if (status === this.FRAMEBUFFER_COMPLETE && this.__lastArea > limit) {
        return this.FRAMEBUFFER_UNSUPPORTED;
      }
      return status;
    };
  }, LIMIT);

  await page.goto(url, { waitUntil: 'load' });

  const blocked = await page.evaluate(() =>
    getComputedStyle(document.getElementById('message')).display !== 'none'
      ? document.getElementById('messageText').textContent
      : null);
  if (blocked) {
    console.error('FAIL: the page gave up instead of shrinking: ' + blocked);
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
    return {
      glError: o.gl.getError(),
      pixelBudget: o.pixelBudget,
      sceneWidth: o.scene.width,
      sceneHeight: o.scene.height,
      scenePixels: o.scene.width * o.scene.height,
      canvasWidth: o.gl.canvas.width,
      canvasHeight: o.gl.canvas.height,
      // 0 means it could not have one at all and is drawing without shadows.
      shadowSize: o.shadowSize,
      boatKnots: window.__boat.speedKnots,
    };
  });
  console.log(JSON.stringify(stats, null, 2));

  if (!(stats.scenePixels <= LIMIT)) {
    problems.push('the scene target is ' + stats.scenePixels
      + ' pixels, above the cap of ' + LIMIT + ', so the cap was not applied and this '
      + 'proved nothing');
  }
  // A tenth of the cap would be a few hundred pixels across: technically running,
  // but not something anyone would call a working page.
  if (!(stats.scenePixels > LIMIT / 10)) {
    problems.push('it shrank to ' + stats.scenePixels + ' pixels, far further than it '
      + 'needed to');
  }
  // The backing store and the target have to agree, or the frame is drawn at one
  // size and presented at another.
  if (stats.canvasWidth !== stats.sceneWidth || stats.canvasHeight !== stats.sceneHeight) {
    problems.push('canvas is ' + stats.canvasWidth + 'x' + stats.canvasHeight
      + ' but the scene target is ' + stats.sceneWidth + 'x' + stats.sceneHeight);
  }
  // The 1024 map cannot fit under the cap, so this device must have taken a smaller
  // one or none. Either is fine; still asking for the full size would mean the
  // allocation is not being retried at all.
  if (stats.shadowSize >= 1024) {
    problems.push('the shadow map is still ' + stats.shadowSize + ', which cannot fit '
      + 'under the cap, so it was never really refused');
  }
  if (stats.glError !== 0) problems.push('glError 0x' + stats.glError.toString(16));
  if (!(stats.boatKnots > 1)) problems.push('the boat is doing ' + stats.boatKnots + ' kt');

  if (out) await page.screenshot({ path: out, timeout: 120000, animations: 'disabled' });
  await browser.close();

  if (problems.length) {
    console.error('FAIL:\n  ' + problems.join('\n  '));
    process.exit(1);
  }
  console.log('PASS: the ocean gives up resolution rather than giving up, at '
    + stats.sceneWidth + 'x' + stats.sceneHeight + (out ? ' -> ' + out : ''));
})();
