/* Small spreadsheet formula engine (Excel-like). Values: number | string | boolean | null | {err:'#...'} */
(function (g) {
  'use strict';
  function colNum(s) { let n = 0; for (const ch of s.toUpperCase()) n = n * 26 + ch.charCodeAt(0) - 64; return n; }
  function colName(n) { let s = ''; while (n > 0) { const m = (n - 1) % 26; s = String.fromCharCode(65 + m) + s; n = Math.floor((n - 1) / 26); } return s; }
  const ERR = (e) => ({ err: e });
  const isErr = (v) => v && typeof v === 'object' && v.err;
  class Unsupported extends Error {}

  function tokenize(src) {
    const t = []; let i = 0;
    while (i < src.length) {
      const c = src[i];
      if (/\s/.test(c)) { i++; continue; }
      if (c === '"') { let j = i + 1, s = ''; while (j < src.length) { if (src[j] === '"') { if (src[j + 1] === '"') { s += '"'; j += 2; continue; } break; } s += src[j++]; } t.push({ k: 's', v: s }); i = j + 1; continue; }
      if (/[0-9.]/.test(c)) { const m = /^(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?/.exec(src.slice(i)); t.push({ k: 'n', v: parseFloat(m[0]) }); i += m[0].length; continue; }
      if (c === "'" ) { const j = src.indexOf("'", i + 1); const name = src.slice(i + 1, j); i = j + 1; if (src[i] === '!') { i++; const m = /^\$?([A-Za-z]{1,3})\$?(\d+)(?::\$?([A-Za-z]{1,3})\$?(\d+))?/.exec(src.slice(i)); if (m) { t.push({ k: 'ref', sheet: name, a: [colNum(m[1]), +m[2]], b: m[3] ? [colNum(m[3]), +m[4]] : null }); i += m[0].length; continue; } } throw new Unsupported('ref'); }
      const m = /^(?:([A-Za-z_֐-׿][\w.֐-׿]*)!)?\$?([A-Za-z]{1,3})\$?(\d+)(?::\$?([A-Za-z]{1,3})\$?(\d+))?(?![\w(])/.exec(src.slice(i));
      if (m) { t.push({ k: 'ref', sheet: m[1] || null, a: [colNum(m[2]), +m[3]], b: m[4] ? [colNum(m[4]), +m[5]] : null }); i += m[0].length; continue; }
      const f = /^[A-Za-z_][\w.]*/.exec(src.slice(i));
      if (f) {
        const up = f[0].toUpperCase();
        if (src[i + f[0].length] === '(') { t.push({ k: 'fn', v: up }); i += f[0].length + 1; t.push({ k: 'op', v: '(' }); continue; }
        if (up === 'TRUE' || up === 'FALSE') { t.push({ k: 'b', v: up === 'TRUE' }); i += f[0].length; continue; }
        throw new Unsupported('name ' + f[0]);
      }
      const two = src.slice(i, i + 2);
      if (['<=', '>=', '<>'].includes(two)) { t.push({ k: 'op', v: two }); i += 2; continue; }
      if ('+-*/^&=<>(),%:'.includes(c)) { t.push({ k: 'op', v: c }); i++; continue; }
      throw new Unsupported('char ' + c);
    }
    return t;
  }

  function parse(tokens) {
    let p = 0;
    const peek = () => tokens[p], next = () => tokens[p++];
    const isOp = (v) => peek() && peek().k === 'op' && peek().v === v;
    function expr() { return cmp(); }
    function cmp() {
      let l = concat();
      while (peek() && peek().k === 'op' && ['=', '<>', '<', '>', '<=', '>='].includes(peek().v)) { const o = next().v; l = { t: 'bin', o, l, r: concat() }; }
      return l;
    }
    function concat() { let l = add(); while (isOp('&')) { next(); l = { t: 'bin', o: '&', l, r: add() }; } return l; }
    function add() { let l = mul(); while (isOp('+') || isOp('-')) { const o = next().v; l = { t: 'bin', o, l, r: mul() }; } return l; }
    function mul() { let l = pow(); while (isOp('*') || isOp('/')) { const o = next().v; l = { t: 'bin', o, l, r: pow() }; } return l; }
    function pow() { let l = unary(); while (isOp('^')) { next(); l = { t: 'bin', o: '^', l, r: unary() }; } return l; }
    function unary() {
      if (isOp('-')) { next(); return { t: 'neg', e: unary() }; }
      if (isOp('+')) { next(); return unary(); }
      return postfix();
    }
    function postfix() { let e = atom(); while (isOp('%')) { next(); e = { t: 'pct', e }; } return e; }
    function atom() {
      const k = next();
      if (!k) throw new Unsupported('eof');
      if (k.k === 'n') return { t: 'lit', v: k.v };
      if (k.k === 's') return { t: 'lit', v: k.v };
      if (k.k === 'b') return { t: 'lit', v: k.v };
      if (k.k === 'ref') return { t: 'ref', sheet: k.sheet, a: k.a, b: k.b };
      if (k.k === 'fn') {
        next(); // (
        const args = [];
        if (isOp(')')) { next(); return { t: 'fn', n: k.v, args }; }
        for (;;) {
          if (isOp(',')) { args.push({ t: 'lit', v: null }); next(); continue; }
          args.push(expr());
          if (isOp(',')) { next(); continue; }
          if (isOp(')')) { next(); break; }
          throw new Unsupported('args');
        }
        return { t: 'fn', n: k.v, args };
      }
      if (k.k === 'op' && k.v === '(') { const e = expr(); if (!isOp(')')) throw new Unsupported('paren'); next(); return e; }
      throw new Unsupported('token');
    }
    const e = expr();
    if (p < tokens.length) throw new Unsupported('trailing');
    return e;
  }

  const num = (v) => {
    if (isErr(v)) throw v;
    if (v === null || v === undefined || v === '') return 0;
    if (typeof v === 'boolean') return v ? 1 : 0;
    if (typeof v === 'number') return v;
    const n = Number(String(v).replace(/,/g, ''));
    if (isNaN(n)) throw ERR('#VALUE!');
    return n;
  };
  const str = (v) => { if (isErr(v)) throw v; if (v === null || v === undefined) return ''; if (typeof v === 'boolean') return v ? 'TRUE' : 'FALSE'; return String(v); };
  const truthy = (v) => { if (isErr(v)) throw v; if (typeof v === 'string') { if (/^true$/i.test(v)) return true; if (/^false$/i.test(v)) return false; throw ERR('#VALUE!'); } return !!num(v); };

  // flatten any evaluated arg (scalar or {grid}) into an array of values
  const flat = (a) => (a && a.grid ? a.grid.flat() : [a]);
  const nums = (args) => {
    const out = [];
    for (const a of args) for (const v of flat(a)) {
      if (isErr(v)) throw v;
      if (a && a.grid) { if (typeof v === 'number') out.push(v); else if (typeof v === 'boolean') { /* ignored in ranges */ } }
      else out.push(num(v));
    }
    return out;
  };
  const toDate = (s) => 0;

  function crit(c) {
    if (typeof c === 'number') return (v) => typeof v === 'number' && v === c;
    c = String(c);
    const m = /^(<=|>=|<>|<|>|=)?(.*)$/.exec(c); const op = m[1] || '=', rhs = m[2];
    const n = rhs !== '' && !isNaN(Number(rhs)) ? Number(rhs) : null;
    return (v) => {
      if (n !== null) { const vn = typeof v === 'number' ? v : (v !== null && v !== '' && !isNaN(Number(v)) ? Number(v) : null); if (vn === null) return op === '<>'; return op === '=' ? vn === n : op === '<>' ? vn !== n : op === '<' ? vn < n : op === '>' ? vn > n : op === '<=' ? vn <= n : vn >= n; }
      const s = (v === null || v === undefined) ? '' : String(v).toLowerCase(), r = rhs.toLowerCase();
      if (op === '<>') return s !== r;
      if (op !== '=') return false;
      if (/[*?]/.test(r)) return new RegExp('^' + r.replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*').replace(/\?/g, '.') + '$').test(s);
      return s === r;
    };
  }

  const FN = {
    SUM: (a) => nums(a).reduce((x, y) => x + y, 0),
    AVERAGE: (a) => { const n = nums(a); if (!n.length) throw ERR('#DIV/0!'); return n.reduce((x, y) => x + y, 0) / n.length; },
    MIN: (a) => { const n = nums(a); return n.length ? Math.min(...n) : 0; },
    MAX: (a) => { const n = nums(a); return n.length ? Math.max(...n) : 0; },
    COUNT: (a) => { let c = 0; for (const x of a) for (const v of flat(x)) if (typeof v === 'number') c++; return c; },
    COUNTA: (a) => { let c = 0; for (const x of a) for (const v of flat(x)) if (v !== null && v !== '') c++; return c; },
    COUNTBLANK: (a) => { let c = 0; for (const x of a) for (const v of flat(x)) if (v === null || v === '') c++; return c; },
    MEDIAN: (a) => { const n = nums(a).sort((x, y) => x - y); if (!n.length) throw ERR('#NUM!'); const m = n.length >> 1; return n.length % 2 ? n[m] : (n[m - 1] + n[m]) / 2; },
    PRODUCT: (a) => nums(a).reduce((x, y) => x * y, 1),
    ABS: (a) => Math.abs(num(a[0])), SQRT: (a) => { const v = num(a[0]); if (v < 0) throw ERR('#NUM!'); return Math.sqrt(v); },
    INT: (a) => Math.floor(num(a[0])), POWER: (a) => Math.pow(num(a[0]), num(a[1])),
    MOD: (a) => { const d = num(a[1]); if (d === 0) throw ERR('#DIV/0!'); const n = num(a[0]); return n - d * Math.floor(n / d); },
    ROUND: (a) => { const d = a[1] === undefined ? 0 : num(a[1]), k = Math.pow(10, d), v = num(a[0]); return Math.sign(v) * Math.round(Math.abs(v) * k + 1e-9) / k; },
    ROUNDUP: (a) => { const k = Math.pow(10, a[1] === undefined ? 0 : num(a[1])), v = num(a[0]); return Math.sign(v) * Math.ceil(Math.abs(v) * k - 1e-9) / k; },
    ROUNDDOWN: (a) => { const k = Math.pow(10, a[1] === undefined ? 0 : num(a[1])), v = num(a[0]); return Math.sign(v) * Math.floor(Math.abs(v) * k + 1e-9) / k; },
    IF: (a) => (truthy(a[0]) ? (a[1] === undefined ? true : a[1]) : (a[2] === undefined ? false : a[2])),
    IFERROR: (a) => (isErr(a[0]) ? a[1] : a[0]),
    AND: (a) => a.every((x) => flat(x).every(truthy)), OR: (a) => a.some((x) => flat(x).some(truthy)), NOT: (a) => !truthy(a[0]),
    CONCAT: (a) => a.map((x) => flat(x).map(str).join('')).join(''), CONCATENATE: (a) => a.map((x) => flat(x).map(str).join('')).join(''),
    LEN: (a) => str(a[0]).length, UPPER: (a) => str(a[0]).toUpperCase(), LOWER: (a) => str(a[0]).toLowerCase(), TRIM: (a) => str(a[0]).trim().replace(/\s+/g, ' '),
    LEFT: (a) => str(a[0]).slice(0, a[1] === undefined ? 1 : num(a[1])), RIGHT: (a) => { const s = str(a[0]), n = a[1] === undefined ? 1 : num(a[1]); return n ? s.slice(-n) : ''; },
    MID: (a) => str(a[0]).substr(num(a[1]) - 1, num(a[2])),
    TODAY: () => { const d = new Date(); return Math.floor((Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) - Date.UTC(1899, 11, 30)) / 864e5); },
    NOW: () => { const d = new Date(); return (Date.UTC(d.getFullYear(), d.getMonth(), d.getDate(), d.getHours(), d.getMinutes(), d.getSeconds()) - Date.UTC(1899, 11, 30)) / 864e5; },
    SUMIF: (a) => { const f = crit(a[1]), r = flat(a[0]), s = a[2] ? flat(a[2]) : r; let t = 0; r.forEach((v, i) => { if (f(v)) { const x = s[i]; if (typeof x === 'number') t += x; } }); return t; },
    COUNTIF: (a) => { const f = crit(a[1]); return flat(a[0]).filter((v) => f(v)).length; },
    AVERAGEIF: (a) => { const f = crit(a[1]), r = flat(a[0]), s = a[2] ? flat(a[2]) : r; let t = 0, c = 0; r.forEach((v, i) => { if (f(v) && typeof s[i] === 'number') { t += s[i]; c++; } }); if (!c) throw ERR('#DIV/0!'); return t / c; },
    VLOOKUP: (a) => {
      const key = a[0], grid = a[1].grid, col = num(a[2]) - 1, approx = a[3] === undefined ? true : truthy(a[3]);
      if (!grid || col < 0 || col >= (grid[0] || []).length) throw ERR('#REF!');
      if (!approx) { for (const row of grid) if (String(row[0]).toLowerCase() === String(key).toLowerCase()) return row[col]; throw ERR('#N/A'); }
      let best = null; for (const row of grid) { if (typeof row[0] === typeof key && row[0] <= key) best = row; } if (!best) throw ERR('#N/A'); return best[col];
    },
    ISBLANK: (a) => a[0] === null || a[0] === '', ISNUMBER: (a) => typeof a[0] === 'number', ISERROR: (a) => !!isErr(a[0]),
  };

  function compare(a, b) {
    const ta = typeof a, tb = typeof b;
    if (a === null) a = tb === 'string' ? '' : 0; if (b === null) b = ta === 'string' ? '' : 0;
    if (typeof a === 'string' && typeof b === 'string') { a = a.toLowerCase(); b = b.toLowerCase(); }
    else if (typeof a !== typeof b) { return typeof a === 'string' ? 1 : -1; }
    return a < b ? -1 : a > b ? 1 : 0;
  }

  function run(node, ctx) {
    switch (node.t) {
      case 'lit': return node.v;
      case 'neg': return -num(run(node.e, ctx));
      case 'pct': return num(run(node.e, ctx)) / 100;
      case 'ref': {
        if (!node.b) { const v = ctx.get(node.sheet, node.a[1], node.a[0]); if (isErr(v)) throw v; return v; }
        const r1 = Math.min(node.a[1], node.b[1]), r2 = Math.max(node.a[1], node.b[1]), c1 = Math.min(node.a[0], node.b[0]), c2 = Math.max(node.a[0], node.b[0]);
        const grid = []; for (let r = r1; r <= r2; r++) { const row = []; for (let c = c1; c <= c2; c++) row.push(ctx.get(node.sheet, r, c)); grid.push(row); }
        return { grid };
      }
      case 'bin': {
        const o = node.o;
        if (o === '&') return str(run(node.l, ctx)) + str(run(node.r, ctx));
        const l = run(node.l, ctx), r = run(node.r, ctx);
        if (['=', '<>', '<', '>', '<=', '>='].includes(o)) {
          if (isErr(l)) throw l; if (isErr(r)) throw r;
          const c = compare(l, r); return o === '=' ? c === 0 : o === '<>' ? c !== 0 : o === '<' ? c < 0 : o === '>' ? c > 0 : o === '<=' ? c <= 0 : c >= 0;
        }
        const a = num(l), b = num(r);
        if (o === '+') return a + b; if (o === '-') return a - b; if (o === '*') return a * b;
        if (o === '/') { if (b === 0) throw ERR('#DIV/0!'); return a / b; }
        if (o === '^') return Math.pow(a, b);
        throw new Unsupported(o);
      }
      case 'fn': {
        const f = FN[node.n];
        if (!f) throw new Unsupported(node.n);
        if (node.n === 'IF') { // lazy branches
          const c = truthy(run(node.args[0], ctx));
          const br = node.args[c ? 1 : 2]; return br ? run(br, ctx) : (c ? true : false);
        }
        if (node.n === 'IFERROR') { try { const v = run(node.args[0], ctx); return isErr(v) ? run(node.args[1], ctx) : v; } catch (e) { if (e && e.err) return run(node.args[1], ctx); throw e; } }
        const args = node.args.map((a) => run(a, ctx));
        const out = f(args);
        return out;
      }
    }
    throw new Unsupported(node.t);
  }

  const cache = new Map();
  function compile(src) {
    let n = cache.get(src);
    if (!n) { n = parse(tokenize(src)); if (cache.size > 2000) cache.clear(); cache.set(src, n); }
    return n;
  }
  // returns {ok:true, v} | {ok:false} (unsupported -> caller keeps the cached result)
  function evaluate(src, ctx) {
    try {
      const v = run(compile(src.replace(/^=/, '')), ctx);
      return { ok: true, v: v && v.grid ? v.grid[0][0] : v };
    } catch (e) {
      if (e && e.err) return { ok: true, v: e };
      if (e instanceof Unsupported) return { ok: false };
      return { ok: true, v: ERR('#VALUE!') };
    }
  }

  // shift references in a formula when rows/cols are inserted (delta>0) or deleted (delta<0); sheet = sheet that changed
  function shift(src, axis, at, delta, selfSheet, changedSheet) {
    return src.replace(/(?:(?:'([^']+)'|([A-Za-z_֐-׿][\w.֐-׿]*))!)?(\$?)([A-Za-z]{1,3})(\$?)(\d+)(?::(\$?)([A-Za-z]{1,3})(\$?)(\d+))?(?![\w(])/g,
      (m, q1, q2, d1, c1, d2, r1, d3, c2, d4, r2) => {
        const sh = q1 || q2 || selfSheet;
        if (sh !== changedSheet) return m;
        const prefix = q1 ? "'" + q1 + "'!" : q2 ? q2 + '!' : '';
        const mv = (c, r) => {
          let col = colNum(c), row = +r;
          if (axis === 'row') { if (row >= at) { if (delta < 0 && row < at - delta) return null; row += delta; } }
          else if (col >= at) { if (delta < 0 && col < at - delta) return null; col += delta; }
          return [col, row];
        };
        const a = mv(c1, r1); if (!a) return '#REF!';
        let out = prefix + d1 + colName(a[0]) + d2 + a[1];
        if (c2) { const b = mv(c2, r2) || [colNum(c2), +r2]; out += ':' + d3 + colName(b[0]) + d4 + b[1]; }
        return out;
      });
  }

  g.FX = { evaluate, colNum, colName, shift, isErr };
})(typeof window !== 'undefined' ? window : globalThis);
