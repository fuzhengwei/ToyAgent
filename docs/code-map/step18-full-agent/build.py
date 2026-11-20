
import re
tpl = open('/Users/fuzhengwei/.walicode/skills/xfg-coding-map-skills/assets/code-map-template.html', encoding='utf-8').read()
tpl = tpl.replace('<title>xfg-2-1 开发串联全景图 · 对比基线 xfg-1-3</title>','<title>Step18 FullAgent · 最小完整 Agent 代码地图</title>')
tpl = tpl.replace('<h1>xfg-2-1 开发串联全景图</h1>','<h1>Step18 FullAgent 最小完整 Agent</h1>')
tpl = tpl.replace('<p>基线 <b>xfg-1-3</b><i>→</i>本节 <b>xfg-2-1</b> · LLM 端口-适配器链路</p>','<p>基线 <b>step03 LLM 原语</b><i>→</i>本节 <b>step18</b> · 生产级 Agent 公共骨架：守卫 / 循环 / 治理 / 记忆</p>')
tpl = tpl.replace("""<div class="stat"><span class="num">20</span><span class="lbl">新增文件</span></div>
    <div class="stat"><span class="num amber">4</span><span class="lbl">调整文件</span></div>
    <div class="stat"><span class="num">+3278</span><span class="lbl">/ -1 行</span></div>""","""<div class="stat"><span class="num">1</span><span class="lbl">新增文件</span></div>
    <div class="stat"><span class="num amber">0</span><span class="lbl">调整文件</span></div>
    <div class="stat"><span class="num">+373</span><span class="lbl">/ -0 行</span></div>""")
tpl = tpl.replace('cn.xiaofuge.deepseek.harness</span></div>','cn.xiaofuge.ai.agent.step18</span></div>')
tpl = tpl.replace('<div class="cap">变更说明 · vs xfg-1-3</div>','<div class="cap">方法说明 · step18 FullAgent</div>')
tpl = tpl.replace('<h2>xfg-2-1 开发串联全景图</h2>','<h2>Step18 FullAgent 最小完整 Agent</h2>')
tpl = tpl.replace('<div class="w-sub">基线 <b>xfg-1-3</b> → 本节 <b>xfg-2-1</b> · LLM 端口-适配器链路 · 新增 20 文件 / +3278 行</div>','<div class="w-sub">基线 <b>step03</b> → 本节 <b>step18</b> · 生产级 Agent 公共骨架 · 单文件 FullAgent / +373 行 / 1 接口 + 1 Impl + 20+ 方法</div>')
tpl = tpl.replace("<li>点上方<b>「跟着流程走」</b>导航条，8 站从装配走到组装，每站自动定位联动</li>","<li>点上方<b>「跟着流程走」</b>导航条，9 站从守卫走到记忆回写，每站自动定位联动</li>")
tpl = tpl.replace('<i><span class="dot" style="background:#f1f3f6;border:1.5px dashed #9aa3af"></span>1-3 复用</i>','<i><span class="dot" style="background:#f1f3f6;border:1.5px dashed #9aa3af"></span>step03 复用</i>')
tpl = tpl.replace("xfg21-welcomed", "step18-fullagent-welcomed")
data = open('data-region.js', encoding='utf-8').read()
start = tpl.index('/* ════════ MAP-DATA-START')
end_marker = '/* ════════ MAP-DATA-END ════════ */'
end = tpl.index(end_marker) + len(end_marker)
tpl = tpl[:start] + data.rstrip('\n') + tpl[end:]
open('step18-full-agent.html','w', encoding='utf-8').write(tpl)
print('rebuilt')
