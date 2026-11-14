#!/usr/bin/env python3
"""Inlines editor/note-editor.{css,js} into the phone asset and into web/index.html (between the NE markers)."""
import re, pathlib
root = pathlib.Path(__file__).resolve().parent.parent
css = (root / 'editor/note-editor.css').read_text(encoding='utf-8')
js = (root / 'editor/note-editor.js').read_text(encoding='utf-8')
t = (root / 'editor/phone-template.html').read_text(encoding='utf-8')
out = root / 'app/src/main/assets/note_editor.html'
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(t.replace('/*NE-CSS*/', css).replace('/*NE-JS*/', js), encoding='utf-8')
print('wrote', out)
idx = root / 'web/index.html'
if idx.exists():
    s = idx.read_text(encoding='utf-8')
    s2 = re.sub(r'(/\*NE-CSS-START\*/).*?(/\*NE-CSS-END\*/)', lambda m: m.group(1) + '\n' + css + '\n' + m.group(2), s, flags=re.S)
    s2 = re.sub(r'(/\*NE-JS-START\*/).*?(/\*NE-JS-END\*/)', lambda m: m.group(1) + '\n' + js + '\n' + m.group(2), s2, flags=re.S)
    if s2 != s: idx.write_text(s2, encoding='utf-8'); print('updated', idx)
