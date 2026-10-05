<script def>
{
  "navigationBarTitleText": "Gesture Lab",
  "description": "Hand-gesture playground driven by the glasses camera."
}
</script>

<script setup>
import wx from 'wx';
import { analyze, calibrate, createVision, defaultModel, GRID_W } from '../../lib/vision.js';
import { createGestures } from '../../lib/gestures.js';
import { makeFrame, script } from '../../lib/synth.js';

const CANVAS_ID = 'stage';
// The Rokid Glasses display. The canvas buffer is fixed to it because this runtime
// does not apply data-bound canvas sizes; keep in step with the template and style.
const STAGE_W = 480;
const STAGE_H = 352;
const FRAME_INTERVAL_MS = 33;
const CAMERA_WARMUP_MS = 4000;
const HUD_H = 30;
const FOOT_H = 22;
// The hand rarely reaches the frame edges, so stretch its travel to cover the screen.
const CURSOR_GAIN = 1.4;
const DIAL_GAIN = 200;
// Grabbing a camera frame takes about 0.9 s on current glasses firmware, so with the
// real camera every timing window has to span several seconds.
const SLOW_CAMERA = {
  poseHoldMs: 0,
  lostAfterMs: 2500,
  smoothing: 1,
  // One sample a second cannot tell a swipe from a detection jumping between
  // objects, so pages turn with the temple controls only.
  swipeDistance: Infinity,
  swipeWindowMs: 2500,
  latchMs: 600,
};
// Share of the remaining distance the drawn cursor covers per animation frame.
const CURSOR_EASE = 0.15;
const PAGES = ['TILES', 'DIAL', 'VISION'];
const TILES = ['LIGHT', 'MUSIC', 'TIMER', 'NOTES', 'FOCUS', 'MUTE'];

const green = (a) => `rgba(64, 255, 94, ${a})`;
const clamp01 = (v) => (v < 0 ? 0 : v > 1 ? 1 : v);
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export default {
  data: {
    previewSrc: '',
  },

  onLoad() {
    this.state = 'idle'; // idle | starting | running
    this.session = 0;
    this.stream = null;
    this.capture = null;
    this.sim = false;
    this.camError = '';
    this.model = defaultModel();
    this.gestures = createGestures();
    this.vision = null;
    this.gw = GRID_W;
    this.gh = 36;
    this.lastFrame = null;
    this.readMode = ''; // '' until probed, then 'scaled' or 'full'
    this.source = '';
    this.page = 0;
    this.tiles = TILES.map(() => false);
    this.dial = 50;
    this.dialGrab = null;
    this.hand = { tracked: false, x: 0.5, y: 0.5, pose: 'unknown', solidity: 0 };
    this.cursor = null;
    this.obs = { found: false };
    this.fps = 0;
    this.workMs = 0;
    this.grabMs = 0;
    this.readMs = 0;
    this.visionMs = 0;
    this.toast = '';
    this.toastUntil = 0;
    this.rafId = null;
    this.previewUrl = '';
    this.camStart = 0;
    this.loggedAt = 0;

    this.cw = STAGE_W;
    this.ch = STAGE_H;
  },

  onShow() {
    this.startRender();
  },

  onHide() {
    this.stop();
    this.stopRender();
  },

  onUnload() {
    this.stop();
    this.stopRender();
  },

  // Canvas drawing only reaches the screen from inside an animation frame, so
  // painting runs on its own loop and the camera loop just updates state.
  startRender() {
    this.stopRender();
    const frame = () => {
      this.rafId = requestAnimationFrame(frame);
      this.draw();
    };
    this.rafId = requestAnimationFrame(frame);
  },

  stopRender() {
    if (this.rafId !== null) cancelAnimationFrame(this.rafId);
    this.rafId = null;
  },

  onTap() {
    this.primary();
  },

  onKeyUp(event) {
    if (!event) return;
    if (event.code === 'Enter') {
      event.preventDefault();
      this.primary();
    } else if (event.code === 'ArrowDown') {
      event.preventDefault();
      this.turnPage(1);
    } else if (event.code === 'ArrowUp') {
      event.preventDefault();
      this.turnPage(-1);
    }
  },

  primary() {
    if (this.state === 'idle') this.start();
    else if (this.state === 'running') this.calibrateNow();
  },

  // Must be reached from a temple tap or key press: the camera only opens
  // from a user interaction.
  async start() {
    if (this.state !== 'idle') return;
    this.state = 'starting';
    const session = ++this.session;

    let stream = null;
    try {
      const media = typeof navigator !== 'undefined' ? navigator.mediaDevices : null;
      if (!media || typeof media.getUserMedia !== 'function') throw new Error('camera API unavailable');
      if (typeof ImageCapture === 'undefined') throw new Error('frame capture unavailable');
      stream = await media.getUserMedia({
        audio: false,
        video: { facingMode: 'environment', width: 640, height: 360 },
      });
      const track = stream.getVideoTracks()[0];
      if (!track) throw new Error('camera returned no video');
      this.capture = new ImageCapture(track);
      this.stream = stream;
      // Frames can only be grabbed while the stream is playing in a <video>.
      this.previewUrl = URL.createObjectURL(stream);
      this.setData({ previewSrc: this.previewUrl });
      this.sim = false;
      this.camError = '';
    } catch (error) {
      this.stream = stream;
      this.releaseCamera();
      this.sim = true;
      this.camError = String((error && error.message) || error);
    }

    if (session !== this.session) {
      this.releaseCamera();
      return;
    }
    this.state = 'running';
    this.simStart = performance.now();
    this.camStart = this.simStart;
    this.gestures = createGestures(this.sim ? {} : SLOW_CAMERA);
    this.loop(session);
  },

  stop() {
    this.session += 1;
    this.releaseCamera();
    this.state = 'idle';
    this.dialGrab = null;
    this.hand = { tracked: false, x: 0.5, y: 0.5, pose: 'unknown', solidity: 0 };
  },

  releaseCamera() {
    if (this.stream) this.stream.getTracks().forEach((t) => t && t.stop && t.stop());
    this.stream = null;
    this.capture = null;
    if (this.previewUrl) {
      URL.revokeObjectURL(this.previewUrl);
      this.previewUrl = '';
      this.setData({ previewSrc: '' });
    }
  },

  async loop(session) {
    let last = performance.now();
    while (session === this.session) {
      const t0 = performance.now();
      try {
        const frame = await this.nextFrame();
        if (session !== this.session) return;
        this.lastFrame = frame;
        const v0 = performance.now();
        this.obs = analyze(frame, this.model, this.vision);
        this.visionMs += (performance.now() - v0 - this.visionMs) * 0.3;
        this.hand = this.gestures.update(this.obs, t0);
        for (const e of this.hand.events) this.onGesture(e);
        this.trackDial();
      } catch (error) {
        if (session !== this.session) return;
        // The first frames take a moment to arrive after the camera opens.
        if (!this.sim && performance.now() - this.camStart < CAMERA_WARMUP_MS) {
          await sleep(100);
          continue;
        }
        // A camera that stops delivering frames must not leave a frozen screen.
        this.releaseCamera();
        this.sim = true;
        this.camError = String((error && error.message) || error);
        this.simStart = performance.now();
        this.gestures.reset();
      }
      const now = performance.now();
      this.workMs += (now - t0 - this.workMs) * 0.2;
      this.fps += (1000 / Math.max(1, now - last) - this.fps) * 0.2;
      last = now;
      if (now - this.loggedAt > 2000) {
        this.loggedAt = now;
        console.info(
          `[gesture-lab] src=${this.source} read=${this.readMode || '-'} fps=${this.fps.toFixed(1)} work=${this.workMs.toFixed(0)}ms grab=${this.grabMs.toFixed(0)} read=${this.readMs.toFixed(0)} vision=${this.visionMs.toFixed(0)} ` +
            `hand=${this.obs.found ? `${this.obs.x.toFixed(2)},${this.obs.y.toFixed(2)} shape=${this.obs.solidity.toFixed(2)} size=${(this.obs.area * 100).toFixed(0)}%` : 'none'} ` +
            `pose=${this.hand.pose}${this.camError ? ` camError=${this.camError}` : ''}`,
        );
      }
      await sleep(Math.max(0, FRAME_INTERVAL_MS - (performance.now() - t0)));
    }
  },

  ensureGrid(srcW, srcH) {
    const gh = Math.max(8, Math.round((GRID_W * srcH) / srcW));
    if (!this.vision || gh !== this.gh) {
      this.gh = gh;
      this.vision = createVision(GRID_W, gh);
      this.small = null;
    }
  },

  async nextFrame() {
    if (this.sim) {
      this.ensureGrid(16, 9);
      this.source = 'simulated';
      return makeFrame(this.gw, this.gh, script(performance.now() - this.simStart));
    }
    const g0 = performance.now();
    const bitmap = await this.capture.grabFrame();
    const g1 = performance.now();
    try {
      this.ensureGrid(bitmap.width, bitmap.height);
      this.source = `${bitmap.width}x${bitmap.height}`;
      return this.readPixels(bitmap);
    } finally {
      this.grabMs += (g1 - g0 - this.grabMs) * 0.3;
      this.readMs += (performance.now() - g1 - this.readMs) * 0.3;
      if (bitmap && typeof bitmap.close === 'function') bitmap.close();
    }
  },

  // Shrinks a camera frame to the analysis grid. Prefers letting the canvas do the
  // scaling; if this runtime ignores scaled bitmap draws, copies at full size and
  // samples every Nth pixel instead.
  readPixels(bitmap) {
    const gw = this.gw;
    const gh = this.gh;
    if (this.readMode !== 'full') {
      if (!this.small) {
        this.small = new Canvas(gw, gh);
        this.smallCtx = this.small.getContext('2d');
      }
      this.smallCtx.clearRect(0, 0, gw, gh);
      this.smallCtx.drawImage(bitmap, 0, 0, gw, gh);
      const data = this.smallCtx.getImageData(0, 0, gw, gh).data;
      if (this.readMode === 'scaled') return data;
      let drawn = false;
      for (let i = 3; i < data.length; i += 4 * 97) {
        if (data[i]) {
          drawn = true;
          break;
        }
      }
      if (drawn) {
        this.readMode = 'scaled';
        return data;
      }
      this.readMode = 'full';
    }

    const bw = bitmap.width;
    const bh = bitmap.height;
    if (!this.full || this.full.width !== bw || this.full.height !== bh) {
      this.full = new Canvas(bw, bh);
      this.fullCtx = this.full.getContext('2d');
      this.gridBuf = new Uint8ClampedArray(gw * gh * 4);
    }
    this.fullCtx.drawImage(bitmap, 0, 0);
    const src = this.fullCtx.getImageData(0, 0, bw, bh).data;
    const out = this.gridBuf;
    for (let y = 0; y < gh; y++) {
      const sy = Math.min(bh - 1, Math.floor(((y + 0.5) * bh) / gh));
      for (let x = 0; x < gw; x++) {
        const sx = Math.min(bw - 1, Math.floor(((x + 0.5) * bw) / gw));
        const s = (sy * bw + sx) * 4;
        const d = (y * gw + x) * 4;
        out[d] = src[s];
        out[d + 1] = src[s + 1];
        out[d + 2] = src[s + 2];
        out[d + 3] = 255;
      }
    }
    return out;
  },

  calibrateBox() {
    const size = Math.max(4, Math.round(this.gh * 0.28));
    return {
      x: Math.round(this.gw / 2 - size / 2),
      y: Math.round(this.gh / 2 - size / 2),
      w: size,
      h: size,
    };
  },

  calibrateNow() {
    if (!this.lastFrame) return;
    const model = calibrate(this.lastFrame, this.gw, this.gh, this.calibrateBox());
    if (!model) {
      this.say('TOO DARK TO CALIBRATE');
      return;
    }
    this.model = model;
    this.gestures.reset();
    this.say('HAND COLOUR SAVED');
  },

  say(text) {
    this.toast = text;
    this.toastUntil = performance.now() + 1600;
  },

  turnPage(step) {
    this.page = (this.page + step + PAGES.length) % PAGES.length;
    this.dialGrab = null;
    this.say(`${PAGES[this.page]}  ${this.page + 1}/${PAGES.length}`);
  },

  stageRect() {
    return { x: 0, y: HUD_H, w: this.cw, h: this.ch - HUD_H - FOOT_H };
  },

  toScreen(x, y) {
    const r = this.stageRect();
    return {
      x: r.x + clamp01(0.5 + (x - 0.5) * CURSOR_GAIN) * r.w,
      y: r.y + clamp01(0.5 + (y - 0.5) * CURSOR_GAIN) * r.h,
    };
  },

  tileRects() {
    const r = this.stageRect();
    const cols = 3;
    const rows = 2;
    const gap = 10;
    const pad = 14;
    const tw = (r.w - pad * 2 - gap * (cols - 1)) / cols;
    const th = (r.h - pad * 2 - gap * (rows - 1)) / rows;
    return TILES.map((label, i) => ({
      label,
      x: r.x + pad + (i % cols) * (tw + gap),
      y: r.y + pad + Math.floor(i / cols) * (th + gap),
      w: tw,
      h: th,
    }));
  },

  tileAt(p) {
    return this.tileRects().findIndex((t) => p.x >= t.x && p.x <= t.x + t.w && p.y >= t.y && p.y <= t.y + t.h);
  },

  onGesture(e) {
    if (e.type === 'swipe-left') this.turnPage(1);
    else if (e.type === 'swipe-right') this.turnPage(-1);
    else if (e.type === 'grab') {
      if (this.page === 0) {
        const i = this.tileAt(this.toScreen(e.x, e.y));
        if (i >= 0) this.tiles[i] = !this.tiles[i];
      } else if (this.page === 1) {
        this.dialGrab = { x: e.x, value: this.dial };
      }
    } else if (e.type === 'release' || e.type === 'lost') {
      this.dialGrab = null;
    }
  },

  trackDial() {
    if (!this.dialGrab || !this.hand.tracked) return;
    const v = this.dialGrab.value + (this.hand.x - this.dialGrab.x) * DIAL_GAIN;
    this.dial = Math.round(v < 0 ? 0 : v > 100 ? 100 : v);
  },

  draw() {
    const ctx = wx.createCanvasContext(CANVAS_ID);
    if (!ctx) return;
    const { cw, ch } = this;
    ctx.clearRect(0, 0, cw, ch);
    ctx.textBaseline = 'middle';

    if (this.state !== 'running') {
      this.drawIdle(ctx);
    } else {
      this.easeCursor();
      this.drawHud(ctx);
      if (this.page === 0) this.drawTiles(ctx);
      else if (this.page === 1) this.drawDial(ctx);
      else this.drawVision(ctx);
      if (this.page !== 2) this.drawCursor(ctx);
      this.drawFooter(ctx);
    }
    ctx.flush();
  },

  drawIdle(ctx) {
    const { cw, ch } = this;
    ctx.textAlign = 'center';
    ctx.fillStyle = green(1);
    ctx.font = '22px Arial';
    ctx.fillText('Gesture Lab', cw / 2, ch / 2 - 34);
    ctx.fillStyle = green(0.72);
    ctx.font = '14px Arial';
    ctx.fillText(
      this.state === 'starting' ? 'Starting camera...' : 'Tap the temple to start the camera',
      cw / 2,
      ch / 2 + 4,
    );
    ctx.fillStyle = green(0.48);
    ctx.font = '12px Arial';
    ctx.fillText('Open hand moves the cursor. Fist selects. Swipe turns the page.', cw / 2, ch / 2 + 34);
  },

  drawHud(ctx) {
    const { cw } = this;
    ctx.font = '11px Arial';
    ctx.textAlign = 'left';
    ctx.fillStyle = green(0.72);
    ctx.fillText(`GESTURE LAB / ${PAGES[this.page]}`, 12, HUD_H / 2);
    ctx.textAlign = 'right';
    ctx.fillStyle = green(this.sim ? 1 : 0.48);
    ctx.fillText(`${this.sim ? 'SIMULATED HAND' : 'CAMERA'}  ${Math.round(this.fps)} FPS`, cw - 12, HUD_H / 2);
    ctx.fillStyle = green(0.24);
    ctx.fillRect(12, HUD_H - 1, cw - 24, 1);
  },

  drawFooter(ctx) {
    const { cw, ch } = this;
    const y = ch - FOOT_H / 2;
    ctx.font = '11px Arial';
    ctx.textAlign = 'center';
    let text;
    let alpha = 0.48;
    if (performance.now() < this.toastUntil) {
      text = this.toast;
      alpha = 1;
    } else if (this.sim && this.camError) {
      text = `NO CAMERA: ${this.camError}`.slice(0, 64);
      alpha = 0.72;
    } else if (!this.hand.tracked) {
      text = 'SHOW YOUR HAND TO THE CAMERA  /  TAP TO CALIBRATE';
    } else {
      text = `${this.hand.pose.toUpperCase()}  /  ${this.sim ? 'SWIPE OR ' : ''}TEMPLE UP-DOWN TO CHANGE PAGE`;
    }
    ctx.fillStyle = green(alpha);
    ctx.fillText(text, cw / 2, y);
  },

  // The hand position arrives in steps; glide the drawn cursor between them.
  easeCursor() {
    if (!this.hand.tracked) {
      this.cursor = null;
      return;
    }
    const target = this.toScreen(this.hand.x, this.hand.y);
    if (!this.cursor) this.cursor = target;
    this.cursor.x += (target.x - this.cursor.x) * CURSOR_EASE;
    this.cursor.y += (target.y - this.cursor.y) * CURSOR_EASE;
  },

  drawTiles(ctx) {
    const cursor = this.cursor;
    const hover = cursor ? this.tileAt(cursor) : -1;
    this.tileRects().forEach((t, i) => {
      const on = this.tiles[i];
      if (on) {
        ctx.fillStyle = green(0.12);
        ctx.fillRect(t.x, t.y, t.w, t.h);
      }
      ctx.lineWidth = i === hover ? 2 : 1;
      ctx.strokeStyle = green(i === hover ? 1 : on ? 0.72 : 0.24);
      ctx.strokeRect(t.x, t.y, t.w, t.h);
      ctx.textAlign = 'center';
      ctx.font = '14px Arial';
      ctx.fillStyle = green(on || i === hover ? 1 : 0.72);
      ctx.fillText(t.label, t.x + t.w / 2, t.y + t.h / 2 - 9);
      ctx.font = '11px Arial';
      ctx.fillStyle = green(on ? 1 : 0.48);
      ctx.fillText(on ? '[ ON ]' : 'OFF', t.x + t.w / 2, t.y + t.h / 2 + 12);
    });
  },

  drawDial(ctx) {
    const r = this.stageRect();
    const holding = Boolean(this.dialGrab);
    const x0 = r.x + 50;
    const x1 = r.x + r.w - 50;
    const y = r.y + r.h / 2 + 30;
    const kx = x0 + ((x1 - x0) * this.dial) / 100;

    ctx.textAlign = 'center';
    ctx.fillStyle = green(1);
    ctx.font = '44px Arial';
    ctx.fillText(String(this.dial), r.x + r.w / 2, r.y + r.h / 2 - 40);
    ctx.font = '11px Arial';
    ctx.fillStyle = green(holding ? 1 : 0.48);
    ctx.fillText(holding ? '[ HOLDING ]  MOVE LEFT OR RIGHT' : 'CLOSE YOUR FIST TO GRAB THE DIAL', r.x + r.w / 2, r.y + r.h / 2);

    ctx.fillStyle = green(0.24);
    ctx.fillRect(x0, y, x1 - x0, 1);
    ctx.fillStyle = green(0.72);
    ctx.fillRect(x0, y - 1, kx - x0, 3);
    for (let i = 0; i <= 10; i++) {
      const tx = x0 + ((x1 - x0) * i) / 10;
      ctx.fillStyle = green(0.48);
      ctx.fillRect(tx, y + 8, 1, i % 5 === 0 ? 8 : 4);
    }
    ctx.beginPath();
    ctx.arc(kx, y, holding ? 9 : 6, 0, Math.PI * 2);
    if (holding) {
      ctx.fillStyle = green(1);
      ctx.fill();
    } else {
      ctx.lineWidth = 2;
      ctx.strokeStyle = green(1);
      ctx.stroke();
    }
  },

  drawVision(ctx) {
    const r = this.stageRect();
    const gw = this.gw;
    const gh = this.gh;
    const cell = Math.max(1, Math.floor(Math.min((r.w - 24) / gw, (r.h - 64) / gh)));
    const ox = Math.round(r.x + (r.w - gw * cell) / 2);
    const oy = r.y + 8;

    ctx.lineWidth = 1;
    ctx.strokeStyle = green(0.24);
    ctx.strokeRect(ox - 0.5, oy - 0.5, gw * cell + 1, gh * cell + 1);

    const mask = this.vision ? this.vision.mask : null;
    if (mask) {
      ctx.fillStyle = green(0.48);
      for (let y = 0; y < gh; y++) {
        let run = -1;
        for (let x = 0; x <= gw; x++) {
          const hit = x < gw && mask[y * gw + x];
          if (hit && run < 0) run = x;
          if (!hit && run >= 0) {
            ctx.fillRect(ox + run * cell, oy + y * cell, (x - run) * cell, cell - 1);
            run = -1;
          }
        }
      }
    }

    const b = this.calibrateBox();
    ctx.setLineDash([3, 3]);
    ctx.strokeStyle = green(0.72);
    ctx.strokeRect(ox + b.x * cell, oy + b.y * cell, b.w * cell, b.h * cell);
    ctx.setLineDash([]);

    if (this.obs.found) {
      ctx.beginPath();
      ctx.arc(ox + this.obs.x * gw * cell, oy + this.obs.y * gh * cell, 4, 0, Math.PI * 2);
      ctx.fillStyle = green(1);
      ctx.fill();
    }

    const ty = oy + gh * cell + 16;
    ctx.font = '11px Arial';
    ctx.textAlign = 'center';
    ctx.fillStyle = green(0.72);
    const shape = this.obs.found
      ? `SHAPE ${this.obs.solidity.toFixed(2)} ${this.hand.pose.toUpperCase()}   SIZE ${Math.round(this.obs.area * 100)}%`
      : 'NO HAND';
    ctx.fillText(shape, r.x + r.w / 2, ty);
    ctx.fillStyle = green(0.48);
    ctx.fillText(
      `${this.source} ${this.readMode || '-'}   ${this.workMs.toFixed(0)} MS/FRAME   COLOUR ${this.model.calibrated ? 'CALIBRATED' : 'DEFAULT'}`,
      r.x + r.w / 2,
      ty + 16,
    );
    ctx.fillText('FILL THE DASHED BOX WITH YOUR HAND, THEN TAP', r.x + r.w / 2, ty + 32);
  },

  drawCursor(ctx) {
    const p = this.cursor;
    if (!p) return;
    ctx.beginPath();
    if (this.hand.pose === 'fist') {
      ctx.arc(p.x, p.y, 6, 0, Math.PI * 2);
      ctx.fillStyle = green(1);
      ctx.fill();
    } else {
      ctx.arc(p.x, p.y, 10, 0, Math.PI * 2);
      ctx.lineWidth = 2;
      ctx.strokeStyle = green(1);
      ctx.stroke();
      ctx.fillStyle = green(1);
      ctx.fillRect(p.x - 1, p.y - 1, 2, 2);
    }
  },
};
</script>

<page>
  <view class="root" bindtap="onTap">
    <canvas id="stage" class="stage" width="480" height="352"></canvas>
    <video
      class="feed"
      src="{{previewSrc}}"
      autoplay
      muted
      object-fit="cover"
      render-mode="wireframe"
      wireframe-quality="low"
    />
  </view>
</page>

<style>
.root {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100vw;
  height: 100vh;
  background-color: #000000;
}

.stage {
  width: 480px;
  height: 352px;
}
.feed {
  position: absolute;
  left: 4px;
  bottom: 2px;
  width: 32px;
  height: 18px;
}
</style>
