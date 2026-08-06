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

  await page.evaluate(async (n) => {
    const ocean = window.__ocean;
    ocean.time = 40;
    for (let i = 0; i < n; i++) {
      ocean.render(1 / 30);
      if (i % 10 === 0) await new Promise((r) => setTimeout(r, 0));
    }
  }, frames);

  const stats = await page.evaluate(() => {
    const o = window.__ocean;
    return {
      glError: o.gl.getError(),
      significantWaveHeight: o.sea.significantWaveHeight,
      exposure: o.exposure,
      cascades: o.cascades.length,
      resolution: o.resolution,
    };
  });
  console.log(JSON.stringify(stats, null, 2));

  if (stats.glError !== 0) problems.push('glError 0x' + stats.glError.toString(16));
  // A sea state that produced no waves would still render a blank blue frame
  // without erroring, so assert the spectrum actually built something.
  if (!(stats.significantWaveHeight > 0.5)) {
    problems.push('significant wave height is ' + stats.significantWaveHeight + ' m');
  }

  if (out) await page.screenshot({ path: out });
  await browser.close();

  if (problems.length) {
    console.error('FAIL:\n  ' + problems.join('\n  '));
    process.exit(1);
  }
  console.log('PASS: the WebGL ocean ran clean' + (out ? ' -> ' + out : ''));
})();
