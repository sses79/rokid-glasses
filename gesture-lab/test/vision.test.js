import test from 'node:test';
import assert from 'node:assert/strict';
import { analyze, calibrate, createVision, defaultModel, GRID_W } from '../lib/vision.js';
import { createGestures } from '../lib/gestures.js';
import { makeFrame, script } from '../lib/synth.js';

const W = GRID_W;
const H = 36;

function see(hand, model = defaultModel()) {
  const st = createVision(W, H);
  return analyze(makeFrame(W, H, hand), model, st);
}

test('empty frame finds no hand', () => {
  assert.equal(see({ present: false }).found, false);
});

test('hand position follows the hand', () => {
  const left = see({ x: 0.3, y: 0.5 });
  const right = see({ x: 0.7, y: 0.5 });
  assert.ok(left.found && right.found);
  assert.ok(Math.abs(left.x - 0.3) < 0.06, `left.x=${left.x}`);
  assert.ok(Math.abs(right.x - 0.7) < 0.06, `right.x=${right.x}`);
});

test('open hand and fist separate on solidity across the frame', () => {
  for (const x of [0.25, 0.4, 0.5, 0.6, 0.75]) {
    for (const y of [0.4, 0.5, 0.65]) {
      for (const size of [0.16, 0.2, 0.26]) {
        const open = see({ x, y, size, open: true });
        const fist = see({ x, y, size, open: false });
        assert.ok(open.solidity < 0.74, `open ${x},${y},${size}: ${open.solidity}`);
        assert.ok(fist.solidity > 0.82, `fist ${x},${y},${size}: ${fist.solidity}`);
      }
    }
  }
});

test('calibration learns an unusual hand colour', () => {
  // A blue glove: invisible to the default skin model.
  const glove = (hand) => {
    const f = makeFrame(W, H, hand);
    for (let i = 0; i < f.length; i += 4) {
      if (f[i] === 205) { f[i] = 40; f[i + 1] = 90; f[i + 2] = 220; }
    }
    return f;
  };
  const st = createVision(W, H);
  const frame = glove({ x: 0.5, y: 0.5, open: false, size: 0.3 });
  assert.equal(analyze(frame, defaultModel(), st).found, false);
  const model = calibrate(frame, W, H, { x: W / 2 - 3, y: H / 2 - 3, w: 6, h: 6 });
  assert.ok(model && model.calibrated);
  assert.equal(analyze(glove({ x: 0.4, y: 0.5 }), model, st).found, true);
});

test('calibration rejects a dark sample', () => {
  const dark = new Uint8ClampedArray(W * H * 4);
  assert.equal(calibrate(dark, W, H, { x: 10, y: 10, w: 6, h: 6 }), null);
});

function perform(fps) {
  const st = createVision(W, H);
  const g = createGestures();
  const model = defaultModel();
  const events = [];
  for (let t = 0; t < 9000; t += 1000 / fps) {
    const out = g.update(analyze(makeFrame(W, H, script(t)), model, st), t);
    for (const e of out.events) events.push({ ...e, t: Math.round(t) });
  }
  return events;
}

for (const fps of [30, 12, 6]) {
  test(`scripted performance yields the expected gestures at ${fps} fps`, () => {
    const events = perform(fps);
    const types = events.map((e) => e.type);
    assert.deepEqual(
      types.filter((x) => x !== 'found' && x !== 'lost'),
      ['grab', 'release', 'grab', 'release', 'swipe-right'],
      JSON.stringify(events),
    );
    const grabs = events.filter((e) => e.type === 'grab');
    assert.ok(Math.abs(grabs[0].x - 0.36) < 0.08, `grab 1 at ${grabs[0].x}`);
    assert.ok(Math.abs(grabs[1].x - 0.62) < 0.08, `grab 2 at ${grabs[1].x}`);
  });
}

test('slow-camera profile still registers grab and release at about 1 fps', () => {
  const st = createVision(W, H);
  const g = createGestures({ poseHoldMs: 0, lostAfterMs: 2500, smoothing: 1, swipeWindowMs: 2500, swipeSettleMs: 1000, swipeCooldownMs: 2500, latchMs: 600 });
  const model = defaultModel();
  const events = [];
  for (let t = 0; t < 10000; t += 900) {
    const open = t < 3500 || t > 7000;
    const out = g.update(analyze(makeFrame(W, H, { x: 0.36, y: 0.5, open }), model, st), t);
    for (const e of out.events) events.push(e);
  }
  assert.deepEqual(events.map((e) => e.type), ['found', 'grab', 'release']);
  assert.ok(Math.abs(events[1].x - 0.36) < 0.08, `grab at ${events[1].x}`);
});
