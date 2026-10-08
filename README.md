# ToyAgent

> 用最简单的方式，讲清楚智能体实现 —— **一个接口类，就是一个智能体的最小 MVP**。

Java 17 · 零依赖 · 21 个渐进式场景 · 配套可视化测试页面。配套教程：[AI Agent 通识教程（ai-agent-guide）](../README.md)。

## 一句话读懂

智能体的灵魂只有一个方法：

```java
public interface Agent {
    String chat(String input);   // 输入一句话，输出一句话
}
```

21 个场景 = 往这个最小骨架里，每次只加一种能力；第 13 个全流程综合，第 14 个知识编译持久复用，第 15-21 个参考 deepseek-harness-java 走向运行时基座、人机协同/安全溯源与插件生态。

## 21 个场景

| # | 场景 | 接口类 | 新增能力 | 对应教程章节 |
|:---:|------|--------|----------|:---:|
| 01 | 对话智能体 | `ChatAgent` | 最小 MVP：接通模型 | ch01-03 |
| 02 | 提示词工程 | `PromptAgent` | 系统提示词三层结构 | ch04 |
| 03 | ReAct 循环 | `ReActAgent` | 思考-行动-观察 | ch04b/05 |
| 04 | 工具调用 | `ToolCallAgent` | Function Calling | ch03/10 |
| 05 | 记忆系统 | `MemoryAgent` | 多轮记忆 + 上下文压缩 | ch06 |
| 06 | 意图路由 | `RouterAgent` | 意图识别 + 决策中枢 | ch06b/07 |
| 07 | MCP 协议 | `McpAgent` | 工具标准化接口 | ch07b/11 |
| 08 | 技能编排 | `SkillAgent` | Skills L0/L1/L2 | ch08/12 |
| 09 | RAG | `RagAgent` | 检索增强生成 | ch16/20 |
| 10 | 多智能体 | `MultiAgent` | 规划-执行-审查协作 | ch10/14 |
| 11 | Loop 运行时 | `LoopAgent` | 守卫 + 保险丝 + 兜底 | ch08b/18/21 |
| 12 | 工作流状态机 | `WorkflowAgent` | LangGraph 思想编排 | ch11b/15 |
| 13 | 全流程智能体 | `FullAgent` | 守卫+记忆+ReAct+工具+保险丝 综合链路 | 综合 Step01-12 |
| 14 | LLM-Wiki 知识编译 | `WikiAgent` | 知识编译一次、持久维护、增量沉淀 | ch13/26 |
| 15 | 工具注册表 | `RegistryAgent` | ToolDefinition 协议：注册/发现/热注销（disposer） | dsh-java |
| 16 | ReAct 运行时 | `RuntimeAgent` | turn/step 两级循环 + 上下文裁剪 + TurnEndReason | dsh-java |
| 17 | 人工介入 | `AskAgent` | ask_user_question：提问挂起 → 人工答复续跑 | dsh-java |
| 18 | 审批门禁 | `ApprovalAgent` | 风险分级 + 审批挂起 + 会话免审 + 超时默认 DENY | dsh-java |
| 19 | 沙箱纵深防御 | `SandboxAgent` | 策略/黑名单/边界/规范化 四层拦截 | dsh-java |
| 20 | 事件溯源 | `EventSourcedAgent` | JSONL 事件流 append-only + 回放投影重建状态 | dsh-java |
| 21 | 插件机制 | `PluginAgent` | AgentPlugin 契约 + 隔离 ClassLoader 加载 + 工具桥接批量注册/注销 | dsh-java |

每个场景一个文件，位于 `src/main/java/cn/xiaofuge/ai/agent/stepXX/`，接口注释即教程，嵌套实现类即全部逻辑。

## 快速开始

### 方式一：零依赖运行（推荐，无需 Maven）

```bash
cd ToyAgent

# 编译（JDK 17+）
javac -encoding UTF-8 -d target/classes $(find src/main/java -name "*.java")

# 启动（默认 8099 端口，TOY_AGENT_PORT 环境变量可改）
java -cp target/classes cn.xiaofuge.ai.Application
```

打开测试页面：<http://localhost:8099>

### 方式二：Maven

```bash
mvn package
java -jar target/ToyAgent.jar
```

### 接入真实大模型（可选）

复制配置模板并填入任意 OpenAI 兼容 API（DeepSeek / 通义 / 智谱 / 火山方舟…）：

```properties
# config.properties
base-url=https://api.deepseek.com/v1
api-key=sk-xxx
model=deepseek-chat
```

> 未配置 Key 时自动使用内置 **Mock 模型**：不联网、不花钱，所有场景的执行轨迹（ReAct 循环、工具调用、路由决策、RAG 检索…）照常完整展示。配上真实 Key，同一套骨架立刻换上真大脑。

## 项目结构

```
ToyAgent/
├── pom.xml                              # cn.xiaofuge.ai:ToyAgent:1.0（零依赖）
├── config.properties.example            # 模型配置模板
├── web/                                 # 测试页面（原生 HTML/CSS/JS）
│   ├── index.html
│   ├── css/style.css                    # 设计令牌 + 三栏布局 + 深色代码块
│   └── js/
│       ├── data.js                      # 场景元数据 + 示例问题
│       ├── articles.js                  # 12 篇右侧原理文章
│       └── app.js                       # 导航/对话/轨迹时间线/代码高亮
└── src/main/java/cn/xiaofuge/ai/
    ├── Application.java                 # HTTP 服务：静态页面 + API 路由
    ├── llm/                             # 模型层
    │   ├── ChatModel.java               # 模型抽象（大脑）
    │   ├── OpenAiChatModel.java         # OpenAI 兼容协议实现
    │   ├── MockChatModel.java           # 教学 Mock 模型
    │   ├── Models.java                  # 配置工厂
    │   ├── Message.java                 # 对话消息 record
    │   └── Json.java                    # 零依赖 JSON 编解码
    └── agent/
        ├── Agent.java                   # 智能体契约：chat(input)
        └── step01..step21/              # 21 个场景，一场景一接口类
```

## HTTP API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/` | 测试页面 |
| GET  | `/api/config` | 当前模型模式（mock / real） |
| POST | `/api/{stepId}/chat` | 对话，请求体 `{"message": "..."}` |
| POST | `/api/{stepId}/reset` | 重置场景内部状态（记忆等） |

响应示例：

```json
{
  "id": "step03",
  "name": "Step03 · ReAct 智能体（思考-行动-观察循环）",
  "answer": "北京今天晴，26℃…",
  "trace": [
    {"type": "thought", "label": "Thought 1", "detail": "需要调用天气工具"},
    {"type": "action", "label": "Action 1", "detail": "get_weather(北京)"},
    {"type": "observation", "label": "Observation 1", "detail": "晴，26℃"},
    {"type": "final", "label": "Final Answer", "detail": "..."}
  ],
  "ms": 86
}
```

## 设计理念

1. **一个接口类 = 一个智能体 MVP** —— 拒绝框架魔法，所有逻辑看得见、摸得着；
2. **零依赖** —— 只用 JDK 自带能力（`com.sun.net.httpserver` + `java.net.http`），手写 JSON 工具，clone 即跑；
3. **由浅入深** —— 21 个场景严格递进，每个场景只新增一种能力（step13 全流程综合，step14 知识工程，step15-20 运行时基座 / 人机协同 / 安全溯源，step21 插件机制），接口注释就是教程；
4. **页面即测试** —— 原生 HTML/CSS/JS 三栏布局：左侧场景导航、中间对话 + 执行轨迹时间线、右侧原理文章（含教程章节对照）；
5. **Mock/真实双模式** —— 骨架与大脑分离，学习时用 Mock 观察轨迹，进阶时配 Key 换真模型。

## License

Apache-2.0 © 小傅哥
