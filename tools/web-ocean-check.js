// Headless check for the WebGL showcase.
//
// The web ocean is the only build an iPhone can run, so it needs the same
// treatment as the native one: actually executed, not merely served. This loads
// the page in Chromium, drives a fixed number of frames so foam accumulates,
// fails on any shader compile error, page exception or GL error, and writes a
// screenshot for inspection.
//
// Usage: node tools/web-ocean-check.js <url> <output.png> [frames]
// Set CHROMIUM_PATH to use a browser that is already installed.
//
// An ES module, because tools/package.json declares "type": "module".
import { chromium } from 'playwright';

(async () => {
  const url = process.argv[2];
  const out = process.argv[3];
  const frames = Number(process.argv[4] || 150);

  const launch = {
    // SwiftShader: CI has no GPU, and correctness is what is being checked here.
    args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'],
  };
  if (process.env.CHROMIUM_PATH) launch.executablePath = process.env.CHROMIUM_PATH;

  const browser = await chromium.launch(launch);
  const page = await browser.newPage({ viewport: { width: 900, height: 620 }, deviceScaleFactor: 1 });

  const problems = [];
  page.on('console', (m) => { if (m.type() === 'error') problems.push('console: ' + m.text()); });
  page.on('pageerror', (e) => problems.push('pageerror: ' + e.message));

  await page.goto(url, { waitUntil: 'load' });

  const blocked = await page.evaluate(() =>
    getComputedStyle(document.getElementById('message')).display !== 'none'
      ? document.getElementById('messageText').textContent
      : null);
  if (blocked) {
    console.error('FAIL: the page refused to run: ' + blocked);
    await browser.close();
    process.exit(1);
  }

  // Drives the page's own frame step, not just the renderer: the boat is sailed
  // there, so calling `ocean.render` alone would leave the hull parked at the
  // origin and pass a page whose physics never ran.
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
    const b = window.__boat;
    return {
      glError: o.gl.getError(),
      significantWaveHeight: o.sea.significantWaveHeight,
      exposure: o.exposure,
      cascades: o.cascades.length,
      resolution: o.resolution,
      boatKnots: b.speedKnots,
      boatTrueWindAngle: (b.wind.trueAngle * 180) / Math.PI,
      boatSailed: Math.hypot(b.x, b.z),
      boatHeave: b.heave,
      boatHeelDegrees: (b.windHeel * 180) / Math.PI,
      hullTriangles: o.boatMeshes.hull.count / 3,
      sailTriangles: o.boatMeshes.sail.count / 3,
    };
  });
  console.log(JSON.stringify(stats, null, 2));

  if (stats.glError !== 0) problems.push('glError 0x' + stats.glError.toString(16));
  // A sea state that produced no waves would still render a blank blue frame
  // without erroring, so assert the spectrum actually built something.
  if (!(stats.significantWaveHeight > 0.5)) {
    problems.push('significant wave height is ' + stats.significantWaveHeight + ' m');
  }
  // Same reasoning for the boat: a hull that never accelerated would draw fine.
  if (!(stats.boatKnots > 1)) {
    problems.push('the boat is doing ' + stats.boatKnots + ' kt');
  }
  // One hull length. A boat accelerating from rest on a nine-second time constant
  // covers about twenty metres in the ten seconds this check drives, so the
  // threshold is loose enough not to be a tuning knob and tight enough that a
  // hull which never left the origin fails.
  if (!(stats.boatSailed > 12.18)) {
    problems.push('the boat has covered ' + stats.boatSailed + ' m');
  }
  // And a hull sitting on a mean-water plane is not floating on the sea state.
  if (!(Math.abs(stats.boatHeave) > 1e-3)) {
    problems.push('the boat is not riding the waves, heave is ' + stats.boatHeave + ' m');
  }
  // The page starts close-hauled on starboard, so the wind is over the starboard
  // side and the boat must be lying over to port - a negative roll by this
  // convention. A boat heeling the wrong way is the sort of thing that looks
  // merely odd on screen and is a sign inversion underneath.
  if (!(stats.boatHeelDegrees < -8)) {
    problems.push('close-hauled on starboard the boat should heel to port, got '
      + stats.boatHeelDegrees + ' degrees');
  }
  if (!(stats.hullTriangles > 0 && stats.sailTriangles > 0)) {
    problems.push('boat geometry is empty: ' + JSON.stringify(stats));
  }

  if (out) await page.screenshot({ path: out });
  await browser.close();

  if (problems.length) {
    console.error('FAIL:\n  ' + problems.join('\n  '));
    process.exit(1);
  }
  console.log('PASS: the WebGL ocean ran clean' + (out ? ' -> ' + out : ''));
})();
