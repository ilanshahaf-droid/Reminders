import * as pdfjsLib from 'pdfjs-dist/legacy/build/pdf.min.mjs';
import { PDFName, PDFArray, PDFDict } from 'pdf-lib';

/* Form-field detection + filling helpers, ported from My-Forms ("חתימה על מסמך"). */
const vpRect = (v, r) => { const t = v.transform, p = (x, y) => [t[0] * x + t[2] * y + t[4], t[1] * x + t[3] * y + t[5]]; const q1 = p(r[0], r[1]), q2 = p(r[2], r[3]); return [q1[0], q1[1], q2[0], q2[1]]; };
const uid = () => Math.random().toString(36).slice(2, 9);
export const dmyFull = () => { const n = new Date(); return `${n.getDate()}/${n.getMonth() + 1}/${n.getFullYear()}`; };
export const store = {
  get(k) { try { return localStorage.getItem(k) || ''; } catch (_) { return ''; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch (_) {} }
};

const GENERIC = /^(text|field|untitled|check ?(box)?|button|fill|radio|combo|list|dropdown|sign(ature)?|טקסט|שדה)[\s_-]*\d*$/i;
const BLANK = /_{3,}|\.{6,}|…{2,}/;   // בלי g: לפיצול ולהחלפה
const blankG = () => /_{3,}|\.{6,}|…{2,}/g;   // חדש לכל לולאה
const cleanLabel = (s, head) => String(s || "").replace(/_{2,}|\.{3,}|…+/g, " ").replace(/[:\s]+$/,"").replace(/^[:\s]+/,"").replace(/\s+/g," ").trim()
  .replace(/(^|\s)([\u05d0-\u05ea])\.([\u05d0-\u05ea])(?=\s|$)/g, (m, a, x, y) => a + y + "." + x)   // "ז.ת" שהתהפך -> "ת.ז"
  .replace(/^(.{40}).+$/, head ? "$1…" : "$&").slice(head ? 0 : -40);
const mctx = document.createElement("canvas").getContext("2d");
function posFrac(str, i){ mctx.font = "20px Arial, sans-serif"; const all = mctx.measureText(str).width || 1; return mctx.measureText(str.slice(0, i)).width / all; }
// טבלאות: פס כותרת (מלבן נמוך עם טקסט) – השדה הוא התא הריק שמעליו, או השורות הריקות שמתחתיו (כותרת של טבלה)
async function detectCells(pg, texts, frac, pi, OUT){
  const OPS = pdfjsLib.OPS, ol = await pg.getOperatorList(), H = [], R = [];
  let ctm = [1,0,0,1,0,0]; const st = [];
  const mul = (m, n) => [m[0]*n[0]+m[2]*n[1], m[1]*n[0]+m[3]*n[1], m[0]*n[2]+m[2]*n[3], m[1]*n[2]+m[3]*n[3], m[0]*n[4]+m[2]*n[5]+m[4], m[1]*n[4]+m[3]*n[5]+m[5]];
  const T = (x, y) => [ctm[0]*x + ctm[2]*y + ctm[4], ctm[1]*x + ctm[3]*y + ctm[5]];
  ol.fnArray.forEach((fn, i) => { const a = ol.argsArray[i];
    if(fn === OPS.save) st.push(ctm.slice()); else if(fn === OPS.restore) ctm = st.pop() || ctm; else if(fn === OPS.transform) ctm = mul(ctm, a);
    else if(fn === OPS.constructPath){
      // pdf.js >= 4: a = [paintOp, [Float32Array path], minMax]; path codes 0 move, 1 line, 2 curve, 3 quad, 4 close
      const data = a[1] && a[1][0]; if(!data || typeof data.length !== "number") return;
      let sub = [], k = 0;
      const flush = () => {
        if(sub.length >= 4 && sub.length <= 5){
          const xs = sub.map(p => p[0]), ys = sub.map(p => p[1]), x0 = Math.min(...xs), x1 = Math.max(...xs), y0 = Math.min(...ys), y1 = Math.max(...ys);
          const axis = sub.every(p => (Math.abs(p[0] - x0) < 0.5 || Math.abs(p[0] - x1) < 0.5) && (Math.abs(p[1] - y0) < 0.5 || Math.abs(p[1] - y1) < 0.5));
          if(axis && x1 - x0 > 0.5 && y1 - y0 > 0.5){ R.push({ x0, x1, y0, y1 }); if(x1 - x0 > 15) H.push({ x0, x1, y: y0 }, { x0, x1, y: y1 }); sub = []; return; }
        }
        for(let q = 1; q < sub.length; q++){ const p = sub[q], c = sub[q - 1];
          if(Math.abs(p[1] - c[1]) < 0.8 && Math.abs(p[0] - c[0]) > 15) H.push({ x0: Math.min(p[0], c[0]), x1: Math.max(p[0], c[0]), y: (p[1] + c[1]) / 2 }); }
        sub = [];
      };
      while(k < data.length){
        const op = data[k++];
        if(op === 0){ flush(); sub = [T(data[k], data[k + 1])]; k += 2; }
        else if(op === 1){ sub.push(T(data[k], data[k + 1])); k += 2; }
        else if(op === 2){ k += 6; sub = []; }
        else if(op === 3){ k += 4; sub = []; }
        else if(op === 4){ if(sub.length) sub.push(sub[0]); }
        else break;
      }
      flush();
    }
  });
  if(!H.length) return;
  const isMark = s => /^[-✗✘✓✔×xX○◯☐□▢❑*]+$/.test(s.trim());
  const textIn = (x0, x1, y0, y1) => texts.some(t => { if(isMark(t.str)) return false; const cy = t.y + t.h * 0.35; return Math.min(t.x + t.w, x1) - Math.max(t.x, x0) > 2 && cy > y0 && cy < y1; });
  const covers = (h, x0, x1) => Math.min(h.x1, x1) - Math.max(h.x0, x0) > (x1 - x0) * 0.6;
  const strips = R.filter(r => r.y1 - r.y0 > 6 && r.y1 - r.y0 < 20 && r.x1 - r.x0 > 20).map(r => ({ ...r, txt: texts.filter(t => !isMark(t.str) && t.x + t.w / 2 > r.x0 && t.x + t.w / 2 < r.x1 && t.y > r.y0 - 1 && t.y < r.y1 - 2) }))
    .filter(r => r.txt.length && r.txt.every(t => t.h < 12) && r.y0 > 28);   // לא בשולי הדף (מספר עמוד וכו')
  const add = (x0, x1, y0, y1, label) => { const r = frac([x0 + 1.5, y0 + 1, x1 - 1.5, y1 - 1]);
    OUT.push({ id: uid(), src: "line", page: pi, ...r, label, kind: /חתימ/.test(label) ? "sig" : "text", value: "", cell: true }); };
  strips.forEach(S => {
    const label = cleanLabel(S.txt.sort((a, b) => b.y - a.y || b.x - a.x).map(t => t.str).join(" ").replace(/\*/g, ""));
    const up = H.filter(h => h.y > S.y1 + 2 && covers(h, S.x0, S.x1)).sort((a, b) => a.y - b.y)[0];
    if(up && up.y - S.y1 >= 8 && up.y - S.y1 <= 46 && !textIn(S.x0, S.x1, S.y1 + 0.5, up.y - 0.5) && !strips.some(o => o !== S && o.y0 > S.y1 && o.y0 < up.y && covers(o, S.x0, S.x1))){
      add(S.x0, S.x1, S.y1, up.y, label); return; }
    // כותרת של טבלה: שורות ריקות מתחת
    let top = S.y0, row = 0; const rows = [];
    while(row < 12){
      const dn = H.filter(h => h.y < top - 2 && covers(h, S.x0, S.x1)).sort((a, b) => b.y - a.y)[0];
      if(!dn || top - dn.y < 8 || top - dn.y > 40 || textIn(S.x0, S.x1, dn.y + 0.5, top - 0.5) || strips.some(o => o !== S && o.y1 > dn.y + 1 && o.y0 < top - 1 && covers(o, S.x0, S.x1))) break;
      row++; rows.push([dn.y, top]); top = dn.y;
    }
    if(rows.length >= 2) rows.forEach(([y0, y1], k) => add(S.x0, S.x1, y0, y1, `${label} – שורה ${k + 1}`));
  });
}
export async function detectFields(pg, v1, pi){
  const OUT = [];
  const W = v1.width, H = v1.height;
  const frac = r => { const [a,b,c,d] = vpRect(v1, r); const x0 = Math.min(a,c), x1 = Math.max(a,c), y0 = Math.min(b,d), y1 = Math.max(b,d);
    return { x: x0 / W, y: y0 / H, w: (x1 - x0) / W, h: (y1 - y0) / H }; };
  let tc = null; try{ tc = await pg.getTextContent(); }catch(_){}
  const texts = (tc ? tc.items : []).filter(t => t.str && t.str.trim()).map(t => ({ str: t.str, dir: t.dir, x: t.transform[4], y: t.transform[5], w: t.width, h: Math.hypot(t.transform[2], t.transform[3]) || t.height || 10 }));
  // כותרת לשדה: טקסט באותה שורה – קודם מימין (עברית), אחר כך משמאל, ואם אין – מעל
  const words = () => texts.filter(t => !/^[\ue000-\uf8ff○◯☐□▢❑✗✘✓✔]+$/.test(t.str.trim()));   // בלי סמלים (עיגולים, ✗)
  const labelNear = (x0, x1, yb, h) => {
    const same = words().filter(t => Math.abs(t.y - yb) < Math.max(h, t.h) * 0.45 && t.str.replace(blankG(), "").trim());
    let best = null, bd = 1e9;
    same.forEach(t => { const d = t.x - x1; if(d > -3 && d < 260 && d < bd){ bd = d; best = t; } });
    if(best) return cleanLabel(best.str.split(BLANK).filter(z => z.trim()).pop());
    bd = 1e9; same.forEach(t => { const d = x0 - (t.x + t.w); if(d > -3 && d < 200 && d < bd){ bd = d; best = t; } });
    if(best) return cleanLabel(best.str.split(BLANK).filter(z => z.trim())[0]);
    bd = 1e9; words().forEach(t => { const d = t.y - yb; if(d > 0 && d < h * 2.6 && t.x < x1 + 20 && t.x + t.w > x0 - 20 && d < bd){ bd = d; best = t; } });
    return best ? cleanLabel(best.str) : "";
  };
  let anns = []; try{ anns = await pg.getAnnotations(); }catch(_){}
  const wid = anns.filter(a => a.subtype === "Widget" && a.fieldType && !a.hidden && !a.pushButton && !a.readOnly);
  const radios = {};
  wid.forEach(a => {
    const r = frac(a.rect), nm = String(a.fieldName || ""), last = nm.split(".").pop();
    let label = a.alternativeText || (last && !GENERIC.test(last) ? last.replace(/[_]+/g, " ").trim() : "");
    if(!label){ const yb = Math.min(a.rect[1], a.rect[3]); label = labelNear(Math.min(a.rect[0], a.rect[2]), Math.max(a.rect[0], a.rect[2]), yb + 2, Math.abs(a.rect[3] - a.rect[1])); }
    const base = { id: uid(), src: "acro", name: nm, page: pi, ...r, label: cleanLabel(label) };
    const isSig = /חתימ/.test(label) || /^sign(ature)?[\s_-]*\d*$/i.test(last);
    if(a.fieldType === "Tx") OUT.push({ ...base, kind: isSig ? "sig" : "text", value: typeof a.fieldValue === "string" ? a.fieldValue : "" });
    else if(a.fieldType === "Sig") OUT.push({ ...base, kind: "sig", value: "" });
    else if(a.fieldType === "Ch") OUT.push({ ...base, kind: "select", options: (a.options || []).map(o => o.displayValue || o.exportValue), value: [].concat(a.fieldValue || [])[0] || "" });
    else if(a.fieldType === "Btn" && a.radioButton){
      const g = radios[nm] = radios[nm] || { ...base, kind: "radio", options: [], value: "" };
      const opt = String(a.buttonValue || ""); g.options.push({ value: opt, page: pi, ...r, label: labelNear(Math.min(a.rect[0], a.rect[2]), Math.max(a.rect[0], a.rect[2]), Math.min(a.rect[1], a.rect[3]) + 2, Math.abs(a.rect[3] - a.rect[1])) || opt });
      if(a.fieldValue && a.fieldValue === a.buttonValue) g.value = opt;
    }
    else if(a.fieldType === "Btn") OUT.push({ ...base, kind: "check", value: !!(a.fieldValue && a.fieldValue !== "Off") });
  });
  Object.values(radios).forEach(g => { const o = g.options[0]; Object.assign(g, { page: o.page, x: o.x, y: o.y, w: o.w, h: o.h, label: g.label || "בחירה" }); OUT.push(g); });
  if(wid.length) return OUT;
  // אין שדות אמיתיים: 1) טבלאות – תא ריק ליד פס כותרת; 2) קווים ריקים; 3) עיגולים וריבועי סימון
  try{ await detectCells(pg, texts, frac, pi, OUT); }catch(_){}
  const GLY = /^[\ue000-\uf8ff○◯☐□▢❑]$/;
  texts.filter(t => GLY.test(t.str.trim())).forEach(t => {
    const sz = Math.max(t.w, t.h * 0.8), same = texts.filter(z => z !== t && !GLY.test(z.str.trim()) && Math.abs(z.y - t.y) < t.h * 0.6);
    let best = null, bd = 30;
    same.forEach(z => { const g = Math.min(Math.abs(z.x + z.w - t.x), Math.abs(t.x + sz - z.x)); if(g < bd){ bd = g; best = z; } });
    const r = frac([t.x, t.y - t.h * 0.12, t.x + sz, t.y + t.h * 0.88]);
    if(!OUT.some(f => f.page === pi && Math.abs(f.x - r.x) < 0.004 && Math.abs(f.y - r.y) < 0.004))
      OUT.push({ id: uid(), src: "line", page: pi, ...r, label: best ? cleanLabel(best.str, true) : "", kind: "check", value: false });
  });
  texts.forEach(t => {
    const n = t.str.length, re = blankG(); let m, prev = 0;
    while((m = re.exec(t.str))){
      const a = m.index, b = a + m[0].length; let x0, x1;
      const fa = posFrac(t.str, a), fb = posFrac(t.str, b);   // לפי רוחב התווים האמיתי (נקודות צרות מאותיות)
      if(t.dir === "rtl"){ x1 = t.x + t.w - fa * t.w; x0 = t.x + t.w - fb * t.w; } else { x0 = t.x + fa * t.w; x1 = t.x + fb * t.w; }
      if(x1 - x0 >= 18){
        // הכותרת שמימין לקו: בשורה הפוכה (ltr) היא אחרי הקו במחרוזת, בשורה רגילה (rtl) – לפניו
        const nb = blankG(); nb.lastIndex = b; const nx = nb.exec(t.str), next = nx ? nx.index : n;
        const right = cleanLabel(t.dir === "rtl" ? t.str.slice(prev, a) : t.str.slice(b, next)), left = cleanLabel(t.dir === "rtl" ? t.str.slice(b, next) : t.str.slice(prev, a));
        const label = right || (left && !/[\u0590-\u05ff]/.test(left) ? left : "") || labelNear(x0, x1, t.y, t.h) || left;
        OUT.push({ id: uid(), src: "line", page: pi, ...frac([x0, t.y - t.h * 0.22, x1, t.y + t.h * 1.0]), label, kind: /חתימ/.test(label) ? "sig" : "text", value: "" });
      }
      prev = b;
    }
    const cb = /[☐□▢❑]/g; let c; if(t.str.trim().length <= 1) return;
    while((c = cb.exec(t.str))){
      const a = c.index, cw = t.w / n, cx = t.dir === "rtl" ? t.x + t.w - (a + 1) * cw : t.x + a * cw, sz = Math.max(cw, t.h * 0.8);
      const lab = cleanLabel(t.str.slice(a + 1).split(/[☐□▢❑]/)[0]) || labelNear(cx, cx + sz, t.y, t.h);
      OUT.push({ id: uid(), src: "line", page: pi, ...frac([cx, t.y - t.h * 0.1, cx + sz, t.y + t.h * 0.85]), label: lab, kind: "check", value: false });
    }
  });
  return OUT;
}

export function dedupe(fields){
  const ov = (a, b) => a.page === b.page && Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x) > 0.3 * Math.min(a.w, b.w) && Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y) > 0.3 * Math.min(a.h, b.h);
  return fields.filter(f => !(f.cell && fields.some(g => !g.cell && g.kind !== "check" && ov(f, g))));
}
// מילוי אוטומטי מ"הפרטים שלי"
export function autofill(fields){
  const name = (store.get("pv_name") || "").trim(), tz = (store.get("pv_tz") || "").trim(), parts = name.split(/\s+/);
  const used = {};   // השם והת.ז שלי – רק בשדה הראשון מכל סוג (השאר שייכים לאנשים אחרים בטופס), ולא בשורות של טבלה
  fieldOrder(fields).forEach(f => {
    if(f.kind !== "text" || f.value || / – שורה \d+$/.test(f.label)) return; const l = f.label; let v = "", key = "";
    if(/שם\s*פרטי/.test(l) && !/משפחה/.test(l)){ v = parts[0] || ""; key = "first"; }
    else if(/שם\s*ה?משפחה/.test(l) && !/פרטי/.test(l)){ v = parts.slice(1).join(" "); key = "last"; }
    else if(/^שם$|שם\s*מלא|שם\s*פרטי\s*ו|שם\s*ה?(עובד|מבקש|מטפל|פסיכולוג|חותם|ממלא|מגיש)/.test(l)){ v = name; key = "name"; }
    else if(/ת\.?\s?ז|ז\.\s?ת|תעודת\s*זהות|מס(פר|[׳'])?\s*זהות/.test(l)){ v = tz; key = "tz"; }
    else if(/^תאריך$|^תאריך\s*(החתימה|הבקשה|מילוי|היום)/.test(l)) v = dmyFull();
    if(key){ if(used[key]) return; used[key] = true; }
    if(v){ f.value = v; f.auto = true; }
  });
}
export function dropFields(doc, names){
  const N = PDFName.of.bind(PDFName), ctx = doc.context;
  const txt = v => v && v.decodeText ? v.decodeText() : (v ? String(v) : "");
  const fullName = d => { const parts = []; let n = d, guard = 0; while(n && guard++ < 20){ const t = n.get(N("T")); if(t) parts.unshift(txt(t)); const p = n.get(N("Parent")); n = p ? ctx.lookup(p) : null; } return parts.join("."); };
  const ours = d => d instanceof PDFDict && d.get(N("Subtype")) === N("Widget") && names.has(fullName(d));
  doc.getPages().forEach(pg => {
    const arr = pg.node.lookup(N("Annots")); if(!(arr instanceof PDFArray)) return;
    const keep = arr.asArray().filter(r => !ours(ctx.lookup(r)));
    if(keep.length !== arr.size()) pg.node.set(N("Annots"), ctx.obj(keep));
  });
  const af = doc.catalog.lookup(N("AcroForm")); if(!(af instanceof PDFDict)) return;
  const fs = af.lookup(N("Fields")); if(!(fs instanceof PDFArray)) return;
  const rest = fs.asArray().filter(r => { const d = ctx.lookup(r), nm = fullName(d); return !(names.has(nm) || [...names].some(x => x.startsWith(nm + "."))); });
  if(rest.length) af.set(N("Fields"), ctx.obj(rest)); else doc.catalog.delete(N("AcroForm"));
}

export const fieldOrder = fields => fields.slice().sort((a, b) => a.page - b.page || (Math.abs(a.y - b.y) > 0.008 ? a.y - b.y : (b.x + b.w) - (a.x + a.w)));
export const filledF = f => f.kind === 'check' ? f.value === true : !!f.value;
