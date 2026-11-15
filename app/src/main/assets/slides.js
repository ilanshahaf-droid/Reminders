/* PowerPoint (pptx) viewer / light editor. Works directly on the OOXML parts (JSZip) so everything we don't touch is preserved. */
(() => {
  'use strict';
  const NSA = 'http://schemas.openxmlformats.org/drawingml/2006/main';
  const NSP = 'http://schemas.openxmlformats.org/presentationml/2006/main';
  const NSR = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships';
  const NSREL = 'http://schemas.openxmlformats.org/package/2006/relationships';
  const NSCT = 'http://schemas.openxmlformats.org/package/2006/content-types';
  const T_SLIDE = 'application/vnd.openxmlformats-officedocument.presentationml.slide+xml';
  const RT = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships/';
  const $ = (id) => document.getElementById(id);
  const A = () => window.Android || { ready() {}, docData() { return ''; }, docSaved() {}, saveFailed() {}, dirty() {}, toast() {}, opened() {} };
  const parser = new DOMParser(), ser = new XMLSerializer();
  const S = { zip: null, docs: {}, rels: {}, mod: new Set(), order: [], cur: 0, dark: false, dirty: false, zoom: 1, urls: {}, sz: { cx: 9144000, cy: 5143500 }, pres: null, ed: null, present: false };

  /* ---------------- xml helpers ---------------- */
  const kids = (el, name) => (el ? Array.from(el.children).filter((c) => c.localName === name) : []);
  const kid = (el, name) => (el ? Array.from(el.children).find((c) => c.localName === name) || null : null);
  const path = (el, ...names) => { let c = el; for (const n of names) { c = kid(c, n); if (!c) return null; } return c; };
  const num = (v, d) => { const n = parseFloat(v); return isNaN(n) ? d : n; };
  const dirOf = (p) => p.slice(0, p.lastIndexOf('/') + 1);
  const baseOf = (p) => p.slice(p.lastIndexOf('/') + 1);
  function resolve(base, target) {
    if (target[0] === '/') return target.slice(1);
    const parts = (dirOf(base) + target).split('/'), out = [];
    for (const s of parts) { if (s === '..') out.pop(); else if (s !== '.' && s !== '') out.push(s); }
    return out.join('/');
  }
  const relsPath = (p) => dirOf(p) + '_rels/' + baseOf(p) + '.rels';
  async function getDoc(p) {
    if (S.docs[p]) return S.docs[p];
    const f = S.zip.file(p); if (!f) return null;
    S.docs[p] = parser.parseFromString(await f.async('string'), 'application/xml');
    return S.docs[p];
  }
  async function loadRels(p) {
    const rp = relsPath(p);
    if (S.rels[p]) return S.rels[p];
    const d = await getDoc(rp), m = {};
    if (d) for (const r of Array.from(d.documentElement.children)) {
      m[r.getAttribute('Id')] = { type: (r.getAttribute('Type') || '').split('/').pop(), target: r.getAttribute('TargetMode') === 'External' ? null : resolve(p, r.getAttribute('Target')), raw: r.getAttribute('Target') };
    }
    S.rels[p] = m; return m;
  }
  const relOfType = (p, type) => Object.values(S.rels[p] || {}).find((r) => r.type === type);
  function touch(p) { S.mod.add(p); markDirty(); }
  function markDirty() { if (!S.dirty) { S.dirty = true; A().dirty(true); } }

  /* ---------------- package loading ---------------- */
  async function loadSlideDeps(sp) {
    await getDoc(sp); await loadRels(sp);
    const lay = relOfType(sp, 'slideLayout'); if (!lay) return;
    await getDoc(lay.target); await loadRels(lay.target);
    const mas = relOfType(lay.target, 'slideMaster'); if (!mas) return;
    await getDoc(mas.target); await loadRels(mas.target);
    const th = relOfType(mas.target, 'theme'); if (th) await getDoc(th.target);
  }
  async function loadPackage(buf) {
    S.zip = await JSZip.loadAsync(buf); S.docs = {}; S.rels = {}; S.mod = new Set(); S.urls = {};
    const pp = 'ppt/presentation.xml';
    S.pres = await getDoc(pp); const prels = await loadRels(pp);
    if (!S.pres) throw new Error('presentation.xml חסר');
    const sz = path(S.pres.documentElement, 'sldSz'); if (sz) S.sz = { cx: num(sz.getAttribute('cx'), 9144000), cy: num(sz.getAttribute('cy'), 5143500) };
    S.order = [];
    for (const s of kids(kid(S.pres.documentElement, 'sldIdLst'), 'sldId')) {
      const r = prels[s.getAttributeNS(NSR, 'id')]; if (r && r.target) S.order.push({ path: r.target, el: s });
    }
    for (const o of S.order) await loadSlideDeps(o.path);
  }

  /* ---------------- colours ---------------- */
  const hex2rgb = (h) => [parseInt(h.slice(0, 2), 16) || 0, parseInt(h.slice(2, 4), 16) || 0, parseInt(h.slice(4, 6), 16) || 0];
  function rgb2hsl(r, g, b) {
    r /= 255; g /= 255; b /= 255;
    const mx = Math.max(r, g, b), mn = Math.min(r, g, b); let h = 0, s = 0; const l = (mx + mn) / 2;
    if (mx !== mn) { const d = mx - mn; s = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn); h = mx === r ? (g - b) / d + (g < b ? 6 : 0) : mx === g ? (b - r) / d + 2 : (r - g) / d + 4; h /= 6; }
    return [h, s, l];
  }
  function hsl2rgb(h, s, l) {
    if (s === 0) { const v = Math.round(l * 255); return [v, v, v]; }
    const q = l < 0.5 ? l * (1 + s) : l + s - l * s, p = 2 * l - q;
    const f = (t) => { if (t < 0) t += 1; if (t > 1) t -= 1; return t < 1 / 6 ? p + (q - p) * 6 * t : t < 1 / 2 ? q : t < 2 / 3 ? p + (q - p) * (2 / 3 - t) * 6 : p; };
    return [Math.round(f(h + 1 / 3) * 255), Math.round(f(h) * 255), Math.round(f(h - 1 / 3) * 255)];
  }
  const PRST = { black: [0, 0, 0], white: [255, 255, 255], red: [255, 0, 0], green: [0, 128, 0], blue: [0, 0, 255], yellow: [255, 255, 0], gray: [128, 128, 128], grey: [128, 128, 128] };
  const CLRNAMES = ['dk1', 'lt1', 'dk2', 'lt2', 'accent1', 'accent2', 'accent3', 'accent4', 'accent5', 'accent6', 'hlink', 'folHlink'];
  function themeOf(ctx) {
    const cache = ctx._th; if (cache) return cache;
    const th = {}; let doc = null;
    if (ctx.master) { const r = relOfType(ctx.master, 'theme'); if (r) doc = S.docs[r.target]; }
    const cs = doc && doc.getElementsByTagNameNS(NSA, 'clrScheme')[0];
    if (cs) for (const n of CLRNAMES) {
      const e = kid(cs, n), c = e && e.children[0]; if (!c) continue;
      th[n] = c.localName === 'sysClr' ? (c.getAttribute('lastClr') || '000000') : (c.getAttribute('val') || '000000');
    }
    const fs = doc && doc.getElementsByTagNameNS(NSA, 'fontScheme')[0];
    th._major = fs && path(fs, 'majorFont', 'latin') ? path(fs, 'majorFont', 'latin').getAttribute('typeface') : '';
    th._minor = fs && path(fs, 'minorFont', 'latin') ? path(fs, 'minorFont', 'latin').getAttribute('typeface') : '';
    ctx._th = th; return th;
  }
  function schemeHex(name, ctx) {
    const th = themeOf(ctx); let n = name;
    const map = ctx.masterDoc && kid(ctx.masterDoc.documentElement, 'clrMap');
    const alias = { bg1: 'lt1', tx1: 'dk1', bg2: 'lt2', tx2: 'dk2' };
    if (alias[n]) n = map && map.getAttribute(n) ? map.getAttribute(n) : alias[n];
    return th[n] || (n === 'lt1' ? 'FFFFFF' : n === 'dk1' ? '000000' : '808080');
  }
  function clr(parent, ctx) {
    const c = parent && Array.from(parent.children).find((k) => /^(srgbClr|schemeClr|sysClr|prstClr)$/.test(k.localName)); if (!c) return null;
    let rgb; const v = c.getAttribute('val'); let alpha = 1;
    if (c.localName === 'srgbClr') rgb = hex2rgb(v || '000000');
    else if (c.localName === 'sysClr') rgb = hex2rgb(c.getAttribute('lastClr') || '000000');
    else if (c.localName === 'prstClr') rgb = PRST[v] || [0, 0, 0];
    else rgb = hex2rgb(schemeHex(v === 'phClr' ? 'accent1' : v, ctx));
    for (const m of Array.from(c.children)) {
      const val = num(m.getAttribute('val'), 0) / 100000;
      if (m.localName === 'lumMod' || m.localName === 'lumOff') {
        const hsl = rgb2hsl(...rgb);
        if (m.localName === 'lumMod') hsl[2] = Math.min(1, hsl[2] * val); else hsl[2] = Math.min(1, hsl[2] + val);
        rgb = hsl2rgb(...hsl);
      } else if (m.localName === 'tint') rgb = rgb.map((x) => Math.round(x * val + 255 * (1 - val)));
      else if (m.localName === 'shade') rgb = rgb.map((x) => Math.round(x * val));
      else if (m.localName === 'alpha') alpha = val;
    }
    return alpha < 1 ? `rgba(${rgb[0]},${rgb[1]},${rgb[2]},${alpha})` : `rgb(${rgb[0]},${rgb[1]},${rgb[2]})`;
  }
  function gradCss(g, ctx) {
    const stops = kids(kid(g, 'gsLst'), 'gs').map((s) => `${clr(s, ctx) || '#888'} ${num(s.getAttribute('pos'), 0) / 1000}%`);
    if (!stops.length) return null;
    if (stops.length === 1) stops.push(stops[0]);
    const lin = kid(g, 'lin'), ang = lin ? num(lin.getAttribute('ang'), 0) / 60000 : 90;
    return `linear-gradient(${Math.round(ang + 90)}deg,${stops.join(',')})`;
  }
  /** returns {bg, line:{w,color,dash}} for a shape's spPr (+ style) */
  function paint(spPr, style, ctx) {
    let bg = null, line = null;
    const fills = spPr ? Array.from(spPr.children) : [];
    const sf = fills.find((c) => c.localName === 'solidFill'), gf = fills.find((c) => c.localName === 'gradFill'), nf = fills.find((c) => c.localName === 'noFill');
    if (sf) bg = clr(sf, ctx); else if (gf) bg = gradCss(gf, ctx); else if (!nf && style) { const fr = kid(style, 'fillRef'); if (fr && num(fr.getAttribute('idx'), 0) > 0) bg = clr(fr, ctx); }
    const ln = spPr && kid(spPr, 'ln');
    if (ln && kid(ln, 'noFill')) line = null;
    else if (ln && kid(ln, 'solidFill')) line = { w: num(ln.getAttribute('w'), 12700), color: clr(kid(ln, 'solidFill'), ctx), dash: path(ln, 'prstDash') ? path(ln, 'prstDash').getAttribute('val') : '' };
    else if (style) { const lr = kid(style, 'lnRef'); if (lr && num(lr.getAttribute('idx'), 0) > 0) line = { w: ln ? num(ln.getAttribute('w'), 12700) : 12700, color: clr(lr, ctx), dash: '' }; }
    return { bg, line };
  }

  /* ---------------- placeholder / style inheritance ---------------- */
  const normType = (t) => (t === 'ctrTitle' ? 'title' : t === 'subTitle' ? 'body' : (!t || t === 'obj' || t === 'tbl' || t === 'chart' || t === 'pic' || t === 'media' || t === 'clipArt' || t === 'dgm') ? 'body' : t);
  function shapesOf(doc) { const tree = doc && path(doc.documentElement, 'cSld', 'spTree'); return tree ? Array.from(tree.getElementsByTagNameNS(NSP, 'sp')) : []; }
  const phOf = (sp) => path(sp, 'nvSpPr', 'nvPr', 'ph');
  function findPh(doc, ph) {
    if (!doc || !ph) return null;
    const all = shapesOf(doc).filter((s) => phOf(s)); const idx = ph.getAttribute('idx'), ty = normType(ph.getAttribute('type'));
    let m = null;
    if (idx !== null) m = all.find((s) => phOf(s).getAttribute('idx') === idx && normType(phOf(s).getAttribute('type')) === ty) || all.find((s) => phOf(s).getAttribute('idx') === idx && ty === 'body');
    return m || all.find((s) => normType(phOf(s).getAttribute('type')) === ty) || null;
  }
  function slideCtx(sp) {
    const lay = relOfType(sp, 'slideLayout'), mas = lay && relOfType(lay.target, 'slideMaster');
    return { slide: sp, layout: lay && lay.target, master: mas && mas.target, layoutDoc: lay && S.docs[lay.target], masterDoc: mas && S.docs[mas.target], doc: S.docs[sp] };
  }
  function xfrmOf(sp, ctx) {
    let x = path(sp, 'spPr', 'xfrm'); if (x) return x;
    const ph = phOf(sp); if (!ph) return null;
    const l = findPh(ctx.layoutDoc, ph); x = l && path(l, 'spPr', 'xfrm'); if (x) return x;
    const m = findPh(ctx.masterDoc, ph); return m && path(m, 'spPr', 'xfrm');
  }

  /* ---------------- text ---------------- */
  function lstChain(sp, ctx) {
    const out = [];
    const own = sp && (kid(kid(sp, 'txBody'), 'lstStyle')); if (own) out.push(own);
    const ph = sp && phOf(sp);
    let kind = 'other';
    if (ph) {
      const t = normType(ph.getAttribute('type')); kind = t === 'title' ? 'title' : (t === 'body' ? 'body' : 'other');
      const l = findPh(ctx.layoutDoc, ph); if (l) out.push(kid(kid(l, 'txBody'), 'lstStyle'));
      const m = findPh(ctx.masterDoc, ph); if (m) out.push(kid(kid(m, 'txBody'), 'lstStyle'));
    }
    const ts = ctx.masterDoc && path(ctx.masterDoc.documentElement, 'txStyles');
    if (ts) out.push(kid(ts, kind === 'title' ? 'titleStyle' : kind === 'body' ? 'bodyStyle' : 'otherStyle'));
    const dts = kid(S.pres.documentElement, 'defaultTextStyle'); if (dts) out.push(dts);
    return out.filter(Boolean);
  }
  const lvlEl = (lst, lvl) => kid(lst, 'lvl' + (lvl + 1) + 'pPr');
  function firstAttr(els, name) { for (const e of els) if (e && e.hasAttribute(name)) return e.getAttribute(name); return null; }
  function firstKid(els, name) { for (const e of els) { const k = e && kid(e, name); if (k) return k; } return null; }
  function fontFam(rKidLatin, ctx) {
    let f = rKidLatin && rKidLatin.getAttribute('typeface'); const th = themeOf(ctx);
    if (!f) return '';
    if (f === '+mj-lt') f = th._major; else if (f === '+mn-lt') f = th._minor;
    return f ? `"${f}",` : '';
  }

  /** Renders a txBody into host. info collects first-run state for the edit toolbar. */
  function renderText(tx, host, ctx, sp, K, opts) {
    opts = opts || {};
    const chain = lstChain(sp, ctx), PT = K * 12700;
    const bp = kid(tx, 'bodyPr'); const fs = bp && path(bp, 'normAutofit') ? num(path(bp, 'normAutofit').getAttribute('fontScale'), 100000) / 100000 : 1;
    const nums = {}; let first = true; const info = { sz: 1800, b: false, i: false };
    let defColor = null;
    const st = sp && kid(sp, 'style'); if (st && kid(st, 'fontRef')) defColor = clr(kid(st, 'fontRef'), ctx);
    for (const p of kids(tx, 'p')) {
      const pPr = kid(p, 'pPr'), lvl = Math.min(8, num(pPr && pPr.getAttribute('lvl'), 0));
      const pl = [pPr, ...chain.map((l) => lvlEl(l, lvl))];
      const rtl = firstAttr(pl, 'rtl') === '1';
      let algn = firstAttr(pl, 'algn'); if (!algn) algn = rtl ? 'r' : 'l';
      const marL = num(firstAttr(pl, 'marL'), 0), indent = num(firstAttr(pl, 'indent'), 0);
      const d = document.createElement('div'); d.dir = rtl ? 'rtl' : 'ltr';
      d.style.textAlign = algn === 'ctr' ? 'center' : algn === 'r' ? 'right' : algn === 'just' ? 'justify' : 'left';
      d.style.paddingInlineStart = Math.max(0, marL * K) + 'px';
      // spacing
      const ln = firstKid(pl, 'lnSpc'); let lh = 1.2;
      if (ln) { const pc = kid(ln, 'spcPct'), pt = kid(ln, 'spcPts'); if (pc) lh = 1.2 * num(pc.getAttribute('val'), 100000) / 100000; else if (pt) lh = null, d.style.lineHeight = (num(pt.getAttribute('val'), 1200) / 100 * PT) + 'px'; }
      if (lh) d.style.lineHeight = String(lh);
      const sb = firstKid(pl, 'spcBef'), sa = firstKid(pl, 'spcAft');
      const spc = (e, sz) => { if (!e) return 0; const pc = kid(e, 'spcPct'), pt = kid(e, 'spcPts'); return pc ? num(pc.getAttribute('val'), 0) / 100000 * sz * 1.2 : pt ? num(pt.getAttribute('val'), 0) / 100 * PT : 0; };
      // runs
      const rl0 = (rPr) => [rPr, ...pl.map((l) => kid(l, 'defRPr'))];
      const mkRun = (rPr, text, isBr) => {
        const rl = rl0(rPr);
        const sz = (num(firstAttr(rl, 'sz'), 1800) / 100) * fs;
        const sp2 = document.createElement('span');
        if (isBr) return document.createElement('br');
        sp2.textContent = text;
        sp2.style.fontSize = (sz * PT) + 'px';
        const b = firstAttr(rl, 'b') === '1', it = firstAttr(rl, 'i') === '1', u = firstAttr(rl, 'u');
        if (b) sp2.style.fontWeight = '700'; if (it) sp2.style.fontStyle = 'italic';
        if (u && u !== 'none') sp2.style.textDecoration = 'underline';
        if (firstAttr(rl, 'strike') && firstAttr(rl, 'strike') !== 'noStrike') sp2.style.textDecoration += ' line-through';
        const bl = firstAttr(rl, 'baseline'); if (bl && num(bl, 0) !== 0) { sp2.style.verticalAlign = num(bl, 0) > 0 ? 'super' : 'sub'; sp2.style.fontSize = (sz * PT * 0.7) + 'px'; }
        let col = null; for (const e of rl) { const sf = e && kid(e, 'solidFill'); if (sf) { col = clr(sf, ctx); break; } }
        sp2.style.color = col || defColor || clr({ children: [{ localName: 'schemeClr', getAttribute: (k) => (k === 'val' ? 'tx1' : null), children: [] }] }, ctx) || '#000';
        const lat = firstKid(rl, 'latin'); const ff = fontFam(lat, ctx); if (ff) sp2.style.fontFamily = ff + 'system-ui,sans-serif';
        if (first) { info.sz = sz * 100; info.b = b; info.i = it; info.color = col; first = false; }
        return sp2;
      };
      let sizeForP = 18, any = false, firstSz = null;
      for (const c of Array.from(p.children)) {
        if (c.localName === 'r' || c.localName === 'fld') { const t = kid(c, 't'); const sp2 = mkRun(kid(c, 'rPr'), t ? t.textContent : '', false); d.appendChild(sp2); any = true; if (firstSz === null) firstSz = parseFloat(sp2.style.fontSize); }
        else if (c.localName === 'br') { d.appendChild(document.createElement('br')); any = true; }
      }
      if (!any) { const e = kid(p, 'endParaRPr'); const sp2 = mkRun(e, '​', false); d.appendChild(sp2); firstSz = parseFloat(sp2.style.fontSize); }
      sizeForP = (firstSz || 18 * PT) / PT;
      d.style.marginTop = (spc(sb, sizeForP)) + 'px'; d.style.marginBottom = (spc(sa, sizeForP)) + 'px';
      // bullet
      const bn = firstKid(pl, 'buNone'), bc = firstKid(pl, 'buChar'), ba = firstKid(pl, 'buAutoNum');
      // the first bullet-ish element found wins; buNone beats later ones only if it comes first
      let bu = null;
      for (const e of pl) { if (!e) continue; const k = Array.from(e.children).find((x) => /^bu(None|Char|AutoNum|Blip)$/.test(x.localName)); if (k) { bu = k; break; } }
      const hasText = any && d.textContent.replace(/​/g, '') !== '';
      if (bu && bu.localName !== 'buNone' && hasText) {
        const label = document.createElement('span'); label.style.display = 'inline-block'; label.style.minWidth = Math.max(0, -indent * K) + 'px'; label.style.paddingInlineEnd = '0.25em'; label.style.boxSizing = 'border-box';
        label.style.marginInlineStart = Math.min(0, indent * K) + 'px';
        let txt = '•';
        if (bu.localName === 'buChar') { txt = bu.getAttribute('char') || '•'; const bf = firstKid(pl, 'buFont'); const tf = bf && bf.getAttribute('typeface') || ''; if (/wingdings|symbol/i.test(tf) || txt.charCodeAt(0) < 32 || txt.charCodeAt(0) > 0xF000) txt = '▪'; }
        else if (bu.localName === 'buAutoNum') {
          const ty = bu.getAttribute('type') || 'arabicPeriod'; nums[lvl] = (nums[lvl] === undefined ? num(bu.getAttribute('startAt'), 1) : nums[lvl] + 1);
          for (let k = lvl + 1; k < 9; k++) delete nums[k];
          const n = nums[lvl]; const al = String.fromCharCode(96 + ((n - 1) % 26) + 1);
          txt = /alphaLc/.test(ty) ? al : /alphaUc/.test(ty) ? al.toUpperCase() : String(n);
          txt += /ParenR/.test(ty) ? ')' : /ParenBoth/.test(ty) ? ')' : /Plain/.test(ty) ? '' : '.';
        } else txt = '•';
        label.textContent = txt; label.style.fontSize = (sizeForP * PT) + 'px';
        d.insertBefore(label, d.firstChild); d.style.textIndent = '0';
      } else { d.style.textIndent = (indent * K) + 'px'; if (indent < 0 && marL === 0) d.style.textIndent = '0'; }
      if (!bu || bu.localName === 'buNone') { delete nums[lvl]; }
      host.appendChild(d);
    }
    if (opts.keep) opts.keep(info);
    return info;
  }

  /* ---------------- images ---------------- */
  const MIME = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', svg: 'image/svg+xml', webp: 'image/webp', bmp: 'image/bmp' };
  function setImg(img, p) {
    if (S.urls[p]) { img.src = S.urls[p]; return; }
    const f = S.zip.file(p); if (!f) return;
    const ext = p.split('.').pop().toLowerCase(); if (!MIME[ext]) { img.style.background = '#8883'; return; }
    f.async('uint8array').then((u) => { if (!S.urls[p]) S.urls[p] = URL.createObjectURL(new Blob([u], { type: MIME[ext] })); img.src = S.urls[p]; });
  }

  /* ---------------- slide rendering ---------------- */
  function px(v, K) { return (v * K) + 'px'; }
  function renderShapeList(parent, host, ctx, tf, K, editable, layerPath) {
    for (const e of Array.from(parent.children)) {
      const n = e.localName;
      if (n === 'AlternateContent') { const ch = kid(e, 'Choice') || kid(e, 'Fallback'); if (ch) renderShapeList(ch, host, ctx, tf, K, editable, layerPath); continue; }
      if (n === 'grpSp') {
        const gx = path(e, 'grpSpPr', 'xfrm'); let t2 = tf;
        if (gx) {
          const off = kid(gx, 'off'), ext = kid(gx, 'ext'), co = kid(gx, 'chOff'), ce = kid(gx, 'chExt');
          const ox = num(off && off.getAttribute('x'), 0), oy = num(off && off.getAttribute('y'), 0), cx = num(ext && ext.getAttribute('cx'), 1), cy = num(ext && ext.getAttribute('cy'), 1);
          const chx = num(co && co.getAttribute('x'), 0), chy = num(co && co.getAttribute('y'), 0), cex = num(ce && ce.getAttribute('cx'), cx) || 1, cey = num(ce && ce.getAttribute('cy'), cy) || 1;
          const sx = cx / cex, sy = cy / cey;
          t2 = { ox: tf.ox + (ox - chx * sx) * tf.sx, oy: tf.oy + (oy - chy * sy) * tf.sy, sx: tf.sx * sx, sy: tf.sy * sy };
        }
        renderShapeList(e, host, ctx, t2, K, editable, layerPath); continue;
      }
      if (n !== 'sp' && n !== 'pic' && n !== 'cxnSp' && n !== 'graphicFrame') continue;
      const hidden = path(e, n === 'graphicFrame' ? 'nvGraphicFramePr' : n === 'pic' ? 'nvPicPr' : n === 'cxnSp' ? 'nvCxnSpPr' : 'nvSpPr', 'cNvPr');
      if (hidden && hidden.getAttribute('hidden') === '1') continue;
      if (layerPath !== 'slide' && n === 'sp' && phOf(e)) continue; // placeholders on layout/master are templates, not content
      const xf = n === 'graphicFrame' ? kid(e, 'xfrm') : xfrmOf(e, ctx);
      if (!xf) continue;
      const off = kid(xf, 'off'), ext = kid(xf, 'ext');
      const x = tf.ox + num(off && off.getAttribute('x'), 0) * tf.sx, y = tf.oy + num(off && off.getAttribute('y'), 0) * tf.sy;
      const w = num(ext && ext.getAttribute('cx'), 0) * tf.sx, h = num(ext && ext.getAttribute('cy'), 0) * tf.sy;
      const rot = num(xf.getAttribute('rot'), 0) / 60000, fh = xf.getAttribute('flipH') === '1', fv = xf.getAttribute('flipV') === '1';
      const box = document.createElement('div'); box.className = 'sh';
      Object.assign(box.style, { position: 'absolute', left: px(x, K), top: px(y, K), width: px(w, K), height: px(h, K), boxSizing: 'border-box' });
      let tr = ''; if (rot) tr += `rotate(${rot}deg) `;
      if (n === 'pic') {
        const bl = path(e, 'blipFill', 'blip'); const rid = bl && bl.getAttributeNS(NSR, 'embed'); const rel = rid && S.rels[ctx.owner[layerPath]] && S.rels[ctx.owner[layerPath]][rid];
        box.style.overflow = 'hidden';
        if (rel && rel.target) {
          const img = document.createElement('img'); img.style.position = 'absolute'; img.draggable = false;
          const sr = path(e, 'blipFill', 'srcRect');
          if (sr) {
            const l = num(sr.getAttribute('l'), 0) / 100000, t = num(sr.getAttribute('t'), 0) / 100000, r = num(sr.getAttribute('r'), 0) / 100000, b = num(sr.getAttribute('b'), 0) / 100000;
            const fw = 1 - l - r || 1, fh2 = 1 - t - b || 1;
            Object.assign(img.style, { width: (100 / fw) + '%', height: (100 / fh2) + '%', left: (-l / fw * 100) + '%', top: (-t / fh2 * 100) + '%' });
          } else Object.assign(img.style, { width: '100%', height: '100%', left: 0, top: 0 });
          setImg(img, rel.target); box.appendChild(img);
        }
        if (fh) tr += 'scaleX(-1) '; if (fv) tr += 'scaleY(-1) ';
        const pn = paint(kid(e, 'spPr'), null, ctx); if (pn.line) box.style.border = `${Math.max(1, pn.line.w * K)}px solid ${pn.line.color}`;
      } else if (n === 'cxnSp' || (n === 'sp' && path(e, 'spPr', 'prstGeom') && /^(line|straightConnector1)$/.test(path(e, 'spPr', 'prstGeom').getAttribute('prst')) && !kid(e, 'txBody'))) {
        const pn = paint(kid(e, 'spPr'), kid(e, 'style'), ctx);
        const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg'); svg.setAttribute('width', '100%'); svg.setAttribute('height', '100%'); svg.style.overflow = 'visible';
        const l = document.createElementNS('http://www.w3.org/2000/svg', 'line');
        l.setAttribute('x1', fh ? '100%' : '0'); l.setAttribute('x2', fh ? '0' : '100%'); l.setAttribute('y1', fv ? '100%' : '0'); l.setAttribute('y2', fv ? '0' : '100%');
        l.setAttribute('stroke', (pn.line && pn.line.color) || '#888'); l.setAttribute('stroke-width', Math.max(1, ((pn.line && pn.line.w) || 12700) * K));
        if (pn.line && /dash|sysDot/i.test(pn.line.dash)) l.setAttribute('stroke-dasharray', '6 4');
        svg.appendChild(l); box.appendChild(svg);
      } else if (n === 'graphicFrame') {
        const tbl = e.getElementsByTagNameNS(NSA, 'tbl')[0];
        if (tbl) renderTable(tbl, box, ctx, K, w, editable);
        else { box.style.border = '1px dashed #8886'; box.style.display = 'flex'; box.style.alignItems = 'center'; box.style.justifyContent = 'center'; box.style.color = '#888'; box.style.fontSize = '13px'; box.textContent = 'רכיב שלא נתמך בתצוגה (תרשים / SmartArt)'; }
      } else { // sp
        const spPr = kid(e, 'spPr'), pn = paint(spPr, kid(e, 'style'), ctx);
        const geom = path(spPr, 'prstGeom'), prst = geom ? geom.getAttribute('prst') : 'rect';
        if (pn.bg) box.style.background = pn.bg;
        if (pn.line) box.style.border = `${Math.max(1, pn.line.w * K)}px ${/dash|sysDot/i.test(pn.line.dash) ? 'dashed' : 'solid'} ${pn.line.color}`;
        if (prst === 'ellipse') box.style.borderRadius = '50%';
        else if (prst === 'roundRect') box.style.borderRadius = (Math.min(w, h) * K * 0.1667) + 'px';
        else if (prst === 'triangle') { box.style.clipPath = 'polygon(50% 0,0 100%,100% 100%)'; box.style.border = 'none'; }
        else if (prst === 'diamond') { box.style.clipPath = 'polygon(50% 0,100% 50%,50% 100%,0 50%)'; box.style.border = 'none'; }
        else if (prst === 'rightArrow') { box.style.clipPath = 'polygon(0 25%,60% 25%,60% 0,100% 50%,60% 100%,60% 75%,0 75%)'; box.style.border = 'none'; }
        else if (prst === 'leftArrow') { box.style.clipPath = 'polygon(100% 25%,40% 25%,40% 0,0 50%,40% 100%,40% 75%,100% 75%)'; box.style.border = 'none'; }
        const tx = kid(e, 'txBody');
        if (tx) {
          const bp = kid(tx, 'bodyPr');
          const g = (a, d) => num(bp && bp.getAttribute(a), d) * K;
          box.style.padding = `${g('tIns', 45720)}px ${g('rIns', 91440)}px ${g('bIns', 45720)}px ${g('lIns', 91440)}px`;
          const anchor = (bp && bp.getAttribute('anchor')) || ((phOf(e) && findAnchor(e, ctx)) || 't');
          box.style.display = 'flex'; box.style.flexDirection = 'column'; box.style.justifyContent = anchor === 'ctr' ? 'center' : anchor === 'b' ? 'flex-end' : 'flex-start';
          if (bp && bp.getAttribute('wrap') === 'none') box.style.whiteSpace = 'nowrap';
          if (rot) { /* keep text upright with box */ }
          renderText(tx, box, ctx, e, K, { keep: (inf) => { box._info = inf; } });
          if (editable && layerPath === 'slide') { box.classList.add('tx'); box._tx = tx; box._sp = e; box._path = ctx.slide; }
        }
      }
      if (tr) box.style.transform = tr.trim();
      host.appendChild(box);
    }
  }
  function findAnchor(sp, ctx) {
    const ph = phOf(sp);
    for (const doc of [ctx.layoutDoc, ctx.masterDoc]) { const m = findPh(doc, ph); const bp = m && path(m, 'txBody', 'bodyPr'); if (bp && bp.getAttribute('anchor')) return bp.getAttribute('anchor'); }
    return 't';
  }
  function renderTable(tbl, box, ctx, K, wPx, editable) {
    const cols = kids(kid(tbl, 'tblGrid'), 'gridCol').map((g) => num(g.getAttribute('w'), 0));
    const t = document.createElement('table'); t.style.cssText = 'border-collapse:collapse;table-layout:fixed;position:absolute;left:0;top:0;width:' + (cols.reduce((a, b) => a + b, 0) * K) + 'px';
    const pr = kid(tbl, 'tblPr'); const hdr = pr && pr.getAttribute('firstRow') === '1', band = pr && pr.getAttribute('bandRow') === '1';
    const cg = document.createElement('colgroup'); cols.forEach((w) => { const c = document.createElement('col'); c.style.width = (w * K) + 'px'; cg.appendChild(c); }); t.appendChild(cg);
    let ri = 0;
    for (const tr of kids(tbl, 'tr')) {
      const row = document.createElement('tr'); row.style.height = (num(tr.getAttribute('h'), 0) * K) + 'px';
      for (const tc of kids(tr, 'tc')) {
        if (tc.getAttribute('hMerge') === '1' || tc.getAttribute('vMerge') === '1') continue;
        const td = document.createElement('td');
        if (tc.getAttribute('gridSpan')) td.colSpan = num(tc.getAttribute('gridSpan'), 1);
        if (tc.getAttribute('rowSpan')) td.rowSpan = num(tc.getAttribute('rowSpan'), 1);
        const pp = kid(tc, 'tcPr'); let bg = pp && kid(pp, 'solidFill') ? clr(kid(pp, 'solidFill'), ctx) : null;
        if (!bg && !(pp && kid(pp, 'noFill'))) { if (hdr && ri === 0) bg = clr({ children: [{ localName: 'schemeClr', getAttribute: (k) => (k === 'val' ? 'accent1' : null), children: [] }] }, ctx); else if (band && ri % 2 === 1) bg = 'rgba(128,128,128,.15)'; }
        td.style.cssText = `border:1px solid rgba(128,128,128,.45);padding:${Math.max(2, 45720 * K)}px ${Math.max(3, 91440 * K)}px;vertical-align:top;overflow:hidden;` + (bg ? 'background:' + bg + ';' : '');
        const tx = kid(tc, 'txBody');
        if (tx) {
          renderText(tx, td, ctx, null, K, { keep: (inf) => { td._info = inf; } });
          if (hdr && ri === 0 && bg && !(pp && kid(pp, 'solidFill'))) td.querySelectorAll('span').forEach((s) => { s.style.color = '#fff'; s.style.fontWeight = '700'; });
          if (editable) { td.classList.add('tx'); td._tx = tx; td._path = ctx.slide; }
        }
        row.appendChild(td);
      }
      t.appendChild(row); ri++;
    }
    box.appendChild(t);
  }
  function bgOf(ctx) {
    for (const doc of [ctx.doc, ctx.layoutDoc, ctx.masterDoc]) {
      const bg = doc && path(doc.documentElement, 'cSld', 'bg'); if (!bg) continue;
      const pr = kid(bg, 'bgPr');
      if (pr) { const sf = kid(pr, 'solidFill'), gf = kid(pr, 'gradFill'); if (sf) return clr(sf, ctx); if (gf) return gradCss(gf, ctx); }
      const ref = kid(bg, 'bgRef'); if (ref) { const c = clr(ref, ctx); if (c) return c; }
    }
    return '#fff';
  }
  /** Builds the slide DOM at the given pixel width. */
  function renderSlide(i, W, editable) {
    const o = S.order[i], sp = o.path, ctx = slideCtx(sp); ctx.owner = { slide: sp, layout: ctx.layout, master: ctx.master };
    const K = W / S.sz.cx, H = S.sz.cy * K;
    const el = document.createElement('div'); el.className = 'slide';
    Object.assign(el.style, { position: 'relative', width: W + 'px', height: H + 'px', overflow: 'hidden', background: bgOf(ctx), flex: 'none' });
    const T0 = { ox: 0, oy: 0, sx: 1, sy: 1 };
    const showM = !(ctx.layoutDoc && ctx.layoutDoc.documentElement.getAttribute('showMasterSp') === '0'), showL = ctx.doc.documentElement.getAttribute('showMasterSp') !== '0';
    const tree = (d) => d && path(d.documentElement, 'cSld', 'spTree');
    try {
      if (showM && showL && tree(ctx.masterDoc)) renderShapeList(tree(ctx.masterDoc), el, ctx, T0, K, false, 'master');
      if (showL && tree(ctx.layoutDoc)) renderShapeList(tree(ctx.layoutDoc), el, ctx, T0, K, false, 'layout');
      renderShapeList(tree(ctx.doc), el, ctx, T0, K, editable, 'slide');
    } catch (e) { console.error(e); }
    return el;
  }

  /* ---------------- UI ---------------- */
  function stageWidth() {
    const st = $('stage'), aw = st.clientWidth - 16, ah = st.clientHeight - 16;
    const fit = Math.max(120, Math.min(aw, ah * S.sz.cx / S.sz.cy));
    return Math.round(fit * S.zoom);
  }
  function renderStage() {
    const st = $('stage'); st.innerHTML = '';
    if (!S.order.length) return;
    const wrap = document.createElement('div'); wrap.id = 'slidewrap'; const s = renderSlide(S.cur, stageWidth(), true);
    wrap.appendChild(s); st.appendChild(wrap);
    $('cnt').textContent = (S.cur + 1) + ' / ' + S.order.length;
  }
  function renderStrip() {
    const t = $('strip'); t.innerHTML = '';
    S.order.forEach((o, i) => {
      const b = document.createElement('button'); b.className = i === S.cur ? 'th on' : 'th';
      const W = 112; b.style.width = W + 'px'; b.style.height = Math.round(W * S.sz.cy / S.sz.cx) + 'px';
      try { const s = renderSlide(i, W, false); s.style.pointerEvents = 'none'; b.appendChild(s); } catch (_) {}
      const n = document.createElement('i'); n.textContent = i + 1; b.appendChild(n);
      b.onclick = () => { S.cur = i; renderAll(false); };
      t.appendChild(b);
    });
    const on = t.querySelector('.on'); if (on) on.scrollIntoView({ inline: 'center', block: 'nearest' });
  }
  function renderAll(strip) { renderStage(); if (strip !== false) renderStrip(); else { document.querySelectorAll('#strip .th').forEach((b, i) => b.classList.toggle('on', i === S.cur)); const on = document.querySelector('#strip .th.on'); if (on) on.scrollIntoView({ inline: 'center', block: 'nearest' }); } }
  function toast(m) { const t = $('toast'); t.textContent = m; t.style.display = 'block'; clearTimeout(toast._t); toast._t = setTimeout(() => { t.style.display = 'none'; }, 2200); }

  /* ----- text editing dialog ----- */
  const HEB = /[֐-׿]/;
  const COLORS = ['#000000', '#ffffff', '#e5484d', '#f08c00', '#2f9e44', '#1c7ed6', '#7048e8', '#868e96'];
  function parasOf(tx) { return kids(tx, 'p'); }
  const ptext = (p) => Array.from(p.children).filter((c) => /^(r|fld|br)$/.test(c.localName)).map((c) => (c.localName === 'br' ? '\n' : (kid(c, 't') ? kid(c, 't').textContent : ''))).join('');
  function mk(ns, name, attrs) { const e = S.pres.createElementNS(ns, name); for (const k in attrs || {}) e.setAttribute(k, attrs[k]); return e; }
  function setParaText(p, text) {
    const old = ptext(p); if (old === text) return;
    let model = Array.from(p.children).find((c) => c.localName === 'r' && kid(c, 'rPr')); const mp = model && kid(model, 'rPr');
    const end = kid(p, 'endParaRPr');
    for (const c of Array.from(p.children)) if (/^(r|fld|br)$/.test(c.localName)) p.removeChild(c);
    const lines = text.split('\n'); const insertBefore = end || null;
    lines.forEach((ln, i) => {
      if (i > 0) { const br = mk(NSA, 'a:br'); if (mp) br.appendChild(mp.cloneNode(true)); p.insertBefore(br, insertBefore); }
      if (ln === '' && lines.length === 1) return;
      const r = mk(NSA, 'a:r'); const rp = mp ? mp.cloneNode(true) : (end ? renameEl(end.cloneNode(true), 'a:rPr') : mk(NSA, 'a:rPr', { lang: 'he-IL' }));
      r.appendChild(rp); const t = mk(NSA, 'a:t'); t.textContent = ln; r.appendChild(t); p.insertBefore(r, insertBefore);
    });
  }
  function renameEl(e, qn) { const n = mk(NSA, qn); for (const a of Array.from(e.attributes)) n.setAttribute(a.name, a.value); while (e.firstChild) n.appendChild(e.firstChild); return n; }
  function ensurePPr(p) { let pp = kid(p, 'pPr'); if (!pp) { pp = mk(NSA, 'a:pPr'); p.insertBefore(pp, p.firstChild); } return pp; }
  function applyText(tx, text) {
    const lines = text.split('\n'); let ps = parasOf(tx);
    if (!ps.length) { const p = mk(NSA, 'a:p'); tx.appendChild(p); ps = [p]; }
    const last = ps[ps.length - 1];
    lines.forEach((ln, i) => {
      let p = ps[i];
      if (!p) { p = last.cloneNode(true); for (const c of Array.from(p.children)) if (/^(r|fld|br)$/.test(c.localName)) p.removeChild(c); const base = ps[ps.length - 1]; const r0 = Array.from(last.children).find((c) => c.localName === 'r'); p._model = r0; tx.appendChild(p); ps.push(p); if (r0 && !kid(p, 'endParaRPr')) { const er = renameEl(kid(r0, 'rPr') ? kid(r0, 'rPr').cloneNode(true) : mk(NSA, 'a:rPr'), 'a:endParaRPr'); p.appendChild(er); } }
      if (p._model && !Array.from(p.children).some((c) => c.localName === 'r')) { /* new paragraph: copy run properties from the previous one */
        const t = ln; const r = mk(NSA, 'a:r'); const rp = kid(p._model, 'rPr'); r.appendChild(rp ? rp.cloneNode(true) : mk(NSA, 'a:rPr', { lang: 'he-IL' })); const te = mk(NSA, 'a:t'); te.textContent = t; r.appendChild(te);
        const end = kid(p, 'endParaRPr'); p.insertBefore(r, end || null);
      } else setParaText(p, ln);
      if (HEB.test(ln)) { const pp = ensurePPr(p); if (pp.getAttribute('rtl') !== '1' && !(S.ed && S.ed.rtlOK)) { pp.setAttribute('rtl', '1'); if (!pp.getAttribute('algn') || pp.getAttribute('algn') === 'l') pp.setAttribute('algn', 'r'); } }
    });
    for (let i = lines.length; i < ps.length; i++) if (ps[i].parentNode === tx) tx.removeChild(ps[i]);
  }
  function eachRun(tx, fn) { for (const p of parasOf(tx)) { for (const c of Array.from(p.children)) if (c.localName === 'r') { let rp = kid(c, 'rPr'); if (!rp) { rp = mk(NSA, 'a:rPr', { lang: 'he-IL' }); c.insertBefore(rp, c.firstChild); } fn(rp); } const e = kid(p, 'endParaRPr'); if (e) fn(e); } }
  const FILL_AFTER = ['effectLst', 'effectDag', 'highlight', 'uLnTx', 'uLn', 'uFillTx', 'uFill', 'latin', 'ea', 'cs', 'sym', 'hlinkClick', 'hlinkMouseOver', 'rtl', 'extLst'];
  function setFill(rp, hex) {
    for (const c of Array.from(rp.children)) if (/^(solidFill|gradFill|noFill|pattFill|blipFill|grpFill)$/.test(c.localName)) rp.removeChild(c);
    const sf = mk(NSA, 'a:solidFill'); sf.appendChild(mk(NSA, 'a:srgbClr', { val: hex.replace('#', '').toUpperCase() }));
    const before = Array.from(rp.children).find((c) => FILL_AFTER.includes(c.localName)); rp.insertBefore(sf, before || null);
  }
  function openEdit(el) {
    const tx = el._tx; if (!tx) return;
    S.ed = { el, tx, rtlOK: false };
    const ps = parasOf(tx); $('dtxt').value = ps.map(ptext).join('\n');
    $('dlg').classList.add('show'); setTimeout(() => { const t = $('dtxt'); t.focus(); }, 60);
    $('ddel').style.display = el._sp ? '' : 'none';
  }
  function closeEdit(commit) {
    const e = S.ed; if (!e) return; $('dlg').classList.remove('show'); S.ed = null;
    if (commit) {
      const cur = parasOf(e.tx).map(ptext).join('\n'), nv = $('dtxt').value.replace(/\r/g, '');
      if (cur !== nv) { applyText(e.tx, nv); touch(e.el._path); }
    }
    renderAll();
  }
  function fmt(kind, val) {
    const e = S.ed; if (!e) return; const tx = e.tx, inf = e.el._info || { sz: 1800 };
    // keep unsaved text edits: commit text first
    const nv = $('dtxt').value.replace(/\r/g, ''); if (parasOf(tx).map(ptext).join('\n') !== nv) applyText(tx, nv);
    if (kind === 'b') { const on = !(e.b === undefined ? inf.b : e.b); e.b = on; eachRun(tx, (rp) => rp.setAttribute('b', on ? '1' : '0')); }
    else if (kind === 'i') { const on = !(e.i === undefined ? inf.i : e.i); e.i = on; eachRun(tx, (rp) => rp.setAttribute('i', on ? '1' : '0')); }
    else if (kind === 'sz') { const cur = e.sz || inf.sz || 1800; const nz = Math.max(600, Math.min(20000, Math.round(cur * (val > 0 ? 1.12 : 1 / 1.12) / 100) * 100)); e.sz = nz; eachRun(tx, (rp) => rp.setAttribute('sz', String(nz))); }
    else if (kind === 'color') eachRun(tx, (rp) => setFill(rp, val));
    else if (kind === 'algn') { for (const p of parasOf(tx)) ensurePPr(p).setAttribute('algn', val); e.rtlOK = true; }
    else if (kind === 'rtl') { const on = val; for (const p of parasOf(tx)) { const pp = ensurePPr(p); pp.setAttribute('rtl', on ? '1' : '0'); pp.setAttribute('algn', on ? 'r' : 'l'); } e.rtlOK = true; }
    touch(e.el._path);
    // refresh the live preview behind the dialog
    const keepTx = e.tx, keepEl = e.el; renderAll(); S.ed = e; // note: DOM re-created; remap element
    const again = Array.from(document.querySelectorAll('#stage .tx')).find((x) => x._tx === keepTx); if (again) e.el = again; else e.el = keepEl;
  }
  function deleteShape() {
    const e = S.ed; if (!e || !e.el._sp) return;
    const sp = e.el._sp, p = e.el._path; sp.parentNode.removeChild(sp); touch(p); S.ed = null; $('dlg').classList.remove('show'); renderAll();
  }

  /* ----- slide operations ----- */
  function syncOrder() {
    const lst = kid(S.pres.documentElement, 'sldIdLst'); S.order.forEach((o) => lst.appendChild(o.el)); touch('ppt/presentation.xml');
  }
  function moveSlide(d) {
    const i = S.cur, j = i + d; if (j < 0 || j >= S.order.length) return;
    const t = S.order[i]; S.order[i] = S.order[j]; S.order[j] = t; S.cur = j; syncOrder(); renderAll();
  }
  function nextNum(re) { let m = 0; S.zip.forEach((p) => { const x = re.exec(p); if (x) m = Math.max(m, +x[1]); }); return m + 1; }
  async function addPart(sp, slideXml, relsXml, after) {
    const prp = 'ppt/_rels/presentation.xml.rels'; const prd = await getDoc(prp);
    let max = 0; for (const r of Array.from(prd.documentElement.children)) { const m = /(\d+)$/.exec(r.getAttribute('Id')); if (m) max = Math.max(max, +m[1]); }
    const rid = 'rId' + (max + 1);
    const rel = prd.createElementNS(NSREL, 'Relationship'); rel.setAttribute('Id', rid); rel.setAttribute('Type', RT + 'slide'); rel.setAttribute('Target', sp.replace(/^ppt\//, '')); prd.documentElement.appendChild(rel); touch(prp);
    const ct = await getDoc('[Content_Types].xml'); const ov = ct.createElementNS(NSCT, 'Override'); ov.setAttribute('PartName', '/' + sp); ov.setAttribute('ContentType', T_SLIDE); ct.documentElement.appendChild(ov); touch('[Content_Types].xml');
    S.zip.file(sp, slideXml); S.zip.file(relsPath(sp), relsXml);
    let mx = 255; for (const o of S.order) mx = Math.max(mx, +o.el.getAttribute('id')); for (const e of kids(kid(S.pres.documentElement, 'sldIdLst'), 'sldId')) mx = Math.max(mx, +e.getAttribute('id'));
    const sid = S.pres.createElementNS(NSP, 'p:sldId'); sid.setAttribute('id', String(mx + 1)); sid.setAttributeNS(NSR, 'r:id', rid);
    S.order.splice(after + 1, 0, { path: sp, el: sid }); S.docs[prp] = prd; S.rels['ppt/presentation.xml'] = null; await loadRels('ppt/presentation.xml');
    delete S.docs[sp]; delete S.docs[relsPath(sp)]; delete S.rels[sp];
    await loadSlideDeps(sp); syncOrder(); S.cur = after + 1; markDirty(); renderAll();
  }
  const XMLH = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n';
  async function addSlide() {
    const cur = S.order[S.cur], lay = relOfType(cur.path, 'slideLayout'); const layRaw = lay ? lay.raw : '../slideLayouts/slideLayout1.xml';
    const sp = 'ppt/slides/slide' + nextNum(/^ppt\/slides\/slide(\d+)\.xml$/) + '.xml';
    const W = S.sz.cx, H = S.sz.cy, mx = Math.round(W * 0.06);
    const tb = (id, name, y, h, sz, bold, txt, bullet) => `<p:sp><p:nvSpPr><p:cNvPr id="${id}" name="${name}"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="${mx}" y="${y}"/><a:ext cx="${W - 2 * mx}" cy="${h}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr wrap="square" rtlCol="0"><a:normAutofit/></a:bodyPr><a:lstStyle/><a:p><a:pPr algn="r" rtl="1"${bullet ? ' marL="342900" indent="-342900"' : ''}>${bullet ? '<a:buFont typeface="Arial"/><a:buChar char="•"/>' : ''}</a:pPr><a:r><a:rPr lang="he-IL" sz="${sz}" b="${bold ? 1 : 0}" dirty="0"/><a:t>${txt}</a:t></a:r></a:p></p:txBody></p:sp>`;
    const xml = XMLH + `<p:sld xmlns:a="${NSA}" xmlns:r="${NSR}" xmlns:p="${NSP}"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>` +
      tb(2, 'Title', Math.round(H * 0.06), Math.round(H * 0.18), 3200, true, 'כותרת', false) + tb(3, 'Content', Math.round(H * 0.28), Math.round(H * 0.6), 2400, false, 'הקש כדי לערוך טקסט', true) +
      `</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>`;
    const rels = XMLH + `<Relationships xmlns="${NSREL}"><Relationship Id="rId1" Type="${RT}slideLayout" Target="${layRaw}"/></Relationships>`;
    await addPart(sp, xml, rels, S.cur);
  }
  async function dupSlide() {
    const cur = S.order[S.cur]; const sp = 'ppt/slides/slide' + nextNum(/^ppt\/slides\/slide(\d+)\.xml$/) + '.xml';
    const xml = XMLH + ser.serializeToString(S.docs[cur.path].documentElement);
    const rd = parser.parseFromString(ser.serializeToString(S.docs[relsPath(cur.path)] || parser.parseFromString(`<Relationships xmlns="${NSREL}"/>`, 'application/xml')), 'application/xml');
    for (const r of Array.from(rd.documentElement.children)) if (/notesSlide$/.test(r.getAttribute('Type') || '')) rd.documentElement.removeChild(r);
    await addPart(sp, xml, XMLH + ser.serializeToString(rd.documentElement), S.cur);
  }
  async function delSlide() {
    if (S.order.length <= 1) { toast('אי אפשר למחוק את השקף האחרון'); return; }
    if (!delSlide._arm || Date.now() - delSlide._arm > 3000) { delSlide._arm = Date.now(); toast('לחץ שוב על 🗑 כדי למחוק את שקף ' + (S.cur + 1)); return; } delSlide._arm = 0;
    const o = S.order[S.cur], sp = o.path, rel = S.rels[sp] || {};
    const notes = Object.values(rel).find((r) => r.type === 'notesSlide');
    const ct = await getDoc('[Content_Types].xml'); const dropCt = (pn) => { for (const e of Array.from(ct.documentElement.children)) if (e.getAttribute('PartName') === '/' + pn) ct.documentElement.removeChild(e); };
    if (notes && notes.target) { dropCt(notes.target); S.zip.remove(notes.target); S.zip.remove(relsPath(notes.target)); }
    dropCt(sp); touch('[Content_Types].xml'); S.zip.remove(sp); S.zip.remove(relsPath(sp)); delete S.docs[sp]; delete S.docs[relsPath(sp)]; delete S.rels[sp]; S.mod.delete(sp);
    const prd = S.docs['ppt/_rels/presentation.xml.rels']; const rid = o.el.getAttributeNS(NSR, 'id');
    for (const r of Array.from(prd.documentElement.children)) if (r.getAttribute('Id') === rid) prd.documentElement.removeChild(r); touch('ppt/_rels/presentation.xml.rels');
    if (o.el.parentNode) o.el.parentNode.removeChild(o.el);
    S.order.splice(S.cur, 1); S.cur = Math.min(S.cur, S.order.length - 1); S.rels['ppt/presentation.xml'] = null; await loadRels('ppt/presentation.xml'); syncOrder(); renderAll();
  }
  function addTextBox() {
    const o = S.order[S.cur], d = S.docs[o.path], tree = path(d.documentElement, 'cSld', 'spTree');
    let mx = 1; for (const e of Array.from(tree.getElementsByTagNameNS(NSP, 'cNvPr'))) mx = Math.max(mx, +e.getAttribute('id') || 0);
    const W = S.sz.cx, H = S.sz.cy, w = Math.round(W * 0.6), h = Math.round(H * 0.16);
    const xml = `<root xmlns:a="${NSA}" xmlns:p="${NSP}"><p:sp><p:nvSpPr><p:cNvPr id="${mx + 1}" name="TextBox ${mx + 1}"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="${Math.round((W - w) / 2)}" y="${Math.round((H - h) / 2)}"/><a:ext cx="${w}" cy="${h}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr wrap="square" rtlCol="0"><a:normAutofit/></a:bodyPr><a:lstStyle/><a:p><a:pPr algn="r" rtl="1"/><a:r><a:rPr lang="he-IL" sz="2400" dirty="0"/><a:t>טקסט חדש</a:t></a:r></a:p></p:txBody></p:sp></root>`;
    const n = d.importNode(parser.parseFromString(xml, 'application/xml').documentElement.firstElementChild, true); tree.appendChild(n); touch(o.path); renderAll();
    const el = Array.from(document.querySelectorAll('#stage .tx')).find((x) => x._sp === n); if (el) openEdit(el);
  }

  /* ----- present mode ----- */
  function startPresent() {
    S.present = true; let i = S.cur; const ov = $('pres'); ov.classList.add('show');
    const draw = () => { ov.innerHTML = ''; const W = Math.min(innerWidth, innerHeight * S.sz.cx / S.sz.cy); const s = renderSlide(i, Math.floor(W), false); ov.appendChild(s); const c = document.createElement('div'); c.className = 'pc'; c.textContent = (i + 1) + ' / ' + S.order.length; ov.appendChild(c); };
    ov._go = (d) => { if (d === 0) { ov.classList.remove('show'); S.present = false; S.cur = i; renderAll(false); return; } const j = i + d; if (j < 0) return; if (j >= S.order.length) { ov._go(0); return; } i = j; draw(); };
    draw();
  }

  /* ---------------- open / save ---------------- */
  function b64ToBuf(b) { const bin = atob(b), u = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i); return u.buffer; }
  function bufToB64(u8) { let s = ''; const CH = 0x8000; for (let i = 0; i < u8.length; i += CH) s += String.fromCharCode.apply(null, u8.subarray(i, i + CH)); return btoa(s); }
  async function newDeck() {
    const p = new PptxGenJS(); p.layout = 'LAYOUT_16x9'; p.rtlMode = true;
    const s = p.addSlide();
    s.addText('מצגת חדשה', { x: 0.6, y: 1.5, w: 8.8, h: 1.2, fontSize: 44, bold: true, align: 'center', rtlMode: true });
    s.addText('הקש על הטקסט כדי לערוך', { x: 0.6, y: 2.8, w: 8.8, h: 0.8, fontSize: 22, color: '666666', align: 'center', rtlMode: true });
    return await p.write({ outputType: 'arraybuffer' });
  }
  async function open(dark) {
    S.dark = !!dark; document.documentElement.classList.toggle('dark', !!dark);
    try {
      const b64 = A().docData();
      await loadPackage(b64 ? b64ToBuf(b64) : await newDeck());
      if (!b64) markDirty(); else { S.dirty = false; }
      S.cur = 0; renderAll(); A().opened();
      if (!b64) A().dirty(true);
      toast('הקש על טקסט בשקף כדי לערוך');
    } catch (e) { A().toast('לא הצלחתי לפתוח את המצגת: ' + (e && e.message || e)); }
  }
  async function save() {
    try {
      for (const p of S.mod) { const d = S.docs[p]; if (!d) continue; let s = ser.serializeToString(d); if (!/^<\?xml/.test(s)) s = XMLH + s; S.zip.file(p, s); }
      const out = await S.zip.generateAsync({ type: 'uint8array', compression: 'DEFLATE', mimeType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation' });
      A().docSaved(bufToB64(out)); S.mod = new Set(); S.dirty = false; A().dirty(false);
    } catch (e) { A().toast('השמירה נכשלה: ' + (e && e.message || e)); A().saveFailed(); }
  }

  /* ---------------- bind ---------------- */
  function bind() {
    $('stage').addEventListener('click', (e) => { const t = e.target.closest('.tx'); if (t && !S.pinching) openEdit(t); });
    $('dok').onclick = () => closeEdit(true); $('dno').onclick = () => closeEdit(false); $('ddel').onclick = deleteShape;
    document.querySelectorAll('#fmt [data-f]').forEach((b) => { b.onmousedown = (e) => e.preventDefault(); b.onclick = () => { const f = b.dataset.f; if (f === 'sz+') fmt('sz', 1); else if (f === 'sz-') fmt('sz', -1); else if (f === 'rtl') fmt('rtl', true); else if (f === 'ltr') fmt('rtl', false); else if (f.startsWith('al-')) fmt('algn', { 'al-l': 'l', 'al-c': 'ctr', 'al-r': 'r' }[f]); else fmt(f); }; });
    const pal = $('pal'); COLORS.forEach((c) => { const i = document.createElement('i'); i.style.background = c; i.onmousedown = (e) => e.preventDefault(); i.onclick = () => fmt('color', c); pal.appendChild(i); });
    document.querySelectorAll('#tb [data-a]').forEach((b) => b.addEventListener('click', () => {
      const a = b.dataset.a;
      if (a === 'add') addSlide(); else if (a === 'dup') dupSlide(); else if (a === 'del') delSlide(); else if (a === 'left') moveSlide(-1); else if (a === 'right') moveSlide(1); else if (a === 'tbox') addTextBox(); else if (a === 'play') startPresent();
      else if (a === 'zin') { S.zoom = Math.min(3, S.zoom * 1.25); renderStage(); } else if (a === 'zout') { S.zoom = Math.max(0.5, S.zoom / 1.25); renderStage(); } else if (a === 'prev') { if (S.cur > 0) { S.cur--; renderAll(false); } } else if (a === 'next') { if (S.cur < S.order.length - 1) { S.cur++; renderAll(false); } }
    }));
    const pr = $('pres'); pr.addEventListener('click', (e) => { const r = pr.getBoundingClientRect(), x = e.clientX - r.left; if (e.target.closest('.px')) return pr._go(0); pr._go(x > r.width * 0.67 ? -1 : 1); });
    // pinch zoom + swipe
    const st = $('stage'); let d0 = 0, z0 = 1, raf = 0;
    const dist = (t) => Math.hypot(t[0].clientX - t[1].clientX, t[0].clientY - t[1].clientY);
    st.addEventListener('touchstart', (e) => { if (e.touches.length === 2) { d0 = dist(e.touches); z0 = S.zoom; S.pinching = true; } else d0 = 0; }, { passive: true });
    st.addEventListener('touchmove', (e) => { if (e.touches.length === 2 && d0) { e.preventDefault(); S.zoom = Math.max(0.5, Math.min(4, z0 * dist(e.touches) / d0)); if (!raf) raf = requestAnimationFrame(() => { raf = 0; renderStage(); }); } }, { passive: false });
    st.addEventListener('touchend', () => { d0 = 0; setTimeout(() => { S.pinching = false; }, 250); }, { passive: true });
    addEventListener('resize', () => { if (S.order.length && !S.present) renderStage(); });
    addEventListener('error', (e) => { try { A().toast('שגיאה: ' + (e.message || e)); } catch (_) {} });
  }
  window.SL = { open, save, _S: S, _render: renderAll, _edit: (txt) => { const el = document.querySelector('#stage .tx'); openEdit(el); $('dtxt').value = txt; closeEdit(true); } };
  bind(); A().ready();
})();
