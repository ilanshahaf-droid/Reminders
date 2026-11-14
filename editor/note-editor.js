/* Rich note editor shared by the phone (WebView) and the web page. createNoteEditor(root, {onChange(html, text), theme}) */
(function(g){
  var TXT = ['#14161f','#d93025','#e8710a','#188038','#1a73e8','#9334e6','#d01884','#5f6368'];
  var HL  = ['#fff475','#fbbc04','#ccff90','#a7ffeb','#aecbfa','#d7aefb','#fdcfe8','#e8eaed'];
  var SIZES = [['קטן','2'],['רגיל','3'],['גדול','5'],['ענק','7']];

  function clean(html){
    var d = new DOMParser().parseFromString('<body>' + (html || '') + '</body>', 'text/html');
    d.body.querySelectorAll('script,style,iframe,object,embed,link,meta,form,input,textarea,button,svg').forEach(function(n){ n.remove(); });
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
    function changed(){
      if(!ed.textContent.trim() && !ed.querySelector('li,img,hr')) { if(ed.innerHTML !== '') ed.innerHTML = ''; }
      clearTimeout(timer);
      timer = setTimeout(function(){ if(opts.onChange) opts.onChange(ed.innerHTML, toText(ed)); }, 350);
    }
    function flush(){ clearTimeout(timer); if(opts.onChange) opts.onChange(ed.innerHTML, toText(ed)); }
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
    var items = [
      ['↶', function(){ cmd('undo'); }, 'ביטול'], ['↷', function(){ cmd('redo'); }, 'שחזור'], null,
      ['<b>B</b>', function(){ cmd('bold'); }, 'מודגש'], ['<i>I</i>', function(){ cmd('italic'); }, 'נטוי'],
      ['<u>U</u>', function(){ cmd('underline'); }, 'קו תחתון'], ['<s>S</s>', function(){ cmd('strikeThrough'); }, 'קו חוצה'], null,
      ['<span style="background:#fff475;padding:0 4px;border-radius:4px">A</span>', function(){ showPal('hi'); }, 'הדגשה בצבע'],
      ['<span style="color:#d93025;font-weight:700;border-bottom:3px solid #d93025">A</span>', function(){ showPal('fore'); }, 'צבע טקסט'],
      ['Aa', function(){ showPal('size'); }, 'גודל טקסט'], null,
      ['•≡', function(){ plainList('UL'); }, 'תבליטים'], ['1.', function(){ plainList('OL'); }, 'רשימה ממוספרת'], ['☑', checklist, 'רשימת סימון'], null,
      ['⇥', function(){ cmd('justifyRight'); }, 'יישור לימין'], ['≡', function(){ cmd('justifyCenter'); }, 'מרכוז'], ['⇤', function(){ cmd('justifyLeft'); }, 'יישור לשמאל'], null,
      ['⌫A', function(){ cmd('removeFormat'); }, 'נקה עיצוב']
    ];
    items.forEach(function(it){
      if(!it){ var s = document.createElement('div'); s.className = 'ne-sep'; row.appendChild(s); return; }
      var b = document.createElement('button'); b.type = 'button'; b.innerHTML = it[0]; b.setAttribute('aria-label', it[2]); b.title = it[2];
      b.addEventListener('pointerdown', function(e){ e.preventDefault(); });
      b.addEventListener('mousedown', function(e){ e.preventDefault(); });
      b.addEventListener('click', function(e){ e.preventDefault(); it[1](); });
      row.appendChild(b);
    });
    pal.addEventListener('pointerdown', function(e){ e.preventDefault(); });
    pal.addEventListener('mousedown', function(e){ e.preventDefault(); });

    ed.addEventListener('input', changed);
    ed.addEventListener('blur', flush);
    ed.addEventListener('keyup', function(e){
      if(e.key === 'Enter'){
        var s = g.getSelection(); var n = s && s.anchorNode; if(n && n.nodeType === 3) n = n.parentNode;
        while(n && n !== ed && n.tagName !== 'LI') n = n.parentNode;
        if(n && n.tagName === 'LI' && n.classList.contains('done') && !n.textContent.trim()) n.classList.remove('done');
      }
    });
    ed.addEventListener('click', function(e){
      var li = e.target; if(!li || li.tagName !== 'LI' || !li.parentNode.classList.contains('cl')) return;
      var r = li.getBoundingClientRect(), rtl = g.getComputedStyle(li).direction === 'rtl';
      var x = rtl ? r.right - e.clientX : e.clientX - r.left;
      if(x >= 0 && x <= 40){ li.classList.toggle('done'); e.preventDefault(); changed(); }
    });
    ed.addEventListener('paste', function(e){
      var t = (e.clipboardData || g.clipboardData); if(!t) return;
      e.preventDefault(); document.execCommand('insertText', false, t.getData('text/plain')); changed();
    });

    return {
      el: ed,
      setHtml: function(h){ ed.innerHTML = clean(h); },
      getHtml: function(){ return ed.innerHTML; },
      getText: function(){ return toText(ed); },
      focus: function(){ ed.focus(); },
      flush: flush,
      setTheme: function(t){ root.setAttribute('data-theme', t); }
    };
  }
  g.createNoteEditor = createNoteEditor;
  g.NoteEditorUtil = { clean: clean, toText: function(html){ var d = document.createElement('div'); d.innerHTML = clean(html); return toText(d); } };
})(window);
