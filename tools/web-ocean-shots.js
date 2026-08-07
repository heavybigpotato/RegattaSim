// Renders the WebGL showcase to stills, from a script of framings.
//
// This exists because the page is the only build most people can run - and on a
// phone, the only way to look at it is to look at it. Checking that the frame is
// right needs a picture, and a picture needs a browser driven to a known state:
// a chosen sea, a chosen sun, a chosen camera, a fixed number of frames so foam
// and heel have settled. Reading numbers out of the page proves it did not
// crash; it does not prove the boat is above the water.
//
// It is a developer tool, not a CI gate - tools/web-ocean-check.js is the gate.
// This is what you reach for when a change should have altered how the scene
// looks and you want to see whether it did.
//
// Usage:
//   node tools/web-ocean-shots.js <url> <shots.json|inline-json> [outputDirectory]
//
// Each shot is an object; every field is optional except `out`:
//   out     file name for the screenshot
//   wind    true wind speed, knots         swell  swell height, metres
//   sun     sun elevation, degrees         dist   chase camera distance, metres
//   height  chase camera height, metres    orbit  extra camera yaw, radians
//   frames  frames to drive before the shot (default 150)
//
// Set CHROMIUM_PATH to use a browser that is already installed.
//
// An ES module, because tools/package.json declares "type": "module".
import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const KNOTS = 0.514444;

(async () => {
  const url = process.argv[2];
  const script = process.argv[3];
  const directory = process.argv[4] || '.';
  if (!url || !script) {
    console.error('usage: node tools/web-ocean-shots.js <url> <shots.json> [directory]');
    process.exit(2);
  }

  // Either a path to a file of shots or the JSON itself, so a one-off framing
  // does not need a file created for it.
  const shots = JSON.parse(
    script.trimStart().startsWith('[') ? script : readFileSync(script, 'utf8'));

  const launch = { args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] };
  if (process.env.CHROMIUM_PATH) launch.executablePath = process.env.CHROMIUM_PATH;

  const browser = await chromium.launch(launch);
  const page = await browser.newPage({ viewport: { width: 1100, height: 700 }, deviceScaleFactor: 1 });

  const problems = [];
  page.on('pageerror', (e) => problems.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') problems.push('console: ' + m.text()); });

  await page.goto(url, { waitUntil: 'load' });

  for (const shot of shots) {
    const state = await page.evaluate(async (s) => {
      const ocean = window.__ocean;
      const boat = window.__boat;
      // The panel is a control surface, not part of the scene.
      document.getElementById('panel').classList.add('hidden');

      if (s.wind !== undefined || s.swell !== undefined) {
        const spectrum = await import('./spectrum.js');
        ocean.setSeaState(spectrum.seaState({
          windSpeed: (s.wind ?? 22) * 0.514444,
          swellHeight: s.swell ?? 2.4,
        }));
      }
      if (s.sun !== undefined) ocean.sunElevation = (s.sun * Math.PI) / 180;
      if (s.dist !== undefined) ocean.chaseDistance = s.dist;
      if (s.height !== undefined) ocean.chaseHeight = s.height;
      if (s.orbit !== undefined) ocean.orbit = s.orbit;

      // A fixed start time, so the same script produces the same sea every run.
      ocean.time = 40;
      for (let i = 0; i < (s.frames ?? 150); i++) {
        window.__advance(1 / 30);
        // Yielding lets the compositor keep up on a software rasteriser, which
        // otherwise queues the whole run and times the page out.
        if (i % 20 === 0) await new Promise((r) => setTimeout(r, 0));
      }
      return {
        knots: +boat.speedKnots.toFixed(1),
        heelDegrees: +((boat.windHeel * 180) / Math.PI).toFixed(1),
        significantWaveHeight: +ocean.sea.significantWaveHeight.toFixed(2),
      };
    }, shot);

    const path = join(directory, shot.out);
    // Generous, because a software rasteriser with a full ocean and a boat on it
    // never goes idle, and the default wait for a quiet frame times out.
    await page.screenshot({ path, timeout: 120000, animations: 'disabled' });
    console.log(`${path}  ${state.knots} kt, heel ${state.heelDegrees} deg, `
      + `Hs ${state.significantWaveHeight} m`);
  }

  await browser.close();
  if (problems.length) {
    console.error('FAIL:\n  ' + problems.join('\n  '));
    process.exit(1);
  }
})();
