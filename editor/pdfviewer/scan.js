import { PDFDocument } from 'pdf-lib';

/* Scan editor: crop / rotate / filter every photographed page, then PDF settings -> PDF (shown inside a WebView). */
const $ = id => document.getElementById(id);
const A = () => window.Android || {};
const FILTERS = [['orig', 'מקור'], ['auto', 'אוטומטי'], ['doc', 'מסמך'], ['bw', 'שחור-לבן'], ['color', 'צבעוני']];
const S = { pages: [], i: 0, mode: 'filter', screen: 'edit', set: { name: '', margin: true, comp: 'med', size: 'A4', orient: 'auto' } };
const COMP = { low: [2400, 0.9], med: [1700, 0.75], high: [1200, 0.55] };

const b64ToBytes = b => { const s = atob(b), u = new Uint8Array(s.length); for (let i = 0; i < s.length; i++) u[i] = s.charCodeAt(i); return u; };
const loadImg = bytes => new Promise((res, rej) => {
  const url = URL.createObjectURL(new Blob([bytes], { type: 'image/jpeg' })), im = new Image();
  im.onload = () => res(im); im.onerror = rej; im.src = url;
});
const mk = (w, h) => { const c = document.createElement('canvas'); c.width = Math.max(1, Math.round(w)); c.height = Math.max(1, Math.round(h)); return c; };

/* ---------- image ops ---------- */
function rotated(p, maxDim) {
  const im = p.img, k = Math.min(1, maxDim / Math.max(im.naturalWidth, im.naturalHeight));
  const w = im.naturalWidth * k, h = im.naturalHeight * k, odd = p.rot % 180 !== 0;
  const c = mk(odd ? h : w, odd ? w : h), x = c.getContext('2d');
  x.translate(c.width / 2, c.height / 2); x.rotate(p.rot * Math.PI / 180); x.drawImage(im, -w / 2, -h / 2, w, h);
  return c;
}
function cropped(p, maxDim) {
  const r = rotated(p, maxDim), cr = p.crop;
  const sx = cr.x * r.width, sy = cr.y * r.height, sw = cr.w * r.width, sh = cr.h * r.height;
  const o = mk(sw, sh); o.getContext('2d').drawImage(r, sx, sy, sw, sh, 0, 0, o.width, o.height);
  return o;
}
function background(c) {
  const sm = mk(c.width / 24, c.height / 24); sm.getContext('2d').drawImage(c, 0, 0, sm.width, sm.height);
  const bg = mk(c.width, c.height), bx = bg.getContext('2d', { willReadFrequently: true });
  bx.imageSmoothingQuality = 'high'; bx.drawImage(sm, 0, 0, c.width, c.height);
  return bx.getImageData(0, 0, c.width, c.height).data;
}
function applyFilter(c, kind) {
  if (kind === 'orig') return c;
  const x = c.getContext('2d', { willReadFrequently: true }), a = x.getImageData(0, 0, c.width, c.height), d = a.data;
  const bg = kind === 'color' ? null : background(c);
  for (let i = 0; i < d.length; i += 4) {
    if (kind === 'auto') {
      for (let k = 0; k < 3; k++) { let v = d[i + k] / (bg[i + k] + 1) * 245; v = (v - 20) * 1.08; d[i + k] = v < 0 ? 0 : v > 255 ? 255 : v; }
    } else if (kind === 'color') {
      const g = 0.299 * d[i] + 0.587 * d[i + 1] + 0.114 * d[i + 2];
      for (let k = 0; k < 3; k++) { let v = g + (d[i + k] - g) * 1.3; v = (v - 128) * 1.12 + 136; d[i + k] = v < 0 ? 0 : v > 255 ? 255 : v; }
    } else {
      const g = 0.299 * d[i] + 0.587 * d[i + 1] + 0.114 * d[i + 2], gb = 0.299 * bg[i] + 0.587 * bg[i + 1] + 0.114 * bg[i + 2] + 1;
      let v = g / gb * 255;
      v = kind === 'bw' ? (v < 168 ? 0 : 255) : (v - 90) * (255 / 145);
      v = v < 0 ? 0 : v > 255 ? 255 : v; d[i] = d[i + 1] = d[i + 2] = v;
    }
  }
  x.putImageData(a, 0, 0); return c;
}
/* guess the sheet: bounding box of the bright region */
function autoCrop(p) {
  const r = rotated(p, 220), x = r.getContext('2d', { willReadFrequently: true }), d = x.getImageData(0, 0, r.width, r.height).data;
  const n = r.width * r.height, g = new Uint8Array(n), hist = new Uint32Array(256);
  for (let i = 0; i < n; i++) { const v = (0.299 * d[i * 4] + 0.587 * d[i * 4 + 1] + 0.114 * d[i * 4 + 2]) | 0; g[i] = v; hist[v]++; }
  let sum = 0; for (let t = 0; t < 256; t++) sum += t * hist[t];
  let sB = 0, wB = 0, best = 0, thr = 128;
  for (let t = 0; t < 256; t++) { wB += hist[t]; if (!wB) continue; const wF = n - wB; if (!wF) break; sB += t * hist[t]; const mB = sB / wB, mF = (sum - sB) / wF, v = wB * wF * (mB - mF) * (mB - mF); if (v > best) { best = v; thr = t; } }
  const rows = new Uint32Array(r.height), cols = new Uint32Array(r.width);
  for (let yy = 0; yy < r.height; yy++) for (let xx = 0; xx < r.width; xx++) if (g[yy * r.width + xx] > thr) { rows[yy]++; cols[xx]++; }
  const f = (arr, min) => { let a = 0, b = arr.length - 1; while (a < b && arr[a] < min) a++; while (b > a && arr[b] < min) b--; return [a, b]; };
  const [y0, y1] = f(rows, r.width * 0.3), [x0, x1] = f(cols, r.height * 0.3);
  if (x1 - x0 < r.width * 0.3 || y1 - y0 < r.height * 0.3) return { x: 0.02, y: 0.02, w: 0.96, h: 0.96 };
  return { x: x0 / r.width, y: y0 / r.height, w: (x1 - x0 + 1) / r.width, h: (y1 - y0 + 1) / r.height };
}

/* ---------- pages ---------- */
async function addPage(b64, replace) {
  const im = await loadImg(b64ToBytes(b64));
  const p = { img: im, rot: 0, crop: { x: 0.02, y: 0.02, w: 0.96, h: 0.96 }, filter: 'doc' };
  p.crop = autoCrop(p);
  if (replace != null && S.pages[replace]) { S.pages[replace] = p; S.i = replace; } else { S.pages.push(p); S.i = S.pages.length - 1; }
  render();
}
const cur = () => S.pages[S.i];

/* ---------- editor view ---------- */
let stage = { w: 0, h: 0 };
function render() {
  const p = cur(); if (!p) return;
  $('count').textContent = (S.i + 1) + '/' + S.pages.length;
  $('prev').disabled = S.i === 0; $('next').disabled = S.i === S.pages.length - 1;
  $('modeBtn').querySelector('span').textContent = S.mode === 'crop' ? 'סיום חיתוך' : 'חיתוך';
  $('doneBtn').textContent = S.mode === 'crop' ? '✓ אישור' : 'הבא';
  document.querySelectorAll('.cropOnly').forEach(e => e.classList.toggle('hide', S.mode !== 'crop'));
  document.querySelectorAll('.filterOnly').forEach(e => e.classList.toggle('hide', S.mode === 'crop'));
  const box = $('stage').getBoundingClientRect(); stage = { w: box.width, h: box.height };
  const src = S.mode === 'crop' ? rotated(p, 1400) : applyFilter(cropped(p, 1400), p.filter);
  const k = Math.min((stage.w - 24) / src.width, (stage.h - 24) / src.height);
  const cv = $('cv'); cv.width = src.width; cv.height = src.height; cv.style.width = src.width * k + 'px'; cv.style.height = src.height * k + 'px';
  cv.getContext('2d').drawImage(src, 0, 0);
  $('crop').classList.toggle('hide', S.mode !== 'crop');
  placeCrop();
  if (S.mode !== 'crop') thumbs();
}
function placeCrop() {
  const cv = $('cv'), st = $('stage').getBoundingClientRect(), r = cv.getBoundingClientRect(), p = cur(); if (!p) return;
  const o = $('crop'); o.style.left = (r.left - st.left) + 'px'; o.style.top = (r.top - st.top) + 'px'; o.style.width = r.width + 'px'; o.style.height = r.height + 'px';
  const c = p.crop, b = $('box');
  b.style.left = c.x * 100 + '%'; b.style.top = c.y * 100 + '%'; b.style.width = c.w * 100 + '%'; b.style.height = c.h * 100 + '%';
}
function thumbs() {
  const row = $('filters'); const p = cur(); row.innerHTML = '';
  const small = cropped(p, 140);
  FILTERS.forEach(([k, label]) => {
    const t = mk(small.width, small.height); t.getContext('2d').drawImage(small, 0, 0);
    applyFilter(t, k);
    const d = document.createElement('div'); d.className = 'ft' + (p.filter === k ? ' on' : '');
    t.style.cssText = 'width:100%;height:76px;object-fit:cover;border-radius:8px;display:block'; d.appendChild(t);
    const l = document.createElement('div'); l.textContent = label; d.appendChild(l);
    d.onclick = () => { p.filter = k; render(); }; row.appendChild(d);
  });
}
function bindCrop() {
  document.querySelectorAll('#box [data-h]').forEach(h => {
    h.onpointerdown = e => {
      e.preventDefault(); h.setPointerCapture(e.pointerId);
      const p = cur(), r = $('crop').getBoundingClientRect(), start = Object.assign({}, p.crop), type = h.dataset.h;
      const mv = ev => {
        const u = Math.max(0, Math.min(1, (ev.clientX - r.left) / r.width)), v = Math.max(0, Math.min(1, (ev.clientY - r.top) / r.height)), min = 0.08;
        let x0 = start.x, y0 = start.y, x1 = start.x + start.w, y1 = start.y + start.h;
        if (type.includes('l')) x0 = Math.min(u, x1 - min); if (type.includes('r')) x1 = Math.max(u, x0 + min);
        if (type.includes('t')) y0 = Math.min(v, y1 - min); if (type.includes('b')) y1 = Math.max(v, y0 + min);
        p.crop = { x: x0, y: y0, w: x1 - x0, h: y1 - y0 }; placeCrop();
      };
      const up = () => { h.removeEventListener('pointermove', mv); h.removeEventListener('pointerup', up); };
      h.addEventListener('pointermove', mv); h.addEventListener('pointerup', up);
    };
  });
}
function rotate(d) { const p = cur(); p.rot = (p.rot + d + 360) % 360; p.crop = autoCrop(p); render(); }
function del() {
  S.pages.splice(S.i, 1);
  if (!S.pages.length) { A().close && A().close(); return; }
  S.i = Math.min(S.i, S.pages.length - 1); render();
}

/* ---------- settings + build ---------- */
const OPTS = {
  comp: [['low', 'דחיסה נמוכה (איכות גבוהה)'], ['med', 'דחיסה בינונית'], ['high', 'דחיסה גבוהה (קובץ קטן)']],
  size: [['A4', 'A4'], ['Letter', 'Letter'], ['fit', 'לפי גודל התמונה']],
  orient: [['auto', 'אוטומטי'], ['p', 'לאורך'], ['l', 'לרוחב']]
};
function showSettings() {
  S.screen = 'set'; $('edit').classList.add('hide'); $('settings').classList.remove('hide');
  const p = S.pages[0], t = applyFilter(cropped(p, 200), p.filter), th = $('sThumb'); th.width = t.width; th.height = t.height; th.getContext('2d').drawImage(t, 0, 0);
  $('sCount').textContent = S.pages.length + ' עמודים'; $('sName').value = S.set.name; $('sMargin').checked = S.set.margin; fillOpts();
}
function fillOpts() {
  ['comp', 'size', 'orient'].forEach(k => {
    const box = $('o_' + k); box.innerHTML = '';
    OPTS[k].forEach(([v, label]) => { const b = document.createElement('button'); b.className = 'opt' + (S.set[k] === v ? ' on' : ''); b.textContent = label; b.onclick = () => { S.set[k] = v; fillOpts(); }; box.appendChild(b); });
  });
}
async function build() {
  $('make').disabled = true; $('make').textContent = 'יוצר…';
  try {
    const out = await PDFDocument.create(), [mx, q] = COMP[S.set.comp], M = S.set.margin ? 14 : 0;
    for (const p of S.pages) {
      const c = applyFilter(cropped(p, mx), p.filter), url = c.toDataURL('image/jpeg', q);
      const img = await out.embedJpg(b64ToBytes(url.split(',')[1]));
      let PW, PH;
      if (S.set.size === 'fit') { PW = 595; PH = 595 * img.height / img.width; }
      else {
        [PW, PH] = S.set.size === 'Letter' ? [612, 792] : [595, 842];
        const land = S.set.orient === 'l' || (S.set.orient === 'auto' && img.width > img.height);
        if (land) [PW, PH] = [PH, PW];
      }
      const k = Math.min((PW - 2 * M) / img.width, (PH - 2 * M) / img.height), w = img.width * k, h = img.height * k;
      out.addPage([PW, PH]).drawImage(img, { x: (PW - w) / 2, y: (PH - h) / 2, width: w, height: h });
    }
    A().result($('sName').value.trim() || S.set.name, await out.saveAsBase64({ dataUri: false }));
  } catch (e) { A().toast('היצירה נכשלה: ' + (e && e.message ? e.message : e)); }
  $('make').disabled = false; $('make').textContent = 'שמירה';
}

window.SC = {
  async open(dark, name) {
    document.documentElement.className = dark ? 'dark' : '';
    S.set.name = name || '';
    try {
      const n = A().fileCount ? A().fileCount() : 1;
      for (let i = 0; i < n; i++) await addPage(A().fileData(i));
      S.i = 0;
    } catch (e) { A().toast('התמונה לא נטענה'); A().close(); return; }
    bindCrop();
    $('prev').onclick = () => { if (S.i > 0) { S.i--; render(); } }; $('next').onclick = () => { if (S.i < S.pages.length - 1) { S.i++; render(); } };
    $('modeBtn').onclick = () => { S.mode = S.mode === 'crop' ? 'filter' : 'crop'; render(); };
    $('rotL').onclick = () => rotate(-90); $('rotR').onclick = () => rotate(90);
    $('delBtn').onclick = del;
    $('retake').onclick = () => A().camera('r' + S.i); $('addBtn').onclick = () => A().camera('a'); $('galBtn').onclick = () => A().gallery();
    $('autoCrop').onclick = () => { cur().crop = autoCrop(cur()); render(); };
    $('fullCrop').onclick = () => { cur().crop = { x: 0, y: 0, w: 1, h: 1 }; render(); };
    $('doneBtn').onclick = () => { if (S.mode === 'crop') { S.mode = 'filter'; render(); return; } showSettings(); };
    $('backBtn').onclick = () => SC.back();
    $('sBack').onclick = () => SC.back();
    $('sName').oninput = () => { S.set.name = $('sName').value; };
    $('sMargin').onchange = () => { S.set.margin = $('sMargin').checked; };
    $('make').onclick = build;
    window.addEventListener('resize', () => { if (S.screen === 'edit') render(); });
    A().opened && A().opened();
  },
  shot(b64, mode) { addPage(b64, mode && mode[0] === 'r' ? +mode.slice(1) : null); },
  back() {
    if (S.screen === 'set') { S.screen = 'edit'; $('settings').classList.add('hide'); $('edit').classList.remove('hide'); render(); }
    else if (S.mode === 'crop') { S.mode = 'filter'; render(); }
    else A().close();
  }
};
