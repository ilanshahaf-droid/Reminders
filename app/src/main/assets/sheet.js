/* Spreadsheet (xlsx) editor for the docs tab. Own model on top of ExcelJS; formulas via fx.js. */
(function () {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const A = () => window.Android || { ready() {}, docData() { return ''; }, docSaved() {}, saveFailed() {}, dirty() {}, toast() {}, opened() {} };
  const key = (r, c) => r + ',' + c;
  const colName = FX.colName;
  const DEF_W = 96, DEF_H = 28, ROW_CHUNK = 60;

  let idc = 0;
  const S = { wb: null, sheets: [], cur: 0, sel: { ar: 1, ac: 1, r1: 1, c1: 1, r2: 1, c2: 1 }, dirty: false, undo: [], redo: [], rangeMode: false, zoom: 1, rowsShown: ROW_CHUNK, editing: false, removed: [], dark: false };
  const sh = () => S.sheets[S.cur];

  /* ---------------- number formatting ---------------- */
  const MONTHS = ['ינו', 'פבר', 'מרץ', 'אפר', 'מאי', 'יונ', 'יול', 'אוג', 'ספט', 'אוק', 'נוב', 'דצמ'];
  function serialToDate(n) { return new Date(Math.round((n - 25569) * 864e5)); }
  function dateToSerial(d) { return (Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) - Date.UTC(1899, 11, 30)) / 864e5; }
  const isDateFmt = (nf) => !!nf && /[ymdhs]/i.test(nf.replace(/"[^"]*"|\[[^\]]*\]|\\./g, '')) && !/^general$/i.test(nf);
  function pad(n, l) { return String(n).padStart(l || 2, '0'); }
  function fmtDate(v, nf) {
    const d = serialToDate(v); const U = (k) => d['getUTC' + k]();
    let f = nf.replace(/"[^"]*"/g, (m) => '\u0001' + m.slice(1, -1) + '\u0002').replace(/\[[^\]]*\]/g, '');
    const hasAmPm = /am\/pm|a\/p/i.test(f);
    // minutes vs months: "m" directly after h or before s is minutes
    f = f.replace(/h+[^a-z]*m+|m+[^a-z]*s+/gi, (m) => m.replace(/m+/i, (x) => x.length === 1 ? '\u0003' : '\u0004'));
    let out = f.replace(/yyyy|yy|mmmm|mmm|mm|m|dddd|ddd|dd|d|hh|h|ss|s|\u0004|\u0003|am\/pm|a\/p/gi, (t) => {
      switch (t.toLowerCase()) {
        case 'yyyy': return U('FullYear'); case 'yy': return pad(U('FullYear') % 100);
        case 'mmmm': case 'mmm': return MONTHS[U('Month')]; case 'mm': return pad(U('Month') + 1); case 'm': return U('Month') + 1;
        case 'dddd': case 'ddd': return 'יום ' + 'אבגדהוש'[U('Day')]; case 'dd': return pad(U('Date')); case 'd': return U('Date');
        case 'hh': return pad(hasAmPm ? ((U('Hours') + 11) % 12) + 1 : U('Hours')); case 'h': return hasAmPm ? ((U('Hours') + 11) % 12) + 1 : U('Hours');
        case 'ss': return pad(U('Seconds')); case 's': return U('Seconds');
        case '\u0004': return pad(U('Minutes')); case '\u0003': return U('Minutes');
        case 'am/pm': case 'a/p': return U('Hours') < 12 ? 'AM' : 'PM';
      }
      return t;
    });
    return out.replace(/\u0001/g, '').replace(/\u0002/g, '');
  }
  function fmtNum(v, nf) {
    if (!nf || /^general$/i.test(nf)) {
      if (Number.isInteger(v)) return String(v);
      let s = String(+v.toPrecision(11)); if (s.includes('e')) return s; return s;
    }
    if (nf === '@') return String(v);
    const sections = nf.split(';'); let f = sections[0];
    if (v < 0 && sections[1]) { f = sections[1]; v = -v; }
    const pct = /%/.test(f);
    if (pct) v *= 100;
    const clean = f.replace(/"([^"]*)"/g, '').replace(/\[[^\]]*\]/g, '').replace(/\\./g, '').replace(/_./g, '').replace(/\*./g, '');
    const m = /([#0?,]*)(?:\.([0#?]*))?/.exec(clean.replace(/[^#0?,.]/g, ''));
    const dec = m && m[2] ? m[2].length : 0, thousands = /,/.test(m ? m[1] : '');
    let intPart = (m ? m[1] : '').replace(/,/g, ''); const minInt = (intPart.match(/0/g) || []).length;
    const abs = Math.abs(v); let s = abs.toFixed(dec);
    let [i, d] = s.split('.');
    if (i.length < minInt) i = i.padStart(minInt, '0'); if (minInt === 0 && i === '0' && dec > 0) i = '';
    if (thousands) i = i.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    // trailing optional decimals (#)
    if (d && m && m[2]) { const keep = (m[2].match(/0/g) || []).length; d = d.replace(/0+$/, '').padEnd(keep, '0'); }
    let body = (v < 0 && abs !== 0 ? '-' : '') + i + (d ? '.' + d : '');
    // literal prefix / suffix (currency, %, text)
    const lit = (f.replace(/\[\$([^\]\-]*)[^\]]*\]/g, '\u0005$1\u0005').replace(/"([^"]*)"/g, '\u0005$1\u0005'));
    const first = lit.search(/[#0?]/), lastIdx = Math.max(lit.lastIndexOf('0'), lit.lastIndexOf('#'), lit.lastIndexOf('?'));
    const strip = (t) => t.replace(/\u0005/g, '').replace(/[_*].?/g, '').replace(/\\/g, '');
    const pre = strip(lit.slice(0, first < 0 ? 0 : first)).replace(/[#0?,.]/g, ''), suf = strip(lit.slice(lastIdx + 1)).replace(/[#0?,.]/g, '');
    return pre + body + suf;
  }
  function display(cell) {
    if (!cell) return '';
    let v = cell.f !== null && cell.f !== undefined ? cell.r : cell.v;
    const nf = cell.st && cell.st.numFmt;
    if (v === null || v === undefined) return '';
    if (typeof v === 'object' && v.err) return v.err;
    if (typeof v === 'boolean') return v ? 'TRUE' : 'FALSE';
    if (typeof v === 'number') {
      if (nf && isDateFmt(nf)) return fmtDate(v, nf);
      return fmtNum(v, nf);
    }
    return String(v);
  }

  /* ---------------- model <-> ExcelJS ---------------- */
  const clone = (o) => (o === undefined ? undefined : JSON.parse(JSON.stringify(o)));
  function fromWs(ws) {
    const sheet = { id: ++idc, name: ws.name, rtl: !!(ws.views && ws.views[0] && ws.views[0].rightToLeft), cols: {}, rows: {}, cells: {}, merges: [], maxR: 1, maxC: 1, ws };
    ws.columns && ws.columns.forEach((col, i) => { if (col && col.width) sheet.cols[i + 1] = Math.round(col.width * 7 + 5); });
    ws.eachRow({ includeEmpty: false }, (row, r) => {
      if (row.height) sheet.rows[r] = Math.round(row.height / 0.75);
      row.eachCell({ includeEmpty: false }, (cell, c) => {
        if (cell.master && cell.master !== cell) return; // merged slave
        let v = cell.value, f = null, res = null;
        if (v && typeof v === 'object' && !(v instanceof Date)) {
          if ('formula' in v || 'sharedFormula' in v) { f = (cell.formula || v.formula || '').replace(/^=/, ''); res = v.result === undefined ? null : v.result; v = null; }
          else if (v.richText) v = v.richText.map((t) => t.text).join('');
          else if (v.text !== undefined) v = String(v.text);
          else if (v.error) v = { err: v.error };
          else v = String(v);
        }
        if (res instanceof Date) res = dateToSerial(res);
        if (res && typeof res === 'object' && res.error) res = { err: res.error };
        if (v instanceof Date) v = (v.getTime() / 864e5) + 25569;
        const st = clone(cell.style) || {};
        sheet.cells[key(r, c)] = { v, f, r: res, st };
        if (r > sheet.maxR) sheet.maxR = r; if (c > sheet.maxC) sheet.maxC = c;
      });
    });
    (ws.model && ws.model.merges || []).forEach((m) => {
      const mm = /^([A-Z]+)(\d+):([A-Z]+)(\d+)$/.exec(m); if (mm) sheet.merges.push([+mm[2], FX.colNum(mm[1]), +mm[4], FX.colNum(mm[3])]);
    });
    return sheet;
  }
  function toWs(sheet, wb) {
    let ws = sheet.ws;
    if (!ws) { ws = wb.addWorksheet(sheet.name, { views: [{ rightToLeft: sheet.rtl }] }); sheet.ws = ws; }
    ws.name = sheet.name;
    if (!ws.views || !ws.views.length) ws.views = [{ rightToLeft: sheet.rtl }]; else ws.views[0].rightToLeft = sheet.rtl;
    (ws.model && ws.model.merges ? ws.model.merges.slice() : []).forEach((m) => { try { ws.unMergeCells(m); } catch (_) {} });
    ws.eachRow({ includeEmpty: true }, (row) => { row.height = undefined; row.eachCell({ includeEmpty: true }, (c) => { c.value = null; c.style = {}; }); });
    for (const k in sheet.cells) {
      const [r, c] = k.split(',').map(Number), cell = sheet.cells[k];
      const x = ws.getCell(r, c);
      if (cell.f !== null && cell.f !== undefined) {
        const res = cell.r && cell.r.err ? { error: cell.r.err } : cell.r;
        x.value = { formula: cell.f, result: res === null || res === undefined ? undefined : res };
      } else if (cell.v && cell.v.err) x.value = { error: cell.v.err };
      else x.value = cell.v === undefined ? null : cell.v;
      if (cell.st && Object.keys(cell.st).length) x.style = clone(cell.st);
    }
    for (let c = 1; c <= sheet.maxC + 2; c++) { const w = sheet.cols[c]; if (w) ws.getColumn(c).width = Math.max(2, (w - 5) / 7); }
    for (const r in sheet.rows) ws.getRow(+r).height = sheet.rows[r] * 0.75;
    sheet.merges.forEach((m) => { try { ws.mergeCells(m[0], m[1], m[2], m[3]); } catch (_) {} });
  }

  /* ---------------- calculation ---------------- */
  let epoch = 0;
  function byName(n) { return S.sheets.find((s) => s.name === n); }
  function getVal(sheet, r, c, stack) {
    const cell = sheet.cells[key(r, c)];
    if (!cell) return null;
    if (cell.f === null || cell.f === undefined) return cell.v === undefined ? null : cell.v;
    if (cell.ep === epoch) return cell.r;
    if (cell.busy) return { err: '#REF!' };
    cell.busy = true;
    const res = FX.evaluate(cell.f, {
      get: (sn, rr, cc) => { const s2 = sn ? byName(sn) : sheet; return s2 ? getVal(s2, rr, cc) : { err: '#REF!' }; },
    });
    cell.busy = false;
    if (res.ok) { cell.r = res.v === undefined ? null : res.v; cell.uns = false; } else { cell.uns = true; if (cell.r === undefined || cell.fresh) cell.r = { err: '#NAME?' }; }
    cell.ep = epoch;
    return cell.r;
  }
  function recalc() {
    epoch++;
    S.sheets.forEach((s) => { for (const k in s.cells) { const c = s.cells[k]; if (c.f !== null && c.f !== undefined) { const [r, cc] = k.split(',').map(Number); getVal(s, r, cc); } } });
  }

  /* ---------------- history ---------------- */
  function snap() { return JSON.stringify({ cur: S.cur, sheets: S.sheets.map((s) => ({ id: s.id, name: s.name, rtl: s.rtl, cols: s.cols, rows: s.rows, merges: s.merges, maxR: s.maxR, maxC: s.maxC, ws: !!s.ws, cells: Object.fromEntries(Object.entries(s.cells).map(([k, c]) => [k, { v: c.v, f: c.f, r: c.r, st: c.st }])) })) }); }
  function restore(js) {
    const o = JSON.parse(js);
    // keep the ExcelJS worksheet handles by name; sheets that exist only in the snapshot get a fresh sheet on save
    const handles = new Map(S.sheets.filter((s) => s.ws).map((s) => [s.id, s.ws]));
    S.sheets = o.sheets.map((d) => ({ id: d.id, name: d.name, rtl: d.rtl, cols: d.cols, rows: d.rows, merges: d.merges, maxR: d.maxR, maxC: d.maxC, cells: d.cells, ws: handles.get(d.id) || null }));
    S.cur = Math.min(o.cur, S.sheets.length - 1);
  }
  function pushUndo() { S.undo.push(snap()); if (S.undo.length > 40) S.undo.shift(); S.redo = []; }
  function changed() { S.dirty = true; A().dirty(true); recalc(); }
  function doUndo() { if (!S.undo.length) return; S.redo.push(snap()); restore(S.undo.pop()); changed(); renderAll(); }
  function doRedo() { if (!S.redo.length) return; S.undo.push(snap()); restore(S.redo.pop()); changed(); renderAll(); }

  /* ---------------- rendering ---------------- */
  function colW(c) { return sh().cols[c] || DEF_W; }
  function mergeAt(r, c) { return sh().merges.find((m) => r >= m[0] && r <= m[2] && c >= m[1] && c <= m[3]); }
  function cellStyleCss(cell) {
    const st = (cell && cell.st) || {}, f = st.font || {}, css = [];
    if (f.bold) css.push('font-weight:700'); if (f.italic) css.push('font-style:italic');
    const dec = [f.underline ? 'underline' : '', f.strike ? 'line-through' : ''].filter(Boolean).join(' '); if (dec) css.push('text-decoration:' + dec);
    if (f.size) css.push('font-size:' + Math.round(f.size * 1.333) + 'px');
    if (f.color && f.color.argb) css.push('color:#' + f.color.argb.slice(-6));
    if (st.fill && st.fill.type === 'pattern' && st.fill.pattern === 'solid' && st.fill.fgColor && st.fill.fgColor.argb) css.push('background-color:#' + st.fill.fgColor.argb.slice(-6));
    const al = st.alignment || {};
    let h = al.horizontal;
    if (!h || h === 'general') { const v = cell && (cell.f != null ? cell.r : cell.v); h = typeof v === 'number' ? (sh().rtl ? 'left' : 'right') : null; }
    if (h) css.push('text-align:' + (h === 'centerContinuous' ? 'center' : h === 'fill' ? 'left' : h));
    const b = st.border;
    if (b) for (const [side, p] of [['top', 'top'], ['bottom', 'bottom'], ['left', 'left'], ['right', 'right']]) if (b[side] && b[side].style) css.push('border-' + p + ':1px solid #' + (b[side].color && b[side].color.argb ? b[side].color.argb.slice(-6) : '000000'));
    return css.join(';');
  }
  function tdHtml(r, c) {
    const m = mergeAt(r, c);
    if (m && (m[0] !== r || m[1] !== c)) return '';
    const cell = sh().cells[key(r, c)], st = cell && cell.st || {};
    const span = m ? ` rowspan="${m[2] - m[0] + 1}" colspan="${m[3] - m[1] + 1}"` : '';
    const wrap = st.alignment && st.alignment.wrapText ? ' class="wr"' : '';
    const esc = display(cell).replace(/&/g, '&amp;').replace(/</g, '&lt;');
    return `<td data-r="${r}" data-c="${c}"${span}${wrap} style="${cellStyleCss(cell)}">${esc}</td>`;
  }
  function renderAll() {
    const s = sh(); document.documentElement.dir = 'rtl';
    const C = Math.max(s.maxC + 3, 12), R = Math.min(Math.max(S.rowsShown, s.maxR + 5), 20000);
    S.C = C; S.R = R;
    let h = '<colgroup><col style="width:38px">';
    for (let c = 1; c <= C; c++) h += `<col style="width:${colW(c)}px">`;
    h += '</colgroup><thead><tr><th class="corner" data-corner="1"></th>';
    for (let c = 1; c <= C; c++) h += `<th data-hc="${c}">${colName(c)}</th>`;
    h += '</tr></thead><tbody>';
    for (let r = 1; r <= R; r++) {
      h += `<tr style="height:${s.rows[r] || DEF_H}px"><th data-hr="${r}">${r}</th>`;
      for (let c = 1; c <= C; c++) h += tdHtml(r, c);
      h += '</tr>';
    }
    $('tbl').dir = s.rtl ? 'rtl' : 'ltr';
    { let tw = 38; for (let c = 1; c <= C; c++) tw += colW(c); $('tbl').style.width = tw + 'px'; }
    $('tbl').innerHTML = h + '</tbody>';
    renderTabs(); paintSel(); syncBar();
    S.fit = S.fit || {}; if (!S.fit[s.id]) { S.fit[s.id] = true; fitZoom(); }
    spillSoon();
  }
  /** Like Excel on a phone: the first time a sheet is shown, zoom out so that its used columns fit the screen width
   *  (not below 55 %, so the text stays readable; pinch / + / - still work afterwards). */
  function fitZoom() {
    const s = sh(); let w = 38;
    for (let c = 1; c <= Math.min(s.maxC, 30); c++) w += colW(c);
    const vw = ($('wrap') && $('wrap').clientWidth) || window.innerWidth;
    setZoom(Math.max(0.55, Math.min(1, vw / w)));
  }
  /** Text that is longer than its cell goes on over the empty cells next to it (as in Excel) instead of being cut off. */
  let spillT = 0;
  function spillSoon() { clearTimeout(spillT); spillT = setTimeout(spill, 60); }
  function spill() {
    const t = $('tbl'); if (!t) return; const rtl = sh().rtl;
    t.querySelectorAll('td[data-spilled]').forEach((e) => { e.removeAttribute('data-spilled'); e.style.overflow = ''; e.style.position = ''; e.style.zIndex = ''; });
    t.querySelectorAll('td[data-under]').forEach((e) => { e.removeAttribute('data-under'); e.style.backgroundColor = e.dataset.bg || ''; });
    const cand = [];
    t.querySelectorAll('td').forEach((e) => {
      if (e.classList.contains('wr') || !e.textContent || e.colSpan > 1) return;
      const sty = e.getAttribute('style') || '', m = /text-align:(\w+)/.exec(sty);
      if (m && m[1] !== (rtl ? 'right' : 'left')) return;
      if (e.scrollWidth > e.clientWidth + 1) cand.push([e, e.scrollWidth]);
    });
    for (const [e, need] of cand) {
      let have = e.clientWidth, n = e.nextElementSibling; const under = [];
      while (n && n.tagName === 'TD' && have < need && !n.textContent && n.colSpan === 1 && !/background-color/.test(n.getAttribute('style') || '')) { under.push(n); have += n.clientWidth; n = n.nextElementSibling; }
      if (!under.length) continue;
      e.setAttribute('data-spilled', '1'); e.style.overflow = 'visible'; e.style.position = 'relative'; e.style.zIndex = '1';
      under.forEach((u) => { u.setAttribute('data-under', '1'); u.dataset.bg = u.style.backgroundColor || ''; u.style.backgroundColor = 'transparent'; });
    }
  }
  function td(r, c) { return $('tbl').querySelector(`td[data-r="${r}"][data-c="${c}"]`); }
  function repaintCell(r, c) {
    const m = mergeAt(r, c); if (m) { r = m[0]; c = m[1]; }
    const e = td(r, c); if (!e) return;
    const tmp = document.createElement('tbody'); tmp.innerHTML = '<tr>' + tdHtml(r, c) + '</tr>';
    const n = tmp.querySelector('td'); if (n) e.replaceWith(n);
  }
  function repaintRange(r1, c1, r2, c2) { for (let r = r1; r <= r2; r++) for (let c = c1; c <= c2; c++) repaintCell(r, c); paintSel(); spillSoon(); }
  function paintSel() {
    const t = $('tbl');
    t.querySelectorAll('td.sel,td.cur').forEach((e) => e.classList.remove('sel', 'cur'));
    t.querySelectorAll('th.hs').forEach((e) => e.classList.remove('hs'));
    const { r1, c1, r2, c2, ar, ac } = S.sel;
    const lr = Math.min(r2, S.R), lc = Math.min(c2, S.C);
    if ((lr - r1 + 1) * (lc - c1 + 1) <= 6000) for (let r = r1; r <= lr; r++) for (let c = c1; c <= lc; c++) { const e = td(r, c); if (e) e.classList.add('sel'); }
    const cur = td(...mergeOrigin(ar, ac)); if (cur) cur.classList.add('cur');
    for (let c = c1; c <= lc; c++) { const e = t.querySelector(`th[data-hc="${c}"]`); if (e) e.classList.add('hs'); }
    for (let r = r1; r <= lr; r++) { const e = t.querySelector(`th[data-hr="${r}"]`); if (e) e.classList.add('hs'); }
    $('addr').textContent = colName(c1) + r1 + (r2 > r1 || c2 > c1 ? ':' + colName(c2) + r2 : '');
  }
  function mergeOrigin(r, c) { const m = mergeAt(r, c); return m ? [m[0], m[1]] : [r, c]; }
  function renderTabs() {
    const box = $('tabs'); box.innerHTML = '';
    S.sheets.forEach((s, i) => {
      const b = document.createElement('button'); b.textContent = s.name; if (i === S.cur) b.className = 'on';
      b.onclick = () => { if (i === S.cur) tabMenu(b); else { commit(); S.cur = i; S.rowsShown = ROW_CHUNK; S.sel = { ar: 1, ac: 1, r1: 1, c1: 1, r2: 1, c2: 1 }; renderAll(); } };
      box.appendChild(b);
    });
    const add = document.createElement('button'); add.textContent = '＋'; add.onclick = addSheet; box.appendChild(add);
  }
  function rawOf(cell) {
    if (!cell) return '';
    if (cell.f !== null && cell.f !== undefined) return '=' + cell.f;
    const v = cell.v; if (v === null || v === undefined) return '';
    if (typeof v === 'object') return v.err || '';
    const nf = cell.st && cell.st.numFmt;
    if (typeof v === 'number' && nf && isDateFmt(nf)) return display(cell);
    if (typeof v === 'boolean') return v ? 'TRUE' : 'FALSE';
    return String(v);
  }
  function syncBar() {
    const cell = sh().cells[key(S.sel.ar, S.sel.ac)];
    if (document.activeElement !== $('fxin')) $('fxin').value = rawOf(cell);
    const st = (cell && cell.st) || {}, f = st.font || {};
    document.querySelector('[data-a=bold]').classList.toggle('on', !!f.bold);
    document.querySelector('[data-a=italic]').classList.toggle('on', !!f.italic);
    document.querySelector('[data-a=under]').classList.toggle('on', !!f.underline);
    document.querySelector('[data-a=wrap]').classList.toggle('on', !!(st.alignment && st.alignment.wrapText));
    document.querySelector('[data-a=range]').classList.toggle('on', S.rangeMode);
    document.querySelector('[data-a=merge]').classList.toggle('on', !!mergeAt(S.sel.ar, S.sel.ac));
    const nf = st.numFmt || 'General'; const sel = $('nf'); sel.value = [...sel.options].some((o) => o.value === nf) ? nf : 'General';
  }

  /* ---------------- input parsing / editing ---------------- */
  function parseInput(txt, cell) {
    const prev = cell && cell.st && cell.st.numFmt;
    let t = txt;
    if (t === '') return { v: null, f: null };
    if (t[0] === '=') return { v: null, f: t.slice(1), r: null, fresh: true };
    if (prev === '@') return { v: t, f: null };
    if (/^(true|false)$/i.test(t)) return { v: /^true$/i.test(t), f: null };
    const n = t.trim().replace(/,/g, '');
    if (/^[-+]?(\d+\.?\d*|\.\d+)(e[-+]?\d+)?$/i.test(n) && !/^0\d+$/.test(n)) return { v: parseFloat(n), f: null };
    if (/^[-+]?(\d+\.?\d*|\.\d+)%$/.test(n)) return { v: parseFloat(n) / 100, f: null, nf: /\./.test(n) ? '0.00%' : '0%' };
    const d = /^(\d{1,2})[./](\d{1,2})[./](\d{2,4})$/.exec(t.trim());
    if (d) { let y = +d[3]; if (y < 100) y += 2000; const dt = new Date(y, +d[2] - 1, +d[1]); if (dt.getMonth() === +d[2] - 1) return { v: dateToSerial(dt), f: null, nf: 'dd/mm/yyyy' }; }
    const tm = /^(\d{1,2}):(\d{2})(?::(\d{2}))?$/.exec(t.trim());
    if (tm && +tm[1] < 24 && +tm[2] < 60) return { v: (+tm[1] * 3600 + +tm[2] * 60 + (+tm[3] || 0)) / 86400, f: null, nf: 'hh:mm' };
    return { v: t, f: null };
  }
  function ensureCell(r, c) { const k = key(r, c); const s = sh(); if (!s.cells[k]) s.cells[k] = { v: null, f: null, r: null, st: {} }; if (r > s.maxR) s.maxR = r; if (c > s.maxC) s.maxC = c; return s.cells[k]; }
  function setCellText(r, c, txt, noUndo) {
    const cur = sh().cells[key(r, c)];
    if (rawOf(cur) === txt) return false;
    if (!noUndo) pushUndo();
    const cell = ensureCell(r, c), p = parseInput(txt, cell);
    cell.v = p.v; cell.f = p.f; cell.r = p.r === undefined ? null : p.r; cell.fresh = !!p.fresh; cell.ep = 0;
    if (p.nf && !(cell.st.numFmt && cell.st.numFmt !== 'General')) cell.st.numFmt = p.nf;
    if (p.v === null && p.f === null && !Object.keys(cell.st).length) delete sh().cells[key(r, c)];
    return true;
  }
  function startEdit(initial) {
    const [r, c] = mergeOrigin(S.sel.ar, S.sel.ac), e = td(r, c); if (!e) return;
    const ed = $('ed'), g = $('grid').getBoundingClientRect(), b = e.getBoundingClientRect();
    ed.style.display = 'block'; S.editing = true;
    ed.style.left = (b.left - g.left) / S.zoom + 'px'; ed.style.top = (b.top - g.top) / S.zoom + 'px';
    ed.style.width = Math.max(b.width / S.zoom, 110) + 'px'; ed.style.height = b.height / S.zoom + 'px';
    ed.value = initial !== undefined ? initial : rawOf(sh().cells[key(r, c)]);
    ed.dataset.r = r; ed.dataset.c = c; ed.focus(); const l = ed.value.length; ed.setSelectionRange(l, l);
  }
  function commit(move) {
    const ed = $('ed'); if (!S.editing) return;
    S.editing = false; ed.style.display = 'none';
    const r = +ed.dataset.r, c = +ed.dataset.c;
    if (setCellText(r, c, ed.value)) { changed(); repaintAll(); }
    if (move) selectCell(Math.min(S.sel.ar + 1, 20000), S.sel.ac);
  }
  function cancelEdit() { S.editing = false; $('ed').style.display = 'none'; }
  function repaintAll() { // values may change anywhere because of formulas: refresh the visible window cheaply
    const t = $('tbl'); t.querySelectorAll('td').forEach((e) => { const r = +e.dataset.r, c = +e.dataset.c; const cell = sh().cells[key(r, c)]; const txt = display(cell); if (e.textContent !== txt) e.textContent = txt; });
    paintSel(); syncBar();
  }
  function selectCell(r, c, extend) {
    r = Math.max(1, r); c = Math.max(1, c);
    if (r > S.R - 2) { S.rowsShown = r + ROW_CHUNK; renderAll(); }
    if (c > S.C - 1) { sh().maxC = Math.max(sh().maxC, c); renderAll(); }
    if (extend) { S.sel.r1 = Math.min(S.sel.ar, r); S.sel.r2 = Math.max(S.sel.ar, r); S.sel.c1 = Math.min(S.sel.ac, c); S.sel.c2 = Math.max(S.sel.ac, c); }
    else S.sel = { ar: r, ac: c, r1: r, c1: c, r2: r, c2: c };
    const m = mergeAt(r, c); if (m && !extend) { S.sel.r1 = m[0]; S.sel.c1 = m[1]; S.sel.r2 = m[2]; S.sel.c2 = m[3]; }
    paintSel(); syncBar();
    const e = td(...mergeOrigin(r, c)); if (e) e.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }

  /* ---------------- operations on the selection ---------------- */
  function eachSel(fn) { const { r1, c1, r2, c2 } = S.sel; for (let r = r1; r <= r2; r++) for (let c = c1; c <= c2; c++) fn(ensureCell(r, c), r, c); }
  function styleOp(fn) {
    commit(); pushUndo(); const { r1, c1, r2, c2 } = S.sel;
    if ((r2 - r1 + 1) * (c2 - c1 + 1) > 20000) { toast('הטווח גדול מדי'); return; }
    eachSel((cell) => { cell.st = cell.st || {}; fn(cell.st); });
    S.dirty = true; A().dirty(true); renderAll();
  }
  function toggleFont(prop) {
    const cell = sh().cells[key(S.sel.ar, S.sel.ac)], on = !(cell && cell.st && cell.st.font && cell.st.font[prop]);
    styleOp((st) => { st.font = Object.assign({}, st.font || {}, { [prop]: on }); });
  }
  function setAlign(h) { styleOp((st) => { st.alignment = Object.assign({}, st.alignment || {}, { horizontal: h }); }); }
  function toggleWrap() {
    const cell = sh().cells[key(S.sel.ar, S.sel.ac)], on = !(cell && cell.st && cell.st.alignment && cell.st.alignment.wrapText);
    styleOp((st) => { st.alignment = Object.assign({}, st.alignment || {}, { wrapText: on, vertical: 'top' }); });
  }
  function toggleBorder() {
    const cell = sh().cells[key(S.sel.ar, S.sel.ac)], on = !(cell && cell.st && cell.st.border && cell.st.border.top);
    const b = { style: 'thin', color: { argb: 'FF000000' } };
    styleOp((st) => { if (on) st.border = { top: b, bottom: b, left: b, right: b }; else delete st.border; });
  }
  function setNumFmt(nf) { styleOp((st) => { if (nf === 'General') delete st.numFmt; else st.numFmt = nf; }); }
  function clearSel() { commit(); pushUndo(); eachSel((cell, r, c) => { delete sh().cells[key(r, c)]; }); changed(); renderAll(); }
  function toggleMerge() {
    commit(); const { r1, c1, r2, c2 } = S.sel, s = sh(); pushUndo();
    const ex = mergeAt(S.sel.ar, S.sel.ac);
    if (ex) s.merges = s.merges.filter((m) => m !== ex);
    else if (r2 > r1 || c2 > c1) {
      s.merges = s.merges.filter((m) => !(m[0] >= r1 && m[2] <= r2 && m[1] >= c1 && m[3] <= c2));
      for (let r = r1; r <= r2; r++) for (let c = c1; c <= c2; c++) if ((r !== r1 || c !== c1)) delete s.cells[key(r, c)];
      s.merges.push([r1, c1, r2, c2]);
    } else { toast('בחר טווח כדי למזג'); S.undo.pop(); return; }
    S.dirty = true; A().dirty(true); renderAll();
  }
  function autoSum() {
    commit(); const { r1, c1, r2, c2 } = S.sel;
    pushUndo();
    if (r2 > r1 || c1 === c2) { const t = r2 + 1; setCellText(t, c1, `=SUM(${colName(c1)}${r1}:${colName(c1)}${r2})`, true); selectCell(t, c1); }
    else { const t = c2 + 1; setCellText(r1, t, `=SUM(${colName(c1)}${r1}:${colName(c2)}${r1})`, true); selectCell(r1, t); }
    changed(); renderAll();
  }
  function sortSel(desc) {
    commit(); const { r1, c1, r2, c2 } = S.sel, s = sh();
    if (r2 <= r1) { toast('בחר טווח של כמה שורות'); return; }
    const rows = []; for (let r = r1; r <= r2; r++) { const row = []; for (let c = c1; c <= c2; c++) { const x = s.cells[key(r, c)]; if (x && x.f != null) { toast('המיון לא זמין לטווח עם נוסחאות'); return; } row.push(x ? clone(x) : null); } rows.push(row); }
    pushUndo();
    const kv = (row) => { const x = row[0]; return x && x.v !== null && x.v !== undefined ? x.v : null; };
    rows.sort((a, b) => { const x = kv(a), y = kv(b); if (x === null) return y === null ? 0 : 1; if (y === null) return -1; const r = (typeof x === 'number' && typeof y === 'number') ? x - y : String(x).localeCompare(String(y), 'he'); return desc ? -r : r; });
    rows.forEach((row, i) => row.forEach((x, j) => { const k = key(r1 + i, c1 + j); if (x) s.cells[k] = x; else delete s.cells[k]; }));
    changed(); renderAll();
  }

  /* structural changes: insert/delete rows or columns; formulas follow */
  function restructure(axis, at, delta) {
    commit(); pushUndo(); const s = sh(), idx = axis === 'row' ? 0 : 1;
    const nc = {};
    for (const k in s.cells) {
      const p = k.split(',').map(Number); let v = p[idx];
      if (v >= at) { if (delta < 0 && v < at - delta) continue; v += delta; }
      p[idx] = v; nc[key(p[0], p[1])] = s.cells[k];
    }
    s.cells = nc;
    const dims = axis === 'row' ? s.rows : s.cols, nd = {};
    for (const k in dims) { let v = +k; if (v >= at) { if (delta < 0 && v < at - delta) continue; v += delta; } nd[v] = dims[k]; }
    if (axis === 'row') s.rows = nd; else s.cols = nd;
    s.merges = s.merges.map((m) => { const n = m.slice(); const a = axis === 'row' ? 0 : 1, b = a + 2; if (n[a] >= at) n[a] += delta; if (n[b] >= at) n[b] += delta; if (delta < 0 && n[b] < n[a]) return null; return n; }).filter(Boolean);
    S.sheets.forEach((sheet) => { for (const k in sheet.cells) { const c = sheet.cells[k]; if (c.f != null) c.f = FX.shift(c.f, axis, at, delta, sheet.name, s.name); } });
    if (axis === 'row') s.maxR = Math.max(1, s.maxR + delta); else s.maxC = Math.max(1, s.maxC + delta);
    changed(); renderAll();
  }
  function setColWidth(d) { commit(); pushUndo(); for (let c = S.sel.c1; c <= S.sel.c2; c++) sh().cols[c] = Math.max(30, Math.min(500, colW(c) + d)); S.dirty = true; A().dirty(true); renderAll(); }
  function setRowHeight(d) { commit(); pushUndo(); for (let r = S.sel.r1; r <= S.sel.r2; r++) sh().rows[r] = Math.max(16, Math.min(400, (sh().rows[r] || DEF_H) + d)); S.dirty = true; A().dirty(true); renderAll(); }

  /* sheets */
  function dlg(title, init, ok) {
    $('dtitle').textContent = title; $('dinp').value = init; $('dlg').classList.add('show'); $('dinp').focus();
    $('dok').onclick = () => { const v = $('dinp').value.trim(); $('dlg').classList.remove('show'); ok(v); };
    $('dno').onclick = () => $('dlg').classList.remove('show');
  }
  function addSheet() {
    commit(); pushUndo(); let n = S.sheets.length + 1, name; do { name = 'גיליון' + n++; } while (byName(name));
    S.sheets.push({ id: ++idc, name, rtl: sh().rtl, cols: {}, rows: {}, cells: {}, merges: [], maxR: 1, maxC: 1, ws: null });
    S.cur = S.sheets.length - 1; S.rowsShown = ROW_CHUNK; S.sel = { ar: 1, ac: 1, r1: 1, c1: 1, r2: 1, c2: 1 }; S.dirty = true; A().dirty(true); renderAll();
  }
  function tabMenu(anchor) {
    pop([
      ['✏️ שנה שם', () => dlg('שם הגיליון', sh().name, (v) => { if (!v || (byName(v) && byName(v) !== sh())) return toast('שם לא תקין או קיים'); pushUndo(); const old = sh().name; sh().name = v; S.sheets.forEach((x) => { for (const k in x.cells) { const c = x.cells[k]; if (c.f) c.f = c.f.replace(new RegExp("(^|[^\\w'])" + old.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '!', 'g'), "$1'" + v.replace(/'/g, "''") + "'!"); } }); changed(); renderAll(); })],
      ['↔️ כיוון: ימין לשמאל / שמאל לימין', () => { pushUndo(); sh().rtl = !sh().rtl; S.dirty = true; A().dirty(true); renderAll(); }],
      ['🗑 מחק גיליון', () => { if (S.sheets.length < 2) return toast('חייב להישאר גיליון אחד'); pushUndo(); const d = S.sheets.splice(S.cur, 1)[0]; if (d.ws) S.removed.push(d.ws); S.cur = Math.max(0, S.cur - 1); changed(); renderAll(); }, true],
    ], anchor);
  }

  /* popups */
  const PALETTE = ['000000', '434343', '666666', '999999', 'CCCCCC', 'EFEFEF', 'F3F3F3', 'FFFFFF', 'E5484D', 'FF9900', 'FFE599', '30A46C', '00A3A3', '4F46E5', '8E4EC6', 'E93D82', 'F4CCCC', 'FCE5CD', 'FFF2CC', 'D9EAD3', 'D0E0E3', 'C9DAF8', 'D9D2E9', 'EAD1DC', '990000', 'B45F06', '7F6000', '274E13', '0C343D', '1C4587', '20124D', '4C1130'];
  function closePop() { $('pop').classList.remove('show'); }
  function place(anchor) { const p = $('pop'), b = anchor.getBoundingClientRect(); p.classList.add('show'); const ph = p.offsetHeight; p.style.top = Math.max(8, b.top - ph - 6) + 'px'; p.style.left = Math.max(6, Math.min(innerWidth - p.offsetWidth - 6, b.left)) + 'px'; }
  function pop(items, anchor) {
    const p = $('pop'); p.innerHTML = '';
    items.forEach(([label, fn, danger]) => { const b = document.createElement('button'); b.className = 'it' + (danger ? ' dng' : ''); b.textContent = label; b.onclick = () => { closePop(); fn(); }; p.appendChild(b); });
    place(anchor);
  }
  function palette(anchor, cb) {
    const p = $('pop'); p.innerHTML = ''; const g = document.createElement('div'); g.className = 'pal';
    const none = document.createElement('i'); none.className = 'none'; none.onclick = () => { closePop(); cb(null); }; g.appendChild(none);
    PALETTE.forEach((c) => { const i = document.createElement('i'); i.style.background = '#' + c; i.onclick = () => { closePop(); cb(c); }; g.appendChild(i); });
    p.appendChild(g); place(anchor);
  }
  function toast(m) { const t = $('toast'); t.textContent = m; t.style.display = 'block'; clearTimeout(toast._t); toast._t = setTimeout(() => (t.style.display = 'none'), 2200); }

  /* ---------------- events ---------------- */
  function bind() {
    const tbl = $('tbl'), wrap = $('wrap'), ed = $('ed'), fx = $('fxin');
    // selecting cells / headers
    tbl.addEventListener('pointerdown', (e) => {
      const t = e.target.closest('td,th'); if (!t) return;
      // while typing a formula, tapping a cell inserts its reference
      if (S.editing && /^=/.test(ed.value) && /[=+\-*/(,:&<>^]$/.test(ed.value) && t.tagName === 'TD') {
        e.preventDefault(); ed.value += colName(+t.dataset.c) + t.dataset.r; ed.focus(); return;
      }
      if (S.editing) commit();
      if (t.dataset.corner) { S.sel = { ar: 1, ac: 1, r1: 1, c1: 1, r2: Math.max(sh().maxR, 1), c2: Math.max(sh().maxC, 1) }; paintSel(); syncBar(); return; }
      if (t.dataset.hc) { const c = +t.dataset.hc; S.sel = { ar: 1, ac: c, r1: 1, c1: c, r2: Math.max(sh().maxR, 1), c2: c }; paintSel(); syncBar(); return; }
      if (t.dataset.hr) { const r = +t.dataset.hr; S.sel = { ar: r, ac: 1, r1: r, c1: 1, r2: r, c2: Math.max(sh().maxC, 1) }; paintSel(); syncBar(); return; }
      const r = +t.dataset.r, c = +t.dataset.c;
      if (S.rangeMode) { selectCell(r, c, true); return; }
      const same = S.sel.ar === r && S.sel.ac === c && S.sel.r1 === S.sel.r2 && S.sel.c1 === S.sel.c2;
      S.tapSame = same; if (!same) selectCell(r, c);
    });
    // editing starts on click (after the focus change of the tap), otherwise the editor loses focus immediately
    tbl.addEventListener('click', (e) => { const t = e.target.closest('td'); if (t && S.tapSame && !S.rangeMode) { S.tapSame = false; startEdit(); } });
    ed.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); commit(true); }
      else if (e.key === 'Escape') { cancelEdit(); }
      else if (e.key === 'Tab') { e.preventDefault(); commit(); selectCell(S.sel.ar, S.sel.ac + (sh().rtl ? 1 : 1)); }
    });
    ed.addEventListener('input', () => { fx.value = ed.value; });
    ed.addEventListener('blur', () => { setTimeout(() => { if (S.editing && document.activeElement !== fx && document.activeElement !== ed) commit(); }, 80); });
    fx.addEventListener('focus', () => { if (!S.editing) { const [r, c] = mergeOrigin(S.sel.ar, S.sel.ac); S.fxEdit = { r, c }; } });
    fx.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); applyFx(); fx.blur(); } });
    fx.addEventListener('blur', () => { if (S.fxEdit) applyFx(); });
    function applyFx() {
      const f = S.fxEdit; if (!f) return; S.fxEdit = null;
      if (S.editing) { S.editing = false; ed.style.display = 'none'; }
      if (setCellText(f.r, f.c, fx.value)) { changed(); repaintAll(); }
    }
    // toolbar
    document.querySelectorAll('#tb button').forEach((b) => b.addEventListener('pointerdown', (e) => e.preventDefault()));
    document.getElementById('tb').addEventListener('click', (e) => {
      const b = e.target.closest('button'); if (!b) return; const a = b.dataset.a;
      closePop();
      if (a === 'undo') doUndo(); else if (a === 'redo') doRedo();
      else if (a === 'bold') toggleFont('bold'); else if (a === 'italic') toggleFont('italic'); else if (a === 'under') toggleFont('underline');
      else if (a === 'fc') palette(b, (c) => styleOp((st) => { st.font = Object.assign({}, st.font || {}, c ? { color: { argb: 'FF' + c } } : { color: undefined }); if (!c) delete st.font.color; }));
      else if (a === 'bg') palette(b, (c) => styleOp((st) => { if (c) st.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FF' + c } }; else delete st.fill; }));
      else if (a === 'border') toggleBorder();
      else if (a === 'al-l') setAlign('left'); else if (a === 'al-c') setAlign('center'); else if (a === 'al-r') setAlign('right');
      else if (a === 'wrap') toggleWrap(); else if (a === 'merge') toggleMerge(); else if (a === 'sum') autoSum();
      else if (a === 'clear') clearSel();
      else if (a === 'range') { S.rangeMode = !S.rangeMode; syncBar(); toast(S.rangeMode ? 'מצב טווח: הקש על התא האחרון בטווח' : 'מצב טווח כבוי'); }
      else if (a === 'zin') setZoom(S.zoom * 1.15); else if (a === 'zout') setZoom(S.zoom / 1.15);
      else if (a === 'sort') pop([['מיין א → ת (לפי העמודה הראשונה)', () => sortSel(false)], ['מיין ת → א', () => sortSel(true)]], b);
      else if (a === 'rows') pop([
        ['➕ הוסף שורה מעל', () => restructure('row', S.sel.r1, S.sel.r2 - S.sel.r1 + 1)],
        ['➕ הוסף שורה מתחת', () => restructure('row', S.sel.r2 + 1, S.sel.r2 - S.sel.r1 + 1)],
        ['➕ הוסף עמודה לפני', () => restructure('col', S.sel.c1, S.sel.c2 - S.sel.c1 + 1)],
        ['➕ הוסף עמודה אחרי', () => restructure('col', S.sel.c2 + 1, S.sel.c2 - S.sel.c1 + 1)],
        ['↔ הרחב עמודה', () => setColWidth(24)], ['↔ צמצם עמודה', () => setColWidth(-24)],
        ['↕ הגבה שורה', () => setRowHeight(10)], ['↕ הנמך שורה', () => setRowHeight(-10)],
        ['🗑 מחק שורות נבחרות', () => restructure('row', S.sel.r1, -(S.sel.r2 - S.sel.r1 + 1)), true],
        ['🗑 מחק עמודות נבחרות', () => restructure('col', S.sel.c1, -(S.sel.c2 - S.sel.c1 + 1)), true],
      ], b);
    });
    $('nf').addEventListener('change', () => setNumFmt($('nf').value));
    document.addEventListener('pointerdown', (e) => { if (!e.target.closest('#pop') && !e.target.closest('#tb') && !e.target.closest('#tabs')) closePop(); }, true);
    // load more rows on scroll
    // (only a real downward scroll by the user adds rows; re-rendering itself must never trigger more rendering)
    let lastTop = 0, busy = false;
    wrap.addEventListener('scroll', () => {
      if (busy) return;
      const top = wrap.scrollTop, down = top > lastTop + 4; lastTop = top;
      if (down && top + wrap.clientHeight > wrap.scrollHeight - 300 && S.R < Math.min(20000, sh().maxR + 400)) {
        busy = true; S.rowsShown = S.R + ROW_CHUNK; renderAll(); wrap.scrollTop = top; lastTop = wrap.scrollTop;
        setTimeout(() => { busy = false; }, 250);
      }
    }, { passive: true });
    // pinch zoom
    let d0 = 0, z0 = 1;
    const dist = (t) => Math.hypot(t[0].clientX - t[1].clientX, t[0].clientY - t[1].clientY);
    wrap.addEventListener('touchstart', (e) => { if (e.touches.length === 2) { d0 = dist(e.touches); z0 = S.zoom; } else d0 = 0; }, { passive: true });
    wrap.addEventListener('touchmove', (e) => { if (e.touches.length === 2 && d0) { e.preventDefault(); setZoom(z0 * dist(e.touches) / d0); } }, { passive: false });
    wrap.addEventListener('touchend', () => { d0 = 0; }, { passive: true });
  }
  function setZoom(z) { S.zoom = Math.max(0.5, Math.min(2.5, z)); $('grid').style.zoom = S.zoom; }

  /* ---------------- open / save ---------------- */
  function b64ToBuf(b) { const bin = atob(b), u = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i); return u.buffer; }
  function bufToB64(u8) { let s = ''; const CH = 0x8000; for (let i = 0; i < u8.length; i += CH) s += String.fromCharCode.apply(null, u8.subarray(i, i + CH)); return btoa(s); }
  async function open(dark) {
    S.dark = !!dark; document.documentElement.classList.toggle('dark', !!dark);
    try {
      const b64 = A().docData();
      const wb = new ExcelJS.Workbook();
      if (b64) await wb.xlsx.load(b64ToBuf(b64)); else wb.addWorksheet('גיליון1', { views: [{ rightToLeft: true }] });
      if (!wb.worksheets.length) wb.addWorksheet('גיליון1', { views: [{ rightToLeft: true }] });
      S.wb = wb; S.sheets = wb.worksheets.map(fromWs);
      S.sheets.forEach((s) => { for (const k in s.cells) { const c = s.cells[k]; if (c.f != null) c.ep = 0; } });
      recalc(); S.cur = 0; S.rowsShown = ROW_CHUNK; S.dirty = false; S.undo = []; S.redo = [];
      renderAll(); A().opened();
    } catch (e) { A().toast('לא הצלחתי לפתוח את הקובץ: ' + (e && e.message || e)); }
  }
  async function save() {
    try {
      commit();
      const wb = S.wb;
      S.removed.forEach((ws) => { try { wb.removeWorksheet(ws.id); } catch (_) {} }); S.removed = [];
      S.sheets.forEach((s) => toWs(s, wb));
      wb.calcProperties = Object.assign({}, wb.calcProperties, { fullCalcOnLoad: true });
      const buf = await wb.xlsx.writeBuffer();
      A().docSaved(bufToB64(new Uint8Array(buf)));
      S.dirty = false; A().dirty(false);
    } catch (e) { A().toast('השמירה נכשלה: ' + (e && e.message || e)); A().saveFailed(); }
  }
  window.SE = { open, save, _S: S, _setText: (r, c, t) => { setCellText(r, c, t); changed(); renderAll(); } };
  window.addEventListener('error', (e) => { try { A().toast('שגיאה: ' + (e.message || e)); } catch (_) {} });
  bind();
  A().ready();
})();
