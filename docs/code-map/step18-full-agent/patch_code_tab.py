# -*- coding: utf-8 -*-
"""给 step18 代码地图追加「📜 代码」页签：内嵌 FullAgent.java 源码，行号+语法高亮，
方法签名行作为联动锚点，与树/图/说明卡四方联动。"""
import re, html

HTML = 'step18-full-agent.html'
JAVA = '/Users/fuzhengwei/coding/gitcode/KnowledgePlanet/ToyAgent/src/main/java/cn/xiaofuge/ai/agent/step18/FullAgent.java'

s = open(HTML, encoding='utf-8').read()
assert 'wrap-code' not in s, '已打过补丁'

# ---------- 1. Java 源码 → 逐行语法高亮 HTML ----------
src = open(JAVA, encoding='utf-8').read().rstrip('\n').split('\n')

KEYWORDS = {'package','import','public','private','protected','class','interface','extends','implements',
    'static','final','void','return','if','else','for','while','switch','case','default','break','continue',
    'new','this','super','try','catch','finally','throw','throws','null','true','false','int','long','double',
    'boolean','char','byte','short','float','var','record','enum','yield','instanceof','abstract','transient'}

def esc(t): return html.escape(t, quote=False)

def highlight(line):
    out, i, n = [], 0, len(line)
    while i < n:
        c = line[i]
        two = line[i:i+2]
        if two == '//':                       # 行注释
            out.append('<span class="j-com">' + esc(line[i:]) + '</span>'); break
        if c == '"':                          # 字符串（含文本块内行，简单按单行处理）
            j = i + 1
            while j < n:
                if line[j] == '\\': j += 2; continue
                if line[j] == '"': j += 1; break
                j += 1
            out.append('<span class="j-str">' + esc(line[i:j]) + '</span>'); i = j; continue
        if c == "'":                          # 字符字面量
            j = i + 1
            while j < n:
                if line[j] == '\\': j += 2; continue
                if line[j] == "'": j += 1; break
                j += 1
            out.append('<span class="j-str">' + esc(line[i:j]) + '</span>'); i = j; continue
        if c == '@':                          # 注解
            m = re.match(r'@\w+', line[i:])
            if m:
                out.append('<span class="j-ann">' + esc(m.group(0)) + '</span>'); i += len(m.group(0)); continue
        if c.isdigit():                       # 数字
            m = re.match(r'\d[\d_.]*[dDfFlL]?', line[i:])
            out.append('<span class="j-num">' + esc(m.group(0)) + '</span>'); i += len(m.group(0)); continue
        m = re.match(r'[A-Za-z_$][\w$]*', line[i:])
        if m:
            w = m.group(0)
            after = line[i+len(w):].lstrip()
            if w in KEYWORDS:
                out.append('<span class="j-kw">' + w + '</span>')
            elif after.startswith('('):
                out.append('<span class="j-fn">' + w + '</span>')
            elif re.match(r'^[A-Z]', w):
                out.append('<span class="j-cls">' + w + '</span>')
            else:
                out.append(esc(w))
            i += len(w); continue
        out.append(esc(c)); i += 1
    return ''.join(out) or '&nbsp;'

# 方法签名行（作为联动锚点）：public/private 方法、类/接口声明
SIGN_RE = re.compile(r'^\s*(public|private)\s+[\w<>\[\], .]+\s+(\w+)\(')
CLS_RE = re.compile(r'^\s*(public|final|abstract|static)*\s*(class|interface)\s+(\w+)')

rows, anchors = [], []
in_textblock = False
for idx, raw in enumerate(src, 1):
    stripped = raw.strip()
    if '"""' in raw:
        in_textblock = in_textblock ^ (raw.count('\"\"\"') % 2 == 1)
    marked = None
    m = CLS_RE.match(raw)
    if m:
        marked = m.group(3)
    else:
        m2 = SIGN_RE.match(raw)
        if m2 and not in_textblock:
            marked = m2.group(2)
    if marked:
        anchors.append((marked, idx))
    rows.append(f'<div class="cline" data-line="{idx}" id="cl-{idx}">'
                f'<span class="ln">{idx}</span><span class="cc">{highlight(raw)}</span></div>')
code_html = '\n'.join(rows)

# ---------- 2. 页签按钮 + 容器 ----------
s = s.replace('<div class="tab" data-tab="flow">🔀 流程图</div>',
              '<div class="tab" data-tab="flow">🔀 流程图</div>\n      <div class="tab" data-tab="code">📜 代码</div>')
s = s.replace('''    <div class="diagram-wrap" id="wrap-flow">
      <div class="mermaid-box" id="box-flow">
        <div class="load-msg"><div class="spin"></div>正在渲染流程图…</div>
      </div>
      <details class="src"><summary>查看流程图 Mermaid 源码</summary><pre id="src-flow"></pre></details>
    </div>''',
'''    <div class="diagram-wrap" id="wrap-flow">
      <div class="mermaid-box" id="box-flow">
        <div class="load-msg"><div class="spin"></div>正在渲染流程图…</div>
      </div>
      <details class="src"><summary>查看流程图 Mermaid 源码</summary><pre id="src-flow"></pre></details>
    </div>
    <div class="diagram-wrap" id="wrap-code">
      <div class="code-head">📄 FullAgent.java · step18 最小完整 Agent · <span id="codeMeta"></span></div>
      <div class="code-panel" id="box-code">
''' + code_html + '''
      </div>
      <details class="src"><summary>代码联动锚点</summary><pre id="src-code">绿色 ⟂ 标记的行是联动锚点（方法签名）。点击左树 / 图节点 / 说明卡 / 导览 → 对应方法行高亮并画连线；点击代码任意一行 → 反向联动树与说明卡。</pre></details>
    </div>''')

# ---------- 3. CSS ----------
s = s.replace('  .box-err { padding: 40px; color: var(--red); }',
'''  .box-err { padding: 40px; color: var(--red); }

  /* ══════════ 代码页签 ══════════ */
  .code-head { background: var(--panel); border: 1px solid var(--border); border-bottom: none;
    border-radius: 10px 10px 0 0; padding: 8px 16px; font-size: 12px; color: var(--text-2);
    font-family: var(--mono); max-width: 1020px; }
  .code-head b { color: var(--text); }
  .code-panel { background: #fff; border: 1px solid var(--border); border-radius: 0 0 10px 10px;
    max-width: 1020px; overflow: auto; font-family: var(--mono); font-size: 12px; line-height: 1.65;
    box-shadow: var(--shadow-sm); padding: 6px 0 10px; }
  .cline { display: flex; cursor: pointer; transition: background .1s; }
  .cline:hover { background: #f4f8ff; }
  .cline .ln { flex: none; width: 48px; text-align: right; padding-right: 14px; color: #b3bcc9;
    user-select: none; font-size: 10.5px; line-height: inherit; }
  .cline .cc { white-space: pre; padding-right: 20px; }
  .cline.hl { background: #fff0e6 !important; box-shadow: inset 3px 0 0 var(--orange); }
  .cline.hl2 { background: #fdf4ec !important; }
  .cline.anchor .cc::after { content: ' ⟂'; color: #d1490b; font-weight: 700; font-size: 10px; }
  .j-kw { color: #0033b3; font-weight: 600; }
  .j-str { color: #067d17; }
  .j-com { color: #8c8c8c; font-style: italic; }
  .j-num { color: #1750eb; }
  .j-ann { color: #9e880d; }
  .j-fn { color: #6a3fbf; }
  .j-cls { color: #303a4a; font-weight: 600; }''')

# ---------- 4. 引擎补丁：findSvgMatches 支持 .cline + 代码行反向联动 ----------
s = s.replace("box.querySelectorAll('text, tspan, foreignObject span, foreignObject div').forEach(t => {",
              "box.querySelectorAll('text, tspan, foreignObject span, foreignObject div, .cline').forEach(t => {")
s = s.replace('''      let g = t.closest('g.node, g.actor, g.rootGroup, g.edgeLabel, g.cluster');''',
'''      let g = t.closest('g.node, g.actor, g.rootGroup, g.edgeLabel, g.cluster, .cline');''')
# 高亮 CSS 适配普通 div（g.hl 对 rect/polygon 生效，代码行用 .cline.hl 已覆盖）
# 代码行点击反向联动：追加到 bindReverse 之后
s = s.replace('''  bindReverse();
  TABS.forEach(applyZoom);''',
'''  bindReverse();
  /* 代码行反向联动 + 锚点标记 */
  document.querySelectorAll('#box-code .cline').forEach(row => {
    row.addEventListener('click', () => activateByText((row.querySelector('.cc').textContent || '').trim()));
  });
  const ANCHORS = ''' + str({a[0]: a[1] for a in anchors}) + ''';
  Object.entries(ANCHORS).forEach(([name, ln]) => {
    const el = document.getElementById('cl-' + ln);
    if (el) el.classList.add('anchor');
  });
  document.getElementById('codeMeta').textContent = ''' + str(len(src)) + ''' 行 · ''' + str(len(anchors)) + ''' 个联动锚点;
  TABS.forEach(applyZoom);''')

open(HTML, 'w', encoding='utf-8').write(s)
print('patched:', len(rows), '行代码,', len(anchors), '个锚点:', [a[0] for a in anchors])
