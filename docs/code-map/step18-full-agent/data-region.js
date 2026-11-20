/* ════════ MAP-DATA-START ════════
   Step18 · FullAgent 最小完整 Agent 数据区。规范见技能包 references/data-spec.md */

/* ══════════════ Mermaid 图源 ══════════════ */
const SEQ_SRC = `sequenceDiagram
    autonumber
    participant U as 用户输入
    participant CHAT as Impl.chat() 🆕
    participant LOOPX as runAgentLoop() 🆕
    participant MODEL as ChatModel<br/>step03 基线
    participant TOOL as executeTool()<br/>executeMemory() 🆕
    participant CTX as clearOldToolResults()<br/>compactHistoryIfNeeded() 🆕

    U->>CHAT: "记住我叫小傅哥，查北京天气并推荐穿搭"
    CHAT->>CHAT: 输入守卫：空输入 / 长度>300 / SENSITIVE 敏感词正则
    Note over CHAT,CTX: ① 组装 messages = system(SYSTEM_PROMPT) + history + 本轮 user
    CHAT->>LOOPX: runAgentLoop(messages)
    loop step = 1..MAX_STEPS(5)
        LOOPX->>CTX: clearOldToolResults()：旧观察清成占位符
        LOOPX->>MODEL: chat(messages, toolSchemas())
        alt 返回 tool_calls
            MODEL-->>LOOPX: 结构化调用列表（memory / get_weather…）
            LOOPX->>TOOL: parseArguments() → 逐个 executeTool()
            TOOL-->>LOOPX: 观察结果 → Message.tool(id, observation)
            Note over LOOPX,TOOL: 协议配对：assistant(tool_calls) ⇄ role=tool
        else 无 tool_calls
            MODEL-->>LOOPX: 最终文本回答 → 跳出循环
        end
    end
    Note over LOOPX: 步数耗尽 → 保险丝熔断，返回固定提示
    LOOPX-->>CHAT: answer
    CHAT->>CHAT: outputGuard()：答案再过一遍敏感词
    CHAT->>CTX: rememberTurn()：history += user + assistant
    CHAT->>CTX: compactHistoryIfNeeded()：超 12 条触发压缩
    CTX-->>CHAT: COMPACT_PROMPT 摘要成功 → 摘要 + 最近 4 条<br/>调用失败 → 退化滑动窗口
    CHAT-->>U: 返回 answer`;

const FLOW_SRC = `flowchart TB
    classDef newc fill:#e6f6ec,stroke:#17a45f,stroke-width:2px,color:#0b5c3a
    classDef oldc fill:#f1f3f6,stroke:#9aa3af,stroke-width:1.2px,stroke-dasharray:5 3,color:#5a6068
    classDef warnc fill:#fff4e0,stroke:#e6a23c,stroke-width:1.6px,color:#7a4101
    classDef errc fill:#fdecec,stroke:#e5484d,stroke-width:1.8px,color:#b3232a
    classDef extc fill:#eaf1fe,stroke:#3574f0,stroke-width:1.6px,color:#1c3257

    IN["用户输入 input 🆕"]
    BLANK{"空 / 长度 > MAX_INPUT_LEN(300) ?"}
    SAFE{"SENSITIVE 正则命中 ?<br/>hack|攻击|爆破|破解|密码"}
    REJ1["拒绝：请输入内容 / 输入过长 🆕"]
    REJ2["拦截：不安全操作 🆕<br/>不调用模型直接返回"]
    MSGS["组装 messages 🆕<br/>system(SYSTEM_PROMPT) + history + user"]
    LOOP["runAgentLoop() 🆕<br/>step = 1..MAX_STEPS=5 循环体"]
    CLEAR["clearOldToolResults() 🆕<br/>只保留最近 2 条 role=tool 观察"]
    CALL["model.chat(messages, toolSchemas()) 🆕"]
    TC{"toolCalls 为空 ?"}
    EXEC["parseArguments() 解析 JSON 入参 🆕<br/>executeTool() 逐个执行"]
    W["get_weather 🆕<br/>ReActAgent.MockWeather 复用"]
    C["calculator → calc() 🆕<br/>正则一次四则运算 · 除 0 抛异常"]
    T["get_time 🆕<br/>yyyy-MM-dd HH:mm:ss"]
    M["memory → executeMemory() 🆕<br/>write：key/value 校验 → memory.put<br/>read / keys / delete"]
    OBS["Message.tool(id, 观察结果) 🆕<br/>协议配对：assistant(tool_calls) ⇄ tool"]
    FUSE["保险丝熔断 🆕<br/>已达最大步数，循环终止"]
    OGUARD["outputGuard() 🆕<br/>答案命中敏感词 → 替换提示文案"]
    REM["rememberTurn() 🆕<br/>history += user + assistant"]
    BIG{"history > COMPACT_THRESHOLD(12) ?"}
    COMPACT["compactHistoryIfNeeded() 🆕<br/>renderTranscript() + COMPACT_PROMPT 压缩为摘要<br/>摘要调用失败 → 滑动窗口保留最近 4 条"]
    RET["返回 answer 🆕"]

    IN --> BLANK
    BLANK -->|"是"| REJ1
    BLANK -->|"否"| SAFE
    SAFE -->|"命中"| REJ2
    SAFE -->|"通过"| MSGS
    MSGS --> LOOP
    LOOP --> CLEAR
    CLEAR --> CALL
    CALL --> TC
    TC -->|"是 · 无工具调用"| OGUARD
    TC -->|"否 · 有 tool_calls"| EXEC
    EXEC --> W
    EXEC --> C
    EXEC --> T
    EXEC --> M
    W --> OBS
    C --> OBS
    T --> OBS
    M --> OBS
    OBS -->|"带观察进入下一步"| LOOP
    LOOP -->|"step 耗尽"| FUSE
    FUSE --> OGUARD
    OGUARD --> REM
    REM --> BIG
    BIG -->|"否"| RET
    BIG -->|"是"| COMPACT
    COMPACT --> RET

    class IN,MSGS,LOOP,CLEAR,CALL,EXEC,W,C,T,M,OBS,OGUARD,REM,COMPACT newc
    class BLANK,SAFE,TC,BIG oldc
    class REJ1,REJ2,FUSE errc`;

/* 全景图：FullAgent 单类多方法的骨架地图 */
const OVER_SRC = `flowchart LR
    classDef modc fill:#f4f8ff,stroke:#8fb0e8,stroke-width:1.6px,color:#1c3d6e
    classDef newc fill:#e6f6ec,stroke:#17a45f,stroke-width:2px,color:#0b5c3a
    classDef oldc fill:#f1f3f6,stroke:#9aa3af,stroke-width:1.2px,stroke-dasharray:5 3,color:#5a6068
    classDef extc fill:#eaf1fe,stroke:#3574f0,stroke-width:1.6px,color:#1c3257

    subgraph ENTRY["🚪 入口与契约"]
        direction TB
        IF["FullAgent «interface» 🆕<br/>extends Agent · MAX_STEPS=5<br/>COMPACT_THRESHOLD=12 · KEEP_RECENT=4"]
        MAIN["main() 🆕<br/>Models.openAiOrNull() → mock 兜底"]
    end
    subgraph GUARD["🛡️ 双守卫"]
        direction TB
        CHAT["chat(input) 🆕<br/>主入口：守卫 → 循环 → 清洗 → 回写"]
        SENSITIVE["SENSITIVE 正则 🆕<br/>hack|攻击|爆破|破解|密码"]
        OGUARD["outputGuard() 🆕<br/>输出侧二次清洗"]
    end
    subgraph LOOPL["🔁 工具协议循环"]
        direction TB
        LOOPM["runAgentLoop() 🆕<br/>标准 ReAct 循环 + 保险丝"]
        EXEC["executeTool() 🆕<br/>switch 分发 + 异常归一化"]
        MEM["executeMemory() 🆕<br/>write/read/keys/delete"]
        CALC["calc() 🆕<br/>受限四则运算"]
        SCH["toolSchemas() 🆕<br/>4 个 function schema"]
    end
    subgraph CTX["🧹 上下文与会话治理"]
        direction TB
        HIST["history / memory 字段<br/>会话历史 + 跨轮持久记忆"]
        REM["rememberTurn() 🆕<br/>每轮写回 2 条"]
        COMPACT["compactHistoryIfNeeded() 🆕<br/>摘要压缩 / 滑动窗口兜底"]
        CLEAR["clearOldToolResults() 🆕<br/>旧工具观察清成占位符"]
        TRANS["renderTranscript() 🆕<br/>历史 → 压缩文本"]
    end
    UTIL["parseArguments() / text() 🆕<br/>trace() / reset() 🆕"]
    MODEL[("☁️ ChatModel / Json / Message<br/>step03 LLM 基线")]
    MOCK["ReActAgent.MockWeather<br/>step03 复用"]

    MAIN --> CHAT
    IF -. Impl 实现全部方法 .-> CHAT
    CHAT --> SENSITIVE
    CHAT --> LOOPM
    LOOPM --> SCH
    LOOPM --> MODEL
    LOOPM --> EXEC
    EXEC --> MEM
    EXEC --> CALC
    EXEC --> MOCK
    LOOPM --> CLEAR
    CLEAR --> HIST
    CHAT --> OGUARD
    OGUARD --> REM
    REM --> HIST
    REM --> COMPACT
    COMPACT --> TRANS
    COMPACT --> MODEL
    COMPACT --> HIST
    UTIL -. 支撑全链路 .-> CHAT

    class IF,MAIN,CHAT,SENSITIVE,OGUARD,LOOPM,EXEC,MEM,CALC,SCH,REM,COMPACT,CLEAR,TRANS,UTIL newc
    class HIST,MODEL,MOCK oldc`;

/* ══════════════ 左侧树数据 ══════════════ */
const TREE = [
  {t:'module', n:'ToyAgent', children:[
    {t:'pkg', n:'cn.xiaofuge.ai.agent.step18', children:[
      {t:'iface', n:'FullAgent «最小完整 Agent»', b:'new', doc:'iface', kw:['FullAgent'], children:[
        {t:'method', n:'常量：MAX_STEPS=5 / MAX_INPUT_LEN=300 / COMPACT_THRESHOLD=12 / KEEP_RECENT=4 / KEEP_TOOL_RESULTS=2', b:'new', doc:'iface', kw:['MAX_STEPS','COMPACT_THRESHOLD','KEEP_RECENT']},
        {t:'method', n:'chat(input)：守卫 → 循环 → 输出守卫 → 记忆回写', b:'new', doc:'chat', kw:['chat(input)','runAgentLoop']},
        {t:'method', n:'main()：装配 + 提问验收入口', b:'new', doc:'entry', kw:['main()','mock 兜底']},
      ]},
      {t:'class', n:'FullAgent.Impl', b:'new', doc:'chat', kw:['Impl'], children:[
        {t:'method', n:'chat(input)：双守卫 + 编排五件套', b:'new', doc:'chat', kw:['SENSITIVE','组装 messages']},
        {t:'method', n:'runAgentLoop()：标准工具协议循环 + MAX_STEPS 保险丝', b:'new', doc:'loop', kw:['runAgentLoop','保险丝熔断']},
        {t:'method', n:'executeTool()：get_weather / calculator / get_time / memory 分发', b:'new', doc:'exec', kw:['executeTool','get_weather','calculator']},
        {t:'method', n:'executeMemory()：write/read/keys/delete 跨轮记忆', b:'new', doc:'mem', kw:['executeMemory','memory.put']},
        {t:'method', n:'parseArguments() / asObjectMap() / text()：入参解析三件套', b:'new', doc:'util', kw:['parseArguments','text()']},
        {t:'method', n:'outputGuard()：答案敏感词二次清洗', b:'new', doc:'oguard', kw:['outputGuard','输出侧二次清洗']},
        {t:'method', n:'rememberTurn()：本轮 user+assistant 写入 history', b:'new', doc:'remember', kw:['rememberTurn','history +=']},
        {t:'method', n:'compactHistoryIfNeeded()：摘要压缩 / 滑动窗口兜底', b:'new', doc:'compact', kw:['compactHistoryIfNeeded','COMPACT_PROMPT']},
        {t:'method', n:'renderTranscript()：历史渲染成压缩输入', b:'new', doc:'compact', kw:['renderTranscript']},
        {t:'method', n:'clearOldToolResults()：保留最近 2 条工具观察，配对不变', b:'new', doc:'clear', kw:['clearOldToolResults','保留最近 2 条']},
        {t:'method', n:'calc()：正则受限四则运算', b:'new', doc:'calc', kw:['calc()','除 0 抛异常']},
        {t:'method', n:'toolSchemas() / function() / stringProperty()：4 个工具 schema', b:'new', doc:'schema', kw:['toolSchemas','function schema']},
        {t:'method', n:'trace() / reset() / trace()：观测与重置', b:'new', doc:'util', kw:['trace() / reset()']},
      ]},
    ]},
    {t:'pkg', n:'cn.xiaofuge.ai.llm（step03 复用基线）', children:[
      {t:'class', n:'ChatModel / ChatResponse / Message / ToolCall / Json', b:'old', doc:'reuse', kw:['ChatModel','Message.tool']},
      {t:'class', n:'Models（openAiOrNull / mock 工厂）', b:'old', doc:'reuse', kw:['Models.openAiOrNull','mock 兜底']},
    ]},
    {t:'pkg', n:'cn.xiaofuge.ai.agent（step03 复用基线）', children:[
      {t:'class', n:'Agent / ReActAgent.MockWeather', b:'old', doc:'reuse', kw:['ReActAgent.MockWeather','extends Agent']},
    ]},
  ]},
];

/* ══════════════ 右侧说明数据 ══════════════ */
const DOCS = [
  {id:'iface', b:'新增', t:'FullAgent 接口 — 骨架常量即参数', f:'agent/step18/FullAgent.java · +373 行 · 全新', body: `
    <ul>
      <li><b>本节主题</b>：不是把前 17 个类拼起来，而是展示生产级 Agent 的公共骨架——<b>守卫 → 标准工具协议循环 → 上下文治理 → 输出守卫 → 会话记忆回写</b>。</li>
      <li>接口只声明 <code>MAX_STEPS / MAX_INPUT_LEN / COMPACT_THRESHOLD / KEEP_RECENT / KEEP_TOOL_RESULTS</code> 五个常量，<b>所有调参点前置到接口顶部</b>，改行为不用翻实现体。</li>
      <li><code>MAX_STEPS=5</code> 是单回合工具循环保险丝；<code>COMPACT_THRESHOLD=12 / KEEP_RECENT=4</code> 控制历史压缩；<code>KEEP_TOOL_RESULTS=2</code> 控制工具观察保留条数。</li>
      <li>继承 <code>Agent</code>（name/chat/reset/trace 契约），与前序步骤的 Agent 保持同构。</li>
    </ul>`},
  {id:'chat', b:'新增', t:'chat(input) — 主入口与双守卫', f:'FullAgent.Impl#chat · 骨架五件套的编排者', body: `
    <ul>
      <li><b>四道门依次过</b>：空输入 → 长度 &gt; 300 → <code>SENSITIVE</code> 敏感词正则 → 组装 <code>messages = system(SYSTEM_PROMPT) + history + user</code>。</li>
      <li><b>守卫命中不调用模型</b>：直接返回提示文案，同时写入 trace（"输入守卫"节点），既省 token 又防注入面扩大。</li>
      <li>编排顺序固定：<code>runAgentLoop</code> → <code>outputGuard</code> → <code>rememberTurn</code> → <code>compactHistoryIfNeeded</code>——<b>先清洗再记忆</b>，脏内容不会进入历史。</li>
      <li>坑：SYSTEM_PROMPT 明确"回答事实前先读 memory，不要凭历史猜测"，把记忆策略写进系统提示词而不是只靠工具。</li>
    </ul>`},
  {id:'loop', b:'新增', t:'runAgentLoop() — 标准工具协议循环', f:'FullAgent.Impl#runAgentLoop · MAX_STEPS=5 保险丝', body: `
    <ul>
      <li><b>生产级 ReAct 循环的最小形态</b>：每步先 <code>clearOldToolResults</code> 再 <code>model.chat(messages, toolSchemas())</code>。</li>
      <li><b>出口二选一</b>：无 <code>toolCalls</code> → 返回最终文本；有 tool_calls → 追加 <code>assistant(tool_calls)</code>，逐个执行工具后追加 <code>Message.tool(id, observation)</code>，进入下一步。</li>
      <li><b>协议配对是红线</b>：assistant 的 tool_calls 与 role=tool 消息必须成对存在，OpenAI 协议校验会拒绝断对的上下文。</li>
      <li>步数耗尽不抛异常：<b>保险丝熔断返回固定文案</b>，调用方拿到的永远是字符串。</li>
    </ul>`},
  {id:'exec', b:'新增', t:'executeTool() — 工具分发与异常归一化', f:'FullAgent.Impl#executeTool · switch 表达式', body: `
    <ul>
      <li><b>switch 表达式分发</b>：<code>get_weather</code>（复用 step03 的 <code>ReActAgent.MockWeather</code>）、<code>calculator</code>、<code>get_time</code>、<code>memory</code>，未知工具返回"未知工具: name"。</li>
      <li><b>异常就地归一化</b>：try/catch 包住全部分支，任何异常转成 <code>"工具执行失败: msg"</code> 字符串——<b>工具报错变成观察结果喂回模型</b>，让模型自己决定重试或换路，而不是整轮崩掉。</li>
      <li>name 为 null 时用 <code>""</code> 兜底进 default 分支，防空指针。</li>
      <li>动手验证：问"计算 12*8 等于多少"看 calculator 分支；问不存在的工具观察 default 行为。</li>
    </ul>`},
  {id:'mem', b:'新增', t:'executeMemory() — 跨轮持久记忆', f:'FullAgent.Impl#executeMemory · LinkedHashMap memory', body: `
    <ul>
      <li><b>四个命令</b>：<code>write</code>（key/value 校验非空后 <code>memory.put</code>，返回当前记忆条数）、<code>read</code>（存在则回显，不存在时<b>附上现有键列表引导模型纠偏</b>）、<code>keys</code>、<code>delete</code>。</li>
      <li><b>跨轮生效</b>：memory 是 Impl 字段，与 history 平行存在——history 是对话流水，memory 是模型可主动读写的结构化事实。</li>
      <li>read 未命中时返回"当前记忆键: …"而不是报错，<b>让模型拿到线索继续对话</b>，这是工具返回值设计的细节。</li>
      <li>思考题：memory 在 <code>reset()</code> 时清空，重启进程即丢——生产里应换成什么？</li>
    </ul>`},
  {id:'util', b:'新增', t:'parseArguments / text / trace — 基础设施三小件', f:'FullAgent.Impl#parseArguments · asObjectMap · text · trace', body: `
    <ul>
      <li><b>parseArguments()</b>：空串返回 <code>Map.of()</code>；用 <code>Json.parse</code> 解析后 isinstance 判断 Map，<b>模型给的坏 JSON 不会抛异常</b>，只拿到空 Map。</li>
      <li><b>asObjectMap()</b>：把 <code>Map&lt;?,?&gt;</code> 安全转 <code>Map&lt;String,Object&gt;</code>，键统一 <code>String.valueOf</code>。</li>
      <li><b>text()</b>：取参防空，null → 空串——所有工具实现都基于它取参，<b>不重复写 null 判断</b>。</li>
      <li><b>trace(type,label,detail)</b>：每步写入 lastTrace（guard/model/tool_call/tool_result/context/compact/memory/final 七类），是整条链路的可观测性出口。</li>
    </ul>`},
  {id:'oguard', b:'新增', t:'outputGuard() — 输出守卫', f:'FullAgent.Impl#outputGuard · 敏感词二次清洗', body: `
    <ul>
      <li><b>输入守卫管入口，输出守卫管出口</b>：即使输入干净，模型回答里也可能带敏感词（如复述攻击手法），出口必须再过一遍同一个 <code>SENSITIVE</code> 正则。</li>
      <li>命中 → 替换为固定文案"该内容不宜展示"，并写 trace（"输出守卫"节点）——<b>不静默丢弃，可观测</b>。</li>
      <li>顺带处理 null answer → 空串，保证 rememberTurn 与返回值都不见 null。</li>
      <li>为什么输入输出共用一条正则？教学上说明守卫规则应<b>集中定义、双侧生效</b>；生产可拆成两套策略。</li>
    </ul>`},
  {id:'remember', b:'新增', t:'rememberTurn() — 会话记忆回写', f:'FullAgent.Impl#rememberTurn · 每轮固定 +2 条', body: `
    <ul>
      <li><b>每轮结束后固定写两条</b>：<code>Message.user(input)</code> + <code>Message.assistant(answer)</code>——注意写入的是<b>清洗后的最终答案</b>，不是模型原始输出。</li>
      <li>写完立即 trace（"会话记忆"节点），报当前 history 条数，方便在 UI 上观察压缩阈值何时触发。</li>
      <li><b>工具中间轮不写 history</b>：tool_calls 与 tool 观察只在当轮 messages 里流转，历史里只留一问一答——这是控制历史膨胀的关键取舍。</li>
      <li>动手验证：连续对话多轮后调 <code>reset()</code>，history 与 memory 同时清空。</li>
    </ul>`},
  {id:'compact', b:'新增', t:'compactHistoryIfNeeded() — 上下文压缩', f:'FullAgent.Impl#compactHistoryIfNeeded · 阈值 12 / 保留 4', body: `
    <ul>
      <li><b>触发条件</b>：<code>history.size() > 12</code>；切分点 = 总数 - KEEP_RECENT(4)。</li>
      <li><b>老段压缩</b>：<code>renderTranscript()</code> 把老消息渲染成 <code>[role] content</code> 文本，配 <code>COMPACT_PROMPT</code>（保留目标/决定/稳定事实/失败原因，丢弃寒暄，不确定标 UNVERIFIED）调模型生成摘要，替换为一条 user 消息 <code>[对话摘要]</code>。</li>
      <li><b>摘要调用失败不致命</b>：catch 后退化为滑动窗口（只保留最近 4 条），写 trace 说明降级——<b>上下文治理宁可丢信息也不能断服务</b>。</li>
      <li>坑：压缩发生在 rememberTurn 之后，所以本轮问答一定参与"最近保留"或被压缩，不会丢当轮。</li>
    </ul>`},
  {id:'clear', b:'新增', t:'clearOldToolResults() — 工具观察清理', f:'FullAgent.Impl#clearOldToolResults · KEEP_TOOL_RESULTS=2', body: `
    <ul>
      <li><b>逆向遍历</b>所有 <code>role=tool</code> 消息，最近 2 条保留，更早的替换为 <code>"[工具结果已清理，需要时重新调用]"</code> 占位符。</li>
      <li><b>为什么用占位符而不是删消息</b>：直接删除会破坏 assistant(tool_calls) ⇄ tool 的<b>协议配对</b>，OpenAI 兼容接口会报 400。占位符保住配对结构又释放上下文。</li>
      <li>每步循环开头都执行一次——多步循环里越早的工具观察越没价值，<b>越新越有用</b>。</li>
      <li>这正是 Step18 标题里"上下文治理"的第三件套：历史压缩管长对话，观察清理管单轮内循环。</li>
    </ul>`},
  {id:'calc', b:'新增', t:'calc() — 受限四则运算器', f:'FullAgent.Impl#calc · 单次二元运算', body: `
    <ul>
      <li><b>正则白名单</b>：<code>\\d+(\\.\\d+)? [+-*/] \\d+(\\.\\d+)?</code>，matches() 全匹配——<b>不是通用表达式解析器，故意只支持一次二元运算</b>。</li>
      <li>解析失败抛 <code>IllegalArgumentException</code>，除 0 抛 <code>ArithmeticException</code>——都被 executeTool 的 catch 兜住转成观察文本。</li>
      <li><b>设计动机</b>：演示"计算交给工具而不是模型心算"，同时用受限实现说明——<b>工具能力边界越小越可控</b>，生产换 exp4j/BigDecimal 时接口不变。</li>
    </ul>`},
  {id:'schema', b:'新增', t:'toolSchemas() — 工具声明三件套', f:'FullAgent.Impl#toolSchemas · function() · stringProperty()', body: `
    <ul>
      <li><b>4 个 function schema</b>：get_weather(city)、calculator(expression)、get_time(无参)、memory(command 枚举 + key + value)。</li>
      <li><b>memory 的 command 用 enum 约束</b>（write/read/keys/delete），把"合法用法"写进 schema，模型不会发明第四种命令。</li>
      <li><code>function()</code> 组装 OpenAI 格式 <code>{type:"function", function:{name,description,parameters}}</code>，required 动态可变参数；<code>stringProperty()</code> 造字符串属性。</li>
      <li><b>description 直接决定调用准确性</b>：schema 里的描述就是模型选择工具的唯一依据，要写清"何时必须调用、返回什么"。</li>
    </ul>`},
  {id:'entry', b:'新增', t:'main() — 装配与验收入口', f:'FullAgent#main · Models 工厂 + mock 兜底', body: `
    <ul>
      <li><b>两级装配</b>：先 <code>Models.openAiOrNull()</code> 尝试真实 OpenAI 兼容渠道；未配 Key → <code>Models.mock("step18")</code> 兜底，<b>没网也能跑通结构</b>。</li>
      <li>命令行参数即输入：<code>java … FullAgent "你的问题"</code>；缺省输入为"记住我叫小傅哥，然后查北京天气并推荐穿搭"——一句话同时触发 memory 写、get_weather、推理三种能力。</li>
      <li>只打印 <code>[输入]</code> / <code>[输出]</code> 两行；想看工具调用轨迹，把 trace() 的 lastTrace 也打出来即可（导览第 8 站）。</li>
    </ul>`},
  {id:'reuse', b:'复用', t:'step03 基线复用 — 本节不动的部分', f:'cn.xiaofuge.ai.llm / cn.xiaofuge.ai.agent · step03 已交付', body: `
    <ul>
      <li><b>ChatModel / ChatResponse / Message / ToolCall / Json</b>：LLM 抽象与协议结构全部来自 step03，本节零改动消费。</li>
      <li><b>ReActAgent.MockWeather</b>：天气 mock 直接复用，证明前序工具可被新 Agent 原样集成。</li>
      <li><b>Models</b>：openAiOrNull / mock 双工厂让入口在不同环境都能跑。</li>
      <li>本节全部价值在 <b>step18 的编排层</b>：守卫、循环、治理、记忆都是在这些原语之上的新骨架。</li>
    </ul>`},
];

/* TOUR：导览站点 */
const TOUR = [
  {tab:'over', tree:null, sl:'全景鸟瞰', title:'第 1 站 · 全景鸟瞰',
   text:'这是 Step18 的地图：FullAgent 一个接口 + 一个 Impl，按职责分四个区——入口契约、双守卫、工具协议循环、上下文治理。绿色 = 本节新增，灰色 = step03 基线复用。这张图讲的是"生产级 Agent 的公共骨架"。'},
  {tab:'over', tree:'FullAgent', sl:'契约常量', title:'第 2 站 · 接口契约：常量即参数',
   text:'FullAgent 接口只有 5 个常量：MAX_STEPS=5（循环保险丝）、MAX_INPUT_LEN=300（输入守卫）、COMPACT_THRESHOLD=12 / KEEP_RECENT=4（历史压缩）、KEEP_TOOL_RESULTS=2（观察保留）。把所有调参点前置到接口，是骨架类的设计惯例。'},
  {tab:'flow', tree:'chat(input)', sl:'双守卫', title:'第 3 站 · 输入守卫与主编排',
   text:'切到流程图看 chat()：空输入 → 长度 300 → SENSITIVE 敏感词，三道门命中任何一道都<b>不调用模型直接返回</b>。通过后组装 messages（system + history + user）进入循环。注意红色分支都是"早退"路径。'},
  {tab:'seq', tree:'runAgentLoop', sl:'一次对话', title:'第 4 站 · 一次对话的完整时序',
   text:'切到时序图：示例输入"记住我叫小傅哥，查北京天气并推荐穿搭"会依次触发 memory(write)、get_weather 两个工具，第 2 步拿到最终回答。看左侧编号①②③的阶段划分和 loop 内每步的"清观察 → 调模型 → 执行工具"节奏。'},
  {tab:'flow', tree:'executeTool', sl:'工具分发', title:'第 5 站 · 工具分发与跨轮记忆',
   text:'executeTool() 用 switch 分发 4 个工具，异常全部转成观察文本喂回模型（工具报错 ≠ 整轮失败）。重点看 memory 分支：write 先校验 key/value，read 未命中时返回现有键列表引导模型纠偏——工具返回值也是提示词的一部分。'},
  {tab:'flow', tree:'runAgentLoop()：标准工具协议循环', sl:'保险丝', title:'第 6 站 · 循环边界：保险丝熔断',
   text:'如果模型一直要求调工具，step 耗尽 MAX_STEPS=5 后保险丝熔断，返回固定提示而不是死循环。配合第 3 站的红色早退分支，这张流程图覆盖了"失控输入"与"失控循环"两类生产事故的防线。'},
  {tab:'flow', tree:'clearOldToolResults', sl:'观察清理', title:'第 7 站 · 上下文治理三件套',
   text:'每步循环开头 clearOldToolResults 只保留最近 2 条工具观察——用占位符替换而不是删除，<b>保住 assistant(tool_calls) ⇄ tool 的协议配对</b>。对话级的膨胀则由 compactHistoryIfNeeded 兜底：超 12 条压摘要，摘要失败退化为滑动窗口。'},
  {tab:'over', tree:'main()', sl:'装配验收', title:'第 8 站 · 入口与复用',
   text:'回到全景图右下：main() 先试 Models.openAiOrNull()，没配 Key 就用 mock 兜底，保证结构随时可跑。灰色节点 ChatModel / MockWeather / Json 全部来自 step03——本节的增量全在编排层，这正是"骨架课"要传达的分工。'},
  {tab:'over', tree:null, sl:'自由探索', title:'🎉 导览完成',
   text:'现在自由探索：左侧树按方法展开、支持搜索；中间三张图（全景/时序/流程）可点任意节点联动；右侧 14 张说明卡覆盖 Impl 全部方法。动手验证建议：跑 main() 缺省输入，观察 memory 与 get_weather 是否都被调用。'},
];

/* ════════ MAP-DATA-END ════════ */
