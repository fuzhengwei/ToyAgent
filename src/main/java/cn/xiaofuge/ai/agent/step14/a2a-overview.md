# Step14 · A2A 协作：架构与时序

> 线格式按真实 A2A 规范（Google 发起、现属 Linux Foundation）绘制：JSON-RPC 2.0 over HTTP、
> Agent Card 发现、Task 生命周期、input-required 多轮。ToyAgent 把「网络请求」换成方法调用，
> 状态机与信封结构完全一致。

## 一、架构图

Step13 的子代理是**自家分身**（同进程、共享宿主模型）；Step14 的 A2A 是**外部同行**：
对方是跨进程的完整 Agent，有自己的模型与工具，发起方只认 `task_id` 与标准化信封。

```mermaid
flowchart TB
    U["👤 用户"] -->|"自然语言"| A

    subgraph LOCAL["发起方：ToyAgent 主代理（本进程）"]
        A["🤖 A2AAgent.Impl<br/>标准 Function Calling 循环"]
        L["🧠 宿主模型<br/>决策 + 汇总"]
        T["🧰 tools Schema<br/>a2a_discover · a2a_send · a2a_get"]
        S["🗂 tasks 表<br/>task_id → RemoteTask"]
    end

    subgraph REMOTE["外部：远端 A2A Server（跨进程、跨主人）"]
        direction LR
        TR["🌐 translator 代理<br/>自己的模型"]
        W["🌤 weather 代理<br/>自己的模型与数据"]
        C["名片 agent-card.json<br/>capabilities + skills{id, tags}"]
    end

    T -.->|"声明能力"| A
    A -->|"决策 / 汇总"| L
    A -->|"登记 / 轮询状态"| S

    A ==>|"JSON-RPC 2.0 over HTTP<br/>message/send · tasks/get"| W
    A ==>|"JSON-RPC 2.0 over HTTP<br/>message/send · tasks/get"| TR
    C -.->|"GET /.well-known/agent-card.json<br/>名片发现"| A
```

关键认知：**MCP 给模型接工具，A2A 让代理委派代理**——两者互补。A2A 的对方不是函数，
而是「有自己的大脑、按 Task 生命周期干活」的完整 Agent。

## 二、时序图 ①：名片发现 + 标准任务生命周期（translator）

```mermaid
sequenceDiagram
    autonumber
    actor U as 👤 用户
    participant A as 🤖 主代理 A2AAgent
    participant L as 🧠 宿主模型
    participant R as 🌐 translator 代理（远端 A2A Server）

    Note over A,R: ① 名片发现
    U->>A: 让翻译代理把「你好，智能体」翻译成英文
    A->>R: GET /.well-known/agent-card.json
    R-->>A: Agent Card（name/url/capabilities/skills: translate）

    Note over A,L: ② Function Calling：模型决定发起协作
    A->>L: system + 对话 + tools Schema
    L-->>A: tool_calls: a2a_send {agent, task}
    A->>R: message/send（JSON-RPC）：Message{role, parts:[{type:text}]}
    R-->>A: Task{id, contextId, status: submitted → working}

    Note over A,R: ③ 长任务：tasks/get 轮询（A2A 与 MCP 同步调用的分水岭）
    A->>L: tool 结果回填（working）
    L-->>A: tool_calls: a2a_get {taskId}
    A->>R: tasks/get（第 1 拍）
    R-->>A: Task{status: working}
    A->>R: tasks/get（第 2 拍）
    R->>R: 远端调用自己的模型产出结果
    R-->>A: Task{status: completed, artifacts:[{parts:[{text: 翻译结果}]}]}

    Note over A,L: ④ 汇总作答
    A->>L: completed 回执回填
    L-->>A: 最终自然语言回答
    A-->>U: 翻译结果 + 生命周期说明
```

## 三、时序图 ②：input-required 多轮协作（weather · 人在回路）

对方信息不够时不是失败，而是停进 `input-required` 提问；客户端带**同一 task_id + contextId**
续发消息，任务接着跑。

```mermaid
sequenceDiagram
    autonumber
    actor U as 👤 用户
    participant A as 🤖 主代理
    participant W as 🌤 weather 代理（远端）

    U->>A: 问问天气代理明天适合户外运动吗（未带城市）
    A->>W: message/send：明天适合户外运动吗？
    W-->>A: Task{status: working}
    A->>W: tasks/get
    W-->>A: Task{status: input-required, message: 请问要查询哪个城市？}

    Note over U,A: 主代理转述问题，等用户补充（对话里已有答案则直接续写）
    U->>A: 上海
    A->>W: message/send（同 taskId + contextId）：用户补充「上海」
    W-->>A: Task{status: input-required → working}
    A->>W: tasks/get ×2（轮询两拍）
    W->>W: 远端用自己的模型处理完整任务
    W-->>A: Task{status: completed, artifacts:[明日晴 22-28℃，适合户外]}
    A-->>U: 上海明天适合户外运动！
```

## 四、关键点

1. **派遣即工具**：`a2a_discover` / `a2a_send` / `a2a_get` 以 tools Schema 声明（与 Step04/13
   同一协议），模型返回结构化 `tool_calls`，不再从自由文本猜 JSON。
2. **异步轮询是 A2A 的灵魂**：发起方拿到 `task_id` 即可离开，用 `tasks/get` 轮询或 SSE 流式
   订阅等结果——真实生产里还有 webhook push notification。这正是它与 MCP 同步 `tools/call`
   的本质区别。
3. **信封标准化**：请求是 `Message{role, parts[]}`，回执是 `Task{id, contextId, status, artifacts[]}`。
   发起方只认 task_id，不关心对方用什么框架、什么模型。
4. **input-required 支撑人在回路**：对方缺信息时停下提问，同一任务多轮续写——不是新任务，
   context 不断。
5. **远端有自己的大脑**：`runRemotely()` 让远端代理真调一次自己的模型，而不是宿主代写答案。
6. **防泄密与 Step13 同源**：上下文隔离靠协议边界（只传 task 描述），对方永远看不到发起方的
   会话历史。

### 三种协作形态对比

| 维度 | Step13 子代理 | Step14 A2A | MCP 工具 |
|------|--------------|------------|----------|
| 关系 | 自家分身 | 外部同行（跨主人） | 工具提供方 |
| 位置 | 同进程 | 跨进程/跨网络 | 进程内或独立 Server |
| 发现 | 代码里 CHILDREN 常量 | Agent Card（well-known URI） | tools/list |
| 调用 | dispatch_subagent 工具 | message/send + tasks/get | tools/call |
| 节奏 | 同步（进程内） | 长任务、可轮询/流式 | 同步（或流式） |
| 上下文 | fork 可继承父会话 | 对方独立，只认 task_id | 无状态单次调用 |

## 六、相关源码

- 场景实现：`src/main/java/cn/xiaofuge/ai/agent/step14/A2AAgent.java`
- Mock 剧本：`src/main/java/cn/xiaofuge/ai/llm/MockChatModel.java`（`mockA2aToolCall` + 远端子剧本）
- 对照场景：`step13/SubagentAgent.java`（进程内子代理）、`step09/McpAgent.java`（MCP 工具协议）
