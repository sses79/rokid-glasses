// Turns per-frame hand observations into a smoothed cursor, a debounced pose, and
// discrete gesture events. All timing is in milliseconds so behaviour does not
// depend on the frame rate the glasses manage.

export const DEFAULTS = {
  openBelow: 0.74, // solidity under this reads as an open hand
  fistAbove: 0.82, // solidity over this reads as a fist
  poseHoldMs: 120, // a new pose must persist this long before it is accepted
  lostAfterMs: 350, // hand missing this long ends tracking
  smoothing: 0.5, // cursor EMA weight for the newest sample
  swipeWindowMs: 400,
  swipeDistance: 0.3, // share of frame width travelled inside the window
  swipeSettleMs: 400, // tracking time required before swipes count
  swipeCooldownMs: 800,
  latchMs: 250, // grab/release report where the cursor was this long ago
};

export function createGestures(options = {}) {
  const o = { ...DEFAULTS, ...options };
  const s = {
    tracked: false,
    since: 0,
    lastSeen: 0,
    x: 0.5,
    y: 0.5,
    pose: 'unknown',
    pending: 'unknown',
    pendingSince: 0,
    solidity: 0,
    trail: [],
    swipeReadyAt: 0,
  };

  function reset() {
    s.tracked = false;
    s.pose = 'unknown';
    s.pending = 'unknown';
    s.trail = [];
  }

  // The pose change itself shifts the hand's centre, so target the place the
  // cursor rested just before the change began.
  function latched(t) {
    let p = s.trail[0];
    for (const q of s.trail) {
      if (q.t <= t - o.latchMs) p = q;
    }
    return p ? { x: p.sx, y: p.sy } : { x: s.x, y: s.y };
  }

  function update(obs, t) {
    const events = [];

    if (!obs || !obs.found) {
      if (s.tracked && t - s.lastSeen > o.lostAfterMs) {
        reset();
        events.push({ type: 'lost' });
      }
      return view(events);
    }

    if (!s.tracked) {
      s.tracked = true;
      s.since = t;
      s.x = obs.x;
      s.y = obs.y;
      events.push({ type: 'found' });
    } else {
      s.x += (obs.x - s.x) * o.smoothing;
      s.y += (obs.y - s.y) * o.smoothing;
    }
    s.lastSeen = t;
    s.solidity = obs.solidity;

    s.trail.push({ t, x: obs.x, y: obs.y, sx: s.x, sy: s.y });
    while (s.trail.length && t - s.trail[0].t > o.swipeWindowMs) s.trail.shift();

    // Between the two thresholds the current pose is kept (hysteresis).
    const reading = obs.solidity < o.openBelow ? 'open' : obs.solidity > o.fistAbove ? 'fist' : s.pose;
    if (reading !== s.pending) {
      s.pending = reading;
      s.pendingSince = t;
    }
    if (s.pending !== s.pose && s.pending !== 'unknown' && t - s.pendingSince >= o.poseHoldMs) {
      const from = s.pose;
      s.pose = s.pending;
      if (from === 'open' && s.pose === 'fist') events.push({ type: 'grab', ...latched(t) });
      if (from === 'fist' && s.pose === 'open') events.push({ type: 'release', ...latched(t) });
    }

    if (s.pose !== 'fist' && t - s.since >= o.swipeSettleMs && t >= s.swipeReadyAt && s.trail.length >= 2) {
      const a = s.trail[0];
      const dx = obs.x - a.x;
      const dy = obs.y - a.y;
      if (Math.abs(dx) >= o.swipeDistance && Math.abs(dy) < 0.6 * Math.abs(dx)) {
        events.push({ type: dx > 0 ? 'swipe-right' : 'swipe-left' });
        s.swipeReadyAt = t + o.swipeCooldownMs;
        s.trail = [];
      }
    }

    return view(events);
  }

  function view(events) {
    return { tracked: s.tracked, x: s.x, y: s.y, pose: s.pose, solidity: s.solidity, events };
  }

  return { update, reset };
}
