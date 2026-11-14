/* Rich note editor shared by the phone (WebView) and the web page. createNoteEditor(root, {onChange(html, text), theme}) */
(function(g){
  var TXT = ['#14161f','#d93025','#e8710a','#188038','#1a73e8','#9334e6','#d01884','#5f6368'];
  var HL  = ['#fff475','#fbbc04','#ccff90','#a7ffeb','#aecbfa','#d7aefb','#fdcfe8','#e8eaed'];
  var SIZES = [['קטן','2'],['רגיל','3'],['גדול','5'],['ענק','7']];

  function clean(html){
    var d = new DOMParser().parseFromString('<body>' + (html || '') + '</body>', 'text/html');
    d.body.querySelectorAll('script,style,iframe,object,embed,link,meta,form,input,textarea,button,svg').forEach(function(n){ n.remove(); });
    d.body.querySelectorAll('img').forEach(function(n){
      if(!/^data:image\/(png|jpeg|webp|gif);base64,/i.test(n.getAttribute('src') || '')) n.remove();
      else n.removeAttribute('class');
    });
    d.body.querySelectorAll('*').forEach(function(n){
      Array.prototype.slice.call(n.attributes).forEach(function(a){
        var k = a.name.toLowerCase(), v = String(a.value);
        if(k.indexOf('on') === 0 || /javascript:/i.test(v)) n.removeAttribute(a.name);
      });
    });
    return d.body.innerHTML;
  }

  function inlineText(n){
    var s = '';
    n.childNodes.forEach(function(c){
      if(c.nodeType === 3) s += c.nodeValue;
      else if(c.nodeType === 1){
        var t = c.tagName;
        if(t === 'BR') s += '\n';
        else if(t === 'IMG') s += '[תמונה]';
        else if(t === 'UL' || t === 'OL') return;
        else s += inlineText(c);
      }
    });
    return s;
  }
  function toText(root){
    var out = [];
    function walk(n){
      n.childNodes.forEach(function(c){
        if(c.nodeType === 3){ if(c.nodeValue.trim()) out.push(c.nodeValue.replace(/\s+$/,'')); return; }
        if(c.nodeType !== 1) return;
        var t = c.tagName;
        if(t === 'UL' || t === 'OL'){
          var cl = c.classList.contains('cl'), i = 0;
          c.childNodes.forEach(function(li){
            if(li.nodeType !== 1 || li.tagName !== 'LI') return;
            i++;
            var mark = cl ? (li.classList.contains('done') ? '☑ ' : '☐ ') : (t === 'OL' ? i + '. ' : '• ');
            out.push(mark + inlineText(li).replace(/\n+$/,''));
            li.querySelectorAll(':scope > ul, :scope > ol').forEach(function(sub){ var o = toText(sub); if(o) out.push(o.split('\n').map(function(x){ return '    ' + x; }).join('\n')); });
          });
        } else if(t === 'DIV' || t === 'P' || /^H[1-6]$/.test(t) || t === 'BLOCKQUOTE'){
          if(c.querySelector('ul,ol,div,p')) walk(c); else out.push(inlineText(c).replace(/\n+$/,''));
        } else if(t === 'BR') out.push('');
        else if(t === 'IMG') out.push('[תמונה]');
        else if(inlineText(c).trim()) out.push(inlineText(c));
      });
    }
    walk(root);
    return out.join('\n').replace(/\n{3,}/g, '\n\n').trim();
  }

  function createNoteEditor(root, opts){
    opts = opts || {};
    root.innerHTML = '';
    root.classList.add('ne');
    var body = document.createElement('div'); body.className = 'ne-body';
    var ed = document.createElement('div'); ed.className = 'ne-ed'; ed.contentEditable = 'true';
    ed.setAttribute('data-ph', opts.placeholder || 'כתוב כאן…'); ed.setAttribute('dir', 'rtl'); ed.spellcheck = true;
    body.appendChild(ed);
    var bar = document.createElement('div'); bar.className = 'ne-bar';
    var pal = document.createElement('div'); pal.className = 'ne-row ne-pal hide';
    var row = document.createElement('div'); row.className = 'ne-row';
    bar.appendChild(pal); bar.appendChild(row);
    root.appendChild(body); root.appendChild(bar);

    var timer = null;
    function html(){ return ed.innerHTML.replace(/\sclass="sel"/g, ''); }
    function changed(){
      if(!ed.textContent.trim() && !ed.querySelector('li,img,hr')) { if(ed.innerHTML !== '') ed.innerHTML = ''; }
      clearTimeout(timer);
      timer = setTimeout(function(){ if(opts.onChange) opts.onChange(html(), toText(ed)); }, 350);
    }
    function flush(){ clearTimeout(timer); if(opts.onChange) opts.onChange(html(), toText(ed)); }
    function cmd(c, v){ ed.focus(); try { document.execCommand('styleWithCSS', false, c === 'hiliteColor' || c === 'foreColor'); } catch(e){} document.execCommand(c, false, v == null ? null : v); changed(); }
    function curList(){
      var s = g.getSelection(); if(!s || !s.rangeCount) return null;
      var n = s.anchorNode; if(n && n.nodeType === 3) n = n.parentNode;
      while(n && n !== ed){ if(n.tagName === 'UL' || n.tagName === 'OL') return n; n = n.parentNode; }
      return null;
    }
    function checklist(){
      ed.focus();
      var l = curList();
      if(l && l.tagName === 'UL' && !l.classList.contains('cl')){ l.classList.add('cl'); changed(); return; }
      if(l && l.tagName === 'UL' && l.classList.contains('cl')){ document.execCommand('insertUnorderedList'); changed(); return; }
      document.execCommand('insertUnorderedList');
      var n = curList(); if(n && n.tagName === 'UL') n.classList.add('cl');
      changed();
    }
    function plainList(kind){
      ed.focus();
      var l = curList();
      if(l && l.tagName === 'UL' && l.classList.contains('cl') && kind === 'UL'){ l.classList.remove('cl'); changed(); return; }
      document.execCommand(kind === 'UL' ? 'insertUnorderedList' : 'insertOrderedList');
      var n = curList(); if(n) n.classList.remove('cl');
      changed();
    }
    function showPal(kind){
      if(!pal.classList.contains('hide') && pal.getAttribute('data-k') === kind){ pal.classList.add('hide'); return; }
      pal.innerHTML = ''; pal.setAttribute('data-k', kind); pal.classList.remove('hide');
      if(kind === 'size'){
        SIZES.forEach(function(s){ var b = document.createElement('button'); b.className = 'txt'; b.textContent = s[0]; b.onclick = function(){ cmd('fontSize', s[1]); pal.classList.add('hide'); }; pal.appendChild(b); });
        return;
      }
      (kind === 'fore' ? TXT : HL).forEach(function(c){
        var b = document.createElement('button'); b.style.background = c; b.setAttribute('aria-label', c);
        b.onclick = function(){ cmd(kind === 'fore' ? 'foreColor' : 'hiliteColor', c); pal.classList.add('hide'); };
        pal.appendChild(b);
      });
      var x = document.createElement('button'); x.className = 'txt'; x.textContent = 'ללא';
      x.onclick = function(){ if(kind === 'fore') cmd('foreColor', TXT[0]); else cmd('hiliteColor', 'transparent'); pal.classList.add('hide'); };
      pal.appendChild(x);
    }

    /* ---------- images ---------- */
    var savedRange = null;
    function saveSel(){ var s = g.getSelection(); if(s && s.rangeCount && ed.contains(s.anchorNode)) savedRange = s.getRangeAt(0).cloneRange(); }
    function restoreSel(){
      ed.focus();
      var s = g.getSelection();
      if(savedRange){ s.removeAllRanges(); s.addRange(savedRange); }
      else { var r = document.createRange(); r.selectNodeContents(ed); r.collapse(false); s.removeAllRanges(); s.addRange(r); }
    }
    function putImage(url){
      restoreSel();
      document.execCommand('insertImage', false, url);
      document.execCommand('insertHTML', false, '<br>');
      changed();
    }
    function shrink(src, maxSide, type, q, cb){
      var im = new Image();
      im.onload = function(){
        var w = im.naturalWidth, h = im.naturalHeight, k = Math.min(1, maxSide / Math.max(w, h));
        var c = document.createElement('canvas'); c.width = Math.max(1, Math.round(w * k)); c.height = Math.max(1, Math.round(h * k));
        var x = c.getContext('2d'); if(type === 'image/jpeg'){ x.fillStyle = '#fff'; x.fillRect(0, 0, c.width, c.height); }
        x.drawImage(im, 0, 0, c.width, c.height);
        cb(c.toDataURL(type, q));
      };
      im.onerror = function(){ if(opts.onError) opts.onError('התמונה לא נטענה'); };
      im.src = src;
    }
    var fileIn = document.createElement('input'); fileIn.type = 'file'; fileIn.accept = 'image/*'; fileIn.multiple = true; fileIn.className = 'hide';
    root.appendChild(fileIn);
    fileIn.addEventListener('change', function(){
      var files = Array.prototype.slice.call(fileIn.files || []); fileIn.value = '';
      (function next(){
        var f = files.shift(); if(!f) return;
        var rd = new FileReader();
        rd.onload = function(){ shrink(rd.result, 1280, 'image/jpeg', 0.78, function(u){ putImage(u); next(); }); };
        rd.readAsDataURL(f);
      })();
    });
    function pickImage(){ saveSel(); fileIn.click(); }
    function selImage(e){
      ed.querySelectorAll('img.sel').forEach(function(i){ i.classList.remove('sel'); if(!i.getAttribute('class')) i.removeAttribute('class'); });
      if(e && e.target && e.target.tagName === 'IMG') e.target.classList.add('sel');
    }
    function delImage(){
      var i = ed.querySelector('img.sel'); if(i){ i.remove(); changed(); }
      else if(opts.onError) opts.onError('הקש על תמונה כדי לבחור אותה');
    }

    /* ---------- drawing ---------- */
    var INK = ['#14161f','#d93025','#e8710a','#188038','#1a73e8','#9334e6'];
    function draw(){
      saveSel();
      var ov = document.createElement('div'); ov.className = 'ne-draw';
      var top = document.createElement('div'); top.className = 'ne-dtop';
      var cv = document.createElement('canvas'); cv.className = 'ne-dcv';
      ov.appendChild(top); ov.appendChild(cv); root.appendChild(ov);
      var dpr = Math.min(g.devicePixelRatio || 1, 2), x = cv.getContext('2d');
      var color = INK[0], size = 4, erase = false, hist = [], drawing = false, last = null, dirty = false;
      function snap(){ hist.push(x.getImageData(0, 0, cv.width, cv.height)); if(hist.length > 15) hist.shift(); }
      function mk(label, fn, cls){
        var b = document.createElement('button'); b.type = 'button'; b.innerHTML = label; if(cls) b.className = cls;
        b.addEventListener('click', function(e){ e.preventDefault(); fn(b); }); top.appendChild(b); return b;
      }
      mk('✓', function(){
        if(!dirty){ ov.remove(); return; }
        shrink(cv.toDataURL('image/png'), 1000, 'image/png', 1, function(u){ ov.remove(); putImage(u); });
      }, 'ok');
      mk('✕', function(){ ov.remove(); });
      var colorBtns = [];
      INK.forEach(function(c){ var b = mk('', function(){ color = c; erase = false; mark(); }, 'sw'); b.style.background = c; colorBtns.push([b, c]); });
      var szBtns = [];
      [[3, '•'], [7, '●'], [14, '⬤']].forEach(function(z){ szBtns.push([mk(z[1], function(){ size = z[0]; mark(); }), z[0]]); });
      var eb = mk('🧽', function(){ erase = !erase; mark(); });
      function mark(){
        colorBtns.forEach(function(p){ p[0].classList.toggle('on', !erase && p[1] === color); });
        szBtns.forEach(function(p){ p[0].classList.toggle('on', p[1] === size); });
        eb.classList.toggle('on', erase);
      }
      mark();
      mk('↶', function(){ var h = hist.pop(); if(h) x.putImageData(h, 0, 0); });
      mk('🗑', function(){ snap(); x.fillStyle = '#fff'; x.fillRect(0, 0, cv.width, cv.height); dirty = true; });
      var W = Math.min(g.innerWidth, 900), H = Math.max(200, g.innerHeight - top.offsetHeight - 12);
      cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr); cv.style.width = W + 'px'; cv.style.height = H + 'px';
      x.fillStyle = '#fff'; x.fillRect(0, 0, cv.width, cv.height); x.lineCap = 'round'; x.lineJoin = 'round';
      function pos(e){ var r = cv.getBoundingClientRect(); return [(e.clientX - r.left) * cv.width / r.width, (e.clientY - r.top) * cv.height / r.height]; }
      cv.addEventListener('pointerdown', function(e){
        e.preventDefault(); snap(); drawing = true; dirty = true; try { cv.setPointerCapture(e.pointerId); } catch(_){}
        last = pos(e); x.strokeStyle = erase ? '#fff' : color; x.fillStyle = x.strokeStyle;
        x.lineWidth = (erase ? size * 3 : size) * dpr;
        x.beginPath(); x.arc(last[0], last[1], x.lineWidth / 2, 0, 6.2832); x.fill();
      });
      cv.addEventListener('pointermove', function(e){
        if(!drawing) return; e.preventDefault();
        var p = pos(e); x.beginPath(); x.moveTo(last[0], last[1]); x.lineTo(p[0], p[1]); x.stroke(); last = p;
      });
      var stop = function(){ drawing = false; };
      cv.addEventListener('pointerup', stop); cv.addEventListener('pointercancel', stop);
    }

    var ITEMS = [
      {id:'undo', h:'↶', f:function(){ cmd('undo'); }, t:'ביטול'}, {id:'redo', h:'↷', f:function(){ cmd('redo'); }, t:'שחזור'},
      {id:'b', h:'<b>B</b>', f:function(){ cmd('bold'); }, t:'מודגש'}, {id:'i', h:'<i>I</i>', f:function(){ cmd('italic'); }, t:'נטוי'},
      {id:'u', h:'<u>U</u>', f:function(){ cmd('underline'); }, t:'קו תחתון'}, {id:'s', h:'<s>S</s>', f:function(){ cmd('strikeThrough'); }, t:'קו חוצה'},
      {id:'hi', h:'<span style="background:#fff475;padding:0 4px;border-radius:4px">A</span>', f:function(){ showPal('hi'); }, t:'הדגשה בצבע'},
      {id:'fore', h:'<span style="color:#d93025;font-weight:700;border-bottom:3px solid #d93025">A</span>', f:function(){ showPal('fore'); }, t:'צבע טקסט'},
      {id:'size', h:'Aa', f:function(){ showPal('size'); }, t:'גודל טקסט'},
      {id:'ul', h:'•≡', f:function(){ plainList('UL'); }, t:'תבליטים'}, {id:'ol', h:'1.', f:function(){ plainList('OL'); }, t:'רשימה ממוספרת'},
      {id:'cl', h:'☑', f:checklist, t:'רשימת סימון'},
      {id:'img', h:'🖼', f:pickImage, t:'הוסף תמונה'}, {id:'draw', h:'✏️', f:draw, t:'ציור'}, {id:'delimg', h:'🗑️', f:delImage, t:'מחק תמונה נבחרת'},
      {id:'jr', h:'⇥', f:function(){ cmd('justifyRight'); }, t:'יישור לימין'}, {id:'jc', h:'≡', f:function(){ cmd('justifyCenter'); }, t:'מרכוז'},
      {id:'jl', h:'⇤', f:function(){ cmd('justifyLeft'); }, t:'יישור לשמאל'},
      {id:'clr', h:'⌫A', f:function(){ cmd('removeFormat'); }, t:'נקה עיצוב'}
    ];
    var byId = {}; ITEMS.forEach(function(it){ byId[it.id] = it; });
    var order = ITEMS.map(function(it){ return it.id; });
    try {
      var saved = opts.loadBar && opts.loadBar();
      if(saved){ var arr = JSON.parse(saved); if(Array.isArray(arr)) order = arr.filter(function(k){ return byId[k]; }); }
    } catch(e){}
    function saveOrder(){ try { if(opts.saveBar) opts.saveBar(JSON.stringify(order)); } catch(e){} }
    function mkBtn(html, label, fn){
      var b = document.createElement('button'); b.type = 'button'; b.innerHTML = html; b.setAttribute('aria-label', label); b.title = label;
      b.addEventListener('pointerdown', function(e){ e.preventDefault(); });
      b.addEventListener('mousedown', function(e){ e.preventDefault(); });
      b.addEventListener('click', function(e){ e.preventDefault(); fn(); });
      return b;
    }
    function renderRow(){
      row.innerHTML = '';
      order.forEach(function(k){ var it = byId[k]; row.appendChild(mkBtn(it.h, it.t, it.f)); });
      row.appendChild(mkBtn('⚙️', 'התאמת הסרגל', configure));
    }
    function configure(){
      var ov = document.createElement('div'); ov.className = 'ne-cfg';
      var box = document.createElement('div'); box.className = 'ne-cbox'; ov.appendChild(box);
      function paint(){
        box.innerHTML = '';
        var h = document.createElement('div'); h.className = 'ne-ch'; h.textContent = 'התאמת סרגל העריכה'; box.appendChild(h);
        var hint = document.createElement('div'); hint.className = 'ne-chint'; hint.textContent = 'סמן מה להציג, וסדר עם החצים'; box.appendChild(hint);
        var list = document.createElement('div'); list.className = 'ne-cl'; box.appendChild(list);
        var all = order.concat(ITEMS.map(function(i){ return i.id; }).filter(function(k){ return order.indexOf(k) < 0; }));
        all.forEach(function(k){
          var it = byId[k], on = order.indexOf(k) >= 0, r = document.createElement('div'); r.className = 'ne-cr' + (on ? '' : ' off');
          var cb = document.createElement('input'); cb.type = 'checkbox'; cb.checked = on;
          cb.onchange = function(){ if(cb.checked) order.push(k); else order.splice(order.indexOf(k), 1); saveOrder(); renderRow(); paint(); };
          var ic = document.createElement('span'); ic.className = 'ic'; ic.innerHTML = it.h;
          var lb = document.createElement('span'); lb.className = 'lb'; lb.textContent = it.t;
          r.appendChild(cb); r.appendChild(ic); r.appendChild(lb);
          function mv(d){ var i = order.indexOf(k), j = i + d; if(i < 0 || j < 0 || j >= order.length) return; order.splice(i, 1); order.splice(j, 0, k); saveOrder(); renderRow(); paint(); }
          var up = document.createElement('button'); up.type = 'button'; up.textContent = '▲'; up.disabled = !on; up.onclick = function(){ mv(-1); };
          var dn = document.createElement('button'); dn.type = 'button'; dn.textContent = '▼'; dn.disabled = !on; dn.onclick = function(){ mv(1); };
          r.appendChild(up); r.appendChild(dn); list.appendChild(r);
        });
        var f = document.createElement('div'); f.className = 'ne-cf';
        var rs = document.createElement('button'); rs.type = 'button'; rs.textContent = 'איפוס'; rs.onclick = function(){ order = ITEMS.map(function(i){ return i.id; }); saveOrder(); renderRow(); paint(); };
        var ok = document.createElement('button'); ok.type = 'button'; ok.className = 'ok'; ok.textContent = 'סיום'; ok.onclick = function(){ ov.remove(); };
        f.appendChild(rs); f.appendChild(ok); box.appendChild(f);
      }
      paint(); root.appendChild(ov);
    }
    renderRow();
    pal.addEventListener('pointerdown', function(e){ e.preventDefault(); });
    pal.addEventListener('mousedown', function(e){ e.preventDefault(); });

    body.addEventListener('click', function(e){
      if(e.target === body){ ed.focus(); var r = document.createRange(); r.selectNodeContents(ed); r.collapse(false); var sl = g.getSelection(); sl.removeAllRanges(); sl.addRange(r); }
    });
    ed.addEventListener('input', changed);
    ed.addEventListener('blur', function(){ saveSel(); flush(); });
    ed.addEventListener('keyup', function(e){
      if(e.key === 'Enter'){
        var s = g.getSelection(); var n = s && s.anchorNode; if(n && n.nodeType === 3) n = n.parentNode;
        while(n && n !== ed && n.tagName !== 'LI') n = n.parentNode;
        if(n && n.tagName === 'LI' && n.classList.contains('done') && !n.textContent.trim()) n.classList.remove('done');
      }
    });
    ed.addEventListener('click', function(e){
      selImage(e);
      var li = e.target; if(!li || li.tagName !== 'LI' || !li.parentNode.classList.contains('cl')) return;
      var r = li.getBoundingClientRect(), rtl = g.getComputedStyle(li).direction === 'rtl';
      var x = rtl ? r.right - e.clientX : e.clientX - r.left;
      if(x >= 0 && x <= 40){ li.classList.toggle('done'); e.preventDefault(); changed(); }
    });
    ed.addEventListener('paste', function(e){
      var t = (e.clipboardData || g.clipboardData); if(!t) return;
      var fl = t.files && t.files[0];
      if(fl && /^image\//.test(fl.type)){ e.preventDefault(); var rd = new FileReader(); rd.onload = function(){ shrink(rd.result, 1280, 'image/jpeg', 0.78, putImage); }; rd.readAsDataURL(fl); return; }
      e.preventDefault(); document.execCommand('insertText', false, t.getData('text/plain')); changed();
    });

    return {
      el: ed,
      setHtml: function(h){ ed.innerHTML = clean(h); },
      getHtml: html,
      getText: function(){ return toText(ed); },
      focus: function(){ ed.focus(); },
      flush: flush,
      setTheme: function(t){ root.setAttribute('data-theme', t); }
    };
  }
  g.createNoteEditor = createNoteEditor;
  g.NoteEditorUtil = { clean: clean, toText: function(html){ var d = document.createElement('div'); d.innerHTML = clean(html); return toText(d); } };
})(window);
