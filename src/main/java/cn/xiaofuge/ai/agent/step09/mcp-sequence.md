# Step09 · MCP 对话时序图

> 对应场景：`step09`（教学版 MCP）与 `step09-real`（真实对接百度 AI 搜索 MCP Server）。
> 两者 Agent 流程完全一致，区别只在传输层：教学版是进程内接口直调，真实对接是 JSON-RPC 2.0 over HTTP+SSE。
> 下图按真实对接绘制，教学版只需把「SSE 长连接」理解为方法调用即可。

```mermaid
sequenceDiagram
    autonumber
    actor U as 👤 用户
    participant A as 🤖 McpAgent
    participant L as 🧠 大模型
    participant M as 🔌 MCP Server（AIsearch）

    Note over A,M: ① 建连与握手（每次对话独立会话）
    U->>A: 提问（如：Java 虚拟线程是什么？）
    A->>M: GET /mcp/sse 建立 SSE 长连接
    M-->>A: event: endpoint（消息回传地址，含 session_id）
    A->>M: POST initialize（JSON-RPC id=1）
    M-->>A: SSE 推回 result（serverInfo: AIsearch v1.4.1）
    A->>M: POST notifications/initialized（通知，无 id）

    Note over A,M: ② 工具发现 tools/list
    A->>M: POST tools/list（id=2）
    M-->>A: SSE 推回工具清单（AIsearch，必填 query）

    Note over A,L: ③ 模型决策
    A->>L: 工具摘要 + 用户问题
    L-->>A: {"method":"tools/call","params":{"name":"AIsearch","arguments":{"query":"..."}}}

    Note over A,M: ④ 工具调用 tools/call（响应从 SSE 异步推回，按 id 配对）
    A->>M: POST tools/call（id=3）
    M-->>A: SSE 推回 result.content[].text（搜索结果，约 20s）

    Note over A,L: ⑤ 基于事实生成回答
    A->>L: 用户问题 + 工具结果
    L-->>A: 最终回答（引用来源）

    A-->>U: 回答
```

## 关键点

1. **两条通道**：请求走 `POST /mcp/messages?session_id=...`，响应从 SSE 长连接异步推回，靠 JSON-RPC `id` 配对（`ConcurrentHashMap<id, 响应>` + `CountDownLatch` 等待）。
2. **工具发现是动态的**：工具清单来自 `tools/list` 的真实返回，不硬编码在智能体里。
3. **Agent 骨架零改动**：把教学版的 `McpClient` 换成 `SseMcpClient`，决策、调用、总结的代码一行不变——这正是 MCP 解耦的价值。
4. **相关源码**：`src/main/java/cn/xiaofuge/ai/agent/step09/McpRealAgent.java`
