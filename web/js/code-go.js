// 五语言代码切换：Go 片段（与 articles.js 各文章 <pre> 块按顺序对齐，null=该块语言无关不切换）
// 片段为教学摘录，类型名 / 函数名 / 行为与 ToyAgent-Go 工程真实实现一致。
window.ARTICLE_CODE = window.ARTICLE_CODE || {};
window.ARTICLE_CODE.go = {

  home: [
`// Agent 智能体契约接口 —— 整个 ToyAgent 的灵魂只有一个方法
type Agent interface {
    // Name 场景名称，用于展示
    Name() string
    // Chat 智能体最小契约：输入一句话，输出一句话
    Chat(input string) string
    // Trace 最近一次 Chat 的执行轨迹（供渲染时间线）
    Trace() []map[string]any
    // Reset 清空场景内部状态（如记忆），无状态场景为空实现
    Reset()
}`,
`# 零依赖编译（Go 1.21+，标准库之外零第三方依赖）
cd ToyAgent-Go
go build -o toyagent .

# 启动 CLI（未配 Key 自动使用内置 Mock 模型）
./toyagent                          # 交互 REPL：先选场景，再循环输入
./toyagent list                     # 查看 26 个场景清单
./toyagent run step03 "北京天气怎么样"   # 单轮执行 + 轨迹时间线

# 接入真实大模型：环境变量或 config.properties 填入 API Key
export LLM_API_KEY=sk-xxx`
  ],

  step01: [
`// Impl 最小 MVP 实现：每轮对话独立，输入 → 模型 → 输出
type Impl struct {
    model llm.ChatModel
}

func New(model llm.ChatModel) agent.Agent {
    return &Impl{model: model}
}

func (a *Impl) Chat(input string) string {
    // 全部实现就这一行核心逻辑
    return a.model.Chat([]llm.Message{llm.User(input)})
}`,
`// ChatModel 模型抽象：智能体的"大脑"
type ChatModel interface {
    // Chat 输入一组对话消息，输出模型回复文本
    Chat(messages []Message) string
}

// Message 一条对话消息：Role 取值 system / user / assistant
type Message struct {
    Role    string
    Content string
}

func User(content string) Message { return Message{Role: "user", Content: content} }`,
null
  ],

  step02: [
`// systemPrompt 三层结构系统提示词：角色 → 背景 → 任务与约束
var systemPrompt = "# 角色\\n你是小傅哥的技术助理，专注讲清楚 AI Agent 的原理与工程实践。\\n\\n" +
    "# 背景\\n读者是正在学习智能体开发的 Java 工程师，但对 AI 领域是新手。\\n\\n" +
    "# 任务与约束\\n" +
    "1. 回答使用简体中文，先给结论再展开。\\n" +
    "2. 若用户要求\\"用一句话\\"，必须严格只回答一句话。\\n" +
    "3. 不确定的内容如实说明，禁止编造。"

func (a *Impl) Chat(input string) string {
    return a.model.Chat([]llm.Message{
        llm.System(systemPrompt), // 唯一新增
        llm.User(input),
    })
}`
  ],

  step03: [
`// systemPrompt 用系统提示词约定 ReAct 的输出协议（JSON 形式，便于解析）
var systemPrompt = "你是一个使用 ReAct 模式解决问题的智能体。\\n" +
    "每一轮只输出一个 JSON 对象，格式二选一：\\n" +
    "1. 继续行动：{\\"thought\\": \\"...\\", \\"action\\": \\"工具名\\", \\"action_input\\": \\"参数\\"}\\n" +
    "2. 给出答案：{\\"thought\\": \\"...\\", \\"final\\": \\"最终答案\\"}\\n" +
    "可用工具：\\n" +
    "- get_weather(city): 查询城市天气\\n" +
    "- calculator(expression): 计算四则运算\\n" +
    "- get_time(): 获取当前时间"`,
`for step := 1; step <= maxSteps; step++ {
    raw, _ := llm.SafeChat(a.model, messages) // 1. 模型思考
    parsed := llm.TryParse(strings.TrimSpace(raw))

    if fin := llm.Str(parsed, "final"); fin != "" {
        return fin // 2. 有答案 → 结束
    }

    action := llm.Str(parsed, "action") // 3. 有行动 → 执行工具
    observation := a.executeTool(action,
        llm.Str(parsed, "action_input"))

    messages = append(messages, llm.Assistant(raw)) // 4. 结果喂回模型
    messages = append(messages, llm.User("Observation: "+observation))
}
return "已达最大步数 5，循环终止" // 5. 保险丝`
  ],

  step04: [
`// 第一步：在提示词中声明工具清单（最简 Tool Schema）
const systemPrompt = "你是一个可以调用工具的智能体。可用工具：\\n" +
    "- get_weather(city): 查询指定城市的实时天气\\n" +
    "- calculator(expression): 计算四则运算\\n\\n" +
    "需要工具时，只输出 JSON：{\\"tool\\": \\"工具名\\", \\"arguments\\": {\\"参数名\\": \\"值\\"}}\\n" +
    "不需要工具时，直接自然语言回答。"

// 第二步：模型返回结构化调用请求
first, _ := llm.SafeChat(a.model, messages)
decision := llm.TryParse(strings.TrimSpace(first))
// → {"tool": "get_weather", "arguments": {"city": "广州"}}

if tool := llm.Str(decision, "tool"); tool != "" {
    // 第三步：执行工具，把结果回填
    result := execute(tool, llm.Write(decision["arguments"]))
    messages = append(messages, llm.User("工具 "+tool+" 的执行结果: "+result))
    answer, _ := llm.SafeChat(a.model, messages) // 基于事实作答
    return answer
}`
  ],

  step05: [
`func (a *Impl) Chat(input string) string {
    a.history = append(a.history, llm.User(input))

    // 历史超限 → 压缩：最旧两轮摘成一句话
    if len(a.history) > maxRounds*2 {
        a.compress()
    }

    context := []llm.Message{llm.System(systemPrompt)}
    if a.summary != "" {
        context = append(context, llm.System("【历史摘要】"+a.summary))
    }
    context = append(context, a.history...) // ← 记忆的全部秘密

    answer, _ := llm.SafeChat(a.model, context)
    a.history = append(a.history, llm.Assistant(answer))
    return answer
}`
  ],

  step06: [
`// 1. 意图识别：让模型输出分类 JSON
const routerPrompt = "你是意图识别路由器。分析用户输入，只输出 JSON：\\n" +
    "{\\"intent\\": \\"weather|math|chat\\", \\"reason\\": \\"一句话理由\\"}"

raw, _ := llm.SafeChat(a.model, []llm.Message{llm.System(routerPrompt), llm.User(input)})
intent := llm.Str(llm.TryParse(strings.TrimSpace(raw)), "intent")

// 2. 决策中枢：按意图路由
switch intent {
case "weather":
    return "「" + city + "」现在" + step03.MockWeather(city) + "。" // 走工具
case "math":
    return "计算结果：" + calc(input) + "。" // 走计算器
default:
    return a.handleChat(input) // 走对话
}`
  ],

  step07: [
`// 1. 检索：知识片段与问题打分（bigram 重合率），取 Top2
type hit struct {
    text  string
    score float64
}
var ranked []hit
for _, text := range knowledge {
    ranked = append(ranked, hit{text, score(text, input)})
}
sort.SliceStable(ranked, func(i, j int) bool { return ranked[i].score > ranked[j].score })
if len(ranked) > 2 {
    ranked = ranked[:2]
}

// 2. 增强：命中片段注入提示词，带来源编号
ctx := "【参考资料】\\n"
for i, h := range hits {
    ctx += fmt.Sprintf("[%d] %s\\n", i+1, h.text)
}

// 3. 生成：约束模型"优先依据资料回答，不足则拒答"
answer, _ := llm.SafeChat(a.model, []llm.Message{
    llm.System(systemPrompt),
    llm.User(ctx),
    llm.User(input)})`
  ],

  step08: [
`// baseWiki 基础 Wiki：启动时编译一次（生产中来自仓库里的 AGENTS.md）
var baseWiki = []baseEntry{
    {"架构约定", "ToyAgent 是零依赖教学项目：一个接口类就是一个智能体场景……"},
    {"代码规范", "类与核心方法必须有中文注释说明教学意图……"},
    {"部署流程", "go build 编译后 ./toyagent 启动……"},
}

// 编译指令：把对话中的知识沉淀进 Wiki 并持久化
if strings.HasPrefix(input, "编译：") || strings.HasPrefix(input, "记住：") {
    return a.compile(input) // 解析「编译：主题：内容」
}
// compile 内部：
// a.userWiki[topic] = content   // 增量条目
// a.persistUserWiki()           // 写入 wiki/user-wiki.md，重启不丢`
  ],

  step09: [
`// mcpServer MCP Server 接口：工具的标准化宿主
type mcpServer interface {
    tools() []toolDescriptor               // tools/list 工具发现
    call(toolName, argsJson string) string // tools/call 工具调用
}

// toolDescriptor 工具描述符：标准"名片"
type toolDescriptor struct {
    name, description, parameters string
}

// 智能体侧：动态发现，而不是硬编码
tools := a.client.listTools()
result := a.client.callTool("get_weather", "{\\"city\\": \\"杭州\\"}")`
  ],

  step10: [
`// 1. 技能选择：模型按语义匹配
raw, _ := llm.SafeChat(a.model, []llm.Message{llm.System(selectPrompt), llm.User(input)})
skill := llm.Str(llm.TryParse(strings.TrimSpace(raw)), "skill")

// 2. 技能执行：确定性编排，而非现场发挥
switch skill {
case "travel_plan":
    return a.travelPlan(input) // L1: 组合两个工具
case "weather_report":
    return a.weatherReport(input) // L1: 单工具
default:
    return a.freeChat(input) // L0: 纯提示词
}

// L1 技能内部：工具结果是确定性的两步
weather := step03.MockWeather(city)
timeNow := time.Now().Format("2006-01-02 15:04")
answer, _ := llm.SafeChat(a.model, []llm.Message{
    llm.System("你是旅行顾问。基于天气与时间信息输出攻略..."),
    llm.User(input + "\\n天气: " + weather + "\\n当前时间: " + timeNow)})`
  ],

  step11: [
`// ToolDefinition 工具定义协议 —— 一个接口 = 一个工具
type ToolDefinition interface {
    Name() string        // 模型调用标识
    Description() string // 用途：模型选工具的唯一依据
    Parameters() map[string]string // 参数说明（生产中是 JSON Schema）
    Execute(args map[string]any) (string, error) // 执行手脚
    ConcurrencySafe() bool // 可否并行（预留，默认 false）
}

// 一个 struct 就是一个工具
type WeatherTool struct{}

func (t *WeatherTool) Name() string        { return "get_weather" }
func (t *WeatherTool) Description() string { return "查询指定城市的实时天气" }
func (t *WeatherTool) Execute(args map[string]any) (string, error) { /* ... */ }`,
`// Register 注册工具，返回注销器（disposer）：调用它即把该工具从目录摘除
func (r *ToolRegistry) Register(tool ToolDefinition) func() {
    r.tools[tool.Name()] = tool
    return func() {
        delete(r.tools, tool.Name())
    }
}

disposer := a.registry.Register(&WeatherTool{})
disposer() // 注销：模型下一次对话就"看不见"这个工具了`
  ],

  step12: [
`// 每个角色 = 同一个 ChatModel + 不同的系统提示词
planRaw, _ := llm.SafeChat(a.model, []llm.Message{
    llm.System("你是规划者（Planner）。把任务拆解为三个角色的执行计划，只输出 JSON：\\n" +
        "{\\"plan\\": [{\\"role\\": \\"研究员\\", \\"task\\": \\"...\\"}, " +
        "{\\"role\\": \\"写手\\", \\"task\\": \\"...\\"}, {\\"role\\": \\"审查员\\", \\"task\\": \\"...\\"}]}"),
    llm.User(input)})

// 上游产出作为下游输入 —— 消息在角色间流动
notes, _ := llm.SafeChat(a.model, []llm.Message{
    llm.System("你是研究员，输出 3 条以内研究要点。"),
    llm.User(input)})
draft, _ := llm.SafeChat(a.model, []llm.Message{
    llm.System("你是写手。基于研究笔记写短文。\\n笔记: " + notes),
    llm.User(input)})
// 审查员可以打回 → 触发修改循环`
  ],

  step13: [
`// fork：子代理的 system 里带上父会话
var sys strings.Builder
sys.WriteString("【子代理身份】" + spec.specialty)
if fork {
    sys.WriteString("\\n【继承的父会话上下文】\\n" +
        strings.Join(a.parentTranscript, "\\n"))
} else {
    sys.WriteString("\\n（spawn 模式：全新实例，看不到父会话）")
}

childMessages := []llm.Message{
    llm.System(sys.String()),
    llm.User(task),
}
report, _ := llm.SafeChat(a.model, childMessages) // 子代理独立一轮`
  ],

  step14: [
`// agentCard 名片：A2A 的发现单元（生产中是 .well-known/agent.json）
type agentCard struct {
    name, url, version string
    skills             []string
    description        string
}

// 发送：按名片路由 + 状态机流转（发起方只认 task_id）
a.seq++
taskId := fmt.Sprintf("task-%03d-%s", a.seq, randHex4())
//  submitted → working → completed
return fmt.Sprintf("{\\"task_id\\": \\"%s\\", \\"status\\": \\"completed\\", \\"result\\": \\"%s\\"}",
    taskId, reply)`
  ],

  step15: [
`func (a *Impl) Chat(input string) string {
    // ① 输入守卫：长度限制 + 敏感词检测
    if len([]rune(input)) > maxInputChars {
        return "输入过长，已拦截。"
    }
    for _, word := range blocked {
        if strings.Contains(strings.ToLower(input), word) {
            return "该请求已被输入守卫拦截。"
        }
    }

    // ② Agent Loop（继承 Step03 骨架）
    for step := 1; step <= maxSteps; step++ {
        raw, err := llm.SafeChat(a.model, messages)
        if err != nil {
            return "运行时异常已兜底" // ④ 异常兜底
        }
        if fin := llm.Str(parsed, "final"); fin != "" {
            return guard(fin) // ③ 输出守卫：合规检查后才放行
        }
        observation, execErr := execute(action, actionInput) // 工具异常不拖垮循环
        messages = append(messages, llm.User("Observation: "+observation))
    }
    return "已达最大步数，强制终止" // 保险丝熔断
}`
  ],

  step16: [
`// State：在节点之间流转的共享状态
state := map[string]any{
    "input":       input,
    "answer":      "",
    "observation": "",
}

// 固定入口
a.runNode("classify", state)

// 条件边：按分类结果路由
next, _ := state["next"].(string)
if next == "" {
    next = "faq"
}
a.runNode(next, state)

// 汇合点：统一润色后结束
a.runNode("polish", state)`
  ],

  step17: [
`// turnEndReason 对应 Java sealed TurnEndReason：kind 取 completed/maxSteps/error
type turnEndReason struct {
    kind    string
    summary string
    steps   int
}

// runTurn 一个 turn：循环执行 step，只能以 TurnEndReason 收场
func (a *Impl) runTurn(input string) turnEndReason {
    for step := 1; step <= maxStepsPerTurn; step++ {
        raw, err := llm.SafeChat(a.model, req) // 系统提示词 + 裁剪后的历史
        if err != nil {
            return turnEndReason{kind: endError, summary: "模型调用失败"}
        }
        if fin := llm.Str(parsed, "final"); fin != "" {
            // 模型给出 final → Completed
            return turnEndReason{kind: endCompleted, summary: "模型给出最终答案"}
        }
        observation := a.executeTool(parsed)
        // 观察回填 → 续步（mid-turn continuation，不结束回合）
        a.history = append(a.history, llm.User("Observation: "+observation))
    }
    return turnEndReason{kind: endMaxSteps, steps: maxStepsPerTurn} // 保险丝
}`,
`// token 估算：教学化近似 token ≈ 字符数 / 4（dsh-java 同款）
func estimateTokens(msgs []llm.Message) int {
    n := 0
    for _, m := range msgs {
        n += utf8.RuneCountInString(m.Content) / 4
    }
    return n
}

// 超预算：从最老的消息开始丢，保底留最近 4 条
func (a *Impl) trimToBudget() {
    if estimateTokens(a.history) <= budgetTokens || len(a.history) <= keepMin {
        return
    }
    for estimateTokens(a.history) > budgetTokens && len(a.history) > keepMin {
        a.history = a.history[1:]
    }
}`
  ],

  step18: [
    null,
`func (a *Impl) Chat(input string) string {
    // ① 输入守卫：长度与敏感意图，不通过直接返回，模型预算为零
    if len([]rune(input)) > maxInputLen {
        return "输入过长，请精简后重试"
    }
    if sensitive.MatchString(input) {
        return "该请求已被输入守卫拦截"
    }

    // ② 记忆读取 + 画像提取（我叫 XX → 存档案）
    a.extractProfile(input)

    // ③ 组装上下文：系统提示词（含画像）+ 历史 + 本轮输入
    messages := []llm.Message{llm.System(systemPrompt + a.userPortrait())}
    messages = append(messages, a.history...)
    messages = append(messages, llm.User(input))

    // ④⑤ ReAct 循环 + 保险丝（Step03 的骨架原样复用）
    answer := a.runReAct(messages)

    // ⑥ 输出校验 + 画像兜底
    answer = a.outputGuard(answer, input)

    // ⑦ 回写记忆，超阈值触发上下文压缩
    a.history = append(a.history, llm.User(input))
    a.history = append(a.history, llm.Assistant(answer))
    if len(a.history) > compressThreshold {
        a.compressHistory()
    }
    return answer
}`,
`// 记忆要分两层：对话历史（易失、会压缩）与用户画像（持久、结构化）。
// 模型看不到被压缩掉的历史时，画像仍能回答「我叫什么」—— 先查档案，不问模型。
func (a *Impl) outputGuard(answer, input string) string {
    if strings.Contains(input, "我叫什么") || strings.Contains(input, "我是谁") {
        if name, ok := a.profile["名字"]; ok {
            return "你叫 " + name + "（来自画像记忆，不依赖模型）。"
        }
    }
    return answer
}`,
    null
  ],

  step19: [
`// 分支二：模型发现缺关键信息 → 发起 ask_user_question
if question := llm.Str(parsed, "ask"); question != "" {
    a.pendingQuestion = question // 问题持久化在会话状态（挂起）
    return question + "\\n\\n（直接回复即可，我会带着你的答复继续任务。）"
}

// 分支一：用户的回复不进模型，而是作为人工答复回填
if a.pendingQuestion != "" {
    q := a.pendingQuestion
    a.pendingQuestion = ""
    a.history = append(a.history, llm.User("人工答复："+input))
    return a.complete(q) // turn 恢复，带着完整信息继续
}`
  ],

  step20: [
`// 权限评估：风险分级 + 模式 + 会话免审记忆
dangerous := isDangerous(tool) // send_email / delete_file = 高危写操作
needAsk := a.approveMode == alwaysAsk ||
    (a.approveMode == autoManualSafe && dangerous && !a.trustedTools[tool])

if needAsk {
    a.pendingTool = tool // 审批挂起：先答复人，不执行
    return "⚠️ 即将执行高危操作：" + tool +
        "…… 回复「批准 / 拒绝 / 批准并记住」"
}
// 人回复后进入 resolveApproval：批准→执行；拒绝→终止；批准并记住→加入免审名单`
  ],

  step21: [
`// 第 3 层与第 4 层的分工：看原文 vs 看真实落点
for _, m := range absPathRe.FindAllStringSubmatch(cmd, -1) {
    p := strings.TrimPrefix(m[0], m[1]) // 剥掉前导字符，得到纯路径
    cleaned := filepath.Clean(p)        // 规范化：压平 .. 再比对
    if !strings.HasPrefix(cleaned, workspace) {
        return Deny(3, fmt.Sprintf("绝对路径 %s 越出工作区", p))
    }
}
if strings.Contains(cmd, "..") { // 第 4 层：规范化后复查，../ 逃逸无所遁形
    return Deny(4, "检测到 ../ 相对跳转，疑似路径逃逸")
}`
  ],

  step22: [
    null,
`// project 投影：事件流 → 会话上下文（只挑 USER / AGENT 两类，其余服务审计）
func project(logEntries []map[string]any) []llm.Message {
    var history []llm.Message
    for _, e := range logEntries {
        typ := fmt.Sprintf("%v", e["type"])
        payload, _ := e["payload"].(map[string]any)
        if payload == nil {
            continue
        }
        content := fmt.Sprintf("%v", payload["content"])
        switch typ {
        case evUser:
            history = append(history, llm.User(content))
        case evAgent:
            history = append(history, llm.Assistant(content))
        default:
            // 不进上下文，但留在日志里
        }
    }
    return history
}`
  ],

  step23: [
`// 1. 契约：插件作者唯一要实现的东西
type AgentPlugin interface {
    ID() string
    Version() string
    OnStart()
    OnStop()
    Tools() []step11.ToolDefinition // 插件带来的工具
}

// 2. 加载：Java 版用独立 URLClassLoader 隔离；Go 以契约接口 + 注册发现模拟装载
plugin := &timePlugin{}
plugin.OnStart()

// 3. 工具桥接：注册 + disposer 按 pluginId 归档（卸载即批量注销）
l := &loaded{plugin: plugin}
for _, tool := range plugin.Tools() {
    l.disposers = append(l.disposers, a.registry.Register(tool))
}

// 4. 卸载三步回收：批量 disposer → OnStop → 释放插件实例`
  ],

  step24: [
`// 钩子本体：挂载点 + 匹配规则 + 处理器
// handler 返回 "" = 放行；"DENY:原因" = 拦截；否则替换内容
type hook struct {
    name    string
    phase   string // before / after
    match   []string
    handler func(tool, payload string) string
}

a.hooks = append(a.hooks,
    hook{"风控-敏感外发", "before", []string{"send_sms"}, func(tool, payload string) string {
        if strings.Contains(payload, "密码") {
            return "DENY:敏感信息禁止外发"
        }
        return ""
    }},
    hook{"脱敏", "after", []string{"*"}, func(tool, payload string) string {
        return mobileRe.ReplaceAllString(payload, "$1*********")
    }},
)

// 管线：before 任何一条拦截即终止；after 按注册顺序依次加工`
  ],

  step25: [
`// 终端形态 = Agent.Chat 的 while 循环（完整实现见 main.go runRepl）
in := bufio.NewScanner(os.Stdin)
for {
    fmt.Print("you> ")
    if !in.Scan() {
        return
    }
    line := strings.TrimSpace(in.Text())
    if line == "exit" || line == "/exit" {
        return
    }
    fmt.Println("agent> " + currentAgent.Chat(line)) // 灵魂不变
}

// 手脚换成沙箱执行器：命令按空格拆分，不经 shell（无管道/重定向注入面）
fields := strings.Fields(cmd)
cmdExec := exec.CommandContext(ctx, fields[0], fields[1:]...)
cmdExec.Dir = workspace // 受限工作区 = 沙箱第④层
out, err := cmdExec.CombinedOutput()`,
`# 零依赖编译
cd ToyAgent-Go
go build -o toyagent .

# 交互式 REPL（输入 22 或 step25 选择 CLI 智能体）
./toyagent

# 非交互单命令（Claude Code 同款 -p 习惯）
./toyagent run step25 "看看工作区里有什么"`
  ],

  step26: [
`// 调度由独立 goroutine 承载（对应 Java ScheduledExecutorService 守护线程池）
func (a *Impl) runJob(j *job, delay int64, once bool) {
    timer := time.NewTimer(time.Duration(delay) * time.Second)
    defer timer.Stop()
    for {
        select {
        case <-timer.C:
        case <-j.stop:
            return // 任务被取消
        }
        a.fire(j.id, j.content)
        j.done.Store(true)
        if once {
            return // 一次性：触发即结束
        }
        timer.Reset(time.Duration(j.period) * time.Second) // 循环：首延迟 = 周期
    }
}

// 触发即落盘：append-only JSONL（事件溯源）
f, _ := os.OpenFile(eventFile, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
f.WriteString(fmt.Sprintf("{\\"time\\": \\"%s\\", \\"job\\": \\"%s\\"}\\n", t, id))`
  ]

};
