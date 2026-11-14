import { Document, Packer, Paragraph, TextRun, ImageRun, Table, TableRow, TableCell, WidthType, AlignmentType, LevelFormat, ShadingType, UnderlineType } from 'docx';
import mammoth from 'mammoth/mammoth.browser.js';
import JSZip from 'jszip';

const HEB = /[֐-׿]/;
const BLOCK = new Set(['DIV','P','H1','H2','H3','H4','H5','H6','UL','OL','LI','TABLE','BLOCKQUOTE','PRE']);
const HEAD = { H1: 40, H2: 34, H3: 30, H4: 28, H5: 26, H6: 24 };

function hex(c) {
  if (!c) return null;
  c = c.trim();
  let m = /^#([0-9a-f]{6})$/i.exec(c); if (m) return m[1].toUpperCase();
  m = /^#([0-9a-f]{3})$/i.exec(c); if (m) return m[1].split('').map(x => x + x).join('').toUpperCase();
  m = /^rgba?\((\d+)[ ,]+(\d+)[ ,]+(\d+)/i.exec(c);
  if (m) return [m[1], m[2], m[3]].map(n => (+n).toString(16).padStart(2, '0')).join('').toUpperCase();
  return null;
}
function style(el, name) { return el.style && el.style[name] ? el.style[name] : ''; }
const FSIZE = { 1: 10, 2: 13, 3: 16, 4: 18, 5: 24, 6: 32, 7: 48 };

function b64ToBytes(b64) {
  const s = atob(b64), u = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) u[i] = s.charCodeAt(i);
  return u;
}
function imgSize(src) {
  return new Promise(res => {
    const i = new Image();
    i.onload = () => res([i.naturalWidth || 200, i.naturalHeight || 200]);
    i.onerror = () => res([200, 200]);
    i.src = src;
  });
}

async function htmlToDocxB64(html, title) {
  const dom = new DOMParser().parseFromString('<body>' + (html || '') + '</body>', 'text/html');
  let listInstance = 0;
  const bulletLevels = [0, 1, 2].map(l => ({ level: l, format: LevelFormat.BULLET, text: ['•', '◦', '▪'][l], alignment: AlignmentType.START, style: { paragraph: { indent: { left: 720 + l * 360, hanging: 360 } } } }));
  const numLevels = [0, 1, 2].map(l => ({ level: l, format: LevelFormat.DECIMAL, text: '%' + (l + 1) + '.', alignment: AlignmentType.START, style: { paragraph: { indent: { left: 720 + l * 360, hanging: 360 } } } }));
  const instances = [];

  async function blocks(parent, ctx) {
    const out = [];
    let runs = [], text = '';
    let pctx = ctx;
    async function flush(force) {
      if (!runs.length && !force) { text = ''; return; }
      const heb = HEB.test(text);
      const opts = { children: runs.length ? runs : [new TextRun('')], bidirectional: heb };
      let al = pctx.align;
      if (al === 'right') al = heb ? AlignmentType.START : AlignmentType.END;
      else if (al === 'left') al = heb ? AlignmentType.END : AlignmentType.START;
      else if (al === 'center') al = AlignmentType.CENTER;
      else if (al === 'justify') al = AlignmentType.BOTH;
      else al = null;
      if (al) opts.alignment = al;
      if (pctx.list) opts.numbering = { reference: pctx.list.ref, level: Math.min(pctx.list.level, 2), instance: pctx.list.instance };
      if (pctx.heading) opts.spacing = { before: 200, after: 100 };
      out.push(new Paragraph(opts));
      runs = []; text = '';
    }
    async function inline(node, st) {
      if (node.nodeType === 3) {
        const t = node.nodeValue.replace(/\s+/g, ' ');
        if (!t) return;
        text += t;
        const o = { text: t, bold: st.b, italics: st.i, strike: st.s, color: st.color || undefined, size: st.size || undefined, font: st.font || undefined, rightToLeft: HEB.test(t) };
        if (st.u) o.underline = { type: UnderlineType.SINGLE };
        if (st.bg) o.shading = { type: ShadingType.CLEAR, fill: st.bg, color: 'auto' };
        runs.push(new TextRun(o));
        return;
      }
      if (node.nodeType !== 1) return;
      const tag = node.tagName;
      if (tag === 'BR') { runs.push(new TextRun({ break: 1 })); return; }
      if (tag === 'IMG') {
        const src = node.getAttribute('src') || '';
        const m = /^data:image\/(png|jpeg|jpg|gif|webp);base64,(.*)$/i.exec(src);
        if (!m) return;
        let type = m[1].toLowerCase(); if (type === 'jpeg') type = 'jpg';
        if (type === 'webp') return;
        let [w, h] = await imgSize(src);
        const k = Math.min(1, 480 / w); w = Math.round(w * k); h = Math.round(h * k);
        runs.push(new ImageRun({ type, data: b64ToBytes(m[2]), transformation: { width: w, height: h } }));
        return;
      }
      const s2 = Object.assign({}, st);
      if (tag === 'B' || tag === 'STRONG' || /^(bold|[6-9]00)$/.test(style(node, 'fontWeight'))) s2.b = true;
      if (tag === 'I' || tag === 'EM' || style(node, 'fontStyle') === 'italic') s2.i = true;
      if (tag === 'U' || /underline/.test(style(node, 'textDecoration') + style(node, 'textDecorationLine'))) s2.u = true;
      if (tag === 'S' || tag === 'STRIKE' || tag === 'DEL' || /line-through/.test(style(node, 'textDecoration') + style(node, 'textDecorationLine'))) s2.s = true;
      if (tag === 'FONT') {
        if (node.getAttribute('color')) s2.color = hex(node.getAttribute('color')) || s2.color;
        if (node.getAttribute('face')) s2.font = node.getAttribute('face').split(',')[0].replace(/["']/g, '').trim();
        const sz = node.getAttribute('size'); if (sz && FSIZE[sz]) s2.size = FSIZE[sz] * 2;
      }
      const c = hex(style(node, 'color')); if (c) s2.color = c;
      const bg = hex(style(node, 'backgroundColor')); if (bg) s2.bg = bg;
      const fs = /^([\d.]+)px$/.exec(style(node, 'fontSize')); if (fs) s2.size = Math.round(parseFloat(fs[1]) * 1.5);
      const ff = style(node, 'fontFamily'); if (ff) s2.font = ff.split(',')[0].replace(/["']/g, '').trim();
      if (tag === 'A') { s2.u = true; s2.color = s2.color || '1A73E8'; }
      for (const ch of node.childNodes) await inline(ch, s2);
    }

    async function walk(node, st) {
      if (node.nodeType === 1 && BLOCK.has(node.tagName)) {
        await flush();
        const tag = node.tagName;
        if (tag === 'UL' || tag === 'OL') {
          const cl = node.classList.contains('cl');
          let inst = 0;
          if (tag === 'OL') { inst = ++listInstance; }
          const level = (ctx.list ? ctx.list.level + 1 : 0);
          for (const li of node.children) {
            if (li.tagName !== 'LI') continue;
            const sub = Object.assign({}, ctx, {
              list: cl ? null : { ref: tag === 'OL' ? 'num' : 'bul', level, instance: inst }
            });
            let prefix = cl ? (li.classList.contains('done') ? '☑ ' : '☐ ') : '';
            // direct inline content of li, then nested lists
            const holder = dom.createElement('div');
            const nested = [];
            for (const ch of li.childNodes) {
              if (ch.nodeType === 1 && (ch.tagName === 'UL' || ch.tagName === 'OL')) nested.push(ch); else holder.appendChild(ch.cloneNode(true));
            }
            if (prefix) holder.insertBefore(dom.createTextNode(prefix), holder.firstChild);
            out.push(...await blocks(holder, sub));
            for (const n of nested) {
              const w = dom.createElement('div'); w.appendChild(n.cloneNode(true));
              out.push(...await blocks(w, Object.assign({}, sub, { list: { ref: 'bul', level: level + 1, instance: 0 } })));
            }
          }
          return;
        }
        if (tag === 'TABLE') {
          const rows = [];
          for (const tr of node.querySelectorAll('tr')) {
            const cells = [];
            for (const td of tr.children) {
              if (td.tagName !== 'TD' && td.tagName !== 'TH') continue;
              let kids = await blocks(td, ctx);
              if (!kids.length) kids = [new Paragraph('')];
              const co = { children: kids };
              const cs = parseInt(td.getAttribute('colspan') || '1', 10), rs = parseInt(td.getAttribute('rowspan') || '1', 10);
              if (cs > 1) co.columnSpan = cs;
              if (rs > 1) co.rowSpan = rs;
              const bgc = hex(style(td, 'backgroundColor'));
              if (bgc) co.shading = { type: ShadingType.CLEAR, fill: bgc, color: 'auto' };
              cells.push(new TableCell(co));
            }
            if (cells.length) rows.push(new TableRow({ children: cells }));
          }
          if (rows.length) {
            const to = { rows, width: { size: 100, type: WidthType.PERCENTAGE }, visuallyRightToLeft: node.getAttribute('dir') ? node.getAttribute('dir') === 'rtl' : HEB.test(node.textContent) };
            const cols = Array.from(node.querySelectorAll('col')).map(c => parseFloat((c.getAttribute('style') || '').replace(/.*width:\s*([\d.]+)%.*/, '$1')));
            if (cols.length && cols.every(n => n > 0)) to.columnWidths = cols.map(n => Math.round(n * 90));
            out.push(new Table(to)); out.push(new Paragraph(''));
          }
          return;
        }
        const align = (node.style && node.style.textAlign) || node.getAttribute('align') || ctx.align;
        const sub = Object.assign({}, ctx, { align });
        const saved = pctx; pctx = sub;
        let st2 = st;
        if (HEAD[tag]) { st2 = Object.assign({}, st, { b: true, size: HEAD[tag] }); sub.heading = true; }
        for (const ch of node.childNodes) {
          if (ch.nodeType === 1 && BLOCK.has(ch.tagName)) { await flush(); await walkBlockChild(ch, sub, st2); }
          else await inline(ch, st2);
        }
        await flush(true);
        pctx = saved;
        return;
      }
      await inline(node, st);
    }
    async function walkBlockChild(ch, sub, st) {
      const w = dom.createElement('div'); w.appendChild(ch.cloneNode(true));
      out.push(...await blocks(w, sub));
    }

    for (const ch of parent.childNodes) {
      if (ch.nodeType === 1 && BLOCK.has(ch.tagName)) await walk(ch, {});
      else await inline(ch, {});
    }
    await flush();
    return out;
  }

  const children = await blocks(dom.body, {});
  if (!children.length) children.push(new Paragraph(''));
  const numbering = { config: [{ reference: 'bul', levels: bulletLevels }, { reference: 'num', levels: numLevels }] };
  const doc = new Document({
    title: title || 'מסמך',
    numbering,
    styles: { default: { document: { run: { font: 'Arial', size: 24 } } } },
    sections: [{ properties: {}, children }]
  });
  return await Packer.toBase64String(doc);
}

async function docxToHtml(b64) {
  const ab = b64ToBytes(b64).buffer;
  const r = await mammoth.convertToHtml({ arrayBuffer: ab }, {
    styleMap: ['u => u', 'strike => s', "p[style-name='Title'] => h1:fresh", "p[style-name='Heading 1'] => h1:fresh", "p[style-name='Heading 2'] => h2:fresh", "p[style-name='Heading 3'] => h3:fresh"]
  });
  try { return await tableLayout(b64, r.value); } catch (e) { return r.value; }
}

const WNS = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main';
const wattr = (el, n) => el ? (el.getAttributeNS(WNS, n) || el.getAttribute('w:' + n)) : null;
function kids(el, name) { return Array.from(el.children).filter(c => c.localName === name); }

/** mammoth drops the table design; read widths, direction and cell shading from the docx itself. */
async function tableLayout(b64, html) {
  const zip = await JSZip.loadAsync(b64ToBytes(b64));
  const f = zip.file('word/document.xml'); if (!f) return html;
  const xml = new DOMParser().parseFromString(await f.async('string'), 'application/xml');
  const body = xml.getElementsByTagNameNS(WNS, 'body')[0]; if (!body) return html;
  const tbls = kids(body, 'tbl');
  const doc = new DOMParser().parseFromString('<body>' + html + '</body>', 'text/html');
  const hts = Array.from(doc.body.children).filter(e => e.tagName === 'TABLE');
  if (!tbls.length || tbls.length !== hts.length) return html;
  tbls.forEach((t, ti) => {
    const ht = hts[ti];
    const pr = kids(t, 'tblPr')[0];
    const bidi = pr && kids(pr, 'bidiVisual')[0];
    ht.setAttribute('dir', bidi ? 'rtl' : 'ltr');
    const grid = kids(t, 'tblGrid')[0];
    if (grid) {
      const ws = kids(grid, 'gridCol').map(c => parseInt(wattr(c, 'w') || '0', 10)).filter(n => n > 0);
      const tot = ws.reduce((a, b) => a + b, 0);
      if (ws.length && tot) {
        const cg = doc.createElement('colgroup');
        ws.forEach(w => { const c = doc.createElement('col'); c.setAttribute('style', 'width:' + (w * 100 / tot).toFixed(2) + '%'); cg.appendChild(c); });
        ht.insertBefore(cg, ht.firstChild);
      }
    }
    const hrows = Array.from(ht.querySelectorAll('tr'));
    kids(t, 'tr').forEach((tr, ri) => {
      const hr = hrows[ri]; if (!hr) return;
      const hcells = Array.from(hr.children).filter(c => c.tagName === 'TD' || c.tagName === 'TH');
      const tcs = kids(tr, 'tc').filter(tc => {
        const tcPr = kids(tc, 'tcPr')[0], vm = tcPr && kids(tcPr, 'vMerge')[0];
        return !(vm && wattr(vm, 'val') !== 'restart');
      });
      tcs.forEach((tc, ci) => {
        const hc = hcells[ci]; if (!hc) return;
        const tcPr = kids(tc, 'tcPr')[0], shd = tcPr && kids(tcPr, 'shd')[0];
        const fill = shd && wattr(shd, 'fill');
        if (fill && /^[0-9a-f]{6}$/i.test(fill)) hc.setAttribute('style', 'background-color:#' + fill);
      });
    });
  });
  return doc.body.innerHTML;
}

window.DocLib = { htmlToDocxB64, docxToHtml };
