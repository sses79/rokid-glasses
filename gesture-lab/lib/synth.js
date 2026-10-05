// Synthetic camera frames of a hand, used by the tests and by the page's
// simulation mode when no camera is available (for example in `aix preview`).

const SKIN = [205, 150, 125];
const BACKGROUND = [60, 70, 90];
const FINGER_ANGLES = [-55, -27, 0, 27, 55];

function distToSegment(px, py, ax, ay, bx, by) {
  const dx = bx - ax;
  const dy = by - ay;
  const len2 = dx * dx + dy * dy;
  const u = len2 ? Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2)) : 0;
  return Math.hypot(px - (ax + u * dx), py - (ay + u * dy));
}

// Draws a hand centred at (x, y) in 0..1 frame coordinates, with its forearm
// running off the bottom edge. `size` is the palm radius as a share of frame height.
export function makeFrame(w, h, { x = 0.5, y = 0.5, open = true, size = 0.2, present = true } = {}) {
  const rgba = new Uint8ClampedArray(w * h * 4);
  const cx = x * w;
  const cy = y * h;
  const r = size * h;
  const fingers = FINGER_ANGLES.map((deg) => {
    const a = (deg * Math.PI) / 180;
    return [cx + Math.sin(a) * r * 2.3, cy - Math.cos(a) * r * 2.3];
  });

  for (let py = 0; py < h; py++) {
    for (let px = 0; px < w; px++) {
      const fx = px + 0.5;
      const fy = py + 0.5;
      let skin = false;
      if (present) {
        skin = Math.hypot(fx - cx, fy - cy) <= r || (fy > cy && Math.abs(fx - cx) <= r * 0.6);
        if (!skin && open) {
          for (const [tx, ty] of fingers) {
            if (distToSegment(fx, fy, cx, cy, tx, ty) <= r * 0.2) {
              skin = true;
              break;
            }
          }
        }
      }
      const c = skin ? SKIN : BACKGROUND;
      const i = (py * w + px) * 4;
      rgba[i] = c[0];
      rgba[i + 1] = c[1];
      rgba[i + 2] = c[2];
      rgba[i + 3] = 255;
    }
  }
  return rgba;
}

// A looping scripted performance: hover each tile and grab it, then swipe.
// Returns the hand state at time `t` (ms).
export function script(t) {
  const loop = 9000;
  const u = (t % loop) / 1000;
  const lerp = (a, b, k) => a + (b - a) * Math.max(0, Math.min(1, k));
  if (u < 0.6) return { present: false };
  if (u < 2.2) return { x: lerp(0.3, 0.36, u - 0.6), y: 0.5, open: true };
  if (u < 3.0) return { x: 0.36, y: 0.5, open: false };
  if (u < 4.4) return { x: lerp(0.36, 0.62, (u - 3.0) / 1.2), y: lerp(0.5, 0.62, (u - 3.0) / 1.2), open: true };
  if (u < 5.2) return { x: 0.62, y: 0.62, open: false };
  if (u < 6.4) return { x: lerp(0.62, 0.3, (u - 5.2) / 1.0), y: lerp(0.62, 0.5, (u - 5.2) / 1.0), open: true };
  if (u < 7.4) return { x: 0.3, y: 0.5, open: true };
  if (u < 7.7) return { x: lerp(0.3, 0.72, (u - 7.4) / 0.25), y: 0.5, open: true };
  if (u < 8.4) return { x: 0.72, y: 0.5, open: true };
  return { present: false };
}
