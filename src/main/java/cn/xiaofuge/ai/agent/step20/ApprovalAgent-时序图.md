# Step20 · 审批门禁 —— 时序图

> 对应实现：`ApprovalAgent.java`（原生 Function Calling + 宿主审批门禁）
> 核心结论先记住：**审批不是工具，是宿主插在「工具调用 → 执行」之间的确定性拦截**；
> 挂起 = `chat()` 提前 return + 一个非 null 的 `pendingTool` 字段，**等待发生在人类一侧，进程里没有任何东西在等**。

## 图 1 · 主时序：高危工具 → 门禁挂起 → 人工决策 → 回填续跑

场景：用户请求发周报邮件（高危操作 send_email），走「挂起 → 批准并记住 → 续跑完成」全流程。

```mermaid
sequenceDiagram
    autonumber
    actor U as 用户（Terminal）
    participant A as ApprovalAgent.Impl<br/>（宿主 · 门禁在这里）
    participant M as ChatModel（LLM）

    U->>A: chat("给团队发一封周报邮件")
    Note over A: 分支二：正常对话<br/>history.add(user 消息)
    A->>A: runTurn() 进入单循环<br/>（MAX_STEPS=8 保险丝）

    rect rgb(240, 244, 255)
        Note over A,M: —— runTurn 第 1 圈 ——
        A->>M: chat(messages, TOOLS)<br/>TOOLS 含 send_email Schema（模型视角：普通工具）
        M-->>A: tool_calls: [send_email({to, content})]
        Note over A: 关键不变量：assistant(tool_calls)<br/>原样写入 history
        A->>A: 权限矩阵评估（宿主确定性计算，模型不可见）<br/>isDangerous(send_email)=高危<br/>trustedTools 未命中 → needAsk=true
        Note over A: 门禁拦截：工具不执行！<br/>pendingTool/pendingArgs/pendingCallId 记录挂起位
        A-->>U: ⚠️ 审批请求文本（chat 提前 return）<br/>「批准 ｜ 拒绝 ｜ 批准并记住」
    end

    Note over U: 等待发生在人类一侧<br/>进程空闲，没有线程阻塞

    U->>A: chat("批准并记住")
    Note over A: 分支一：pendingTool != null<br/>本条输入是「人工决策」，不进模型
    A->>A: trustedTools.add("send_email")<br/>（会话级免审 ≈ Claude Code 持久权限）
    A->>A: history.add(Message.tool(call_id,<br/>{"approved":true,"result":"已发送（模拟）…"}))
    Note over A: 审批决策以 role=tool 按 tool_call_id 回填<br/>—— 与普通工具结果同一协议地位

    rect rgb(240, 255, 244)
        Note over A,M: —— 回到同一个 runTurn 循环（第 1 圈）——
        A->>M: chat(messages, TOOLS)
        M-->>A: 最终回答（无 tool_calls）
        Note over A: 收敛出口：assistant(answer) 入 history
        A-->>U: 「周报邮件已发送成功 ✅ …」
    end
```

## 图 2 · 三种人工决策的分岔（分支一内部）

```mermaid
sequenceDiagram
    autonumber
    actor U as 用户（审批者）
    participant A as ApprovalAgent.Impl
    participant M as ChatModel（LLM）

    Note over A: 挂起中：pendingTool="send_email"<br/>pendingCallId="call_em1"

    U->>A: chat(<决策>)
    A->>A: pendingTool/pendingArgs/pendingCallId 清空

    alt 批准（仅本次放行）
        A->>A: execute("send_email", args)（模拟）
        A->>A: Message.tool(call_id, {"approved":true, "result":…})
        A->>M: runTurn() 续跑请求
        M-->>A: 最终回答
        A-->>U: 任务完成（下次调 send_email 仍会审批）
    else 批准并记住（持久权限）
        A->>A: trustedTools.add("send_email")
        A->>A: execute → Message.tool(call_id, {"approved":true,…})
        A->>M: runTurn() 续跑请求
        M-->>A: 最终回答
        A-->>U: 任务完成（本会话后续 send_email 自动放行）
    else 拒绝（denied 回灌，模型自行收场）
        A->>A: Message.tool(call_id, {"denied":true,<br/>"reason":"用户拒绝了该高危操作"})
        A->>M: runTurn() 续跑请求
        M-->>A: 收场回答（如「操作未执行，可先起草内容供确认」）
        A-->>U: 如实收场，不戛然而止<br/>（Anthropic：审批决策应反馈给模型学习边界）
    else 无法识别（fail closed）
        A->>A: 重新记录 pendingTool/Args/CallId
        A-->>U: 「没看懂你的决定，请回复：批准 ｜ 拒绝 ｜ 批准并记住」<br/>（保持挂起，绝不误执行）
    end
```

## 图 3 · 对照：安全工具直通 & 免审命中（不挂起的两条路）

```mermaid
sequenceDiagram
    autonumber
    actor U as 用户
    participant A as ApprovalAgent.Impl
    participant M as ChatModel（LLM）

    alt 安全工具（如 get_weather）
        U->>A: chat("查一下北京的天气")
        A->>M: chat(messages, TOOLS)
        M-->>A: tool_calls: [get_weather({city:"北京"})]
        A->>A: 权限评估：安全 · 判定放行
        A->>A: 圈内直接 execute → Message.tool 回填
        A->>M: runTurn() 下一圈
        M-->>A: 最终回答
        A-->>U: 「北京：晴，26℃」（全程不挂起）
    else 高危工具但命中会话免审
        U->>A: chat("再发一封致歉邮件给客户")
        A->>M: chat(messages, TOOLS)
        M-->>A: tool_calls: [send_email({…})]
        A->>A: 权限评估：高危 · 命中 trustedTools<br/>→ 判定放行（不再打扰人）
        A->>A: 圈内直接 execute → Message.tool 回填
        A->>M: runTurn() 下一圈
        M-->>A: 最终回答
        A-->>U: 「致歉邮件已发送 ✅」
    end
```

## 读图要点（讲解用）

| 要点 | 图上位置 | 说明 |
|---|---|---|
| 单循环 | 两处 `runTurn` 矩形 | 挂起前后是**同一个循环**：决策回填后从第 1 圈重新开始，与 ToyHarness `runLoop` / step19 `runTurn` 同构 |
| 门禁在宿主侧 | `权限矩阵评估` 自环 | 模型只看到普通工具清单，判定 `needAsk` 是确定性代码（isDangerous + Mode + trustedTools），模型不可见、不可绕过 |
| 挂起的本质 | 图 1 第 7 步 | `chat()` 提前 return + `pendingTool != null`；下一条消息改道成「人工决策」，**没有任何线程在等** |
| role=tool 回填 | `Message.tool(call_id, …)` | 批准结果 / denied 都是工具结果，与 get_weather 的结果同一协议地位 |
| 拒绝也是反馈 | 图 2 alt 拒绝 | denied 回灌进推理轨迹，模型可调整方案——审批决策反馈给模型学习边界 |
| fail closed | 图 2 alt 无法识别 | 决策不明 → 保持挂起，宁可不动，不可乱执行（生产版超时同样按 DENY） |

与 step19 的对照：两图形状几乎相同（单循环 + 挂起退出 + role=tool 回填），唯一差别是**谁决定挂起**——step19 是模型主动调 `ask_user_question`（问人是能力，工具化）；step20 是宿主规则拦下高危调用（拦人是权力，门禁化）。

## 参考来源（Anthropic 官网）

| 文章 | 链接 | 对应本图的观点 |
|---|---|---|
| Building Effective Agents（2024-12，Erik Schluntz & Barry Zhang） | anthropic.com/engineering/building-effective-agents | 「Agents can then pause for human feedback at checkpoints or when encountering blockers.」——checkpoint 即挂起点；「stopping conditions (such as a maximum number of iterations)」即 MAX_STEPS 保险丝 |
| Our framework for developing safe and trustworthy agents | anthropic.com/news/our-framework-for-developing-safe-and-trustworthy-agents | 人类保持控制权；Claude Code 默认只读、修改类动作须审批、可授予持久权限（对应「批准并记住」） |
| Trustworthy agents in practice | anthropic.com/research/trustworthy-agents | 每个工具可配置 always allow / needs approval / block（对应权限矩阵 + 免审名单） |
| How we contain Claude across products | anthropic.com/engineering/how-we-contain-claude | 逐次审批是「概率性监督」（93% 审批疲劳数据），容器化才是确定性边界——门禁须配合沙箱纵深防御 |

注：「审批决策回灌让模型学习边界」的表述源自社区对 Claude Code 行为的归纳（拒绝时以 tool_result 告知模型并令其停手），官网原文措辞见上表第 1、4 篇。
