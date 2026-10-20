// 五语言代码切换：JavaScript 片段（与 articles.js 各文章 <pre> 块按顺序对齐，null=该块语言无关不切换）
// 片段为教学摘录，与 ToyAgent-JS 工程（src/agents/stepXX*Agent.js、src/llm/、src/main.js）真实实现一致；
// 源码中的多行模板串在摘录时改写为字符串拼接，注释保持中文、教学意图与 Java 块一致。
window.ARTICLE_CODE = window.ARTICLE_CODE || {};
window.ARTICLE_CODE.javascript = {

  home: [
`// 智能体契约 —— 整个 ToyAgent 的灵魂只有一个方法
export class Agent {

    // 智能体最小契约：输入用户消息，返回回答（Promise）
    async chat(input) {
        throw new Error('Agent 子类必须实现 chat(input)');
    }

    // 最近一次 chat 的执行轨迹（供渲染时间线）
    trace() { return []; }

    // 清空场景内部状态（如记忆），无状态场景无需覆盖
    reset() { }
}`,
`# 零依赖运行（Node 18+，无需安装任何依赖）
cd ToyAgent-JS

# 命令行演示：单轮执行指定场景，打印回答 + 轨迹 + 耗时
node src/main.js run step03 "北京天气怎么样"

# 进入交互 REPL（先选场景再对话，/reset 清空 /exit 退出）
node src/main.js

# 接入真实大模型：复制配置模板并填入 API Key
cp config.properties.example config.properties`
  ],

  step01: [
`export class ChatAgent extends Agent {

    constructor(model) {
        super();
        this.model = model;
    }

    async chat(input) {
        // 全部实现就这一行核心逻辑
        return this.model.chat([Message.user(input)]);
    }
}`,
`// 模型抽象：智能体的"大脑"
export class ChatModel {

    // 输入一组对话消息，输出模型回复文本。子类必须覆盖。
    // 背后是 OpenAI 兼容端点还是本地 Mock，智能体并不关心。
    async chat(messages) {
        throw new Error('ChatModel 子类必须实现 chat(messages)');
    }
}`,
null
  ],

  step02: [
`// 三层结构系统提示词：角色 → 背景 → 任务与约束（源码为多行模板串，此处节选）
const SYSTEM_PROMPT = [
    '# 角色',
    '你是小傅哥的技术助理，专注讲清楚 AI Agent 的原理与工程实践。',
    '',
    '# 背景',
    '读者是正在学习智能体开发的 Java 工程师。',
    '',
    '# 任务与约束',
    '1. 回答使用简体中文，先给结论再展开。',
    '2. 若用户要求"用一句话"，必须严格只回答一句话。',
    '3. 不确定的内容如实说明，禁止编造。',
].join('\\n');

async chat(input) {
    return this.model.chat([
        Message.system(SYSTEM_PROMPT),  // 唯一新增
        Message.user(input),
    ]);
}`
  ],

  step03: [
`// 用系统提示词约定 ReAct 的输出协议（JSON 形式，便于解析）
const SYSTEM_PROMPT = '你是一个使用 ReAct 模式解决问题的智能体。\\n'
    + '每一轮只输出一个 JSON 对象，格式二选一：\\n'
    + '1. 继续行动：{"thought": "你的思考", "action": "工具名", "action_input": "参数"}\\n'
    + '2. 给出答案：{"thought": "你的思考", "final": "最终答案"}\\n'
    + '可用工具：\\n'
    + '- get_weather(city): 查询城市天气\\n'
    + '- calculator(expression): 计算四则运算，如 12*8\\n'
    + '- get_time(): 获取当前时间\\n'
    + '不要输出 JSON 以外的内容。';`,
`for (let step = 1; step <= MAX_STEPS; step++) {
    const raw = (await this.model.chat(messages)).trim();  // 1. 模型思考
    const parsed = tryParse(raw);                          // JSON.parse + 去代码围栏

    const fin = str(parsed, 'final');
    if (fin != null) return fin;                           // 2. 有答案 → 结束

    const action = str(parsed, 'action');                  // 3. 有行动 → 执行工具
    const observation = this.executeTool(action,
            str(parsed, 'action_input'));

    messages.push(Message.assistant(raw));                 // 4. 结果喂回模型
    messages.push(Message.user('Observation: ' + observation));
}
return '已达最大步数 ' + MAX_STEPS + '，循环终止';         // 5. 保险丝`
  ],

  step04: [
`// 第一步：在提示词中声明工具清单（最简 Tool Schema）
const SYSTEM_PROMPT = '你是一个可以调用工具的智能体。可用工具：\\n'
    + '- get_weather(city): 查询指定城市的实时天气\\n'
    + '- calculator(expression): 计算四则运算\\n\\n'
    + '需要工具时，只输出 JSON：{"tool": "工具名", "arguments": {"参数名": "值"}}\\n'
    + '不需要工具时，直接自然语言回答。';

// 第二步：模型返回结构化调用请求
const first = (await this.model.chat(messages)).trim();
// → {"tool": "get_weather", "arguments": {"city": "广州"}}

// 第三步：执行工具，把结果回填
const result = this.execute(tool, args);
messages.push(Message.user('工具 ' + tool + ' 的执行结果: ' + result));
const answer = await this.model.chat(messages);   // 基于事实作答`
  ],

  step05: [
`async chat(input) {
    this.history.push(Message.user(input));

    // 历史超限 → 压缩：最旧两轮摘成一句话
    if (this.history.length > MAX_ROUNDS * 2) {
        this.compress();
    }

    const context = [];
    context.push(Message.system(SYSTEM_PROMPT));
    if (this.summary !== '') {
        context.push(Message.system('【历史摘要】' + this.summary));
    }
    context.push(...this.history);          // ← 记忆的全部秘密

    const answer = await this.model.chat(context);
    this.history.push(Message.assistant(answer));
    return answer;
}`
  ],

  step06: [
`// 1. 意图识别：让模型输出分类 JSON
const ROUTER_PROMPT = '你是意图识别路由器。分析用户输入，只输出 JSON：\\n'
    + '{"intent": "weather|math|chat", "reason": "一句话理由"}';
const raw = (await this.model.chat([
    Message.system(ROUTER_PROMPT),
    Message.user(input),
])).trim();
const intent = str(tryParse(raw), 'intent') ?? 'chat';

// 2. 决策中枢：按意图路由
switch (intent) {
    case 'weather': {   // 走工具
        const city = extractCity(input);
        return '「' + city + '」现在' + MockWeather.query(city) + '。';
    }
    case 'math':        // 走计算器
        return '计算结果：' + this.calc(input) + '。';
    default: {          // 走对话
        return this.model.chat([
            Message.system('你是一个友好的中文聊天助手。'),
            Message.user(input)]);
    }
}`
  ],

  step07: [
`// 1. 检索：知识片段与问题打分（bigram 重合率），取 Top2
const ranked = KNOWLEDGE
    .map(text => ({ text, score: score(text, input) }))
    .sort((a, b) => b.score - a.score)
    .slice(0, 2);

// 2. 增强：命中片段注入提示词，带来源编号
let ctx = '【参考资料】\\n';
for (let i = 0; i < hits.length; i++) {
    ctx += '[' + (i + 1) + '] ' + hits[i].text + '\\n';
}

// 3. 生成：约束模型"优先依据资料回答，不足则拒答"
const answer = await this.model.chat([
    Message.system('优先依据【参考资料】回答并标注来源 [1]；'
        + '资料不足时如实说不知道，禁止编造。'),
    Message.user(ctx),
    Message.user(input)]);`
  ],

  step08: [
`// 基础 Wiki：启动时编译一次（生产中来自仓库里的 AGENTS.md）
const BASE_WIKI = {
    '架构约定': 'ToyAgent 是零依赖教学项目……',
    '代码规范': '类与核心方法必须有中文注释说明教学意图……',
    '部署流程': 'node src/main.js 启动，未配 Key 自动回落 Mock……',
};

// 编译指令：把对话中的知识沉淀进 Wiki 并持久化
if (input.startsWith('编译：') || input.startsWith('记住：')) {
    const body = input.substring(3).trim();
    const sep = body.indexOf('：');
    const topic = body.substring(0, sep).trim();
    const content = body.substring(sep + 1).trim();
    this.userWiki.set(topic, content);   // 增量条目
    this.persistUserWiki();              // 写入 wiki/user-wiki.md，重启不丢
}`
  ],

  step09: [
`// MCP Server：工具的标准化宿主（真实实现为独立进程，JSON-RPC 通信）
class McpServer {
    tools() { throw new Error('McpServer 子类必须实现 tools()'); }
    call(toolName, argsJson) { throw new Error('McpServer 子类必须实现 call()'); }
}

// 工具描述符：标准"名片"
class ToolDescriptor {
    constructor(name, description, parameters) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
    }
}

// 智能体侧：动态发现，而不是硬编码
const tools = this.client.listTools();   // tools/list 工具发现
const result = this.client.callTool('get_weather', '{"city": "杭州"}');`
  ],

  step10: [
`// 1. 技能选择：模型按语义匹配
const raw = (await this.model.chat([
    Message.system(SELECT_PROMPT),
    Message.user(input),
])).trim();
const skill = str(tryParse(raw), 'skill') ?? 'free_chat';

// 2. 技能执行：确定性编排，而非现场发挥
switch (skill) {
    case 'travel_plan':    return this.travelPlan(input);    // L1: 组合两个工具
    case 'weather_report': return this.weatherReport(input); // L1: 单工具
    default:               return this.freeChat(input);      // L0: 纯提示词
}

// L1 技能内部：工具结果是确定性的两步
const weather = MockWeather.query(city);
const time = formatTime().slice(0, 16);   // yyyy-MM-dd HH:mm
const answer = await this.model.chat([
    Message.system('你是旅行顾问。基于天气与时间信息输出攻略...'),
    Message.user(input + '\\n天气: ' + weather + '\\n当前时间: ' + time)]);`
  ],

  step11: [
`// 工具定义协议 —— 一个类 = 一个工具
export class ToolDefinition {
    name() { throw new Error('ToolDefinition 子类必须实现 name()'); }
    description() { throw new Error('ToolDefinition 子类必须实现 description()'); }
    parameters() { throw new Error('ToolDefinition 子类必须实现 parameters()'); }
    async execute(args) { throw new Error('ToolDefinition 子类必须实现 execute(args)'); }
    // 是否可并行执行（fs 读类 true，shell 写类 false）—— 预留并行调度语义
    concurrencySafe() { return false; }
}

// 一个子类就是一个工具
class WeatherTool extends ToolDefinition {
    name() { return 'get_weather'; }
    description() { return '查询指定城市的实时天气'; }
    parameters() { return { 'city': 'string，城市名，如 北京' }; }
    execute(args) { /* 查表返回天气 */ }
}`,
`// 注册工具，返回注销器（disposer）：调用它即把该工具从目录摘除
register(tool) {
    this.tools.set(tool.name(), tool);
    return () => {
        this.tools.delete(tool.name());
    };
}

// 场景侧：注册时把注销器存起来（注册-回收对称性）
this.disposers.set('get_weather', this.registry.register(new WeatherTool()));

// 注销：模型下一次对话就"看不见"这个工具了
const disposer = this.disposers.get('get_time');
disposer();`
  ],

  step12: [
`// 每个角色 = 同一个 ChatModel + 不同的系统提示词
const planRaw = (await this.model.chat([
    Message.system('你是规划者（Planner）。把用户任务拆解为三个角色的执行计划，只输出 JSON：\\n'
        + '{"plan": [{"role": "研究员", "task": "..."}, '
        + '{"role": "写手", "task": "..."}, {"role": "审查员", "task": "..."}]}'),
    Message.user(input),
])).trim();

// 上游产出作为下游输入 —— 消息在角色间流动
const notes = await this.model.chat([
    Message.system('你是研究员，围绕任务输出 3 条以内研究要点。'),
    Message.user(input)]);
const draft = await this.model.chat([
    Message.system('你是写手。基于研究笔记写短文。\\n笔记: ' + notes),
    Message.user(input)]);
// 审查员可以打回 → 触发修改循环`
  ],

  step13: [
`// fork：子代理的 system 里带上父会话；spawn：全新实例
let sys = '【子代理身份】' + specialty;
if (fork) {
    sys += '\\n【继承的父会话上下文】\\n' + this.parentTranscript.join('\\n');
} else {
    sys += '\\n（spawn 模式：全新实例，看不到父会话）';
}

childMessages.push(Message.system(sys));
childMessages.push(Message.user(task));
const report = (await this.model.chat(childMessages)).trim();  // 子代理独立一轮`
  ],

  step14: [
`// 名片：A2A 的发现单元（生产中托管在 /.well-known/agent.json）
class AgentCard {
    constructor(name, url, version, skills, description) {
        this.name = name;
        this.url = url;
        this.version = version;
        this.skills = skills;
        this.description = description;
    }
}

// 发送：按名片路由 + 状态机流转（发起方只认 task_id）
const taskId = 'task-' + String(++this.seq).padStart(3, '0')
    + '-' + randomUUID().slice(0, 4);
//  submitted → working → completed
return '{"task_id": "' + taskId + '", "status": "completed", "result": "' + reply + '"}';`
  ],

  step15: [
`async chat(input) {
    // ① 输入守卫：长度限制 + 敏感词/注入检测
    if (input.length > MAX_INPUT_CHARS) return '输入过长，已拦截';
    for (const word of BLOCKED) {
        if (input.toLowerCase().includes(word)) return '该请求已被输入守卫拦截';
    }

    // ② Agent Loop（继承 Step03 骨架）
    for (let step = 1; step <= MAX_STEPS; step++) {
        let raw;
        try {
            raw = (await this.model.chat(messages)).trim();  // ④ 异常兜底
        } catch (e) {
            return '运行时异常已兜底，请稍后重试。';
        }
        const parsed = tryParse(raw);
        const fin = str(parsed, 'final');
        if (fin != null) {
            // ③ 输出守卫：合规检查后才放行
            return guard(fin);
        }
        // 工具异常不拖垮循环
        let observation;
        try {
            observation = execute(action, actionInput);
        } catch (e) {
            observation = '工具执行异常: ' + e.message;
        }
        messages.push(Message.user('Observation: ' + observation));
    }
    return '已达到最大执行步数，为控制成本与风险强制终止。';  // 保险丝熔断
}`
  ],

  step16: [
`// State：在节点之间流转的共享状态
const state = new Map();
state.set('input', input);
state.set('answer', '');
state.set('observation', '');

// 固定入口
await this.runNode('classify', state);

// 条件边：按分类结果路由
const next = state.get('next') ?? 'faq';
await this.runNode(next, state);

// 汇合点：统一润色后结束
await this.runNode('polish', state);
return String(state.get('answer'));`
  ],

  step17: [
`// 一个 turn：循环执行 step，直到出现结束原因（TurnEndReason）
async runTurn(input) {
    for (let step = 1; step <= MAX_STEPS_PER_TURN; step++) {
        const req = [];   // 系统提示词 + 裁剪后的历史
        req.push(Message.system(SYSTEM_PROMPT + this.registry.promptCatalog()));
        req.push(...this.history);
        const raw = (await this.model.chat(req)).trim();

        // 模型给出 final → Completed；发起工具 → 执行后续步（续步）
        const parsed = tryParse(raw);
        const fin = str(parsed, 'final');
        if (fin != null) {
            this.lastAnswer = fin;
            return TurnEndReason.completed('模型给出最终答案');
        }
        const tool = this.registry.lookup(str(parsed, 'action'));
        const observation = await tool.execute({ text: str(parsed, 'action_input') });
        this.history.push(Message.user('Observation: ' + observation));  // 观察回填
    }
    return TurnEndReason.maxSteps(MAX_STEPS_PER_TURN);  // 保险丝
}`,
`// token 估算：教学化近似 token ≈ 字符数 / 4（dsh-java 同款）
function estimateTokens(msgs) {
    return msgs.reduce((sum, m) => sum + Math.floor(m.content.length / 4), 0);
}

// 超预算：从最老的消息开始丢，保底留最近 4 条
trimToBudget() {
    if (estimateTokens(this.history) <= BUDGET_TOKENS
        || this.history.length <= KEEP_MIN) return;
    let drop = 0;
    while (estimateTokens(this.history) > BUDGET_TOKENS
        && this.history.length > KEEP_MIN) {
        this.history.shift();
        drop++;
    }
}`
  ],

  step18: [
null,
`async chat(input) {
    // ① 输入守卫：长度与敏感意图，不通过直接返回，模型预算为零
    if (input.length > MAX_INPUT_LEN) return '输入过长，请精简后重试';
    if (SENSITIVE.test(input)) return '该请求已被输入守卫拦截';

    // ② 记忆读取 + 画像提取（我叫 XX → 存档案）
    this.extractProfile(input);

    // ③ 组装上下文：系统提示词（含画像）+ 历史 + 本轮输入
    const messages = [];
    messages.push(Message.system(SYSTEM_PROMPT + this.userPortrait()));
    messages.push(...this.history);
    messages.push(Message.user(input));

    // ④⑤ ReAct 循环 + 保险丝（Step03 的骨架原样复用）
    let answer = await this.runReAct(messages);

    // ⑥ 输出校验 + 画像兜底（「我叫什么」不问模型，查档案）
    answer = this.outputGuard(answer, input);

    // ⑦ 回写记忆，超阈值触发上下文压缩
    this.history.push(Message.user(input));
    this.history.push(Message.assistant(answer));
    if (this.history.length > COMPRESS_THRESHOLD) this.compressHistory();
    return answer;
}`,
`// 记忆兜底：模型看不到被压缩掉的历史时，画像仍能回答「我叫什么」
if (input.includes('我叫什么') || input.includes('我是谁')) {
    const name = this.profile.get('名字');
    if (name != null) {
        return '你叫 ' + name + '（来自画像记忆，不依赖模型）。';
    }
}`,
null
  ],

  step19: [
`// 第一次请求：模型发现缺出发地 → 发起 ask_user_question
const question = (parsed && typeof parsed === 'object') ? str(parsed, 'ask') : null;
if (question != null) {
    this.pendingQuestion = question;   // 问题持久化在会话状态（挂起）
    return question + '\\n\\n（直接回复即可，我会带着你的答复继续任务。）';
}

// 第二次请求：用户的回复不进模型，而是作为人工答复回填
if (this.pendingQuestion != null) {
    const q = this.pendingQuestion;
    this.pendingQuestion = null;
    this.history.push(Message.user('人工答复：' + input));
    return this.complete(q);           // turn 恢复，带着完整信息继续
}`
  ],

  step20: [
`// 权限评估：风险分级 + 模式 + 会话免审记忆
const dangerous = isDangerous(tool);   // send_email / delete_file = 高危写操作
const needAsk = this.mode === Mode.ALWAYS_ASK
    || (this.mode === Mode.AUTO_MANUAL_SAFE && dangerous && !this.trustedTools.has(tool));

if (needAsk) {
    this.pendingTool = tool;           // 审批挂起：先答复人，不执行
    return '⚠️ 即将执行高危操作：' + tool + '\\n参数：' + args
        + '\\n\\n请回复：「批准」执行 ｜「拒绝」取消 ｜「批准并记住」本会话内该工具免审';
}
// 人回复后进入 resolveApproval：批准→执行；拒绝→终止；批准并记住→加入免审名单`
  ],

  step21: [
`// 第 3 层与第 4 层的分工：看原文 vs 看真实落点
const abs = /(?<![\\w.])\\/(?:[\\w.-]+\\/)*[\\w.-]+/g;
let m;
while ((m = abs.exec(cmd)) !== null) {      // 逐个检查命令里的绝对路径
    const resolved = path.resolve(m[0]);    // 解析到文件系统真实位置
    if (!(resolved === this.workspace
        || resolved.startsWith(this.workspace + path.sep)))
        return this.deny(3, '绝对路径 ' + m[0] + ' 越出工作区');
}
if (cmd.includes('..'))                     // 第 4 层：规范化后复查，../ 逃逸无所遁形
    return this.deny(4, '检测到 ../ 相对跳转，疑似路径逃逸');`
  ],

  step22: [
null,
`// 投影：事件流 → 会话上下文（只挑 USER / AGENT 两类，其余事件服务审计）
project(log) {
    const history = [];
    for (const e of log) {
        const type = String(e.type);
        const content = String((e.payload ?? {}).content);
        switch (type) {
            case EV_USER:  history.push(Message.user(content)); break;
            case EV_AGENT: history.push(Message.assistant(content)); break;
            default: break;  // 不进上下文，但留在日志里
        }
    }
    return history;
}`
  ],

  step23: [
`// 1. 契约：插件作者唯一要实现的东西
export class AgentPlugin {
    id() { throw new Error('AgentPlugin 子类必须实现 id()'); }        // 卸载时靠它批量回收
    version() { throw new Error('AgentPlugin 子类必须实现 version()'); }
    onStart() { }    // 安装/激活后由宿主调用一次
    onStop() { }     // 卸载前由宿主调用一次
    tools() { throw new Error('AgentPlugin 子类必须实现 tools()'); }  // 插件带来的工具
}

// 2. 隔离加载：Java 用独立 URLClassLoader；JS 适配为动态 import() 独立模块文件
const mod = await import(pathToFileURL(path.join(PLUGIN_DIR, 'time-plugin.mjs')).href);
const plugin = new (mod.TimePlugin ?? mod.default)();
plugin.onStart();

// 3. 工具桥接：注册 + disposer 按 pluginId 归档（卸载即批量注销）
for (const tool of plugin.tools()) disposers.push(this.registry.register(tool));

// 4. 卸载三步回收：批量 disposer → onStop → 释放模块引用`
  ],

  step24: [
`// 钩子本体：name 标识、phase 挂载点（before/after）、match 匹配的工具名（* = 全部）
// handler 返回 null=放行；"DENY:原因"=拦截；否则替换内容
class Hook {
    constructor(name, phase, match, handler) {
        this.name = name;
        this.phase = phase;
        this.match = match;
        this.handler = handler;
    }
}

this.hooks.push(new Hook('风控-敏感外发', 'before', ['send_sms'], (tool, payload) =>
    payload.includes('密码') ? 'DENY:敏感信息禁止外发' : null));

this.hooks.push(new Hook('脱敏', 'after', ['*'], (tool, payload) => {
    const masked = payload.replace(/(1[3-9])\\d{9}/g, '$1*********');
    return masked === payload ? null : masked;
}));

// 管线：before 任何一条拦截即终止；after 按注册顺序依次加工（HookOutputMerger）`
  ],

  step25: [
`// 终端形态 = Agent.chat 的 while 循环（完整实现见 step25CliAgent.js）
const rl = createInterface({ input: stdin, output: stdout });
while (true) {
    const line = await ask('你> ');
    if (line == null) break;
    const input = line.trim();
    if (input === '') continue;
    if (input === '/exit') break;
    if (input === '/reset') { agent.reset(); continue; }
    const answer = await agent.chat(input);      // 灵魂不变
    console.log('Agent> ' + answer);
}

// 手脚换成沙箱执行器：spawnSync 按空格拆分，不经 shell（无管道/重定向注入面）
const parts = cmd.split(/\\s+/);
const p = spawnSync(parts[0], parts.slice(1), {
    cwd: WORKSPACE,   // 受限工作区 = 沙箱第④层
    encoding: 'utf-8',
    timeout: 5000,
});`,
`# 交互式 REPL（先选场景再对话，/reset 清空状态，/exit 退出）
node src/main.js

# 非交互单命令（Claude Code 同款 -p 习惯）
node src/main.js run step25 "看看工作区里有什么"`
  ],

  step26: [
`// 一次性：n 秒后触发（生产用持久化调度器/延时队列）
const timer = setTimeout(() => this.fire(id, content), delaySeconds * 1000);
job.cancelFn = () => clearTimeout(timer);

// 循环：setInterval（首延迟 = 周期），触发次数持续累计
const timer = setInterval(() => this.fire(id, content), intervalSeconds * 1000);
job.cancelFn = () => clearInterval(timer);

// 触发即落盘：append-only JSONL（事件溯源）
appendFileSync(EVENT_FILE,
    '{"time":"' + time + '","job":"' + id + '","content":"' + content + '"}\\n',
    'utf-8');`
  ]

};
