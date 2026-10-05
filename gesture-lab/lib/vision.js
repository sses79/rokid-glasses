// Hand detection on a small RGBA grid. No ML model: skin-colour segmentation,
// largest connected blob, then shape features of the hand part of that blob.
// Pure functions over typed arrays so it runs unchanged on the glasses and in Node.

export const GRID_W = 64;

// Share of the frame a blob must cover before it counts as a hand.
const MIN_AREA = 0.012;
// Rows kept below the topmost hand pixel, as a multiple of the blob's widest row.
// Trims the forearm so pose features describe the hand only.
const HAND_LENGTH = 1.5;

export function defaultModel() {
  return { cb: 108, cr: 152, tb: 20, tr: 18, yMin: 50, calibrated: false };
}

export function createVision(w, h) {
  const n = w * h;
  return {
    w,
    h,
    raw: new Uint8Array(n),
    mask: new Uint8Array(n),
    seen: new Uint8Array(n),
    stack: new Int32Array(n),
    blob: new Int32Array(n),
    best: new Int32Array(n),
    rowMin: new Int16Array(h),
    rowMax: new Int16Array(h),
    rowCount: new Int16Array(h),
  };
}

function chroma(r, g, b) {
  return {
    y: 0.299 * r + 0.587 * g + 0.114 * b,
    cb: 128 - 0.168736 * r - 0.331264 * g + 0.5 * b,
    cr: 128 + 0.5 * r - 0.418688 * g - 0.081312 * b,
  };
}

function clamp(v, lo, hi) {
  return v < lo ? lo : v > hi ? hi : v;
}

// Learn the wearer's hand colour from the pixels inside `box` (grid coordinates).
// Returns null when the box is too dark to be a usable sample.
export function calibrate(rgba, w, h, box) {
  let n = 0;
  let sy = 0;
  let scb = 0;
  let scr = 0;
  let scb2 = 0;
  let scr2 = 0;
  for (let y = box.y; y < box.y + box.h; y++) {
    for (let x = box.x; x < box.x + box.w; x++) {
      const i = (y * w + x) * 4;
      const c = chroma(rgba[i], rgba[i + 1], rgba[i + 2]);
      n++;
      sy += c.y;
      scb += c.cb;
      scr += c.cr;
      scb2 += c.cb * c.cb;
      scr2 += c.cr * c.cr;
    }
  }
  if (!n || sy / n < 25) return null;
  const cb = scb / n;
  const cr = scr / n;
  const sdCb = Math.sqrt(Math.max(0, scb2 / n - cb * cb));
  const sdCr = Math.sqrt(Math.max(0, scr2 / n - cr * cr));
  return {
    cb,
    cr,
    tb: clamp(2.5 * sdCb + 6, 8, 24),
    tr: clamp(2.5 * sdCr + 6, 8, 24),
    yMin: Math.max(30, (sy / n) * 0.35),
    calibrated: true,
  };
}

function cross(o, a, b) {
  return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
}

// Area of the convex hull of `pts` (monotone chain + shoelace).
function hullArea(pts) {
  pts.sort((p, q) => p[0] - q[0] || p[1] - q[1]);
  const hull = [];
  for (let pass = 0; pass < 2; pass++) {
    const start = hull.length;
    for (let k = 0; k < pts.length; k++) {
      const p = pass ? pts[pts.length - 1 - k] : pts[k];
      while (hull.length >= start + 2 && cross(hull[hull.length - 2], hull[hull.length - 1], p) <= 0) hull.pop();
      hull.push(p);
    }
    hull.pop();
  }
  let a = 0;
  for (let i = 0; i < hull.length; i++) {
    const p = hull[i];
    const q = hull[(i + 1) % hull.length];
    a += p[0] * q[1] - q[0] * p[1];
  }
  return Math.abs(a) / 2;
}

// Find the hand in one frame.
// Returns { found: false } or { found, x, y, area, solidity, box }, where x/y are the
// hand anchor in 0..1 frame coordinates and solidity is hand area / convex hull area
// (a fist is compact and scores high; spread fingers leave gaps and score low).
// After the call, st.mask holds 1 for hand pixels (for debug drawing).
export function analyze(rgba, model, st) {
  const { w, h, raw, mask, seen, stack, blob, best, rowMin, rowMax, rowCount } = st;
  const n = w * h;

  for (let i = 0, p = 0; i < n; i++, p += 4) {
    const r = rgba[p];
    const g = rgba[p + 1];
    const b = rgba[p + 2];
    const y = 0.299 * r + 0.587 * g + 0.114 * b;
    const cb = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b;
    const cr = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b;
    raw[i] = y > model.yMin && Math.abs(cb - model.cb) < model.tb && Math.abs(cr - model.cr) < model.tr ? 1 : 0;
  }

  // 3x3 majority vote removes speckle and fills pinholes.
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      let c = 0;
      for (let dy = -1; dy <= 1; dy++) {
        const yy = y + dy;
        if (yy < 0 || yy >= h) continue;
        for (let dx = -1; dx <= 1; dx++) {
          const xx = x + dx;
          if (xx >= 0 && xx < w) c += raw[yy * w + xx];
        }
      }
      mask[y * w + x] = c >= 5 ? 1 : 0;
    }
  }

  // Largest 4-connected blob.
  seen.fill(0);
  let bestLen = 0;
  for (let s = 0; s < n; s++) {
    if (!mask[s] || seen[s]) continue;
    let top = 0;
    let len = 0;
    stack[top++] = s;
    seen[s] = 1;
    while (top) {
      const i = stack[--top];
      blob[len++] = i;
      const x = i % w;
      if (x > 0 && mask[i - 1] && !seen[i - 1]) { seen[i - 1] = 1; stack[top++] = i - 1; }
      if (x < w - 1 && mask[i + 1] && !seen[i + 1]) { seen[i + 1] = 1; stack[top++] = i + 1; }
      if (i >= w && mask[i - w] && !seen[i - w]) { seen[i - w] = 1; stack[top++] = i - w; }
      if (i < n - w && mask[i + w] && !seen[i + w]) { seen[i + w] = 1; stack[top++] = i + w; }
    }
    if (len > bestLen) {
      bestLen = len;
      best.set(blob.subarray(0, len));
    }
  }

  mask.fill(0);
  if (bestLen < MIN_AREA * n) return { found: false };

  rowMin.fill(w);
  rowMax.fill(-1);
  rowCount.fill(0);
  for (let k = 0; k < bestLen; k++) {
    const i = best[k];
    const x = i % w;
    const y = (i - x) / w;
    if (x < rowMin[y]) rowMin[y] = x;
    if (x > rowMax[y]) rowMax[y] = x;
    rowCount[y]++;
  }

  let top = -1;
  let widest = 0;
  for (let y = 0; y < h; y++) {
    if (!rowCount[y]) continue;
    if (top < 0) top = y;
    const rw = rowMax[y] - rowMin[y] + 1;
    if (rw > widest) widest = rw;
  }
  const bottom = Math.min(h - 1, top + Math.round(HAND_LENGTH * widest) - 1);

  let area = 0;
  let sx = 0;
  let sy = 0;
  let minX = w;
  let maxX = -1;
  for (let k = 0; k < bestLen; k++) {
    const i = best[k];
    const x = i % w;
    const y = (i - x) / w;
    if (y > bottom) continue;
    mask[i] = 1;
    area++;
    sx += x;
    sy += y;
    if (x < minX) minX = x;
    if (x > maxX) maxX = x;
  }

  const pts = [];
  for (let y = top; y <= bottom; y++) {
    if (!rowCount[y]) continue;
    pts.push([rowMin[y], y], [rowMax[y] + 1, y], [rowMin[y], y + 1], [rowMax[y] + 1, y + 1]);
  }
  const hull = hullArea(pts);

  return {
    found: true,
    x: (sx / area + 0.5) / w,
    y: (sy / area + 0.5) / h,
    area: area / n,
    solidity: hull > 0 ? Math.min(1, area / hull) : 1,
    box: { x: minX, y: top, w: maxX - minX + 1, h: bottom - top + 1 },
  };
}
