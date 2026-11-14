import * as pdfjsLib from 'pdfjs-dist/legacy/build/pdf.min.mjs';
import { WorkerMessageHandler } from 'pdfjs-dist/legacy/build/pdf.worker.min.mjs';
import { PDFDocument, degrees, rgb } from 'pdf-lib';
import { detectFields, dedupe, autofill, dropFields, fieldOrder, filledF, dmyFull, store } from './forms.js';

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
let history = [], dirty = false, sigData = '', sigs = [], pendingImg = null;

function markDirty(v) { if (dirty !== v) { dirty = v; A().dirty(v); } }
function snapshot() { history.push(pages.map(p => p.anns.slice())); if (history.length > 60) history.shift(); }
function undo() {
  const h = history.pop(); if (!h) return;
  pages.forEach((p, i) => { p.anns = h[i] || []; redraw(p); });
  markDirty(history.length > 0);
  reconcileFields();
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
    } else if (tool === 'date') {
      placeDate(p, u, v);
    } else if (tool === 'fmove') {
      moveFieldTo(p, u, v);
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

let sigTarget = null;
/* signature */
function loadSigs() {
  const raw = A().getSig() || '';
  try { sigs = raw.startsWith('[') ? JSON.parse(raw) : raw ? [raw] : []; } catch (_) { sigs = []; }
  sigData = sigs[0] || '';
}
function saveSigs() { sigs = sigs.slice(0, 6); sigData = sigs[0] || ''; A().saveSig(JSON.stringify(sigs)); }
function useSig(i) { const x = sigs.splice(i, 1)[0]; sigs.unshift(x); saveSigs(); renderTray(); toast2('נבחרה חתימה – הקש על הדף'); }
function stampImage(text) {
  const c = document.createElement('canvas'), x = c.getContext('2d'), fs = 34, pad = 18;
  x.font = 'bold ' + fs + 'px sans-serif'; x.direction = 'rtl';
  const lines = text.split('\n'), w = Math.max(...lines.map(l => x.measureText(l).width)) + pad * 2, h = lines.length * (fs + 8) + pad * 2 - 8;
  c.width = Math.ceil(w); c.height = Math.ceil(h);
  x.font = 'bold ' + fs + 'px sans-serif'; x.direction = 'rtl'; x.textAlign = 'center'; x.textBaseline = 'top';
  x.strokeStyle = '#1a237e'; x.fillStyle = '#1a237e'; x.lineWidth = 4;
  x.beginPath(); x.roundRect ? x.roundRect(3, 3, c.width - 6, c.height - 6, 14) : x.rect(3, 3, c.width - 6, c.height - 6); x.stroke();
  lines.forEach((l, i) => x.fillText(l, c.width / 2, pad + i * (fs + 8)));
  return c.toDataURL('image/png');
}
function openStamp() {
  const name = store.get('pv_name') || '';
  $('stText').value = (name ? name + '\n' : '') + dmyFull();
  $('stModal').classList.remove('hide');
  $('stCancel').onclick = () => $('stModal').classList.add('hide');
  $('stOk').onclick = () => {
    const t = $('stText').value.trim(); if (!t) return;
    pendingImg = { src: stampImage(t), w: 0.34 }; $('stModal').classList.add('hide');
    setTool('sig'); toast2('הקש על הדף במקום החותמת');
  };
}
function placeSig(p, u, v) {
  const src = pendingImg ? pendingImg.src : sigData, wFix = pendingImg ? pendingImg.w : 0.28;
  if (!src) { openSig(); return; }
  const stamp = !!pendingImg; pendingImg = null;
  const im = new Image(); im.onload = () => {
    const w = wFix, h = w * im.naturalHeight / im.naturalWidth;
    snapshot(); const a = { t: 'img', src, u: Math.max(0, Math.min(1 - w, u - w / 2)), v: Math.max(0, v - h / 2), w, h };
    p.anns.push(a); markDirty(true); setTool('move'); setSel(p, a);
  }; im.src = src;
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
  $('sigCancel').onclick = () => { m.classList.add('hide'); const f = sigTarget; sigTarget = null; if (f) openFill(f.id); else if (!sigData) setTool('scroll'); };
  $('sigOk').onclick = () => {
    if (!any) { A().toast('צייר חתימה'); return; }
    // crop to the ink
    const d = x.getImageData(0, 0, cv.width, cv.height).data; let x0 = cv.width, y0 = cv.height, x1 = 0, y1 = 0;
    for (let yy = 0; yy < cv.height; yy += 2) for (let xx = 0; xx < cv.width; xx += 2) if (d[(yy * cv.width + xx) * 4 + 3] > 20) { x0 = Math.min(x0, xx); x1 = Math.max(x1, xx); y0 = Math.min(y0, yy); y1 = Math.max(y1, yy); }
    const pad = 8; x0 = Math.max(0, x0 - pad); y0 = Math.max(0, y0 - pad); x1 = Math.min(cv.width, x1 + pad); y1 = Math.min(cv.height, y1 + pad);
    const c2 = document.createElement('canvas'); c2.width = Math.max(1, x1 - x0); c2.height = Math.max(1, y1 - y0);
    c2.getContext('2d').drawImage(cv, x0, y0, c2.width, c2.height, 0, 0, c2.width, c2.height);
    sigs.unshift(c2.toDataURL('image/png')); saveSigs(); m.classList.add('hide'); renderTray();
    if (sigTarget) { const f = sigTarget; sigTarget = null; setFieldValue(f, sigData).then(() => openFill(f.id)); } else toast2('הקש במקום החתימה על הדף');
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
  if (t === 'sig') { if (!sigData && !pendingImg) openSig(); else if (!pendingImg) toast2('הקש על הדף במקום החתימה'); }
  if (t === 'date') toast2('הקש במקום התאריך על הדף');
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
    sigs.forEach((sg, i) => { const b = mk('sz' + (i === 0 ? ' on' : ''), '<img src="' + sg + '" style="height:26px;max-width:70px">', () => useSig(i)); b.oncontextmenu = ev => { ev.preventDefault(); }; });
    mk('sz', '➕ חדשה', () => openSig());
    mk('sz', '🏷 חותמת', () => openStamp());
    if (sigs.length) mk('sz', '🗑', () => { sigs.shift(); saveSigs(); renderTray(); toast2('החתימה הנוכחית נמחקה'); });
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
  const acro = new Set(fields.filter(f => f.src === 'acro' && filledF(f) && f.value !== f.orig).map(f => f.name));
  if (acro.size) { try { dropFields(out, acro); } catch (_) {} }
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


/* =====================  form fields (ported from My-Forms)  ===================== */
let fields = [], curField = null;
const FINK = '#0b1d4d';

async function detectAll() {
  const all = [];
  for (const p of pages) {
    try { const v1 = p.page.getViewport({ scale: 1 }); all.push(...await detectFields(p.page, v1, p.i - 1)); } catch (_) {}
  }
  fields = dedupe(all); autofill(fields);
  fields.forEach(f => { f.orig = f.src === 'acro' ? f.value : undefined; });
  for (const f of fields) if (filledF(f) && f.auto) await syncField(f);
  drawBoxes(); fillBarUpdate();
  if (fields.length) A().toast(fields.length === 1 ? 'זוהה שדה אחד למילוי' : 'זוהו ' + fields.length + ' שדות למילוי');
}
function imgAr(src) { return new Promise(res => { const im = new Image(); im.onload = () => res(im.naturalHeight / im.naturalWidth); im.onerror = () => res(0.3); im.src = src; }); }
async function placeFieldAnn(f, box, pi, kind, value) {
  const p = pages[pi]; if (!p) return null;
  const vw = p.vw, vh = p.vh;
  if (kind === 'sig') {
    const ar = await imgAr(value), hBox = Math.max(box.h, f.src === 'line' && !f.cell ? 0.045 : 0);
    let w = Math.min(box.w * 0.98, (hBox * vh) / (vw * ar)); w = Math.max(w, 0.03);
    const h = w * ar;
    return { t: 'img', src: value, fid: f.id, u: box.x + (box.w - w) / 2, v: Math.max(0, box.y + box.h - h * vw / vh), w, h };
  }
  const txt = kind === 'check' ? '✓' : value;
  let a = { t: 'text', c: FINK, s: Math.max(7, Math.min(box.h * vh * (kind === 'check' ? 0.95 : 0.78), 13)) / vw, u: 0, v: 0, text: txt, fid: f.id };
  let m = measureText(a, vw);
  const maxW = box.w * vw * 0.96;
  if (m.w > maxW) { a.s = a.s * maxW / m.w; m = measureText(a, vw); }
  const wr = m.w / vw, hr = m.h / vh;
  a.u = kind === 'check' ? box.x + (box.w - wr) / 2 : box.x + box.w - wr - Math.min(box.w * 0.04, 6 / vw);
  a.v = box.y + (box.h - hr) / 2;
  return a;
}
async function syncField(f) {
  pages.forEach(p => { const n = p.anns.filter(a => a.fid !== f.id); if (n.length !== p.anns.length) { p.anns = n; redraw(p); } });
  if (!filledF(f) || (f.src === 'acro' && f.value === f.orig)) return;
  let a = null, pi = f.page;
  if (f.kind === 'radio') { const o = f.options.find(z => z.value === f.value); if (o) { a = await placeFieldAnn(f, o, o.page, 'check', ''); pi = o.page; } }
  else a = await placeFieldAnn(f, f, f.page, f.kind === 'select' ? 'text' : f.kind, f.value);
  if (a && pages[pi]) { pages[pi].anns.push(a); redraw(pages[pi]); }
}
async function setFieldValue(f, v) {
  snapshot(); f.value = v; f.auto = false; await syncField(f); markDirty(true); drawBoxes(); fillBarUpdate();
}
function reconcileFields() {
  fields.forEach(f => {
    if (!filledF(f) || (f.src === 'acro' && f.value === f.orig)) return;
    if (!pages.some(p => p.anns.some(a => a.fid === f.id))) f.value = f.kind === 'check' ? false : '';
  });
  drawBoxes(); fillBarUpdate();
}
function drawBoxes() {
  document.querySelectorAll('.fld').forEach(e => e.remove());
  const mk = (f, pg, x, y, w, h, cls) => {
    const p = pages[pg]; if (!p) return;
    const d = document.createElement('div'); d.className = 'fld ' + cls + (curField === f.id ? ' cur' : '');
    Object.assign(d.style, { left: x * 100 + '%', top: y * 100 + '%', width: w * 100 + '%', height: h * 100 + '%' });
    d.onclick = ev => { ev.stopPropagation(); openFill(f.id); };
    p.el.appendChild(d);
  };
  fields.forEach(f => {
    const done = filledF(f) ? 'done' : '';
    if (f.kind === 'radio') f.options.forEach(o => mk(f, o.page, o.x, o.y, o.w, o.h, done)); else mk(f, f.page, f.x, f.y, f.w, f.h, done);
  });
}
function fillBarUpdate() {
  const n = fields.length, done = fields.filter(filledF).length;
  $('fillGo').classList.toggle('hide', !n);
  $('fillGo').textContent = done === 0 ? '📝 מילוי השדות (' + n + ')' : done < n ? '📝 המשך מילוי (' + done + '/' + n + ')' : '📝 עריכת השדות (' + n + ')';
}

/* fill sheet */
function openFill(id) {
  const list = fieldOrder(fields), i = list.findIndex(f => f.id === id), f = list[i]; if (!f) return;
  if (curField && curField !== id) commitFill();
  curField = id; drawBoxes();
  $('fsheet').classList.remove('hide');
  $('fsPos').textContent = 'שדה ' + (i + 1) + ' מתוך ' + list.length + (f.auto ? ' · מולא אוטומטית' : '');
  $('fsLabel').textContent = f.label || (f.kind === 'sig' ? 'חתימה' : f.kind === 'check' ? 'סימון' : 'שדה בלי כותרת');
  const body = $('fsBody'), e = s => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const chips = [['השם שלי', store.get('pv_name')], ['ת.ז', store.get('pv_tz')], ['התאריך היום', dmyFull()]].filter(c => c[1])
    .map(c => '<button type="button" data-chip="' + e(c[1]) + '">' + c[0] + '</button>').join('');
  if (f.kind === 'text') body.innerHTML = '<input type="text" id="fsIn" value="' + e(f.value) + '" enterkeyhint="next" autocomplete="off" autocorrect="off" spellcheck="false"><div class="chips">' + chips + '</div>';
  else if (f.kind === 'select') body.innerHTML = '<select id="fsIn"><option value="">—</option>' + f.options.map(o => '<option ' + (o === f.value ? 'selected' : '') + '>' + e(o) + '</option>').join('') + '</select>';
  else if (f.kind === 'check') body.innerHTML = '<label class="chk"><input type="checkbox" id="fsIn" ' + (f.value ? 'checked' : '') + '> מסומן</label>';
  else if (f.kind === 'radio') body.innerHTML = '<div class="opts">' + f.options.map((o, k) => '<label><input type="radio" name="fsR" value="' + k + '" ' + (f.value === o.value ? 'checked' : '') + '> ' + e(o.label || o.value) + '</label>').join('') + '</div>';
  else body.innerHTML = '<div class="sigprev">' + (f.value ? '<img src="' + f.value + '">' : '<span class="muted">עוד לא נחתם</span>') + '</div><div class="chips"><button type="button" data-sig="mine">✍️ החתימה שלי</button><button type="button" data-sig="draw">🖊️ חתימה ביד</button>' + (f.value ? '<button type="button" data-sig="clear">ניקוי</button>' : '') + '</div>';
  $('fsPrev').disabled = i === 0; $('fsNext').textContent = i === list.length - 1 ? 'סיום' : 'הבא';
  const inp = $('fsIn'); if (inp && f.kind === 'text') setTimeout(() => inp.focus(), 80);
  const reveal = () => { const el = document.querySelector('.fld[data-x]') || null; const t = document.querySelector('.fld.cur'); if (t) t.scrollIntoView({ block: 'center', inline: 'nearest' }); };
  setTimeout(reveal, 120); setTimeout(reveal, 520);
}
function commitFill() {
  const f = fields.find(q => q.id === curField); if (!f) return;
  const inp = $('fsIn');
  if ((f.kind === 'text' || f.kind === 'select') && inp) { const v = inp.value.trim(); if (v !== f.value) setFieldValue(f, v); }
  else if (f.kind === 'check' && inp) { if (inp.checked !== f.value) setFieldValue(f, inp.checked); }
  else if (f.kind === 'radio') { const r = document.querySelector('input[name="fsR"]:checked'); const v = r ? f.options[+r.value].value : ''; if (v !== f.value) setFieldValue(f, v); }
}
function closeFill() {
  commitFill(); curField = null; $('fsheet').classList.add('hide'); drawBoxes();
  const empty = fields.filter(f => !filledF(f) && f.kind !== 'check').length;
  A().toast(empty ? 'נשארו ' + (empty === 1 ? 'שדה אחד ריק' : empty + ' שדות ריקים') + ' (אפשר להשאיר ריקים)' : 'כל השדות מולאו');
}
function stepFill(d) {
  commitFill(); const list = fieldOrder(fields), i = list.findIndex(f => f.id === curField), n = list[i + d];
  if (n) openFill(n.id); else closeFill();
}
function bindFill() {
  $('fillGo').onclick = () => { const l = fieldOrder(fields), first = l.find(f => !filledF(f)) || l[0]; if (first) openFill(first.id); };
  $('fsNext').onclick = () => stepFill(1); $('fsPrev').onclick = () => stepFill(-1); $('fsX').onclick = closeFill;
  $('fsBody').addEventListener('keydown', e => { if (e.key === 'Enter' && e.target.id === 'fsIn') { e.preventDefault(); stepFill(1); } });
  $('fsBody').addEventListener('click', e => {
    const f = fields.find(q => q.id === curField); if (!f) return;
    const c = e.target.closest('[data-chip]'); if (c) { $('fsIn').value = c.dataset.chip; commitFill(); return; }
    const s = e.target.closest('[data-sig]'); if (!s) return;
    if (s.dataset.sig === 'mine') { if (!sigData) { toast2('אין חתימה שמורה – צייר חתימה'); sigTarget = f; openSig(); return; } setFieldValue(f, sigData).then(() => openFill(f.id)); }
    else if (s.dataset.sig === 'clear') setFieldValue(f, '').then(() => openFill(f.id));
    else if (s.dataset.sig === 'draw') { sigTarget = f; $('fsheet').classList.add('hide'); openSig(); }
  });
  $('fsDel').onclick = () => {
    const f = fields.find(q => q.id === curField); if (!f) return;
    const l = fieldOrder(fields), i = l.findIndex(z => z.id === f.id);
    if (filledF(f)) { snapshot(); f.value = f.kind === 'check' ? false : ''; syncField(f); }
    fields = fields.filter(z => z !== f); curField = null; drawBoxes(); fillBarUpdate();
    const n = fieldOrder(fields)[Math.min(i, fields.length - 1)]; if (n) openFill(n.id); else $('fsheet').classList.add('hide');
  };
  bindFieldTools();
  $('dateGo').onclick = () => setTool('date');
  $('myGo').onclick = () => { $('mdName').value = store.get('pv_name'); $('mdTz').value = store.get('pv_tz'); $('mdModal').classList.remove('hide'); };
  $('mdCancel').onclick = () => $('mdModal').classList.add('hide');
  $('mdOk').onclick = () => {
    store.set('pv_name', $('mdName').value.trim()); store.set('pv_tz', $('mdTz').value.trim()); $('mdModal').classList.add('hide');
    const before = fields.filter(filledF).length; autofill(fields);
    Promise.all(fields.filter(f => f.auto && filledF(f)).map(syncField)).then(() => { drawBoxes(); fillBarUpdate(); if (fields.filter(filledF).length > before) markDirty(true); });
    toast2('הפרטים נשמרו');
  };
}
let moveFld = null;
async function moveFieldTo(p, u, v) {
  const f = moveFld; moveFld = null; if (!f) { setTool('scroll'); return; }
  snapshot();
  f.page = p.i - 1; f.x = Math.max(0, Math.min(1 - f.w, u - f.w / 2)); f.y = Math.max(0, Math.min(1 - f.h, v - f.h / 2));
  await syncField(f); markDirty(true); setTool('scroll'); drawBoxes(); openFill(f.id);
}
function bindFieldTools() {
  $('fsMove').onclick = () => {
    const f = fields.find(q => q.id === curField); if (!f) return;
    if (f.kind === 'radio') { toast2('אי אפשר להזיז בחירה מרובה'); return; }
    moveFld = f; $('fsheet').classList.add('hide'); setTool('fmove'); toast2('הקש על הדף במקום החדש של השדה');
  };
  $('fsRen').onclick = () => {
    const f = fields.find(q => q.id === curField); if (!f) return;
    const l = $('fsLabel'); l.innerHTML = '<input id="fsLbl" type="text" style="width:100%">'; const i = $('fsLbl'); i.value = f.label || ''; i.focus();
    const done = () => { f.label = i.value.trim(); openFill(f.id); };
    i.onkeydown = e => { if (e.key === 'Enter') { e.preventDefault(); done(); } }; i.onblur = done;
  };
  const wid = d => { const f = fields.find(q => q.id === curField); if (!f || f.kind === 'radio') return; snapshot(); f.w = Math.max(0.02, Math.min(1 - f.x, f.w * d)); syncField(f).then(() => { drawBoxes(); markDirty(true); }); };
  $('fsWp').onclick = () => wid(1.2); $('fsWm').onclick = () => wid(1 / 1.2);
}
function placeDate(p, u, v) {
  const a = { t: 'text', c: FINK, s: 0.026, u: 0, v: 0, text: dmyFull() };
  const m = measureText(a, p.vw); a.u = Math.max(0, Math.min(1 - m.w / p.vw, u - m.w / p.vw / 2)); a.v = Math.max(0, v - m.h / p.vh / 2);
  snapshot(); p.anns.push(a); redraw(p); markDirty(true); setTool('move'); setSel(p, a);
}

/* ---------- entry points ---------- */
window.PV = {
  async open(dark) {
    document.documentElement.className = dark ? 'dark' : '';
    loadSigs();
    try {
      bytes = b64ToBytes(A().docData());
      doc = await pdfjsLib.getDocument({ data: bytes.slice(), useSystemFonts: true, isEvalSupported: false }).promise;
      await buildPages(); bindBar(); bindFill(); setTool('scroll'); $('zl').textContent = '100%';
      A().opened();
      detectAll().catch(() => {});
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

/* =====================  PDF tools: organize pages, merge, images → PDF  ===================== */
const AT = () => window.Android || {};
const PT_STATE = { items: [], sel: new Set(), hist: [], doc: null, bytes: null, dirty: false, thumbs: {}, io: null };

function ptDirty(v) { if (PT_STATE.dirty !== v) { PT_STATE.dirty = v; if (AT().dirty) AT().dirty(v); } }
function ptSnap() { PT_STATE.hist.push(PT_STATE.items.map(x => Object.assign({}, x))); if (PT_STATE.hist.length > 40) PT_STATE.hist.shift(); }

async function ptThumb(src, cv) {
  const S = PT_STATE;
  if (S.thumbs[src]) { cv.width = S.thumbs[src].width; cv.height = S.thumbs[src].height; cv.getContext('2d').drawImage(S.thumbs[src], 0, 0); return; }
  const page = await S.doc.getPage(src + 1);
  const base = page.getViewport({ scale: 1 });
  const sc = Math.min(2, (170 * Math.min(window.devicePixelRatio || 1, 2)) / base.width);
  const vp = page.getViewport({ scale: sc });
  const c = document.createElement('canvas'); c.width = Math.floor(vp.width); c.height = Math.floor(vp.height);
  await page.render({ canvasContext: c.getContext('2d'), canvas: c, viewport: vp }).promise;
  S.thumbs[src] = c;
  cv.width = c.width; cv.height = c.height; cv.getContext('2d').drawImage(c, 0, 0);
}

function ptRender() {
  const S = PT_STATE, grid = $('grid'); if (!grid) return;
  grid.innerHTML = '';
  if (S.io) S.io.disconnect();
  S.io = new IntersectionObserver(es => es.forEach(e => {
    if (e.isIntersecting && !e.target._done) { e.target._done = true; ptThumb(e.target._src, e.target).catch(() => {}); }
  }), { root: $('gridwrap'), rootMargin: '400px' });
  S.items.forEach((it, idx) => {
    const cell = document.createElement('div'); cell.className = 'cell' + (S.sel.has(idx) ? ' sel' : '');
    const box = document.createElement('div'); box.className = 'tb';
    const cv = document.createElement('canvas'); cv._src = it.src;
    cv.style.transform = 'rotate(' + it.rot + 'deg)';
    if (it.rot % 180) { cv.style.maxWidth = '100%'; }
    box.appendChild(cv); cell.appendChild(box);
    const n = document.createElement('div'); n.className = 'bd'; n.textContent = idx + 1; cell.appendChild(n);
    cell.onclick = () => { if (S.sel.has(idx)) S.sel.delete(idx); else S.sel.add(idx); ptRender(); ptCount(); };
    grid.appendChild(cell); S.io.observe(cv);
  });
  ptCount();
}
function ptCount() { const el = $('cnt'); if (el) el.textContent = PT_STATE.sel.size ? PT_STATE.sel.size + ' נבחרו' : PT_STATE.items.length + ' עמודים'; }

function ptAct(kind) {
  const S = PT_STATE, picked = [...S.sel].sort((a, b) => a - b);
  if (kind === 'all') { S.sel = S.sel.size === S.items.length ? new Set() : new Set(S.items.map((_, i) => i)); ptRender(); return; }
  if (kind === 'undo') { const h = S.hist.pop(); if (h) { S.items = h; S.sel = new Set(); ptRender(); ptDirty(S.hist.length > 0); } return; }
  if (!picked.length) { AT().toast && AT().toast('בחר עמודים קודם'); return; }
  if (kind === 'extract') { PT.extract(); return; }
  ptSnap();
  if (kind === 'rot') { picked.forEach(i => { S.items[i] = Object.assign({}, S.items[i], { rot: (S.items[i].rot + 90) % 360 }); }); }
  else if (kind === 'del') {
    if (picked.length >= S.items.length) { S.hist.pop(); AT().toast && AT().toast('אי אפשר למחוק את כל העמודים'); return; }
    S.items = S.items.filter((_, i) => !S.sel.has(i)); S.sel = new Set();
  } else if (kind === 'left' || kind === 'right') {
    const d = kind === 'left' ? -1 : 1, arr = S.items, ns = new Set();
    const order = d < 0 ? picked : picked.slice().reverse();
    let blocked = false;
    for (const i of order) { const j = i + d; if (j < 0 || j >= arr.length || (S.sel.has(j) && !ns.has(j) && false)) { blocked = true; break; } }
    if (blocked) { S.hist.pop(); return; }
    const sel2 = new Set(S.sel);
    for (const i of order) { const j = i + d; [arr[i], arr[j]] = [arr[j], arr[i]]; sel2.delete(i); }
    S.sel = new Set(picked.map(i => i + d));
  }
  ptDirty(true); ptRender();
}

async function ptBuild(items) {
  const src = await PDFDocument.load(PT_STATE.bytes, { ignoreEncryption: true, updateMetadata: false });
  const out = await PDFDocument.create();
  const copied = await out.copyPages(src, items.map(x => x.src));
  copied.forEach((pg, i) => {
    const base = ((pg.getRotation().angle % 360) + 360) % 360;
    pg.setRotation(degrees((base + items[i].rot) % 360));
    out.addPage(pg);
  });
  return await out.saveAsBase64({ dataUri: false });
}

window.PT = {
  async open(dark) {
    document.documentElement.className = dark ? 'dark' : '';
    try {
      const b64 = AT().docData();
      PT_STATE.bytes = b64ToBytes(b64);
      PT_STATE.doc = await pdfjsLib.getDocument({ data: PT_STATE.bytes.slice(), useSystemFonts: true, isEvalSupported: false }).promise;
      PT_STATE.items = Array.from({ length: PT_STATE.doc.numPages }, (_, i) => ({ src: i, rot: 0 }));
      document.querySelectorAll('#obar [data-a]').forEach(b => b.onclick = () => ptAct(b.dataset.a));
      ptRender(); AT().opened && AT().opened();
    } catch (e) { AT().toast && AT().toast(e && e.name === 'PasswordException' ? 'הקובץ מוגן בסיסמה' : 'פתיחת ה-PDF נכשלה'); }
  },
  async save() {
    try { const b = await ptBuild(PT_STATE.items); ptDirty(false); AT().result('save', b); }
    catch (e) { AT().fail('השמירה נכשלה: ' + (e && e.message ? e.message : e)); }
  },
  async extract() {
    try {
      const picked = [...PT_STATE.sel].sort((a, b) => a - b).map(i => PT_STATE.items[i]);
      AT().result('extract', await ptBuild(picked));
    } catch (e) { AT().fail('החילוץ נכשל'); }
  },
  async merge(n) {
    try {
      const out = await PDFDocument.create();
      for (let i = 0; i < n; i++) {
        const src = await PDFDocument.load(b64ToBytes(AT().fileData(i)), { ignoreEncryption: true, updateMetadata: false });
        const pgs = await out.copyPages(src, src.getPageIndices());
        pgs.forEach(p => out.addPage(p));
      }
      AT().result('merge', await out.saveAsBase64({ dataUri: false }));
    } catch (e) { AT().fail('האיחוד נכשל: ' + (e && e.message ? e.message : e)); }
  },
  async images(n) {
    try {
      const out = await PDFDocument.create();
      for (let i = 0; i < n; i++) {
        const bytes = b64ToBytes(AT().fileData(i)), mime = (AT().fileMime(i) || '').toLowerCase();
        let img;
        if (mime.includes('jpeg') || mime.includes('jpg')) img = await out.embedJpg(bytes);
        else if (mime.includes('png')) img = await out.embedPng(bytes);
        else {
          const url = URL.createObjectURL(new Blob([bytes], { type: mime || 'image/*' }));
          const im = await new Promise((res, rej) => { const x = new Image(); x.onload = () => res(x); x.onerror = rej; x.src = url; });
          const c = document.createElement('canvas'); c.width = im.naturalWidth; c.height = im.naturalHeight;
          const cx = c.getContext('2d'); cx.fillStyle = '#fff'; cx.fillRect(0, 0, c.width, c.height); cx.drawImage(im, 0, 0);
          img = await out.embedJpg(dataUrlBytes(c.toDataURL('image/jpeg', 0.9)));
        }
        const land = img.width > img.height, PW = land ? 842 : 595, PH = land ? 595 : 842, M = 20;
        const k = Math.min((PW - 2 * M) / img.width, (PH - 2 * M) / img.height);
        const w = img.width * k, h = img.height * k;
        const pg = out.addPage([PW, PH]);
        pg.drawImage(img, { x: (PW - w) / 2, y: (PH - h) / 2, width: w, height: h });
      }
      AT().result('images', await out.saveAsBase64({ dataUri: false }));
    } catch (e) { AT().fail('היצירה נכשלה: ' + (e && e.message ? e.message : e)); }
  }
};
