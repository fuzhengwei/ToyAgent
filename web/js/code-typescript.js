// 五语言代码切换：TypeScript 片段（与 articles.js 各文章 <pre> 块按顺序对齐，null=该块语言无关不切换）
// 片段与 ToyAgent-Typescript 工程（src/agents/stepXX*Agent.ts、src/llm/、src/main.ts）的真实实现一致。
window.ARTICLE_CODE = window.ARTICLE_CODE || {};
window.ARTICLE_CODE.typescript = {

  home: [
`// 智能体最小契约：输入一句话，输出一句话
// 语言适配：Node 的 fetch 是异步的，故 chat 返回 Promise（Java 为阻塞同步）
export interface Agent {
  name(): string;                        // 场景名称，用于展示
  chat(input: string): Promise<string>;  // 最小契约：用户消息 → 回答
  trace(): TraceEntry[];                 // 执行轨迹（供渲染时间线）
  reset(): void;                         // 清空内部状态（如记忆）
}

// 基类：提供 Java 接口 default 方法的等价物（空轨迹 + 空重置）
export abstract class BaseAgent implements Agent {
  abstract name(): string;
  abstract chat(input: string): Promise<string>;
  trace(): TraceEntry[] { return []; }
  reset(): void {}
}`,
`# 安装依赖（TypeScript 仅 devDependencies，运行时零依赖）
cd ToyAgent-Typescript
npm install

# 编译 + 启动 CLI 运行器（Node 18+）
npm run build
node dist/main.js

# 交互式 REPL：先选场景（如 step03），再循环输入
node dist/main.js run step03 "北京天气怎么样"

# 接入真实大模型：在配置中填入 API Key，未配置时自动回落 Mock 模型`
  ],

  step01: [
`export class ChatAgent extends BaseAgent {
  private readonly model: ChatModel;

  constructor(model: ChatModel) {
    super();
    this.model = model;
  }

  async chat(input: string): Promise<string> {
    // 全部实现就这一行核心逻辑
    return this.model.chat([Message.user(input)]);
  }
}`,
`// 模型抽象：智能体的"大脑"
// 背后是 OpenAI 兼容端点还是本地 Mock，智能体的策略代码完全不用改
export interface ChatModel {
  // 输入一组对话消息，输出模型回复文本
  chat(messages: Message[]): Promise<string>;
}

// 一条对话消息：role 取值 system / user / assistant
export interface Message {
  role: 'system' | 'user' | 'assistant';
  content: string;
}`,
null
  ],

  step02: [
`// 三层结构系统提示词：角色 → 背景 → 任务与约束
private static readonly SYSTEM_PROMPT = '# 角色\n'
  + '你是小傅哥的技术助理，专注讲清楚 AI Agent 的原理与工程实践。\n\n'
  + '# 背景\n'
  + '读者是正在学习智能体开发的 Java 工程师，熟悉后端但对 AI 是新手。\n\n'
  + '# 任务与约束\n'
  + '1. 回答使用简体中文，先给结论，再展开解释。\n'
  + '2. 若用户要求"用一句话"，必须严格只回答一句话。\n'
  + '3. 不确定的内容要如实说明，禁止编造。\n';

async chat(input: string): Promise<string> {
  const answer = await this.model.chat([
    Message.system(PromptAgent.SYSTEM_PROMPT),  // 唯一新增
    Message.user(input),
  ]);
  return answer;
}`
  ],

  step03: [
`// 用系统提示词约定 ReAct 的输出协议（JSON 形式，便于解析）
private static readonly SYSTEM_PROMPT = '你是一个使用 ReAct 模式解决问题的智能体。\n'
  + '每一轮只输出一个 JSON 对象，格式二选一：\n'
  + '1. 继续行动：{"thought": "...", "action": "工具名", "action_input": "参数"}\n'
  + '2. 给出答案：{"thought": "...", "final": "最终答案"}\n'
  + '可用工具：\n'
  + '- get_weather(city): 查询城市天气\n'
  + '- calculator(expression): 计算四则运算，如 12*8\n'
  + '- get_time(): 获取当前时间\n'
  + '不要输出 JSON 以外的内容。\n';`,
`for (let step = 1; step <= MAX_STEPS; step++) {
  // 1. 模型思考并决策
  let raw = (await this.model.chat(messages)).trim();
  raw = stripCodeFence(raw);
  let parsed: unknown;
  try {
    parsed = jsonParse(raw);
  } catch {
    return raw;                                   // 模型没按协议 → 视为最终答案
  }

  // 2. 有 final → 循环结束
  const fin = jsonStr(parsed, 'final');
  if (fin !== null) return fin;

  // 3. 有 action → 执行工具，把观察结果喂回模型
  const observation = executeTool(jsonStr(parsed, 'action'), jsonStr(parsed, 'action_input'));
  messages.push(Message.assistant(raw));
  messages.push(Message.user('Observation: ' + observation));
}
return '已达最大步数，循环终止（生产系统必须有这个保险丝）';  // 4. 保险丝`
  ],

  step04: [
`// 第一步：提示词声明工具清单；模型需要工具时只输出 JSON
// 第二轮请求发出前，模型返回 → {"tool": "get_weather", "arguments": {"city": "广州"}}
const first = (await this.model.chat(messages)).trim();
const decision = tryParse(first);

if (isMap(decision) && jsonStr(decision, 'tool') !== null) {
  const tool = jsonStr(decision, 'tool');
  const args = jsonWrite((decision as Record<string, unknown>)['arguments']);

  // 第二步：执行工具
  const result = execute(tool, args);

  // 第三步：结果回填，让模型基于事实作答
  messages.push(Message.assistant(first));
  messages.push(Message.user('工具 ' + tool + ' 的执行结果: ' + result));
  const answer = await this.model.chat(messages);   // 基于事实作答
  return answer;
}
return first;   // 模型选择不调用工具，直接回答`
  ],

  step05: [
`async chat(input: string): Promise<string> {
  this.history.push(Message.user(input));

  // 历史超限 → 压缩：把最旧的两轮合并为一条摘要
  if (this.history.length > MAX_ROUNDS * 2) {
    this.compress();
  }

  // 组装上下文：系统提示词 + 摘要 + 历史
  const context: Message[] = [];
  context.push(Message.system(MemoryAgent.SYSTEM_PROMPT));
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
private static readonly ROUTER_PROMPT = '你是意图识别路由器。分析用户输入，只输出 JSON：\n'
  + '{"intent": "weather|math|chat", "reason": "一句话理由"}\n'
  + '- weather: 查询天气\n- math: 四则运算\n- chat: 其他一切对话\n';

const raw = (await this.model.chat([
  Message.system(RouterAgent.ROUTER_PROMPT),
  Message.user(input),
])).trim();
let intent = jsonStr(tryParse(raw), 'intent');
if (intent === null) intent = 'chat';

// 2. 决策中枢：按意图路由
switch (intent) {
  case 'weather': {
    const result = MockWeather.query(extractCity(input));   // 走工具
    return '「' + extractCity(input) + '」现在' + result + '。';
  }
  case 'math':
    return '计算结果：' + calc(input) + '。';                // 走计算器
  default:
    return this.model.chat([                                 // 走对话
      Message.system('你是一个友好的中文聊天助手。'),
      Message.user(input),
    ]);
}`
  ],

  step07: [
`// 1. 检索：知识片段与问题打分（bigram 重合率），取 Top2
const ranked = RagAgent.KNOWLEDGE
  .map((text) => ({ text, score: score(text, input) }))
  .sort((a, b) => b.score - a.score)
  .slice(0, 2);
const hits = ranked.filter((h) => h.score > 0);

// 2. 增强：命中片段注入提示词，带来源编号
const messages: Message[] = [];
messages.push(Message.system(RagAgent.SYSTEM_PROMPT));
if (hits.length > 0) {
  let ctx = '【参考资料】\n';
  for (let i = 0; i < hits.length; i++) {
    ctx += '[' + (i + 1) + '] ' + hits[i].text + '\n';
  }
  messages.push(Message.user(ctx));
}
messages.push(Message.user(input));

// 3. 生成：约束模型"优先依据资料回答，不足则拒答"
const answer = await this.model.chat(messages);
return answer;`
  ],

  step08: [
`// 基础 Wiki：启动时编译一次（生产中来自仓库里的 AGENTS.md / CLAUDE.md）
private static readonly BASE_WIKI: Record<string, string> = {
  '架构约定': 'ToyAgent 是零依赖教学项目：一个接口类就是一个智能体场景，禁止引入第三方依赖。',
  '代码规范': '类与核心方法必须有中文 Javadoc 说明教学意图；JSON 一律走自研 Json 工具类。',
  '部署流程': 'javac 编译后启动，默认端口 8099；前端静态资源在 web/ 目录由服务直接托管。',
  '业务规则': '未配置 API Key 时所有场景回落 Mock 演示模型。',
};

// 编译指令：把对话中的知识沉淀进 Wiki 并持久化
if (input.startsWith('编译：') || input.startsWith('记住：')) {
  return this.compile(input);   // 解析「编译：主题：内容」→ userWiki.set(topic, content)
}                               // persistUserWiki() 写入 wiki/user-wiki.md，重启不丢`
  ],

  step09: [
`// MCP Server 接口：工具的标准化宿主（真实实现为独立进程，JSON-RPC 通信）
interface McpServer {
  tools(): ToolDescriptor[];                         // tools/list 工具发现
  call(toolName: string, argsJson: string): string;  // tools/call 工具调用
}

// 工具描述符：标准"名片"
interface ToolDescriptor {
  name: string;
  description: string;
  parameters: string;
}

// 智能体侧：动态发现，而不是硬编码
const tools = this.client.listTools();
const result = this.client.callTool('get_weather', '{"city": "杭州"}');`
  ],

  step10: [
`// 1. 技能选择：模型按语义匹配
const raw = (await this.model.chat([
  Message.system(SkillAgent.SELECT_PROMPT),
  Message.user(input),
])).trim();
let skill = jsonStr(tryParse(raw), 'skill');
if (skill === null) skill = 'free_chat';

// 2. 技能执行：确定性编排，而非现场发挥
switch (skill) {
  case 'travel_plan':    return this.travelPlan(input);    // L1: 组合两个工具
  case 'weather_report': return this.weatherReport(input); // L1: 单工具
  default:               return this.freeChat(input);      // L0: 纯提示词
}

// L1 技能内部：工具结果是确定性的两步
const weather = MockWeather.query(city);
const time = formatDateTime(new Date(), 'yyyy-MM-dd HH:mm');
const answer = await this.model.chat([
  Message.system('你是旅行顾问。基于天气与时间信息输出简短出行攻略。'),
  Message.user(input + '\n天气: ' + weather + '\n当前时间: ' + time),
]);`
  ],

  step11: [
`export interface ToolDefinition {
  name(): string;                          // 模型调用标识
  description(): string;                   // 用途：模型选工具的唯一依据
  parameters(): Record<string, string>;    // 参数说明（生产中是 JSON Schema）
  execute(args: Record<string, unknown>): string;  // 执行手脚
  concurrencySafe?(): boolean;             // 可否并行执行（预留）
}

// 一个类就是一个工具
class WeatherTool implements ToolDefinition {
  name(): string { return 'get_weather'; }
  description(): string { return '查询指定城市的实时天气'; }
  parameters(): Record<string, string> { return { 'city': 'string，城市名，如 北京' }; }
  execute(args: Record<string, unknown>): string {
    const city = String(args['city'] ?? '').trim();
    return WeatherTool.DATA[city] ?? ('未收录城市 ' + city + ' 的数据');
  }
}`,
`// 注册工具，返回注销器（disposer）：调用它即把该工具从目录摘除
register(tool: ToolDefinition): () => void {
  this.tools.set(tool.name(), tool);
  return () => {
    this.tools.delete(tool.name());
  };
}

// 注册时保存注销器 —— 持有它才能摘除工具，"注册-回收对称性"
this.disposers.set('get_weather', this.registry.register(new WeatherTool()));

// 注销：模型下一次对话就"看不见"这个工具了
const disposer = this.disposers.get('get_time');
if (disposer) disposer();
this.disposers.delete('get_time');`
  ],

  step12: [
`// 1. 规划者：拆解任务
const planRaw = (await this.model.chat([
  Message.system('你是规划者（Planner）。把用户任务拆解为三个角色的执行计划，只输出 JSON：\n'
    + '{"plan": [{"role": "研究员", "task": "..."}, '
    + '{"role": "写手", "task": "..."}, {"role": "审查员", "task": "..."}]}'),
  Message.user(input),
])).trim();

// 2. 研究员：收集素材
const notes = await this.model.chat([
  Message.system('你是研究员（Researcher），围绕任务输出 3 条以内研究要点，每条一句话。'),
  Message.user(input),
]);

// 3. 写手：基于素材成稿 —— 上游产出作为下游输入，消息在角色间流动
const draft = await this.model.chat([
  Message.system('你是写手（Writer）。基于研究笔记写一段 100 字以内的短文。\n研究笔记: ' + notes),
  Message.user(input),
]);
// 4. 审查员可以打回 → 触发修改循环`
  ],

  step13: [
`// fork：子代理的 system 里带上父会话；spawn：全新实例
const fork = (mode ?? '').toLowerCase() === 'fork';
let sys = '【子代理身份】' + spec.specialty;
if (fork) {
  sys += '\n【继承的父会话上下文】\n' + this.parentTranscript.join('\n');
} else {
  sys += '\n（spawn 模式：全新实例，看不到父会话）';
}

const childMessages: Message[] = [];
childMessages.push(Message.system(sys));
childMessages.push(Message.user(task ?? ''));

const report = (await this.model.chat(childMessages)).trim();  // 子代理独立一轮`
  ],

  step14: [
`// 名片：A2A 的发现单元（生产中托管在 .well-known/agent.json）
interface AgentCard {
  name: string;
  url: string;
  version: string;
  skills: string[];
  description: string;
}

const DIRECTORY = new Map<string, AgentCard>([
  ['translator', { name: 'translator', url: 'http://a2a.local/translator',
    version: '1.0.0', skills: ['translate'], description: '中英互译代理' }],
  ['weather', { name: 'weather', url: 'http://a2a.local/weather',
    version: '2.1.0', skills: ['forecast'], description: '天气预报代理' }],
]);

// 发送：按名片路由 + 状态机流转（发起方只认 task_id）
const taskId = 'task-' + String(this.seq).padStart(3, '0') + '-' + randomUUID().substring(0, 4);
//  submitted → working → completed
return '{"task_id": "' + taskId + '", "status": "completed", "result": "' + reply + '"}';`
  ],

  step15: [
`async chat(input: string): Promise<string> {
  // ① 输入守卫：长度限制 + 敏感词/注入检测
  if (input.length > MAX_INPUT_CHARS) return '输入过长，已拦截。';
  for (const word of BLOCKED) {
    if (input.toLowerCase().includes(word)) return '该请求已被输入守卫拦截。';
  }

  // ② Agent Loop（继承 Step03 骨架）
  for (let step = 1; step <= LOOP_MAX_STEPS; step++) {
    let raw: string;
    try {
      raw = (await this.model.chat(messages)).trim();   // ④ 异常兜底
    } catch (e) {
      return '运行时异常已兜底，请稍后重试。';
    }
    const fin = jsonStr(tryParse(raw), 'final');
    if (fin !== null) {
      // ③ 输出守卫：合规检查后才放行
      return this.outputGuard(fin);
    }
    // 工具异常不拖垮循环
    let observation: string;
    try { observation = execute(action, actionInput); }
    catch (e) { observation = '工具执行异常: ' + e; }
    messages.push(Message.user('Observation: ' + observation));
  }
  return '已达到最大执行步数，强制终止。';  // 保险丝熔断
}`
  ],

  step16: [
`// State：在节点之间流转的共享状态
const state = new Map<string, string>();
state.set('input', input);
state.set('answer', '');
state.set('observation', '');

// 固定入口：classify 节点
await this.runNode('classify', state);

// 条件边：按分类结果路由
const next = state.get('next') ?? 'faq';

await this.runNode(next, state);
await this.runNode('polish', state);   // 汇合点：统一润色后结束

return state.get('answer') ?? '';`
  ],

  step17: [
`// 一个 turn：step 循环执行，直到出现结束原因（工具执行完不结束回合，而是续步）
private async runTurn(_input: string): Promise<TurnEndReason> {
  for (let step = 1; step <= MAX_STEPS_PER_TURN; step++) {
    // 组装请求：系统提示词 + 裁剪后的会话历史
    const req: Message[] = [];
    req.push(Message.system(SYSTEM_PROMPT + this.registry.promptCatalog()));
    for (const m of this.history) req.push(m);

    const raw = (await this.model.chat(req)).trim();
    const parsed = tryParse(raw);

    const fin = jsonStr(parsed, 'final');
    if (fin !== null) {
      this.lastAnswer = fin;
      return { kind: 'Completed', summary: '模型给出最终答案' };
    }

    // 工具调用：观察回填进历史 → 下一 step 模型自然"看见"，循环继续
    const observation = this.executeTool(parsed);
    this.history.push(Message.user('Observation: ' + observation));
  }
  return { kind: 'MaxSteps', steps: MAX_STEPS_PER_TURN };  // 保险丝
}`,
`// token 估算：教学化近似 token ≈ 字符数 / 4（dsh-java 同款）
function estimateTokens(msgs: Message[]): number {
  let total = 0;
  for (const m of msgs) total += Math.floor(m.content.length / 4);
  return total;
}

// 超预算：从最老的消息开始丢，保底留最近 KEEP_MIN 条
private trimToBudget(): void {
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
`async chat(input: string): Promise<string> {
  // ① 输入守卫：长度与敏感意图，不通过直接返回，模型预算为零
  if (input.length > MAX_INPUT_LEN) return '输入过长，请精简后重试';
  if (FullAgent.SENSITIVE.exec(input)) return '该请求已被输入守卫拦截';

  // ② 记忆读取 + 画像提取（我叫 XX → 存档案）
  this.extractProfile(input);

  // ③ 组装上下文：系统提示词（含画像）+ 历史 + 本轮输入
  const messages: Message[] = [];
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
`// 画像兜底：问答时先查档案，不依赖模型（历史压缩后仍记得你叫什么）
if (input.includes('我叫什么') || input.includes('我是谁')) {
  const name = this.profile.get('名字');
  if (name !== undefined) {
    return '你叫 ' + name + '（来自画像记忆，不依赖模型）。';
  }
}

// 画像提取：记住「我叫 XX」（疑问句「我叫什么」被负向先行断言排除）
private static readonly NAME_PTN =
  /我(?:叫|的名字(?:是|叫)?)\\s*(?!(?:什么|啥|谁))([\\u4e00-\\u9fa5A-Za-z0-9]{1,12})/;

private extractProfile(input: string): void {
  const n = FullAgent.NAME_PTN.exec(input);
  if (n) this.profile.set('名字', n[1]);
}`,
null
  ],

  step19: [
`// 分支一：有挂起问题 —— 本条输入是「人工答复」，不进模型
if (this.pendingQuestion !== null) {
  const q = this.pendingQuestion;
  this.pendingQuestion = null;
  this.lastTrace.push({ label: '人工答复回填', detail: '挂起问题收到答复：' + input });
  this.history.push(Message.user('人工答复：' + input));

  const answer = await this.complete(q);  // turn 恢复，带着完整信息继续
  this.history.push(Message.assistant(answer));
  return answer;
}

// 分支二：模型发现缺关键信息 → 发起 ask_user_question（挂起）
const question = isMap(parsed) ? jsonStr(parsed, 'ask') : null;
if (question !== null) {
  this.pendingQuestion = question;        // 问题持久化在会话状态（挂起）
  return question + '\n\n（直接回复即可，我会带着你的答复继续任务。）';
}`
  ],

  step20: [
`// 权限评估：风险分级 + 三档模式 + 会话免审记忆
const dangerous = isDangerous(tool);   // send_email / delete_file = 高危写操作
const needAsk = this.mode === ApprovalMode.ALWAYS_ASK
  || (this.mode === ApprovalMode.AUTO_MANUAL_SAFE && dangerous && !this.trustedTools.has(tool));

if (needAsk) {
  this.pendingTool = tool;             // 审批挂起：先答复人，不执行
  this.pendingArgs = args;
  return '⚠️ 即将执行高危操作：' + tool + '\n'
    + '请回复：「批准」执行 ｜「拒绝」取消 ｜「批准并记住」本会话内该工具免审';
}
// 人回复后进入 resolveApproval：批准→执行；拒绝→终止；批准并记住→加入免审名单
// 生产版审批有超时 —— 超时默认 DENY，宁可不执行`
  ],

  step21: [
`// 第 3 层与第 4 层的分工：看原文 vs 看真实落点
const absRe = /(?<![\\w.])\\/(?:[\\w.-]+\\/)*[\\w.-]+/g;
let m: RegExpExecArray | null;
while ((m = absRe.exec(cmd)) !== null) {   // 逐个检查命令里的绝对路径
  if (!this.insideWorkspace(m[0])) {
    return this.deny(3, '绝对路径 ' + m[0] + ' 越出工作区');
  }
}

if (cmd.includes('..')) {  // 第 4 层：规范化后复查，../ 逃逸无所遁形
  return this.deny(4, '检测到 ../ 相对跳转 —— 规范化后疑似路径逃逸');
}

// 绝对路径是否真实落在工作区内（等价 Java Path.normalize().startsWith(workspace)）
private insideWorkspace(p: string): boolean {
  const abs = path.resolve(p);
  return abs === this.workspace || abs.startsWith(this.workspace + path.sep);
}`
  ],

  step22: [
null,
`// 回放：逐行读 JSONL（append-only，所以重放顺序 = 发生顺序）
private replay(): EventMap[] {
  const log: EventMap[] = [];
  for (const line of fs.readFileSync(this.logFile, 'utf-8').split(/\\r?\\n/)) {
    if (line.trim() === '') continue;
    try {
      const parsed = jsonParse(line);
      if (isMap(parsed)) log.push(parsed);
    } catch { /* 损坏行跳过 —— append-only + 校验和是生产标配 */ }
  }
  return log;
}

// 投影：事件流 → 会话上下文（只挑 USER / AGENT 两类，其余事件服务审计）
private project(log: EventMap[]): Message[] {
  const history: Message[] = [];
  for (const e of log) {
    const type = String(e['type']);
    const payload = isMap(e['payload']) ? e['payload'] : {};
    const content = String(payload['content']);
    switch (type) {
      case EV_USER:  history.push(Message.user(content)); break;
      case EV_AGENT: history.push(Message.assistant(content)); break;
      default:       /* 不进上下文，但留在日志里 */ break;
    }
  }
  return history;
}`
  ],

  step23: [
`// 1. 契约：插件作者唯一需要实现的东西（dsh-java 的 JavaHarnessPlugin 简化版）
export interface AgentPlugin {
  id(): string;                // 插件唯一标识，卸载时靠它批量回收注册项
  version(): string;
  onStart?(): void;            // 生命周期：安装/激活后由宿主调用一次
  onStop?(): void;             // 生命周期：卸载前由宿主调用一次
  tools(): ToolDefinition[];   // 插件带来的工具
}

// 2. 隔离加载：Node 无 ClassLoader，用 createRequire 从插件目录独立加载
const req = createRequire(pathToFileURL(path.resolve(PLUGIN_DIR, PLUGIN_ENTRY_FILE)));
const plugin = req('./' + PLUGIN_ENTRY_FILE) as AgentPlugin;
if (typeof plugin.onStart === 'function') plugin.onStart();

// 3. 工具桥接：注册 + disposer 按 pluginId 归档（卸载即批量注销）
for (const tool of plugin.tools()) {
  disposers.push(this.registry.register(tool));
  toolNames.push(tool.name());
}

// 4. 卸载三步回收：批量 disposer → onStop → 清除模块缓存
for (const d of loaded.disposers) d();
if (typeof loaded.plugin.onStop === 'function') loaded.plugin.onStop();
this.evictRequireCache();      // 等价 Java URLClassLoader.close()`
  ],

  step24: [
`// 钩子本体：name 标识、phase 挂载点（before/after）、match 匹配的工具名（* = 全部）
// handler 返回 null = 放行；返回 "DENY:原因" = 拦截；否则用返回值替换内容
interface Hook {
  name: string;
  phase: string;
  match: string[];
  handler: (tool: string, payload: string) => string | null;
}

this.hooks.push({
  name: '风控-敏感外发', phase: 'before', match: ['send_sms'],
  handler: (_tool, payload) =>
    payload.includes('密码') || payload.includes('验证码')
      ? 'DENY:短信内容含「密码/验证码」等敏感信息，禁止外发'
      : null,
});

this.hooks.push({
  name: '脱敏', phase: 'after', match: ['*'],
  handler: (_tool, payload) => {
    const masked = payload.replace(/(1[3-9])\\d{9}/g, '$1*********');
    return masked === payload ? null : masked;
  },
});

// 管线：before 任何一条拦截即终止；after 按注册顺序依次加工（HookOutputMerger）`
  ],

  step25: [
`// 终端形态 = Agent.chat 的 while 循环（完整实现见 main.ts 的 repl()）
const rl = readline.createInterface({ input: stdin });
while (true) {
  const line = await rl.question('you> ');
  if (line === 'exit' || line === '/exit') break;
  if (line.trim() === '') continue;
  console.log('agent> ' + await agent.chat(line));   // 灵魂不变
}

// 手脚换成沙箱执行器：spawnSync 按空格拆分，不经 shell（无管道/重定向注入面）
// 四层防御复用 Step21：拒绝名单 → 路径边界 → 命令白名单 → 受限工作区
const parts = cmd.split(/\\s+/);
const p = spawnSync(parts[0], parts.slice(1), {
  cwd: path.resolve(WORKSPACE),   // 受限工作区 = 沙箱第④层
  encoding: 'utf-8',
  timeout: 5000,
});`,
`# 安装编译（TypeScript 仅 devDependencies，运行时零依赖）
cd ToyAgent-Typescript
npm install && npm run build

# 交互式 REPL（先选场景 step25，再循环输入）
node dist/main.js

# 非交互单命令（Claude Code 同款单次执行习惯）
node dist/main.js run step25 "运行：ls"`
  ],

  step26: [
`// 一次性调度：n 秒后触发（生产用持久化调度器/延时队列）
const timer = setTimeout(() => this.fire(id, content), delaySeconds * 1000);
timer.unref?.();   // 宿主退出即消亡，教学足够
this.jobs.set(id, { id, type: '一次性', content, period: delaySeconds,
  timer, fired: 0, done: false, cancelled: false });

// 循环调度：每 n 秒触发一次（首延迟 = 周期），触发次数持续累计
const loop = setInterval(() => this.fire(id, content), intervalSeconds * 1000);
loop.unref?.();

// 触发即落盘：append-only JSONL（事件溯源，呼应 Step22）
private fire(id: string, content: string): void {
  const time = formatDateTime(new Date(), 'HH:mm:ss');
  fs.appendFileSync(this.eventFile,
    '{"time":"' + time + '","job":"' + id + '","content":"' + content + '"}\\n', 'utf-8');
}`
  ]

};
