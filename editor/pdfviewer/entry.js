import * as pdfjsLib from 'pdfjs-dist/legacy/build/pdf.min.mjs';
import { WorkerMessageHandler } from 'pdfjs-dist/legacy/build/pdf.worker.min.mjs';
import { PDFDocument, degrees, rgb } from 'pdf-lib';

globalThis.pdfjsWorker = { WorkerMessageHandler };

const HEB = /[֐-׿]/;
const $ = id => document.getElementById(id);
const A = () => window.Android || { toast() {}, dirty() {}, docData() { return ''; }, docSaved() {}, getSig() { return ''; }, saveSig() {} };

const COLORS = ['#14161f', '#d93025', '#1a73e8', '#188038', '#e8710a', '#9334e6'];
const HL_COLORS = ['#fff475', '#a7ffeb', '#fbbcdc', '#ccff90', '#ffd180'];
const SIZES = [0.003, 0.006, 0.012];   // stroke width relative to page width
const TSIZES = [0.022, 0.032, 0.05];   // text size relative to page width

let doc = null, bytes = null, pages = [], zoom = 1, tool = 'scroll';
let color = COLORS[1], hlColor = HL_COLORS[0], sizeIdx = 1, tsizeIdx = 1;
let history = [], dirty = false, sigData = '';

function markDirty(v) { if (dirty !== v) { dirty = v; A().dirty(v); } }
function snapshot() { history.push(pages.map(p => p.anns.slice())); if (history.length > 60) history.shift(); }
function undo() {
  const h = history.pop(); if (!h) return;
  pages.forEach((p, i) => { p.anns = h[i] || []; redraw(p); });
  markDirty(history.length > 0);
}

/* ---------- layout / text ---------- */
const mctx = document.createElement('canvas').getContext('2d');
function measureText(a, W) {
  const px = a.s * W;
  mctx.font = px + 'px Arial, sans-serif';
  const lines = a.text.split('\n');
  const w = Math.max(...lines.map(l => mctx.measureText(l).width), px * 0.5);
  return { lines, px, w, h: lines.length * px * 1.25 };
}
function drawText(ctx, a, W, ox, oy) {
  const m = measureText(a, W);
  ctx.font = m.px + 'px Arial, sans-serif'; ctx.fillStyle = a.c; ctx.textBaseline = 'top';
  const rtl = HEB.test(a.text);
  ctx.direction = rtl ? 'rtl' : 'ltr'; ctx.textAlign = rtl ? 'right' : 'left';
  m.lines.forEach((l, i) => ctx.fillText(l, rtl ? ox + m.w : ox, oy + i * m.px * 1.25));
  return m;
}
function bbox(a, W, H) {
  if (a.t === 'text') { const m = measureText(a, W); return [a.u, a.v, a.u + m.w / W, a.v + m.h / H]; }
  if (a.t === 'img') return [a.u, a.v, a.u + a.w, a.v + a.h * W / H];
  return null;
}

/* ---------- drawing annotations on the overlay ---------- */
function redraw(p) {
  const cv = p.ov; if (!cv.width) return;
  const ctx = cv.getContext('2d'); ctx.clearRect(0, 0, cv.width, cv.height);
  const k = cv.width / p.cssW, W = cv.width, H = cv.height;
  const list = p.cur ? p.anns.concat([p.cur]) : p.anns;
  list.forEach(a => {
    if (a.t === 'pen' || a.t === 'hl') {
      ctx.save(); ctx.globalAlpha = a.t === 'hl' ? 0.38 : 1; ctx.strokeStyle = a.c; ctx.lineWidth = a.w * W;
      ctx.lineCap = a.t === 'hl' ? 'butt' : 'round'; ctx.lineJoin = 'round'; ctx.beginPath();
      a.pts.forEach((q, i) => { const x = q[0] * W, y = q[1] * H; if (i === 0) { ctx.moveTo(x, y); if (a.pts.length === 1) ctx.lineTo(x + 0.1, y); } else ctx.lineTo(x, y); });
      ctx.stroke(); ctx.restore();
    } else if (a.t === 'text') {
      drawText(ctx, a, W, a.u * W, a.v * H);
    } else if (a.t === 'img') {
      const im = imgCache(a.src, () => redraw(p));
      if (im) ctx.drawImage(im, a.u * W, a.v * H, a.w * W, a.h * W);
    }
    if (a === sel && p === selPage) {
      const b = bbox(a, W, H) || strokeBox(a);
      if (b) { ctx.save(); ctx.setLineDash([6, 4]); ctx.strokeStyle = '#4f46e5'; ctx.lineWidth = 2; ctx.strokeRect(b[0] * W - 4, b[1] * H - 4, (b[2] - b[0]) * W + 8, (b[3] - b[1]) * H + 8); ctx.restore(); }
    }
  });
}
// stroke bbox: x in rel width, y in rel height (pages share ratio so convert later)
function strokeBox(a) {
  if (!a.pts) return null;
  const xs = a.pts.map(q => q[0]), ys = a.pts.map(q => q[1]);
  return [Math.min(...xs), Math.min(...ys), Math.max(...xs), Math.max(...ys)];
}
const imgs = {};
function imgCache(src, cb) {
  let e = imgs[src];
  if (!e) { e = imgs[src] = new Image(); e.onload = cb; e.src = src; }
  return e.complete && e.naturalWidth ? e : null;
}

/* ---------- hit testing (everything in page-relative coords, converted to css px) ---------- */
function hit(p, a, u, v) {
  const W = p.cssW, H = p.cssH, x = u * W, y = v * H;
  if (a.t === 'pen' || a.t === 'hl') {
    const tol = Math.max(a.w * W / 2, 12);
    for (let i = 0; i < a.pts.length; i++) {
      const q = a.pts[i], r = a.pts[i + 1] || q;
      if (segDist(x, y, q[0] * W, q[1] * H, r[0] * W, r[1] * H) <= tol) return true;
    }
    return false;
  }
  const b = bbox(a, W, H); if (!b) return false;
  return x >= b[0] * W - 8 && x <= b[2] * W + 8 && y >= b[1] * H - 8 && y <= b[3] * H + 8;
}
function segDist(px, py, ax, ay, bx, by) {
  const dx = bx - ax, dy = by - ay, l = dx * dx + dy * dy;
  let t = l ? ((px - ax) * dx + (py - ay) * dy) / l : 0; t = Math.max(0, Math.min(1, t));
  return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
}
function hitTop(p, u, v) { for (let i = p.anns.length - 1; i >= 0; i--) if (hit(p, p.anns[i], u, v)) return i; return -1; }

/* ---------- pointer interaction ---------- */
let sel = null, selPage = null, drag = null;
function setSel(p, a) { const old = selPage; sel = a; selPage = p; if (old && old !== p) redraw(old); if (p) redraw(p); }

function attach(p) {
  const ov = p.ov;
  const pos = e => { const r = ov.getBoundingClientRect(); return [(e.clientX - r.left) / r.width, (e.clientY - r.top) / r.height]; };
  ov.addEventListener('pointerdown', e => {
    if (tool === 'scroll') return;
    e.preventDefault();
    const [u, v] = pos(e);
    try { ov.setPointerCapture(e.pointerId); } catch (_) {}
    if (tool === 'pen' || tool === 'hl') {
      p.cur = { t: tool, c: tool === 'hl' ? hlColor : color, w: tool === 'hl' ? SIZES[2] * 1.8 : SIZES[sizeIdx], pts: [[u, v]] };
      drag = { p, kind: 'draw' }; redraw(p);
    } else if (tool === 'erase') {
      drag = { p, kind: 'erase' }; eraseAt(p, u, v);
    } else if (tool === 'text') {
      const i = hitTop(p, u, v);
      if (i >= 0 && p.anns[i].t === 'text') editText(p, p.anns[i], null); else editText(p, null, [u, v]);
    } else if (tool === 'sig') {
      placeSig(p, u, v);
    } else if (tool === 'move') {
      const i = hitTop(p, u, v);
      if (i >= 0) { setSel(p, p.anns[i]); drag = { p, kind: 'move', i, last: [u, v], moved: false }; }
      else setSel(null, null);
    }
  });
  ov.addEventListener('pointermove', e => {
    if (!drag || drag.p !== p) return;
    const [u, v] = pos(e);
    if (drag.kind === 'draw') { p.cur.pts.push([u, v]); redraw(p); }
    else if (drag.kind === 'erase') eraseAt(p, u, v);
    else if (drag.kind === 'move') {
      if (!drag.moved) { snapshot(); drag.moved = true; }
      const du = u - drag.last[0], dv = v - drag.last[1]; drag.last = [u, v];
      const a = p.anns[drag.i], n = Object.assign({}, a);
      if (a.pts) n.pts = a.pts.map(q => [q[0] + du, q[1] + dv]); else { n.u = a.u + du; n.v = a.v + dv; }
      p.anns[drag.i] = n; sel = n; redraw(p);
    }
  });
  const up = () => {
    if (!drag) return;
    const d = drag; drag = null;
    if (d.kind === 'draw' && d.p.cur) {
      snapshot(); d.p.anns.push(d.p.cur); d.p.cur = null; redraw(d.p); markDirty(true);
    } else if (d.kind === 'move' && d.moved) markDirty(true);
  };
  ov.addEventListener('pointerup', up); ov.addEventListener('pointercancel', up);
}
let erased = false;
function eraseAt(p, u, v) {
  const i = hitTop(p, u, v);
  if (i >= 0) { if (!erased) { snapshot(); erased = true; } p.anns.splice(i, 1); redraw(p); markDirty(true); }
  else if (!drag) erased = false;
}
document.addEventListener('pointerup', () => { erased = false; });

/* text editing */
let editor = null;
function editText(p, existing, at) {
  closeEditor(true);
  const ta = document.createElement('textarea');
  const W = p.cssW;
  const a0 = existing || { t: 'text', c: color, s: TSIZES[tsizeIdx], u: at[0], v: at[1], text: '' };
  ta.value = a0.text; ta.className = 'ptext';
  ta.style.cssText = `left:${a0.u * W}px;top:${a0.v * p.cssH}px;font-size:${a0.s * W}px;color:${a0.c};min-width:${W * 0.2}px;direction:${HEB.test(a0.text) ? 'rtl' : 'auto'}`;
  p.box.appendChild(ta); ta.focus();
  ta.addEventListener('input', () => { ta.style.direction = HEB.test(ta.value) ? 'rtl' : 'ltr'; ta.style.height = 'auto'; ta.style.height = ta.scrollHeight + 'px'; });
  ta.style.height = 'auto'; ta.style.height = (ta.scrollHeight || 30) + 'px';
  editor = { ta, p, existing, a0 };
  ta.addEventListener('blur', () => closeEditor(false));
  if (existing) { const i = p.anns.indexOf(existing); if (i >= 0) { p.hiddenIdx = i; redraw(p); } }
}
function closeEditor(silent) {
  if (!editor) return;
  const { ta, p, existing, a0 } = editor; editor = null;
  const text = ta.value.replace(/\s+$/, '');
  ta.remove(); p.hiddenIdx = undefined;
  if (existing) {
    const i = p.anns.indexOf(existing);
    if (text !== existing.text) {
      snapshot();
      if (!text) p.anns.splice(i, 1); else p.anns[i] = Object.assign({}, existing, { text });
      markDirty(true);
    }
  } else if (text) {
    snapshot(); p.anns.push(Object.assign({}, a0, { text })); markDirty(true);
  }
  redraw(p);
}

/* signature */
function placeSig(p, u, v) {
  if (!sigData) { openSig(); return; }
  imgCache(sigData, () => {});
  const im = new Image(); im.onload = () => {
    const w = 0.28, h = w * im.naturalHeight / im.naturalWidth;
    snapshot(); const a = { t: 'img', src: sigData, u: Math.max(0, Math.min(1 - w, u - w / 2)), v: Math.max(0, v - h / 2), w, h };
    p.anns.push(a); markDirty(true); setTool('move'); setSel(p, a);
  }; im.src = sigData;
}
function openSig() {
  const m = $('sigModal'); m.classList.remove('hide');
  const cv = $('sigCv'), w = Math.min(window.innerWidth - 40, 520);
  cv.width = w * 2; cv.height = 220 * 2; cv.style.width = w + 'px'; cv.style.height = '220px';
  const x = cv.getContext('2d'); x.clearRect(0, 0, cv.width, cv.height);
  x.lineCap = 'round'; x.lineJoin = 'round'; x.strokeStyle = '#1a237e'; x.lineWidth = 5;
  let on = false, last = null, any = false;
  const pt = e => { const r = cv.getBoundingClientRect(); return [(e.clientX - r.left) * cv.width / r.width, (e.clientY - r.top) * cv.height / r.height]; };
  cv.onpointerdown = e => { e.preventDefault(); on = true; any = true; last = pt(e); cv.setPointerCapture(e.pointerId); x.beginPath(); x.arc(last[0], last[1], 2.5, 0, 7); x.fillStyle = '#1a237e'; x.fill(); };
  cv.onpointermove = e => { if (!on) return; const q = pt(e); x.beginPath(); x.moveTo(last[0], last[1]); x.lineTo(q[0], q[1]); x.stroke(); last = q; };
  cv.onpointerup = () => { on = false; };
  $('sigClear').onclick = () => { x.clearRect(0, 0, cv.width, cv.height); any = false; };
  $('sigCancel').onclick = () => { m.classList.add('hide'); if (!sigData) setTool('scroll'); };
  $('sigOk').onclick = () => {
    if (!any) { A().toast('צייר חתימה'); return; }
    // crop to the ink
    const d = x.getImageData(0, 0, cv.width, cv.height).data; let x0 = cv.width, y0 = cv.height, x1 = 0, y1 = 0;
    for (let yy = 0; yy < cv.height; yy += 2) for (let xx = 0; xx < cv.width; xx += 2) if (d[(yy * cv.width + xx) * 4 + 3] > 20) { x0 = Math.min(x0, xx); x1 = Math.max(x1, xx); y0 = Math.min(y0, yy); y1 = Math.max(y1, yy); }
    const pad = 8; x0 = Math.max(0, x0 - pad); y0 = Math.max(0, y0 - pad); x1 = Math.min(cv.width, x1 + pad); y1 = Math.min(cv.height, y1 + pad);
    const c2 = document.createElement('canvas'); c2.width = Math.max(1, x1 - x0); c2.height = Math.max(1, y1 - y0);
    c2.getContext('2d').drawImage(cv, x0, y0, c2.width, c2.height, 0, 0, c2.width, c2.height);
    sigData = c2.toDataURL('image/png'); A().saveSig(sigData); m.classList.add('hide'); toast2('הקש במקום החתימה על הדף');
  };
}
function toast2(t) { A().toast(t); }

/* ---------- pages ---------- */
let io = null;
async function buildPages() {
  const box = $('pages'); box.innerHTML = '';
  pages = [];
  const n = doc.numPages;
  for (let i = 1; i <= n; i++) {
    const page = await doc.getPage(i);
    const vp = page.getViewport({ scale: 1 });
    const el = document.createElement('div'); el.className = 'pg';
    const cv = document.createElement('canvas'); cv.className = 'pgc';
    const ov = document.createElement('canvas'); ov.className = 'pgo';
    const num = document.createElement('div'); num.className = 'pgn'; num.textContent = i + ' / ' + n;
    el.appendChild(cv); el.appendChild(ov); el.appendChild(num); box.appendChild(el);
    const p = { i, page, vw: vp.width, vh: vp.height, el, cv, ov, box: el, anns: [], rendered: 0, cssW: 0, cssH: 0 };
    el._p = p; pages.push(p); attach(p);
  }
  layout();
  io = new IntersectionObserver(es => es.forEach(e => { const p = e.target._p; if (e.isIntersecting) render(p); else free(p); }), { root: $('scroller'), rootMargin: '700px 0px' });
  pages.forEach(p => io.observe(p.el));
}
function layout() {
  const base = Math.max(200, $('scroller').clientWidth - 16);
  pages.forEach(p => {
    p.cssW = base * zoom; p.cssH = p.cssW * p.vh / p.vw;
    p.el.style.width = p.cssW + 'px'; p.el.style.height = p.cssH + 'px';
    p.rendered = 0;
  });
}
async function render(p) {
  const dpr = Math.min(window.devicePixelRatio || 1, 2.5);
  let scale = (p.cssW / p.vw) * dpr;
  const maxPx = 14e6; if (p.vw * p.vh * scale * scale > maxPx) scale = Math.sqrt(maxPx / (p.vw * p.vh));
  if (p.rendered === scale) return;
  p.rendered = scale;
  const vp = p.page.getViewport({ scale });
  const cv = document.createElement('canvas'); cv.width = Math.floor(vp.width); cv.height = Math.floor(vp.height);
  try {
    if (p.task) { try { p.task.cancel(); } catch (_) {} }
    p.task = p.page.render({ canvasContext: cv.getContext('2d'), canvas: cv, viewport: vp });
    await p.task.promise;
  } catch (e) { if (e && e.name === 'RenderingCancelledException') return; p.rendered = 0; return; }
  p.cv.width = cv.width; p.cv.height = cv.height; p.cv.style.width = p.cssW + 'px'; p.cv.style.height = p.cssH + 'px';
  p.cv.getContext('2d').drawImage(cv, 0, 0);
  const k = dpr; p.ov.width = Math.floor(p.cssW * k); p.ov.height = Math.floor(p.cssH * k);
  p.ov.style.width = p.cssW + 'px'; p.ov.style.height = p.cssH + 'px';
  redraw(p);
}
function free(p) { if (p.task) { try { p.task.cancel(); } catch (_) {} } p.cv.width = 0; p.ov.width = 0; p.rendered = 0; }

function setZoom(z) {
  closeEditor(false);
  const sc = $('scroller'), ratio = sc.scrollTop / Math.max(1, sc.scrollHeight);
  zoom = Math.max(1, Math.min(4, z)); layout();
  pages.forEach(p => { p.cv.width = 0; p.ov.width = 0; });
  io.disconnect(); pages.forEach(p => io.observe(p.el));
  sc.scrollTop = ratio * sc.scrollHeight;
  $('zl').textContent = Math.round(zoom * 100) + '%';
}

/* ---------- toolbar ---------- */
function setTool(t) {
  closeEditor(false);
  tool = t;
  document.querySelectorAll('#bar [data-t]').forEach(b => b.classList.toggle('on', b.dataset.t === t));
  $('pages').classList.toggle('active', t !== 'scroll');
  if (t !== 'move') setSel(null, null);
  renderTray();
  if (t === 'sig') { if (!sigData) openSig(); else toast2('הקש על הדף במקום החתימה'); }
}
function renderTray() {
  const tray = $('tray'); tray.innerHTML = '';
  const show = tool === 'pen' || tool === 'hl' || tool === 'text';
  tray.classList.toggle('hide', !(show || tool === 'sig' || (tool === 'move' && sel)));
  const mk = (cls, html, fn, bg) => { const b = document.createElement('button'); b.className = cls; b.innerHTML = html; if (bg) b.style.background = bg; b.onclick = fn; tray.appendChild(b); return b; };
  if (tool === 'pen' || tool === 'text') {
    COLORS.forEach(c => mk('sw' + (c === color ? ' on' : ''), '', () => { color = c; renderTray(); }, c));
    const sizes = tool === 'pen' ? SIZES : TSIZES, cur = tool === 'pen' ? sizeIdx : tsizeIdx;
    ['•', '●', '⬤'].forEach((s, i) => mk('sz' + (i === cur ? ' on' : ''), tool === 'text' ? ['א', 'א', 'א'][i] : s, () => { if (tool === 'pen') sizeIdx = i; else tsizeIdx = i; renderTray(); }).style.fontSize = tool === 'text' ? [13, 17, 23][i] + 'px' : '');
  } else if (tool === 'hl') {
    HL_COLORS.forEach(c => mk('sw' + (c === hlColor ? ' on' : ''), '', () => { hlColor = c; renderTray(); }, c));
  } else if (tool === 'sig') {
    mk('sz', 'חתימה חדשה', () => openSig());
  } else if (tool === 'move' && sel) {
    mk('sz', '🗑 מחק', () => { if (!sel) return; snapshot(); const p = selPage; p.anns.splice(p.anns.indexOf(sel), 1); setSel(null, null); redraw(p); markDirty(true); renderTray(); });
  }
}
const _setSel = setSel;
function bindBar() {
  document.querySelectorAll('#bar [data-t]').forEach(b => b.onclick = () => setTool(b.dataset.t));
  $('undo').onclick = undo;
  $('zin').onclick = () => setZoom(zoom + 0.5);
  $('zout').onclick = () => setZoom(zoom - 0.5);
  setInterval(() => { if (tool === 'move') renderTray(); }, 400);
}

/* ---------- save (burn the annotations into the PDF with pdf-lib) ---------- */
function b64ToBytes(b64) { const s = atob(b64), u = new Uint8Array(s.length); for (let i = 0; i < s.length; i++) u[i] = s.charCodeAt(i); return u; }
function hexRgb(c) { const n = parseInt(c.slice(1), 16); return rgb(((n >> 16) & 255) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255); }
function dataUrlBytes(u) { return b64ToBytes(u.split(',')[1]); }

async function textPng(a, PW) {
  const unit = 4, fpx = a.s * PW * unit;
  const c = document.createElement('canvas'), x = c.getContext('2d');
  x.font = fpx + 'px Arial, sans-serif';
  const lines = a.text.split('\n'), rtl = HEB.test(a.text);
  const w = Math.ceil(Math.max(...lines.map(l => x.measureText(l).width), fpx * 0.5)) + 4, h = Math.ceil(lines.length * fpx * 1.25) + 4;
  c.width = w; c.height = h;
  x.font = fpx + 'px Arial, sans-serif'; x.fillStyle = a.c; x.textBaseline = 'top'; x.direction = rtl ? 'rtl' : 'ltr'; x.textAlign = rtl ? 'right' : 'left';
  lines.forEach((l, i) => x.fillText(l, rtl ? w - 2 : 2, 2 + i * fpx * 1.25));
  return { bytes: dataUrlBytes(c.toDataURL('image/png')), w: w / unit, h: h / unit };
}

async function save() {
  closeEditor(false);
  const out = await PDFDocument.load(bytes, { ignoreEncryption: true, updateMetadata: false });
  const list = out.getPages();
  for (const p of pages) {
    if (!p.anns.length) continue;
    const pg = list[p.i - 1]; if (!pg) continue;
    const rot = ((pg.getRotation().angle % 360) + 360) % 360;
    const cb = pg.getCropBox(), W = cb.width, H = cb.height, ox = cb.x, oy = cb.y;
    const PWd = rot === 90 || rot === 270 ? H : W, PHd = rot === 90 || rot === 270 ? W : H;
    const toPdf = (dx, dy) => {
      let x, y;
      if (rot === 0) { x = dx; y = H - dy; } else if (rot === 90) { x = dy; y = dx; } else if (rot === 180) { x = W - dx; y = dy; } else { x = W - dy; y = H - dx; }
      return [x + ox, y + oy];
    };
    for (const a of p.anns) {
      if (a.t === 'pen' || a.t === 'hl') {
        const pts = a.pts.map(q => toPdf(q[0] * PWd, q[1] * PHd)).map(q => [q[0], q[1]]);
        if (pts.length === 1) pts.push([pts[0][0] + 0.01, pts[0][1]]);
        // pdf-lib svg paths are y-down from the (x, y) origin → use origin (0, pageTop) and flip y
        const top = oy + H;
        const path = pts.map((q, i) => (i ? 'L' : 'M') + q[0].toFixed(2) + ' ' + (top - q[1]).toFixed(2)).join(' ');
        pg.drawSvgPath(path, { x: 0, y: top, borderColor: hexRgb(a.c), borderWidth: a.w * PWd, borderOpacity: a.t === 'hl' ? 0.38 : 1, borderLineCap: a.t === 'hl' ? 0 : 1, color: undefined });
      } else if (a.t === 'text') {
        const t = await textPng(a, PWd), img = await out.embedPng(t.bytes);
        const [x, y] = toPdf(a.u * PWd, a.v * PHd + t.h);
        pg.drawImage(img, { x, y, width: t.w, height: t.h, rotate: degrees(rot) });
      } else if (a.t === 'img') {
        const img = await out.embedPng(dataUrlBytes(a.src));
        const [x, y] = toPdf(a.u * PWd, a.v * PHd + a.h * PWd);
        pg.drawImage(img, { x, y, width: a.w * PWd, height: a.h * PWd, rotate: degrees(rot) });
      }
    }
  }
  const res = await out.saveAsBase64({ dataUri: false });
  return res;
}

/* ---------- entry points ---------- */
window.PV = {
  async open(dark) {
    document.documentElement.className = dark ? 'dark' : '';
    sigData = A().getSig() || '';
    try {
      bytes = b64ToBytes(A().docData());
      doc = await pdfjsLib.getDocument({ data: bytes.slice(), useSystemFonts: true, isEvalSupported: false }).promise;
      await buildPages(); bindBar(); setTool('scroll'); $('zl').textContent = '100%';
      A().opened();
    } catch (e) {
      A().toast(e && e.name === 'PasswordException' ? 'הקובץ מוגן בסיסמה' : 'פתיחת ה-PDF נכשלה');
    }
  },
  async save() {
    try { const b = await save(); markDirty(false); A().docSaved(b); }
    catch (e) { A().toast('השמירה נכשלה: ' + (e && e.message ? e.message : e)); A().saveFailed(); }
  },
  theme(d) { document.documentElement.className = d ? 'dark' : ''; },
  _state() { return { pages, tool }; }
};
