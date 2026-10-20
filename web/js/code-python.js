// 五语言代码切换：Python 片段（与 articles.js 各文章 <pre> 块按顺序对齐，null=该块语言无关不切换）
// 片段均摘自 ToyAgent-Python 工程（toyagent/agents/stepXX_*.py、toyagent/llm/、main.py），
// 为教学摘录而非整文件，类名 / 方法名 / 行为与工程真实实现一致。
window.ARTICLE_CODE = window.ARTICLE_CODE || {};
window.ARTICLE_CODE.python = {

  home: [
`class Agent:
    """所有场景的基类。子类必须实现 name() / chat(input)。"""

    def chat(self, input: str) -> str:
        # 智能体最小契约：输入一句话，输出一句话
        raise NotImplementedError`,
`# 零依赖运行（Python 3.9+，无需安装任何第三方包）
cd ToyAgent-Python
python3 main.py list                         # 查看 26 个场景清单
python3 main.py run step03 "北京天气怎么样"     # 单轮执行指定场景
python3 main.py repl                         # 交互模式：选场景 → 循环对话

# 接入真实大模型：复制配置模板并填入 API Key
cp config.properties.example config.properties`
  ],

  step01: [
`class ChatAgent(Agent):
    """最小 MVP 实现：每轮对话独立，输入 → 模型 → 输出。"""

    def __init__(self, model):
        self._model = model

    def chat(self, input: str) -> str:
        # 全部实现就这一行核心逻辑
        return self._model.chat([Message.user(input)])`,
`# 模型抽象：智能体的"大脑"
class ChatModel(abc.ABC):

    def chat(self, messages) -> str:
        """输入一组对话消息，输出模型回复文本。"""
        raise NotImplementedError`,
null
  ],

  step02: [
`# 三层结构系统提示词：角色 → 背景 → 任务与约束
SYSTEM_PROMPT = """# 角色
你是小傅哥的技术助理，专注讲清楚 AI Agent 的原理与工程实践。

# 背景
读者是正在学习智能体开发的 Java 工程师，熟悉后端技术，但对 AI 领域是新手。

# 任务与约束
1. 回答使用简体中文，先给结论再展开。
2. 若用户要求"用一句话"，必须严格只回答一句话。
3. 不确定的内容如实说明，禁止编造。
"""

def chat(self, input: str) -> str:
    return self._model.chat([
        Message.system(SYSTEM_PROMPT),  # 唯一新增：先塞一条 system 消息
        Message.user(input)])`
  ],

  step03: [
`MAX_STEPS = 5

# 用系统提示词约定 ReAct 的输出协议（JSON 形式，便于解析）
SYSTEM_PROMPT = """你是一个使用 ReAct 模式解决问题的智能体。
每一轮只输出一个 JSON 对象，格式二选一：
1. 继续行动：{"thought": "你的思考", "action": "工具名", "action_input": "参数"}
2. 给出答案：{"thought": "你的思考", "final": "最终答案"}
可用工具：
- get_weather(city): 查询城市天气
- calculator(expression): 计算四则运算，如 12*8
不要输出 JSON 以外的内容。
"""`,
`for step in range(1, MAX_STEPS + 1):
    raw = strip_code_fence(self._model.chat(messages).strip())  # 1. 模型思考
    try:
        parsed = json.loads(raw)
    except Exception:
        return raw                               # 没按协议输出 → 视为最终答案

    fin = jstr(parsed, "final")
    if fin is not None:
        return fin                               # 2. 有答案 → 结束

    action = jstr(parsed, "action") or ""        # 3. 有行动 → 执行工具
    observation = self._execute_tool(action, jstr(parsed, "action_input") or "")

    messages.append(Message.assistant(raw))      # 4. 结果喂回模型
    messages.append(Message.user("Observation: " + observation))

# 5. 保险丝
return "已达最大步数 " + str(MAX_STEPS) + "，循环终止（生产系统必须有这个保险丝）。"`
  ],

  step04: [
`# 第一步：在提示词中声明工具清单（最简 Tool Schema）
SYSTEM_PROMPT = """你是一个可以调用工具的智能体。可用工具：
- get_weather(city): 查询指定城市的实时天气
- calculator(expression): 计算四则运算，如 12*8

需要工具时，只输出 JSON：{"tool": "工具名", "arguments": {"参数名": "值"}}
不需要工具时，直接自然语言回答。
"""

# 第二步：模型返回结构化调用请求
first = self._model.chat(messages).strip()
decision = try_parse_json(first)
# → {"tool": "get_weather", "arguments": {"city": "广州"}}

# 第三步：执行工具，把结果回填
result = self._execute(tool, args)
messages.append(Message.user("工具 " + tool + " 的执行结果: " + result))
answer = self._model.chat(messages)   # 基于事实作答`
  ],

  step05: [
`def chat(self, input: str) -> str:
    self._history.append(Message.user(input))

    # 历史超限 → 压缩：把最旧的两轮合并为一条摘要
    if len(self._history) > MAX_ROUNDS * 2:
        self._compress()

    # 组装上下文：系统提示词 + 摘要 + 历史
    context = [Message.system(SYSTEM_PROMPT)]
    if self._summary:
        context.append(Message.system("【历史摘要】" + self._summary))
    context.extend(self._history)      # ← 记忆的全部秘密

    answer = self._model.chat(context)
    self._history.append(Message.assistant(answer))
    return answer`
  ],

  step06: [
`# 1. 意图识别：让模型输出分类 JSON
ROUTER_PROMPT = """你是意图识别路由器。分析用户输入，只输出 JSON：
{"intent": "weather|math|chat", "reason": "一句话理由"}
"""
raw = self._model.chat([Message.system(ROUTER_PROMPT), Message.user(input)]).strip()
decision = try_parse_json(raw)
intent = jstr(decision, "intent") if isinstance(decision, dict) else None
if intent is None:
    intent = "chat"

# 2. 决策中枢：按意图路由到不同处理器
if intent == "weather":
    city = self._extract_city(input)
    return "「" + city + "」现在" + MockWeather.query(city) + "。"   # 走工具
if intent == "math":
    return "计算结果：" + _calc(input) + "。"                         # 走计算器
return self._model.chat([           # 其余 → 走对话
    Message.system("你是一个友好的中文聊天助手。"),
    Message.user(input)])`
  ],

  step07: [
`# 1. 检索：知识片段与问题打分（bigram 重合率），取 Top2
ranked = sorted(
    ((text, score(text, input)) for text in KNOWLEDGE),
    key=lambda h: h[1], reverse=True)[:2]
hits = [(t, s) for t, s in ranked if s > 0]

# 2. 增强：命中片段注入提示词，带来源编号
ctx = "【参考资料】\\n"
for i, (t, _s) in enumerate(hits, 1):
    ctx += f"[{i}] {t}\\n"

# 3. 生成：约束模型"优先依据资料回答，不足则拒答"
answer = self._model.chat([
    Message.system(SYSTEM_PROMPT),
    Message.user(ctx),
    Message.user(input)])`
  ],

  step08: [
`# 基础 Wiki：启动时编译一次（生产中来自仓库里的 AGENTS.md / CLAUDE.md）
BASE_WIKI = {
    "架构约定": "ToyAgent 是零依赖教学项目：一个接口类就是一个智能体场景……",
    "代码规范": "Java 17 语法；核心方法必须有中文 Javadoc 说明教学意图……",
    "部署流程": "编译后启动，默认端口 8099，可用环境变量 TOY_AGENT_PORT 覆盖……",
}

# 编译指令：把对话中的知识沉淀进 Wiki 并持久化
if input.startswith(("编译：", "编译:", "记住：", "记住:")):
    return self._compile(input)     # 解析「编译：主题：内容」

# _compile 内部：条目入库并持久化
self._user_wiki[topic] = content    # 增量条目
self._persist_user_wiki()           # 写入 wiki/user-wiki.md，重启不丢`
  ],

  step09: [
`# MCP Server：工具的标准化宿主（真实实现为独立进程，JSON-RPC 通信）
class McpServer:

    def tools(self):
        raise NotImplementedError   # tools/list 工具发现

    def call(self, tool_name: str, args_json: str) -> str:
        raise NotImplementedError   # tools/call 工具调用

# 工具描述符：标准"名片"
ToolDescriptor = namedtuple("ToolDescriptor", ["name", "description", "parameters"])

# 智能体侧：动态发现，而不是硬编码
tools = self._client.list_tools()
result = self._client.call_tool("get_weather", '{"city": "杭州"}')`
  ],

  step10: [
`# 1. 技能选择：模型按语义匹配
raw = self._model.chat([Message.system(SELECT_PROMPT), Message.user(input)]).strip()
decision = try_parse_json(raw)
skill = jstr(decision, "skill") if isinstance(decision, dict) else None
if skill is None:
    skill = "free_chat"

# 2. 技能执行：确定性编排，而非现场发挥
if skill == "travel_plan":
    return self._travel_plan(input)      # L1: 组合两个工具
if skill == "weather_report":
    return self._weather_report(input)   # L1: 单工具
return self._free_chat(input)            # L0: 纯提示词

# L1 技能内部：工具结果是确定性的两步
weather = MockWeather.query(city)
now = datetime.now().strftime("%Y-%m-%d %H:%M")
answer = self._model.chat([
    Message.system("你是旅行顾问。基于天气与时间信息输出简短出行攻略。\\n"),
    Message.user(input + "\\n天气: " + weather + "\\n当前时间: " + now)])`
  ],

  step11: [
`class ToolDefinition(ABC):
    """工具定义协议 —— 一个类 = 一个工具。"""

    @abstractmethod
    def name(self) -> str: ...            # 模型调用标识

    @abstractmethod
    def description(self) -> str: ...     # 用途：模型选工具的唯一依据

    @abstractmethod
    def parameters(self) -> dict: ...     # 参数说明（生产中是 JSON Schema）

    @abstractmethod
    def execute(self, args: dict) -> str: ...   # 执行手脚

    def concurrency_safe(self) -> bool:   # 可否并行（预留）
        return False

# 一个类就是一个工具
class WeatherTool(ToolDefinition):

    def name(self):
        return "get_weather"

    def description(self):
        return "查询指定城市的实时天气"

    def execute(self, args: dict) -> str:
        ...`,
`# 注册：返回注销器（disposer）—— 持有它才能摘除工具，注册-回收对称
def register(self, tool: ToolDefinition):
    self._tools[tool.name()] = tool

    def disposer():
        self._tools.pop(tool.name(), None)

    return disposer

# 注销：模型下一次对话就"看不见"这个工具了
disposer = self._disposers.pop("get_time", None)
disposer()`
  ],

  step12: [
`# 每个角色 = 同一个 ChatModel + 不同的系统提示词
plan_raw = self._model.chat([
    Message.system(
        "你是规划者（Planner）。把用户任务拆解为三个角色的执行计划，只输出 JSON：\\n"
        '{"plan": [{"role": "研究员", "task": "..."}, '
        '{"role": "写手", "task": "..."}, {"role": "审查员", "task": "..."}]}\\n'),
    Message.user(input)]).strip()

# 上游产出作为下游输入 —— 消息在角色间流动
notes = self._model.chat([
    Message.system("你是研究员（Researcher），围绕任务输出 3 条以内研究要点，每条一句话。"),
    Message.user(input)])
draft = self._model.chat([
    Message.system(
        "你是写手（Writer）。基于研究笔记写一段 100 字以内的短文，主题如下。\\n研究笔记: "
        + notes + "\\n"),
    Message.user(input)])
# 审查员可以打回 → 触发修改循环`
  ],

  step13: [
`# 派遣：spawn 给全新上下文；fork 复制父会话再出发
fork = mode is not None and mode.lower() == "fork"

sys_prompt = "【子代理身份】" + specialty
if fork:
    sys_prompt += "\\n【继承的父会话上下文】\\n" + "\\n".join(self._parent_transcript)
else:
    sys_prompt += "\\n（spawn 模式：全新实例，看不到父会话）"

report = self._model.chat([
    Message.system(sys_prompt),
    Message.user(task)]).strip()   # 子代理独立一轮`
  ],

  step14: [
`# 名片：A2A 的发现单元（生产中托管在 /.well-known/agent.json）
AgentCard = namedtuple("AgentCard", ["name", "url", "version", "skills", "description"])

# 本地目录：模拟网络上已注册的外部代理（真实实现是 HTTP 拉取名片）
self._DIRECTORY = {
    "translator": AgentCard("translator", "http://a2a.local/translator", "1.0.0",
                            ["translate"], "中英互译代理"),
}

# 发送：按名片路由 + 状态机流转（发起方只认 task_id）
self._seq += 1
task_id = "task-{:03d}-{}".format(self._seq, uuid.uuid4().hex[:4])
#  submitted → working → completed
return json.dumps({"task_id": task_id, "status": "completed", "result": reply},
                  ensure_ascii=False)`
  ],

  step15: [
`def chat(self, input: str) -> str:
    # ① 输入守卫：长度限制 + 敏感词/注入检测
    if len(input) > MAX_INPUT_CHARS:
        return "输入过长，已拦截（防止上下文爆炸与注入攻击面扩大）。"
    for word in BLOCKED:
        if word in input.lower():
            return "该请求涉及受限内容，已被输入守卫拦截。"

    # ② Agent Loop（继承 Step03 骨架）
    for step in range(1, MAX_STEPS + 1):
        try:
            raw = self._model.chat(messages).strip()   # ④ 异常兜底
        except Exception as e:
            return "运行时异常已兜底，请稍后重试。"
        fin = jstr(parsed, "final")
        if fin is not None:
            # ③ 输出守卫：合规检查后才放行
            for word in BLOCKED:
                if word in fin.lower():
                    return "回答未通过输出合规检查，已按安全策略处理。"
            return fin
        # 工具异常不拖垮循环
        try:
            observation = self._execute(action, action_input)
        except Exception as e:
            observation = "工具执行异常: " + str(e)
        messages.append(Message.user("Observation: " + observation))
    # 保险丝熔断
    return "已达到最大执行步数（" + str(MAX_STEPS) + "），为控制成本与风险强制终止。"`
  ],

  step16: [
`# State：在节点之间流转的共享状态
state = {"input": input, "answer": "", "observation": ""}

# 固定入口：classify 节点
self._run_node("classify", state)

# 条件边：按分类结果路由
next_node = str(state.get("next", "faq"))

self._run_node(next_node, state)
self._run_node("polish", state)   # 汇合点：统一润色后结束

return str(state.get("answer"))`
  ],

  step17: [
`def _run_turn(self):
    """一个 turn：循环执行 step，直到出现结束原因。"""
    for step in range(1, MAX_STEPS_PER_TURN + 1):
        req = [Message.system(SYSTEM_PROMPT + self._registry.prompt_catalog())]
        req.extend(self._history)
        raw = self._model.chat(req).strip()   # 系统提示词 + 裁剪后的历史
        parsed = try_parse_json(raw)
        fin = jstr(parsed, "final") if isinstance(parsed, dict) else None
        if fin is not None:                   # 模型给出 final → 正常收场
            self._last_answer = fin
            self._history.append(Message.assistant(raw))
            return Completed("模型给出最终答案")
        # 有行动 → 查注册表执行工具，观察结果进历史 → 续步（mid-turn continuation）
        tool = self._registry.lookup(action)
        if tool is not None:
            observation = tool.execute({"text": action_input})
        else:
            observation = "未知工具: " + action
        self._history.append(Message.assistant(raw))
        self._history.append(Message.user("Observation: " + observation))
        self._trim_to_budget()
    return MaxSteps(MAX_STEPS_PER_TURN)       # 保险丝`,
`# token 估算：教学化近似 token ≈ 字符数 / 4
def _estimate_tokens(msgs) -> int:
    return sum(len(m.content) // 4 for m in msgs)

def _trim_to_budget(self):
    if _estimate_tokens(self._history) <= BUDGET_TOKENS or len(self._history) <= KEEP_MIN:
        return
    # 超预算：从最老的消息开始丢，保底留最近 4 条
    while _estimate_tokens(self._history) > BUDGET_TOKENS and len(self._history) > KEEP_MIN:
        self._history.pop(0)`
  ],

  step18: [
null,
`def chat(self, input: str) -> str:
    # ① 输入守卫：长度与敏感意图，不通过直接返回，模型预算为零
    if len(input) > MAX_INPUT_LEN:
        return "输入过长，请精简后重试（守卫规则：长度 ≤ " + str(MAX_INPUT_LEN) + " 字符）。"
    if SENSITIVE.search(input):
        return "该请求涉及不安全操作，已被输入守卫拦截。"

    # ② 记忆读取 + 画像提取（我叫 XX → 存档案）
    self._extract_profile(input)

    # ③ 组装上下文：系统提示词（含画像）+ 历史 + 本轮输入
    messages = [Message.system(SYSTEM_PROMPT + self._user_portrait())]
    messages.extend(self._history)
    messages.append(Message.user(input))

    # ④⑤ ReAct 循环 + 保险丝（Step03 的骨架原样复用）
    answer = self._run_re_act(messages)

    # ⑥ 输出校验 + 画像兜底（「我叫什么」不问模型，查档案）
    answer = self._output_guard(answer, input)

    # ⑦ 回写记忆，超阈值触发上下文压缩
    self._history.append(Message.user(input))
    self._history.append(Message.assistant(answer))
    if len(self._history) > COMPRESS_THRESHOLD:
        self._compress_history()
    return answer`,
`def _output_guard(self, answer, input: str) -> str:
    """输出守卫：拦截答案中的敏感内容，并在能回答画像问题时兜底。"""
    if answer is not None and SENSITIVE.search(answer):
        return "该内容不宜展示，已由输出守卫处理。"
    # 记忆兜底：模型看不到被压缩掉的历史时，画像仍能回答「我叫什么」
    if "我叫什么" in input or "我是谁" in input:
        name = self._profile.get("名字")
        if name is not None:
            return "你叫 " + name + "（来自画像记忆，不依赖模型）。"
    return answer`,
null
  ],

  step19: [
`# 第一次请求：模型发现关键信息缺失 → 发起 ask_user_question
question = jstr(parsed, "ask") if isinstance(parsed, dict) else None
if question is not None:
    self._pending_question = question   # 问题持久化在会话状态（挂起）
    return question + "\\n\\n（直接回复即可，我会带着你的答复继续任务。）"

# 第二次请求：用户的回复不进模型，而是作为人工答复回填
if self._pending_question is not None:
    q = self._pending_question
    self._pending_question = None
    self._history.append(Message.user("人工答复：" + input))
    return self._complete(q)            # turn 恢复，带着完整信息继续`
  ],

  step20: [
`# 权限评估：风险分级 + 模式 + 会话免审记忆
dangerous = self._is_dangerous(tool)
need_ask = (self._mode == Mode.ALWAYS_ASK
            or (self._mode == Mode.AUTO_MANUAL_SAFE and dangerous
                and tool not in self._trusted_tools))

if need_ask:
    self._pending_tool = tool           # 审批挂起：先答复人，不执行
    self._pending_args = args
    return ("⚠️ 即将执行高危操作：" + tool + "\\n参数：" + args + "\\n\\n"
            + "请回复：「批准」执行 ｜「拒绝」取消 ｜「批准并记住」本会话内该工具免审")
# 人回复后进入 _resolve_approval：批准→执行；拒绝→终止；批准并记住→加入免审名单`
  ],

  step21: [
`# 第 3 层与第 4 层的分工：看原文 vs 看真实落点
escaped = find_escaped_abs_path(cmd, self._workspace)
if escaped:   # 命令里的绝对路径逐个校验
    return self._deny(3, "绝对路径 " + escaped + " 越出工作区 " + self._workspace.name)

if ".." in cmd:   # 第 4 层：规范化后复查，../ 逃逸无所遁形
    return self._deny(4, "检测到 ../ 相对跳转 —— 规范化后路径将越出工作区，疑似路径逃逸")

# find_escaped_abs_path 的内核：规范化 + 组件级前缀判断（等价 normalize().startsWith）
norm = Path(os.path.normpath(p))
if not _starts_with(norm, workspace):   # child.relative_to(parent) 失败即越界
    return p`
  ],

  step22: [
null,
`def _project(self, log: list) -> list:
    """投影：事件流 → 会话上下文（只挑 USER / AGENT 两类，其余事件服务审计）。"""
    history = []
    for e in log:
        etype = str(e.get("type"))
        content = str((e.get("payload") or {}).get("content"))
        if etype == EV_USER:
            history.append(Message.user(content))
        elif etype == EV_AGENT:
            history.append(Message.assistant(content))
        # SESSION_STARTED / TURN_COMPLETED 不进上下文，但留在日志里
    return history`
  ],

  step23: [
`# 1. 契约：插件作者唯一要实现的东西
class TimePlugin:

    def id(self):
        return "time-plugin"

    def version(self):
        return "1.0.0"

    def onStart(self):
        self.started = True

    def onStop(self):
        self.started = False

    def tools(self):
        return [UptimeTool(time.time()), CoinTool()]   # 插件带来的工具

# 2. 隔离加载：Java 用独立 URLClassLoader；Python 用 importlib 独立模块名
spec = importlib.util.spec_from_file_location(
    module_name, PLUGIN_DIR / "time_plugin.py")
module = importlib.util.module_from_spec(spec)
sys.modules[module_name] = module
spec.loader.exec_module(module)
plugin = module.TimePlugin()
plugin.onStart()

# 3. 工具桥接：注册 + disposer 按 pluginId 归档（卸载即批量注销）
for tool in plugin.tools():
    disposers.append(self._registry.register(tool))

# 4. 卸载三步回收：批量 disposer → onStop → sys.modules 摘除`
  ],

  step24: [
`# 钩子本体：name + phase 挂载点 + match 匹配规则 + handler 处理器
# handler 返回 None=放行；"DENY:原因"=拦截；否则替换内容
Hook = namedtuple("Hook", ["name", "phase", "match", "handler"])

self._hooks.append(Hook("风控-敏感外发", "before", ["send_sms"], self._risk_hook))
self._hooks.append(Hook("脱敏", "after", ["*"], self._mask_hook))

@staticmethod
def _risk_hook(tool, payload):
    if "密码" in payload or "验证码" in payload:
        return "DENY:短信内容含「密码/验证码」等敏感信息，禁止外发"
    return None

@staticmethod
def _mask_hook(tool, payload):
    masked = _PHONE_PTN.sub(r"\\1*********", payload)   # 手机号打码
    return None if masked == payload else masked

# 管线：before 任何一条拦截即终止；after 按注册顺序依次加工（HookOutputMerger）`
  ],

  step25: [
`# 终端形态 = Agent.chat 的 while 循环（完整实现见 main.py 的 repl()）
while True:
    try:
        line = input("你> ").strip()
    except EOFError:
        break
    if not line:
        continue
    if line == "/exit":
        break
    print("Agent> " + agent.chat(line))   # 灵魂不变

# 手脚换成沙箱执行器：命令按空格拆分（不经 shell，无管道/重定向注入面）
# 四层防御复用 Step21：拒绝名单 → 路径边界 → 命令白名单 → 受限工作区
proc = subprocess.run(
    cmd.split(),
    cwd=str(WORKSPACE),   # 受限工作区 = 沙箱第④层
    capture_output=True, text=True, timeout=5)`,
`# 交互式 REPL：先选场景（如 step25），再循环对话
cd ToyAgent-Python
python3 main.py repl

# 非交互单命令（Claude Code 同款 -p 习惯）
python3 main.py run step25 "看看工作区里有什么"`
  ],

  step26: [
`# 一次性：n 秒后触发（threading.Timer 守护线程，对应 Java schedule + delay）
timer = threading.Timer(delay_seconds, lambda: self._fire(job_id, content))
timer.daemon = True
timer.start()

# 循环：每 n 秒执行一次（首延迟 = 周期，对应 scheduleAtFixedRate）
class _RecurringHandle:

    def _run(self, interval, fire_fn):
        while not self._stop.wait(interval):   # Event.wait 兼当睡眠与停止开关
            fire_fn()

# 触发即落盘：append-only JSONL（事件溯源）
with open(self._event_file, "a", encoding="utf-8") as f:
    f.write(json.dumps(
        {"time": time_str, "job": job_id, "content": content},
        ensure_ascii=False, separators=(",", ":")) + "\\n")`
  ]

};
