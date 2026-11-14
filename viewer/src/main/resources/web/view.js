// FTC Test Suite live view: draws what the simulator streams. View only - it never sends anything back.
'use strict';

const IN = 0.0254;
const HISTORY_SECONDS = 30;
const MAX_TRAIL = 4000;

const state = {
  field: null,          // /api/field (static layout)
  fieldVersion: -1,
  loadingVersion: -1,
  prev: null, cur: null, // the two latest frames, for smooth motion
  prevAt: 0, curAt: 0,
  trail: [],
  history: [],          // {t, speed, battery, current, loop}
  hoverAge: null,       // seconds before "now" the charts' crosshair is at, or null
  lastFrameAt: 0,
  lastDom: 0,
  lastPhase: null,
  connected: false,
  follow: false,        // "Follow robot": a closer view that stays on the robot (this page only)
  cam: null,            // where the follow view looks {x, y}, eased toward the robot
  camAt: 0,
};

const FOLLOW_ZOOM = 2.3;       // about 60 in of field across
const FOLLOW_SECONDS = 0.25;   // how quickly the view catches up with the robot

const $ = (id) => document.getElementById(id);

// ------------------------------------------------------------------ connection

function connect() {
  const es = new EventSource('api/stream');
  es.onopen = () => { state.connected = true; setConnection(); };
  es.onerror = () => { state.connected = false; setConnection(); };
  es.onmessage = (ev) => {
    let f;
    try { f = JSON.parse(ev.data); } catch (e) { return; }
    onFrame(f);
  };
}

function setConnection() {
  const el = $('conn'), text = $('conn-text');
  const stale = performance.now() - state.lastFrameAt > 3000;
  if (!state.connected && stale) {
    el.dataset.status = 'critical';
    text.textContent = state.lastFrameAt ? 'Simulator closed or unreachable' : 'Can’t reach the simulator';
  } else if (!state.connected || stale) {
    el.dataset.status = 'warning';
    text.textContent = state.lastFrameAt ? 'Reconnecting' : 'Connecting';
  } else {
    el.dataset.status = 'good';
    text.textContent = 'Live';
  }
}

function onFrame(f) {
  state.lastFrameAt = performance.now();
  if (!f.ready) {
    $('field-name').textContent = 'Waiting for the simulator…';
    setConnection();
    return;
  }
  if (f.v !== state.fieldVersion) {
    loadField(f.v);
    state.trail = [];
    state.history = [];
    state.prev = null;
  }
  state.prev = state.cur;
  state.prevAt = state.curAt;
  state.cur = f;
  state.curAt = performance.now();
  addHistory(f);
  addTrail(f);
  setConnection();
  drawCharts();
  if (performance.now() - state.lastDom > 150) {
    state.lastDom = performance.now();
    updatePanels(f);
  }
}

function loadField(v) {
  if (state.loadingVersion === v) return;
  state.loadingVersion = v;
  fetch('api/field', { cache: 'no-store' })
    .then((r) => r.json())
    .then((fd) => {
      if (!fd.ready) { state.loadingVersion = -1; return; }
      state.field = fd;
      state.fieldVersion = fd.v;
      $('field-name').textContent = fd.name;
      $('robot-name').textContent = fd.robot;
      document.title = 'FTC Test Suite Live · ' + fd.name;
      resizeCanvases();
      return fetch('api/history', { cache: 'no-store' }).then((r) => r.json()).then((hist) => {
        if (hist.v !== state.fieldVersion) return;
        const first = state.history.length ? state.history[0].t : Infinity;
        const older = hist.samples.filter((s) => s[0] < first)
          .map((s) => ({ t: s[0], speed: s[1], battery: s[2], current: s[3], loop: s[4] }));
        state.history = older.concat(state.history);
        const now = state.history.length ? state.history[state.history.length - 1].t : 0;
        while (state.history.length && state.history[0].t < now - HISTORY_SECONDS) state.history.shift();
        drawCharts();
      });
    })
    .catch(() => { state.loadingVersion = -1; });
}

// ------------------------------------------------------------------ history & trail

function addHistory(f) {
  const h = state.history;
  const r = f.robot;
  const last = h[h.length - 1];
  if (last && f.t < last.t - 1e-6) h.length = 0;           // the simulation was rebuilt
  if (last && f.t <= last.t) return;                        // paused: nothing new
  h.push({
    t: f.t,
    speed: Math.hypot(r.vx, r.vy) / IN,
    battery: r.battery,
    current: r.current,
    loop: f.sim.loopMs,
  });
  while (h.length && h[0].t < f.t - HISTORY_SECONDS) h.shift();
}

function addTrail(f) {
  const r = f.robot, tr = state.trail;
  const phase = f.match.phase;
  if (phase === 'AUTO' && state.lastPhase !== 'AUTO') tr.length = 0; // a match started: field was reset
  state.lastPhase = phase;
  const last = tr[tr.length - 1];
  if (last) {
    const d = Math.hypot(r.x - last[0], r.y - last[1]);
    if (d > 12 * IN) { tr.length = 0; }                     // picked up and moved, or reset
    else if (d < 0.25 * IN) return;
  }
  tr.push([r.x, r.y]);
  if (tr.length > MAX_TRAIL) tr.splice(0, tr.length - MAX_TRAIL);
}

// ------------------------------------------------------------------ field drawing

const C = {
  bg: '#1e2023', tileA: '#3b3f44', tileB: '#41454b', seam: '#2a2c30', wall: '#c9cdd2', label: '#8b9096',
  robot: 'rgba(242,179,61,0.84)', robotEdge: '#7a5410', front: '#4fc3f7', trail: 'rgba(79,195,247,0.59)',
  ray: '#ff6b6b', forward: '#4caf50', backward: '#e53935', red: '#e53935', blue: '#1e88e5',
  frame: '#b0bec5', flowerRing: '#e0a82e',
};

const view = { scale: 1, cx: 0, cy: 0 };
const sx = (x) => view.cx + x * view.scale;
const sy = (y) => view.cy - y * view.scale;

function rgba(hex, a) {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
}

function darker(hex, k) {
  const n = parseInt(hex.slice(1), 16);
  const c = (v) => Math.round(v * k);
  return `rgb(${c((n >> 16) & 255)},${c((n >> 8) & 255)},${c(n & 255)})`;
}

function allianceColor(a) { return a === 'red' ? C.red : C.blue; }

function setupCanvas(cv) {
  const rect = cv.getBoundingClientRect();
  const dpr = window.devicePixelRatio || 1;
  const w = Math.max(1, Math.round(rect.width * dpr)), h = Math.max(1, Math.round(rect.height * dpr));
  if (cv.width !== w || cv.height !== h) { cv.width = w; cv.height = h; }
  const ctx = cv.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  return { ctx, w: rect.width, h: rect.height };
}

function layoutField(w, h, fd, pose) {
  const margin = Math.min(34, Math.max(18, w * 0.05));
  const width = fd.size + (fd.season ? 30 * IN : 0);
  view.scale = Math.min((w - 2 * margin) / width, (h - 2 * margin) / fd.size);
  view.cx = w / 2;
  view.cy = h / 2 - 4;
  if (!state.follow || !pose) { state.cam = null; return; }
  // Follow robot: zoomed in, the view easing after the robot rather than bolted to it.
  const now = performance.now();
  const jump = state.cam && Math.hypot(pose.x - state.cam.x, pose.y - state.cam.y) > 1.5;
  if (!state.cam || jump) {
    state.cam = { x: pose.x, y: pose.y };
  } else {
    const k = 1 - Math.exp(-Math.min(0.1, (now - state.camAt) / 1000) / FOLLOW_SECONDS);
    state.cam.x += (pose.x - state.cam.x) * k;
    state.cam.y += (pose.y - state.cam.y) * k;
  }
  state.camAt = now;
  view.scale *= FOLLOW_ZOOM;
  view.cx = w / 2 - state.cam.x * view.scale;
  view.cy = h / 2 + state.cam.y * view.scale;
}

function setFollow(on) {
  state.follow = on;
  state.cam = null;
  $('view-field').setAttribute('aria-pressed', String(!on));
  $('view-follow').setAttribute('aria-pressed', String(on));
  try { localStorage.setItem('ftc-view-follow', on ? '1' : '0'); } catch (e) { /* private window: just don't remember */ }
}

/** The frame to draw now: one frame behind the latest, blended for smooth motion. */
function displayFrame() {
  const a = state.prev, b = state.cur;
  if (!b) return null;
  if (!a || a.v !== b.v) return { frame: b, pose: b.robot, pieces: b.pieces };
  const span = Math.max(1, state.curAt - state.prevAt);
  const k = Math.min(1, Math.max(0, (performance.now() - state.curAt) / span));
  const ra = a.robot, rb = b.robot;
  const dh = Math.atan2(Math.sin(rb.h - ra.h), Math.cos(rb.h - ra.h));
  const jump = Math.hypot(rb.x - ra.x, rb.y - ra.y) > 12 * IN;
  const pose = jump ? rb : { ...rb, x: ra.x + (rb.x - ra.x) * k, y: ra.y + (rb.y - ra.y) * k, h: ra.h + dh * k };
  const byId = new Map(a.pieces.map((p) => [p.id, p]));
  const pieces = b.pieces.map((p) => {
    const q = byId.get(p.id);
    if (!q || q.air !== p.air || Math.hypot(p.x - q.x, p.y - q.y) > 0.5) return p;
    return { ...p, x: q.x + (p.x - q.x) * k, y: q.y + (p.y - q.y) * k, z: q.z + (p.z - q.z) * k };
  });
  return { frame: b, pose, pieces };
}

function drawField() {
  const cv = $('field');
  const { ctx, w, h } = setupCanvas(cv);
  ctx.fillStyle = C.bg;
  ctx.fillRect(0, 0, w, h);
  const fd = state.field;
  if (!fd) {
    ctx.fillStyle = C.label;
    ctx.font = '14px system-ui, sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText(state.lastFrameAt ? 'Loading the field…' : 'Waiting for the simulator…', w / 2, h / 2);
    ctx.textAlign = 'left';
    return;
  }
  const d = displayFrame();
  layoutField(w, h, fd, d && d.pose);
  const bb = d && d.frame.season && d.frame.season.biobuzz && fd.biobuzz ? d.frame.season.biobuzz : null;

  paintTiles(ctx, fd);
  if (bb) paintBioBuzzFloor(ctx, fd.biobuzz, bb);
  for (const o of fd.obstacles) paintObstacle(ctx, o);
  paintTrail(ctx);
  if (!d) return;
  for (const p of d.pieces) if (!p.air) paintPiece(ctx, p);
  for (const r of d.frame.rays) paintRay(ctx, r);
  paintRobot(ctx, fd, d.frame, d.pose);
  if (bb) paintBioBuzzOverhead(ctx, fd.biobuzz, bb);
  for (const p of d.pieces) if (p.air) paintFlying(ctx, p);
}

function paintTiles(ctx, fd) {
  const half = fd.size / 2;
  const tiles = Math.round(fd.size / fd.tile);
  const t = fd.size / tiles;
  for (let i = 0; i < tiles; i++) {
    for (let j = 0; j < tiles; j++) {
      ctx.fillStyle = ((i + j) & 1) === 0 ? C.tileA : C.tileB;
      ctx.fillRect(sx(-half + i * t), sy(-half + (j + 1) * t), t * view.scale + 0.5, t * view.scale + 0.5);
    }
  }
  ctx.strokeStyle = C.seam;
  ctx.lineWidth = 1;
  ctx.beginPath();
  for (let i = 1; i < tiles; i++) {
    ctx.moveTo(sx(-half + i * t), sy(half)); ctx.lineTo(sx(-half + i * t), sy(-half));
    ctx.moveTo(sx(-half), sy(-half + i * t)); ctx.lineTo(sx(half), sy(-half + i * t));
  }
  ctx.stroke();
  ctx.strokeStyle = C.wall;
  ctx.lineWidth = 4;
  ctx.strokeRect(sx(-half), sy(half), fd.size * view.scale, fd.size * view.scale);

  ctx.font = '10px system-ui, sans-serif';
  ctx.fillStyle = C.label;
  for (let i = 0; i <= tiles; i++) {
    const v = -half + i * t;
    const label = String(Math.round(v / IN));
    const lw = ctx.measureText(label).width;
    ctx.fillText(label, sx(v) - lw / 2, sy(-half) + 16);
    ctx.fillText(label, sx(-half) - lw - 8, sy(v) + 4);
  }
  ctx.fillText('+x →', sx(half) - 12, sy(-half) + 27);
  ctx.fillText('+y ↑', sx(-half) - 26, sy(half) - 12);
}

function rectFromZone(z) {
  return [sx(z[0]), sy(z[3]), (z[1] - z[0]) * view.scale, (z[3] - z[2]) * view.scale];
}

function centeredLabel(ctx, text, x, y, keepInside) {
  const tw = ctx.measureText(text).width;
  let left = sx(x) - tw / 2;
  if (keepInside) left = Math.min(Math.max(left, sx(-keepInside) + 4), sx(keepInside) - tw - 4);
  ctx.fillText(text, left, sy(y));
}

function paintBioBuzzFloor(ctx, g, bb) {
  const HALF = g.half;
  const areaW = 10 * IN;
  for (const a of ['red', 'blue']) {
    const x0 = a === 'red' ? -HALF - areaW - 3 * IN : HALF + 3 * IN;
    ctx.fillStyle = rgba(allianceColor(a), 45 / 255);
    ctx.fillRect(sx(x0), sy(48.5 * IN), areaW * view.scale, 97 * IN * view.scale);
    const hp = bb.humanPlayer[a] || { nectar: 0, ready: 0 };
    const d = g.nectarDiameter * view.scale;
    for (let i = 0; i < hp.nectar; i++) {
      ctx.fillStyle = allianceColor(a);
      ctx.beginPath();
      ctx.arc(sx(x0 + areaW / 2), sy(-10 * IN + i * 4.2 * IN), d / 2, 0, Math.PI * 2);
      ctx.fill();
    }
    if (hp.nectar > 0) {
      ctx.font = 'bold 10px system-ui, sans-serif';
      ctx.fillStyle = '#fff';
      const label = hp.ready > 0 ? hp.ready + ' ready' : 'locked';
      ctx.fillText(label, sx(x0 + areaW / 2) - ctx.measureText(label).width / 2, sy(-14 * IN));
    }
  }

  ctx.save();
  ctx.setLineDash([8, 6]);
  ctx.lineWidth = 1.5;
  ctx.strokeStyle = 'rgba(255,255,255,0.16)';
  ctx.beginPath();
  ctx.moveTo(sx(0), sy(HALF)); ctx.lineTo(sx(0), sy(-HALF));
  ctx.stroke();
  ctx.restore();

  for (const a of ['red', 'blue']) {
    const c = allianceColor(a);
    const lz = rectFromZone(a === 'red' ? g.redLoadingZone : g.blueLoadingZone);
    ctx.fillStyle = rgba(c, 40 / 255);
    ctx.fillRect(...lz);
    ctx.strokeStyle = c;
    ctx.lineWidth = Math.max(2, IN * view.scale);
    ctx.strokeRect(...lz);
    ctx.fillStyle = rgba(c, 220 / 255);
    ctx.fillRect(...rectFromZone(a === 'red' ? g.redGarden : g.blueGarden));
  }
  if (view.scale < 120) return paintFrameFeet(ctx, g); // too small for zone names: they'd overlap
  ctx.font = 'bold 9px system-ui, sans-serif';
  ctx.fillStyle = 'rgba(255,255,255,0.59)';
  const R = g.redLoadingZone, B = g.blueLoadingZone, RG = g.redGarden, BG = g.blueGarden;
  centeredLabel(ctx, 'LOADING ZONE', (R[0] + R[1]) / 2, R[3] + 2 * IN, HALF);
  centeredLabel(ctx, 'LOADING ZONE', (B[0] + B[1]) / 2, B[2] - 4 * IN, HALF);
  centeredLabel(ctx, 'GARDEN', (RG[0] + RG[1]) / 2, RG[3] + 2 * IN, HALF);
  centeredLabel(ctx, 'GARDEN', (BG[0] + BG[1]) / 2, BG[2] - 4 * IN, HALF);
  paintFrameFeet(ctx, g);
}

function paintFrameFeet(ctx, g) {
  ctx.fillStyle = C.frame;
  for (const s of [-1, 1]) {
    // The feet's outside faces are frameHalfWidth from the center.
    ctx.fillRect(sx(s * (g.frameHalfWidth - g.frameFoot / 2) - g.frameFoot / 2), sy(g.frameHalfDepth),
      g.frameFoot * view.scale, 2 * g.frameHalfDepth * view.scale);
  }
}

function paintBioBuzzOverhead(ctx, g, bb) {
  ctx.strokeStyle = rgba(C.frame, 160 / 255);
  ctx.lineWidth = 3;
  ctx.beginPath();
  ctx.moveTo(sx(-12 * g.in), sy(0)); ctx.lineTo(sx(12 * g.in), sy(0));
  // The legs lean in from the feet to the top bar.
  for (const a of [-1, 1]) {
    for (const b of [-1, 1]) {
      ctx.moveTo(sx(a * g.legFootX), sy(b * g.legFootY)); ctx.lineTo(sx(a * g.legTopX), sy(b * g.legTopY));
    }
  }
  ctx.stroke();
  for (const h of bb.hives) paintHive(ctx, g, h);
  for (const f of bb.flowers) paintFlower(ctx, g, f);
}

function tagFor(g, alliance, side) {
  for (const t of g.tagClusters) if ((t[1] < 0) === (alliance === 'red') && t[2] === side) return t[0];
  return 0;
}

function paintHive(ctx, g, h) {
  const c = allianceColor(h.alliance);
  const t = h.tilt / 30;
  const MHW = g.mouthHalfWidth;
  for (const side of [1, -1]) {
    const up = side * t;
    const inner = 5.4 * IN, outer = (up > 0 ? 19.25 : 24) * IN;
    const x = sx(h.x - MHW), y = sy(side > 0 ? outer : -inner);
    const w = 2 * MHW * view.scale, hh = (outer - inner) * view.scale;
    ctx.fillStyle = rgba(c, (50 + 90 * Math.max(0, up)) / 255);
    ctx.fillRect(x, y, w, hh);
    ctx.save();
    ctx.strokeStyle = rgba(c, up > 0 ? 1 : 120 / 255);
    if (up > 0) { ctx.lineWidth = 2; } else { ctx.lineWidth = 1.5; ctx.setLineDash([4, 4]); }
    ctx.strokeRect(x, y, w, hh);
    ctx.restore();
    if (up > 0.5 && !h.tipping) {
      ctx.strokeStyle = '#fff';
      ctx.lineWidth = 4;
      ctx.lineCap = 'round';
      const ey = side * outer;
      ctx.beginPath();
      ctx.moveTo(sx(h.x - MHW + IN), sy(ey)); ctx.lineTo(sx(h.x + MHW - IN), sy(ey));
      ctx.stroke();
      ctx.lineCap = 'butt';
      const d = 2.4 * IN * view.scale;
      h.contents.forEach((col, i) => {
        const px = h.x + ((i % 6) - 2.5) * 3.0 * IN, py = side * (inner + 2 * IN + Math.floor(i / 6) * 3 * IN);
        ctx.fillStyle = col;
        ctx.beginPath();
        ctx.arc(sx(px), sy(py), d / 2, 0, Math.PI * 2);
        ctx.fill();
      });
    }
    const first = tagFor(g, h.alliance, side);
    const ty = side * (inner + outer) / 2;
    if (view.scale < 120) continue; // AprilTag ids are unreadable this small
    ctx.fillStyle = 'rgba(20,20,20,0.67)';
    ctx.fillRect(sx(h.x - 3.5 * IN), sy(ty + 1 * IN), 7 * IN * view.scale, 2 * IN * view.scale);
    ctx.font = '8px system-ui, sans-serif';
    ctx.fillStyle = 'rgba(255,255,255,0.78)';
    centeredLabel(ctx, first + '-' + (first + 3), h.x, ty - 0.6 * IN);
  }
  const barW = 16 * IN * view.scale, barH = 4;
  const bx = sx(h.x) - barW / 2, by = sy(0) + 10;
  ctx.fillStyle = 'rgba(0,0,0,0.55)';
  roundRect(ctx, bx, by, barW, barH, 2);
  ctx.fill();
  ctx.fillStyle = h.tipping ? '#fff' : c;
  roundRect(ctx, bx, by, barW * Math.min(1, h.fill), barH, 2);
  ctx.fill();
  ctx.font = 'bold 10px system-ui, sans-serif';
  ctx.fillStyle = '#fff';
  const tips = h.tipping ? 'TIPPING' : 'TIPS ' + h.tips;
  ctx.fillText(tips, sx(h.x) - ctx.measureText(tips).width / 2, by - 3);
}

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  if (w <= 0) return;
  r = Math.min(r, w / 2, h / 2);
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

function paintFlower(ctx, g, f) {
  const ax = sx(f.axisX), ay = sy(f.axisY);
  const ro = 2.6 * IN * view.scale, ri = g.flowerOpeningRadius * view.scale;
  ctx.fillStyle = C.flowerRing;
  circle(ctx, ax, ay, ro); ctx.fill();
  ctx.fillStyle = '#2a2c30';
  circle(ctx, ax, ay, ri); ctx.fill();
  if (f.owner) {
    ctx.strokeStyle = allianceColor(f.owner);
    ctx.lineWidth = 3;
    circle(ctx, ax, ay, ro + 2); ctx.stroke();
  }
  const d = 2.0 * IN * view.scale;
  if (f.stack.length) {
    const a0 = 4.2 * IN, a1 = (4.2 + (f.stack.length - 1) * 2.2) * IN;
    ctx.strokeStyle = '#2e7d32';
    ctx.lineWidth = d + 5;
    ctx.lineCap = 'round';
    ctx.beginPath();
    ctx.moveTo(sx(f.axisX + f.nx * a0), sy(f.axisY + f.ny * a0));
    ctx.lineTo(sx(f.axisX + f.nx * a1), sy(f.axisY + f.ny * a1));
    ctx.stroke();
    ctx.lineCap = 'butt';
  }
  f.stack.forEach((col, i) => {
    const off = (4.2 + i * 2.2) * IN;
    const px = sx(f.axisX + f.nx * off), py = sy(f.axisY + f.ny * off);
    ctx.fillStyle = col;
    circle(ctx, px, py, d / 2); ctx.fill();
    ctx.strokeStyle = 'rgba(0,0,0,0.47)';
    ctx.lineWidth = 1;
    ctx.stroke();
  });
}

function circle(ctx, x, y, r) {
  ctx.beginPath();
  ctx.arc(x, y, Math.max(0, r), 0, Math.PI * 2);
}

function paintObstacle(ctx, o) {
  ctx.save();
  ctx.translate(sx(o.x), sy(o.y));
  ctx.rotate(-o.heading);
  const s = view.scale;
  ctx.beginPath();
  if (o.shape === 'circle') {
    ctx.arc(0, 0, o.diameter / 2 * s, 0, Math.PI * 2);
  } else if (o.shape === 'polygon') {
    o.points.forEach((p, i) => {
      const px = (p[0] - o.x) * s, py = -(p[1] - o.y) * s;
      if (i === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
    });
    ctx.closePath();
  } else {
    ctx.rect(-o.width / 2 * s, -o.height / 2 * s, o.width * s, o.height * s);
  }
  const col = o.color || '#6e7781';
  ctx.fillStyle = rgba(col, 200 / 255);
  ctx.fill();
  ctx.strokeStyle = darker(col, 0.7);
  ctx.lineWidth = 1.5;
  ctx.stroke();
  ctx.restore();
}

function paintTrail(ctx) {
  const tr = state.trail;
  if (tr.length < 2) return;
  ctx.strokeStyle = C.trail;
  ctx.lineWidth = 2;
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';
  ctx.beginPath();
  ctx.moveTo(sx(tr[0][0]), sy(tr[0][1]));
  for (let i = 1; i < tr.length; i++) ctx.lineTo(sx(tr[i][0]), sy(tr[i][1]));
  ctx.stroke();
  ctx.lineCap = 'butt';
}

function paintPiece(ctx, p) {
  const col = p.c || '#888888';
  if (p.ball) {
    circle(ctx, sx(p.x), sy(p.y), p.r * view.scale);
    ctx.fillStyle = col;
    ctx.fill();
    ctx.strokeStyle = darker(col, 0.49);
    ctx.lineWidth = 1;
    ctx.stroke();
    return;
  }
  ctx.save();
  ctx.translate(sx(p.x), sy(p.y));
  ctx.rotate(-p.h);
  const s = view.scale;
  ctx.fillStyle = col;
  ctx.fillRect(-p.w / 2 * s, -p.l / 2 * s, p.w * s, p.l * s);
  ctx.strokeStyle = darker(col, 0.7);
  ctx.strokeRect(-p.w / 2 * s, -p.l / 2 * s, p.w * s, p.l * s);
  ctx.restore();
}

function paintFlying(ctx, p) {
  const r = p.r * view.scale;
  ctx.fillStyle = 'rgba(0,0,0,0.35)';
  ctx.beginPath();
  ctx.ellipse(sx(p.x), sy(p.y), r, 0.7 * r, 0, 0, Math.PI * 2);
  ctx.fill();
  const z = Math.max(0, p.z);
  const rr = r * (1 + z / 1.2);
  const lift = z * view.scale * 0.25;
  circle(ctx, sx(p.x), sy(p.y) - lift, rr);
  ctx.fillStyle = p.c || '#888888';
  ctx.fill();
  ctx.strokeStyle = '#fff';
  ctx.lineWidth = 1.2;
  ctx.stroke();
}

function paintRay(ctx, r) {
  ctx.save();
  ctx.setLineDash([5, 4]);
  ctx.lineWidth = 1.5;
  ctx.strokeStyle = rgba(C.ray, r.hit ? 220 / 255 : 90 / 255);
  ctx.beginPath();
  ctx.moveTo(sx(r.x0), sy(r.y0)); ctx.lineTo(sx(r.x1), sy(r.y1));
  ctx.stroke();
  ctx.restore();
  if (r.hit) {
    ctx.fillStyle = rgba(C.ray, 220 / 255);
    circle(ctx, sx(r.x1), sy(r.y1), 3); ctx.fill();
  }
}

function paintRobot(ctx, fd, f, pose) {
  const s = view.scale;
  ctx.save();
  ctx.translate(sx(pose.x), sy(pose.y));
  ctx.rotate(-pose.h);
  const l = fd.robotSize.length * s, w = fd.robotSize.width * s;
  ctx.fillStyle = C.robot;
  ctx.fillRect(-l / 2, -w / 2, l, w);

  const wheelL = fd.robotSize.wheelRadius * 2 * s, wheelW = 1.5 * IN * s;
  for (const wh of f.wheels) {
    const px = wh.x * s, py = -wh.y * s;
    const d = Math.max(-1, Math.min(1, wh.duty));
    ctx.fillStyle = '#26282b';
    ctx.fillRect(px - wheelL / 2, py - wheelW / 2, wheelL, wheelW);
    ctx.fillStyle = rgba(d >= 0 ? C.forward : C.backward, (60 + 195 * Math.abs(d)) / 255);
    ctx.fillRect(px - wheelL / 2, py - wheelW / 2, wheelL, wheelW);
    ctx.strokeStyle = 'rgba(0,0,0,0.35)';
    ctx.lineWidth = 1;
    const slashUp = wh.label === 'FL' || wh.label === 'BR';
    ctx.beginPath();
    for (let k = -2; k <= 2; k++) {
      const ox = px + k * wheelL / 5;
      ctx.moveTo(ox - wheelW / 2, py + (slashUp ? wheelW / 2 : -wheelW / 2));
      ctx.lineTo(ox + wheelW / 2, py + (slashUp ? -wheelW / 2 : wheelW / 2));
    }
    ctx.stroke();
    if (wh.slip) {
      ctx.strokeStyle = '#ff9800';
      ctx.lineWidth = 2;
      ctx.strokeRect(px - wheelL / 2, py - wheelW / 2, wheelL, wheelW);
    }
    if (wh.lifting) {
      ctx.fillStyle = 'rgba(38,40,43,0.8)';
      ctx.fillRect(px - wheelL / 2, py - wheelW / 2, wheelL, wheelW);
      ctx.save();
      ctx.setLineDash([3, 2]);
      ctx.strokeStyle = '#e53935';
      ctx.lineWidth = 2;
      ctx.strokeRect(px - wheelL / 2, py - wheelW / 2, wheelL, wheelW);
      ctx.restore();
    }
  }

  if (f.parts) paintParts(ctx, f.parts);

  ctx.strokeStyle = C.robotEdge;
  ctx.lineWidth = 2;
  ctx.strokeRect(-l / 2, -w / 2, l, w);
  ctx.strokeStyle = C.front;
  ctx.lineWidth = 4;
  ctx.lineCap = 'round';
  ctx.beginPath();
  ctx.moveTo(l / 2 - 2, -w / 2 + 4); ctx.lineTo(l / 2 - 2, w / 2 - 4);
  ctx.stroke();
  const tip = l * 0.32;
  ctx.strokeStyle = '#202225';
  ctx.fillStyle = '#202225';
  ctx.lineWidth = 2.5;
  ctx.beginPath();
  ctx.moveTo(0, 0); ctx.lineTo(tip, 0);
  ctx.stroke();
  ctx.lineCap = 'butt';
  ctx.beginPath();
  ctx.moveTo(tip + 6, 0); ctx.lineTo(tip - 4, -6); ctx.lineTo(tip - 4, 6);
  ctx.closePath();
  ctx.fill();
  circle(ctx, 0, 0, 3); ctx.fill();
  ctx.restore();
}

function paintParts(ctx, parts) {
  const s = view.scale;
  if (parts.intakeZone) {
    const z = parts.intakeZone;
    ctx.fillStyle = parts.intakeDirection > 0 ? 'rgba(76,175,80,0.59)'
      : parts.intakeDirection < 0 ? 'rgba(255,152,0,0.59)' : 'rgba(255,255,255,0.16)';
    ctx.fillRect(z[0] * s, -z[3] * s, (z[1] - z[0]) * s, (z[3] - z[2]) * s);
  }
  const d = 2.6 * IN * s;
  parts.hopper.forEach((col, i) => {
    const hx = (i < 2 ? 1 : -1) * d * 0.6, hy = (i % 2 === 0 ? -1 : 1) * d * 0.6;
    circle(ctx, hx, hy, d / 2);
    ctx.fillStyle = col;
    ctx.fill();
    const staged = i === 0 && parts.staged;
    ctx.strokeStyle = staged ? '#fff' : 'rgba(0,0,0,0.47)';
    ctx.lineWidth = staged ? 2 : 1;
    ctx.stroke();
  });
  if (parts.launcher) {
    const lx = parts.launcher[0] * s, ly = -parts.launcher[1] * s;
    const spin = Math.min(1, parts.flywheelRpm / 3000);
    ctx.strokeStyle = `rgba(229,57,53,${(80 + 175 * spin) / 255})`;
    ctx.lineWidth = 3;
    ctx.lineCap = 'round';
    ctx.beginPath();
    ctx.moveTo(lx + 4 * IN * s, ly - 3 * IN * s); ctx.lineTo(lx + 4 * IN * s, ly + 3 * IN * s);
    ctx.stroke();
    ctx.lineCap = 'butt';
  }
  for (const c of parts.claws) {
    const cx = c.x * s, cy = -c.y * s, half = 1.6 * IN * s;
    if (c.held) {
      ctx.fillStyle = c.held;
      circle(ctx, cx, cy, half); ctx.fill();
    }
    ctx.strokeStyle = c.closed ? '#37474f' : '#90a4ae';
    ctx.lineWidth = 2.5;
    ctx.beginPath();
    ctx.moveTo(cx - half, cy - half * 1.1); ctx.lineTo(cx + half * 0.4, cy - half * (c.closed ? 0.5 : 1.1));
    ctx.moveTo(cx - half, cy + half * 1.1); ctx.lineTo(cx + half * 0.4, cy + half * (c.closed ? 0.5 : 1.1));
    ctx.stroke();
    if (c.height > 6 * IN) {
      ctx.font = 'bold 9px system-ui, sans-serif';
      ctx.fillStyle = '#fff';
      ctx.fillText(Math.round(c.height / IN) + '"', cx - half * 2.6, cy + 3);
    }
  }
}

// ------------------------------------------------------------------ charts

function css(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

/** Clean axis ticks: 0, 20, 40... */
function niceTicks(lo, hi, count) {
  const span = hi - lo;
  if (!(span > 0)) return [lo];
  const raw = span / count;
  const mag = Math.pow(10, Math.floor(Math.log10(raw)));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => span / s <= count) || 10 * mag;
  const out = [];
  for (let v = Math.ceil(lo / step) * step; v <= hi + 1e-9; v += step) out.push(+v.toFixed(6));
  return out;
}

const CHARTS = [
  { id: 'c-speed', readout: 'r-speed', key: 'speed', unit: 'in/s', digits: 0,
    domain: (lo, hi) => [0, Math.max(80, Math.ceil(hi / 20) * 20)] },
  { id: 'c-battery', readout: 'r-battery', key: 'battery', unit: 'V', digits: 2,
    domain: (lo, hi) => [Math.min(8, Math.floor(lo)), Math.max(14, Math.ceil(hi))],
    ref: { value: 9, label: '9 V' } },
  { id: 'c-current', readout: 'r-current', key: 'current', unit: 'A', digits: 1,
    domain: (lo, hi) => [0, Math.max(10, Math.ceil(hi / 10) * 10)] },
];

function sampleAt(age) {
  const h = state.history;
  if (!h.length) return null;
  const t = h[h.length - 1].t - age;
  let best = h[0];
  for (const p of h) if (Math.abs(p.t - t) < Math.abs(best.t - t)) best = p;
  return best;
}

function drawCharts() {
  const h = state.history;
  const now = h.length ? h[h.length - 1].t : 0;
  const series = css('--series'), grid = css('--grid'), axis = css('--axis'), muted = css('--muted'),
    surface = css('--surface'), text2 = css('--text-2');
  for (const ch of CHARTS) {
    const cv = $(ch.id);
    const { ctx, w, h: hh } = setupCanvas(cv);
    ctx.clearRect(0, 0, w, hh);
    const left = 34, right = 10, top = 6, bottom = 18;
    const pw = w - left - right, ph = hh - top - bottom;
    let lo = Infinity, hi = -Infinity;
    for (const p of h) { lo = Math.min(lo, p[ch.key]); hi = Math.max(hi, p[ch.key]); }
    if (!isFinite(lo)) { lo = 0; hi = 1; }
    const [d0, d1] = ch.domain(lo, hi);
    const X = (t) => left + (t - (now - HISTORY_SECONDS)) / HISTORY_SECONDS * pw;
    const Y = (v) => top + ph - (v - d0) / (d1 - d0) * ph;

    ctx.font = '10.5px system-ui, sans-serif';
    ctx.lineWidth = 1;
    for (const v of niceTicks(d0, d1, 4)) {
      const y = Math.round(Y(v)) + 0.5;
      ctx.strokeStyle = v === d0 ? axis : grid;
      ctx.beginPath(); ctx.moveTo(left, y); ctx.lineTo(left + pw, y); ctx.stroke();
      ctx.fillStyle = muted;
      const label = String(v);
      ctx.fillText(label, left - 6 - ctx.measureText(label).width, y + 3.5);
    }
    for (let s = 0; s <= HISTORY_SECONDS; s += 10) {
      const x = left + (HISTORY_SECONDS - s) / HISTORY_SECONDS * pw;
      const label = s === 0 ? 'now' : '−' + s + ' s';
      const lw = ctx.measureText(label).width;
      ctx.fillStyle = muted;
      ctx.fillText(label, Math.min(Math.max(left, x - lw / 2), left + pw - lw), hh - 4);
    }
    if (ch.ref && ch.ref.value > d0 && ch.ref.value < d1) {
      const y = Math.round(Y(ch.ref.value)) + 0.5;
      ctx.save();
      ctx.setLineDash([4, 3]);
      ctx.strokeStyle = muted;
      ctx.beginPath(); ctx.moveTo(left, y); ctx.lineTo(left + pw, y); ctx.stroke();
      ctx.restore();
      ctx.fillStyle = muted;
      ctx.fillText(ch.ref.label, left + 4, y - 3);
    }
    if (h.length > 1) {
      ctx.beginPath();
      h.forEach((p, i) => { const x = X(p.t), y = Y(p[ch.key]); if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y); });
      ctx.lineTo(X(h[h.length - 1].t), Y(d0));
      ctx.lineTo(X(h[0].t), Y(d0));
      ctx.closePath();
      ctx.globalAlpha = 0.1;
      ctx.fillStyle = series;
      ctx.fill();
      ctx.globalAlpha = 1;
      ctx.beginPath();
      h.forEach((p, i) => { const x = X(p.t), y = Y(p[ch.key]); if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y); });
      ctx.strokeStyle = series;
      ctx.lineWidth = 2;
      ctx.lineJoin = 'round';
      ctx.lineCap = 'round';
      ctx.stroke();
    }
    const at = state.hoverAge != null ? sampleAt(state.hoverAge) : h[h.length - 1];
    if (at) {
      const x = X(at.t), y = Y(at[ch.key]);
      if (state.hoverAge != null) {
        ctx.strokeStyle = text2;
        ctx.lineWidth = 1;
        ctx.beginPath(); ctx.moveTo(Math.round(x) + 0.5, top); ctx.lineTo(Math.round(x) + 0.5, top + ph); ctx.stroke();
      }
      circle(ctx, x, y, 6); ctx.fillStyle = surface; ctx.fill();
      circle(ctx, x, y, 4); ctx.fillStyle = series; ctx.fill();
      const ago = now - at.t;
      const r = $(ch.readout);
      r.textContent = fmt(at[ch.key], ch.digits) + ' ' + ch.unit;
      if (state.hoverAge != null) {
        const span = document.createElement('span');
        span.className = 'muted';
        span.textContent = ago < 0.05 ? ' now' : ' ' + ago.toFixed(1) + ' s ago';
        r.appendChild(span);
      }
    } else {
      $(ch.readout).textContent = '';
    }
  }
}

function setupChartHover() {
  for (const ch of CHARTS) {
    const cv = $(ch.id);
    const fromEvent = (ev) => {
      const rect = cv.getBoundingClientRect();
      const left = 34, right = 10;
      const k = (ev.clientX - rect.left - left) / (rect.width - left - right);
      state.hoverAge = Math.min(HISTORY_SECONDS, Math.max(0, (1 - k) * HISTORY_SECONDS));
      drawCharts();
    };
    cv.addEventListener('pointermove', fromEvent);
    cv.addEventListener('pointerdown', fromEvent);
    cv.addEventListener('pointerleave', () => { state.hoverAge = null; drawCharts(); });
    cv.addEventListener('blur', () => { state.hoverAge = null; drawCharts(); });
    cv.addEventListener('keydown', (ev) => {
      if (ev.key !== 'ArrowLeft' && ev.key !== 'ArrowRight' && ev.key !== 'Escape') return;
      ev.preventDefault();
      if (ev.key === 'Escape') state.hoverAge = null;
      else {
        const age = state.hoverAge == null ? 0 : state.hoverAge;
        state.hoverAge = Math.min(HISTORY_SECONDS, Math.max(0, age + (ev.key === 'ArrowLeft' ? 1 : -1)));
      }
      drawCharts();
    });
  }
}

/** Side view of recent shots: height against distance travelled, like the window's shot view. */
function drawShots(f) {
  const cv = $('shots');
  const { ctx, w, h } = setupCanvas(cv);
  ctx.clearRect(0, 0, w, h);
  const flights = (f && f.flights) || [];
  $('shots-empty').classList.toggle('hidden', flights.length > 0);
  const MAX_X = 110, MAX_Z = 100;
  const left = 34, right = 10, top = 8, bottom = 20;
  const pw = w - left - right, ph = h - top - bottom;
  const X = (d) => left + Math.min(d, MAX_X) / MAX_X * pw;
  const Z = (z) => top + ph - z / MAX_Z * ph;
  const grid = css('--grid'), axis = css('--axis'), muted = css('--muted'), text = css('--text'),
    good = css('--good'), bad = css('--critical');
  ctx.font = '10.5px system-ui, sans-serif';
  ctx.lineWidth = 1;
  for (const z of [0, 24, 48, 72, 96]) {
    const y = Math.round(Z(z)) + 0.5;
    ctx.strokeStyle = z === 0 ? axis : grid;
    ctx.beginPath(); ctx.moveTo(left, y); ctx.lineTo(left + pw, y); ctx.stroke();
    ctx.fillStyle = muted;
    ctx.fillText(String(z), left - 6 - ctx.measureText(String(z)).width, y + 3.5);
  }
  for (let x = 0; x <= MAX_X; x += 24) {
    const label = x + (x === 96 ? ' in' : '');
    ctx.fillStyle = muted;
    ctx.fillText(label, X(x) - ctx.measureText(String(x)).width / 2, h - 5);
  }
  if (state.field && state.field.season === 'biobuzz') {
    ctx.fillStyle = 'rgba(137,135,129,0.14)';
    ctx.fillRect(left, Z(65.6), pw, Z(53.5) - Z(65.6));
    ctx.fillStyle = muted;
    const l1 = 'CELL opening 53.5–65.6 in';
    ctx.fillText(l1, left + pw - ctx.measureText(l1).width - 4, Z(65.6) - 4);
    ctx.strokeStyle = muted;
    ctx.beginPath(); ctx.moveTo(left, Math.round(Z(21.5)) + 0.5); ctx.lineTo(left + pw, Math.round(Z(21.5)) + 0.5); ctx.stroke();
    const l2 = 'FLOWER top 21.5 in';
    ctx.fillText(l2, left + pw - ctx.measureText(l2).width - 4, Z(21.5) - 4);
  }
  flights.forEach((fl, i) => {
    const pts = fl.points;
    if (pts.length < 2) return;
    const newest = i === flights.length - 1;
    const flying = fl.outcome === 'in flight';
    const scored = /HIVE|FLOWER/.test(fl.outcome);
    const color = flying ? muted : scored ? good : bad;
    ctx.strokeStyle = color;
    ctx.globalAlpha = newest ? 1 : 0.45;
    ctx.lineWidth = newest ? 2 : 1.5;
    ctx.lineJoin = 'round';
    // Outcome isn't color alone: missed shots are dashed and end in an x, scored ones end in a dot.
    ctx.setLineDash(!flying && !scored ? [5, 4] : []);
    ctx.beginPath();
    let dist = 0, prev = pts[0];
    ctx.moveTo(X(0), Z(prev[2] / IN));
    for (let k = 1; k < pts.length; k++) {
      const p = pts[k];
      dist += Math.hypot(p[0] - prev[0], p[1] - prev[1]) / IN;
      ctx.lineTo(X(dist), Z(p[2] / IN));
      prev = p;
    }
    ctx.stroke();
    ctx.setLineDash([]);
    const ex = X(dist), ez = Z(prev[2] / IN);
    if (scored) {
      circle(ctx, ex, ez, 6); ctx.fillStyle = css('--surface'); ctx.fill();
      circle(ctx, ex, ez, 4); ctx.fillStyle = color; ctx.fill();
    } else if (!flying) {
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.moveTo(ex - 4, ez - 4); ctx.lineTo(ex + 4, ez + 4);
      ctx.moveTo(ex + 4, ez - 4); ctx.lineTo(ex - 4, ez + 4);
      ctx.stroke();
    }
    ctx.globalAlpha = 1;
    if (newest) {
      ctx.fillStyle = text;
      const label = fl.outcome;
      const lx = Math.min(X(dist) + 6, left + pw - ctx.measureText(label).width);
      ctx.fillText(label, lx, Math.max(top + 10, Z(prev[2] / IN) - 6));
    }
  });
}

// ------------------------------------------------------------------ panels

function fmt(v, digits) {
  if (v == null || !isFinite(v)) return '–';
  return Number(v).toFixed(digits);
}

function clockText(seconds) {
  if (seconds == null || !isFinite(seconds)) return '–:––';
  const s = Math.max(0, Math.ceil(seconds));
  return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
}

const PHASES = { PRACTICE: 'Practice', AUTO: 'Autonomous', TRANSITION: 'Transition', TELEOP: 'TeleOp', ENDED: 'Match over' };
const STATES = { IDLE: '', INIT: 'INIT', RUNNING: 'running', STOPPING: 'stopping', STOPPED: 'stopped' };

function updatePanels(f) {
  const r = f.robot, om = f.opmode, m = f.match;

  // Simulator status chip
  const simChip = $('sim-chip');
  if (f.sim.paused) { simChip.textContent = 'Paused in the simulator'; simChip.classList.remove('hidden'); }
  else if (Math.abs(f.sim.speed - 1) > 1e-3) { simChip.textContent = 'Running at ' + f.sim.speed + '×'; simChip.classList.remove('hidden'); }
  else simChip.classList.add('hidden');

  // Match clock: the match clock during a match, else the OpMode timer.
  const phaseEl = $('phase');
  phaseEl.textContent = PHASES[m.phase] || m.phase;
  phaseEl.dataset.phase = m.phase;
  let clock;
  if (m.phase !== 'PRACTICE' && m.clock != null) clock = clockText(m.clock);
  else if (om.state === 'RUNNING' && om.autonomous && om.autoTimer) clock = clockText(30 - om.elapsed);
  else if (om.state === 'RUNNING' || om.state === 'INIT') clock = clockText(om.elapsed);
  else clock = '–:––';
  $('clock').textContent = clock;
  $('opmode-state').textContent = STATES[om.state] || '';
  $('opmode-name').textContent = om.name ? om.name + (om.state === 'INIT' ? ' — waiting for START' : '') : 'No OpMode running';
  $('opmode-name-small').textContent = om.name || '';
  setBanner('opmode-error', om.error);
  setBanner('opmode-warning', om.warning);

  // Score
  const season = f.season;
  $('score-card').classList.toggle('hidden', !season);
  if (season) {
    $('season-title').textContent = season.title;
    for (const a of ['red', 'blue']) {
      $('score-' + a).textContent = season[a].score;
      $('bd-' + a).textContent = season[a].breakdown.join('\n');
      fillList($('rp-' + a), season[a].rankingPoints);
      document.querySelector('.alliance.' + a).classList.toggle('ours', r.alliance === a);
    }
    const notes = $('notes');
    if (season.notes.length) fillList(notes, season.notes.slice().reverse());
    else fillList(notes, ['Nothing yet.'], 'muted');
  }

  // Stat tiles
  const speed = Math.hypot(r.vx, r.vy) / IN;
  $('t-speed').textContent = fmt(speed, 0);
  let top = 0;
  for (const p of state.history) top = Math.max(top, p.speed);
  $('t-speed-sub').textContent = 'in/s · 30 s top ' + fmt(top, 0);
  $('t-turn').textContent = fmt(r.w * 180 / Math.PI, 0);
  $('t-battery').textContent = fmt(r.battery, 2);
  let low = Infinity;
  for (const p of state.history) low = Math.min(low, p.battery);
  const sub = $('t-battery-sub');
  sub.textContent = 'volts · 30 s low ' + fmt(isFinite(low) ? low : r.battery, 1);
  sub.textContent += ' · ' + fmt(100 * r.charge, 0) + '% left · ' + fmt(r.usedMah, 0) + ' mAh used';
  if (isFinite(low) && low < 9) {
    const warn = document.createElement('span');
    warn.className = 'warn-icon';
    warn.textContent = ' ⚠ below 9 V';
    sub.appendChild(warn);
  }
  $('t-current').textContent = fmt(r.current, 1);
  const loop = f.sim.loopMs;
  $('t-loop').textContent = loop != null ? fmt(loop, 1) : '–';
  $('t-loop-sub').textContent = loop != null ? 'ms · ' + Math.round(1000 / loop) + ' loops/s' : 'no OpMode running';
  let hd = (r.h * 180 / Math.PI) % 360;
  if (hd > 180) hd -= 360;
  if (hd <= -180) hd += 360;
  $('t-pose').textContent = fmt(r.x / IN, 1) + ', ' + fmt(r.y / IN, 1) + ' · ' + fmt(hd, 0) + '°';

  // Field legend line
  const slipping = f.wheels.filter((w) => w.slip).map((w) => w.label);
  const parts = [];
  if (r.lifting) parts.push('⚠ Wheels off the tiles: ' + r.lifting + ' (tipping)');
  if (slipping.length) parts.push('Wheel slip: ' + slipping.join(', '));
  $('field-legend').textContent = parts.join('   ·   ');
  updateDrivetrain(f);

  // Telemetry
  $('telemetry').textContent = om.telemetry.length ? om.telemetry.join('\n') : '—';

  updateMotors(f.motors);
  updateMechanisms(f);
  updateServos(f.servos);
  drawShots(f);
  if ($('history-details').open) updateHistoryTable();
}

function setBanner(id, text) {
  const el = $(id);
  el.textContent = text || '';
  el.classList.toggle('hidden', !text);
}

function fillList(ul, items, cls) {
  ul.replaceChildren(...items.map((t) => {
    const li = document.createElement('li');
    li.textContent = t;
    if (cls) li.className = cls;
    return li;
  }));
}

function cell(text, cls) {
  const td = document.createElement('td');
  td.textContent = text;
  if (cls) td.className = cls;
  return td;
}

function updateMotors(motors) {
  const body = document.querySelector('#motors tbody');
  body.replaceChildren(...motors.map((mo) => {
    const tr = document.createElement('tr');
    tr.append(cell(mo.name), cell(mo.hub.replace('Control Hub', 'CH').replace('Expansion Hub', 'EH') + ' · ' + mo.port),
      cell(mo.mode.replace(/_/g, ' ').toLowerCase()));
    const td = cell('', 'num');
    const wrap = document.createElement('span');
    wrap.className = 'power';
    const bar = document.createElement('span');
    bar.className = 'pbar';
    const fill = document.createElement('i');
    const p = Math.max(-1, Math.min(1, mo.power || 0));
    fill.style.left = (p >= 0 ? 50 : 50 + p * 50) + '%';
    fill.style.width = Math.abs(p) * 50 + '%';
    bar.appendChild(fill);
    const val = document.createElement('span');
    val.textContent = fmt(mo.power, 2);
    wrap.append(bar, val);
    td.appendChild(wrap);
    tr.append(td, cell(fmt(mo.velocity, 0), 'num'), cell(fmt(mo.current, 1) + ' A', 'num'), cell(String(mo.position), 'num'));
    return tr;
  }));
}

/** Each wheel's load as a share of a quarter of the robot's weight (100% = standing still). */
function updateDrivetrain(f) {
  const r = f.robot;
  const staticLoad = r.mass * 9.81 / 4;
  setBanner('tip-warning', r.lifting ? '⚠ Wheels ' + r.lifting + ' are off the tiles: the robot is tipping. Lower the lift or accelerate more gently.' : '');
  $('drive-summary').textContent = 'accel ' + fmt(r.ax / 9.81, 2) + ' g · CoG ' + fmt(r.cgHeight / IN, 1) + ' in up';
  const order = ['FL', 'FR', 'BL', 'BR'];
  const names = { FL: 'Front left', FR: 'Front right', BL: 'Back left', BR: 'Back right' };
  const wheels = order.map((l) => f.wheels.find((w) => w.label === l)).filter(Boolean);
  $('wheels').replaceChildren(...wheels.map((w) => {
    const li = document.createElement('li');
    li.className = 'meter-row';
    const name = document.createElement('span'); name.className = 'm-name'; name.textContent = names[w.label] || w.label;
    if (w.lifting || w.slip) {
      const tag = document.createElement('span');
      tag.className = 'tag ' + (w.lifting ? 'lift' : 'slip');
      tag.textContent = w.lifting ? '⚠ off the tiles' : '↻ slipping';
      name.appendChild(tag);
    }
    const share = w.load / staticLoad;
    const val = document.createElement('span'); val.className = 'm-val';
    val.textContent = fmt(100 * share, 0) + '% · ' + fmt(w.load, 0) + ' N';
    const meter = document.createElement('span'); meter.className = 'meter';
    const fill = document.createElement('i');
    if (w.lifting) fill.className = 'lift'; else if (w.slip) fill.className = 'slip';
    fill.style.width = Math.max(0, Math.min(1, share / 2)) * 100 + '%'; // full bar = twice the standing load
    const tick = document.createElement('span'); tick.className = 'tick'; tick.style.left = '50%'; // standing load
    meter.append(fill, tick);
    li.append(name, val, meter);
    return li;
  }));
}

function updateMechanisms(f) {
  const list = $('mechanisms');
  const rows = f.mechanisms.filter((m) => m.min != null && m.max != null && m.max > m.min);
  if (!rows.length) fillList(list, ['None with travel limits.'], 'muted');
  else list.replaceChildren(...rows.map((m) => {
    const li = document.createElement('li');
    li.className = 'meter-row';
    const unit = m.type === 'slide' ? ' in' : m.type === 'arm' ? '°' : ' rev';
    const name = document.createElement('span'); name.className = 'm-name'; name.textContent = m.name;
    const val = document.createElement('span'); val.className = 'm-val';
    val.textContent = fmt(m.position, 1) + unit + ' of ' + fmt(m.max, 0) + unit;
    const meter = document.createElement('span'); meter.className = 'meter';
    const fill = document.createElement('i');
    fill.style.width = Math.max(0, Math.min(1, (m.position - m.min) / (m.max - m.min))) * 100 + '%';
    meter.appendChild(fill);
    li.append(name, val, meter);
    return li;
  }));

  const parts = $('parts');
  const p = f.parts;
  if (!p) { parts.replaceChildren(); return; }
  const items = [];
  if (p.launcher || p.flywheelRpm) items.push(['Flywheel', fmt(p.flywheelRpm, 0) + ' rpm']);
  if (p.intakeZone) items.push(['Intake', p.intakeDirection > 0 ? 'Pulling in' : p.intakeDirection < 0 ? 'Spitting out' : 'Stopped']);
  items.push(['Hopper', p.hopper]);
  for (const c of p.claws) items.push([c.name.toLowerCase() === 'claw' ? 'Claw' : 'Claw ' + c.name, (c.closed ? 'Closed' : 'Open') + ' · ' + fmt(c.height / IN, 1) + ' in up']);
  parts.replaceChildren(...items.map(([label, value]) => {
    const div = document.createElement('div');
    div.className = 'part';
    const l = document.createElement('span'); l.className = 'p-label'; l.textContent = label;
    const v = document.createElement('span'); v.className = 'p-value';
    if (Array.isArray(value)) {
      v.textContent = value.length + ' of 4' + (p.staged ? ' · staged' : '');
      const balls = document.createElement('span'); balls.className = 'balls';
      for (const col of value) { const b = document.createElement('span'); b.className = 'ball'; b.style.background = col; balls.appendChild(b); }
      v.appendChild(balls);
    } else {
      v.textContent = value;
    }
    div.append(l, v);
    return div;
  }));
}

function updateServos(servos) {
  const body = document.querySelector('#servos tbody');
  body.replaceChildren(...servos.map((s) => {
    const tr = document.createElement('tr');
    tr.append(cell(s.name + (s.continuous ? ' (CR)' : '')),
      cell(s.continuous ? 'power ' + fmt(s.commanded * 2 - 1, 2) : fmt(s.commanded, 3), 'num'),
      cell(s.continuous ? '–' : fmt(s.angleDeg, 0) + '°', 'num'),
      cell(s.enabled ? 'on' : 'off'));
    return tr;
  }));
}

function updateHistoryTable() {
  const h = state.history;
  const body = document.querySelector('#history-table tbody');
  if (!h.length) { body.replaceChildren(); return; }
  const now = h[h.length - 1].t;
  const rows = [];
  for (let age = 0; age <= HISTORY_SECONDS; age += 1) {
    const p = sampleAt(age);
    if (!p || now - p.t > age + 0.6) break;
    const tr = document.createElement('tr');
    tr.append(cell(age === 0 ? 'now' : String(age)), cell(fmt(p.speed, 0), 'num'), cell(fmt(p.battery, 2), 'num'),
      cell(fmt(p.current, 1), 'num'), cell(p.loop != null ? fmt(p.loop, 1) : '–', 'num'));
    rows.push(tr);
  }
  body.replaceChildren(...rows);
}

// ------------------------------------------------------------------ start

function resizeCanvases() {
  drawCharts();
  if (state.cur) drawShots(state.cur);
}

function animate() {
  drawField();
  requestAnimationFrame(animate);
}

window.addEventListener('resize', resizeCanvases);
$('history-details').addEventListener('toggle', updateHistoryTable);
window.matchMedia('(prefers-color-scheme: light)').addEventListener('change', resizeCanvases);
setInterval(setConnection, 1000);
setupChartHover();
$('view-field').addEventListener('click', () => setFollow(false));
$('view-follow').addEventListener('click', () => setFollow(true));
try { if (localStorage.getItem('ftc-view-follow') === '1') setFollow(true); } catch (e) { /* no storage */ }
connect();
requestAnimationFrame(animate);
