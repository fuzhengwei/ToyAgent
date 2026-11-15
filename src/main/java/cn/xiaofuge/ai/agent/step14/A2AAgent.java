package cn.xiaofuge.ai.agent.step14;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.ChatResponse;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;
import cn.xiaofuge.ai.llm.ToolCall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Step14 · A2A 协作 —— 代理之间的「标准化网络协议」
 * （对应 Google A2A / Linux Foundation 规范：Agent Card · JSON-RPC · Task 生命周期）。
 * <p>
 * Step12/13 的多代理都活在<strong>同一个进程</strong>里，主代理直接 new 出子代理。
 * A2A（Agent-to-Agent）解决的是<strong>跨进程、跨主人</strong>的协作：
 * <ul>
 *   <li><b>名片发现</b>：每个代理在 /.well-known/agent-card.json 公布名片 ——
 *       我是谁、住在哪个 URL、capabilities（streaming/pushNotifications）、skills（id + tags）；</li>
 *   <li><b>JSON-RPC 信封</b>：message/send 发起任务，Message 用 role + parts[] 传输；</li>
 *   <li><b>Task 生命周期</b>：submitted → working →（input-required）→ completed/failed/canceled。
 *       发起方拿到 task_id 后用 tasks/get <strong>轮询</strong>，这就是 A2A 与 MCP 的分水岭：
 *       MCP 是同步工具调用，A2A 是长任务、可多轮、可异步；</li>
 *   <li><b>input-required 多轮</b>：对方信息不够时停在 input-required 并提问，
 *       客户端带同一 task_id + contextId 续发消息，任务接着跑 —— 人在回路（human-in-the-loop）。</li>
 * </ul>
 * 与 Step13 子代理的区别：子代理是<strong>自家分身</strong>（进程内、共享宿主模型）；
 * A2A 是<strong>外部同行</strong>（网络调用、对方有自己的模型与工具），类比：请同事帮忙 vs 外包给合作公司。
 * <p>
 * 试试本场景的对话：
 * <pre>
 *   发现一下附近的代理                     —— 拉取两张 Agent Card
 *   让翻译代理把「你好，智能体」翻译成英文   —— message/send → tasks/get 轮询 → completed
 *   问问天气代理明天适合户外运动吗          —— 不带城市 → input-required → 补发消息续 task → completed
 * </pre>
 */
public interface A2AAgent extends Agent {

    /** A2A 技能：名片里可被路由的最小能力单元（真实协议字段：id / name / description / tags）。 */
    record Skill(String id, String name, String description, List<String> tags) {}

    /**
     * Agent Card（名片）：A2A 的发现单元 —— 生产中托管在
     * GET /.well-known/agent-card.json，含 capabilities 与 skills；这里教学化简为 record。
     */
    record AgentCard(String name, String url, String version, boolean streaming,
                     List<Skill> skills, String description) {}

    class Impl implements A2AAgent {

        private final ChatModel model;
        private final List<Message> messages = new ArrayList<>();
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();
        private int reqSeq = 0;

        /** 本地目录：模拟网络上已注册的两个外部代理（真实实现是 GET /.well-known/agent-card.json）。 */
        private static final Map<String, AgentCard> DIRECTORY = new LinkedHashMap<>(Map.of(
                "translator", new AgentCard("translator", "http://a2a.local/translator", "1.0.0", false,
                        List.of(new Skill("translate", "中英互译", "中英双向文本翻译",
                                List.of("translation", "language"))), "中英互译代理"),
                "weather", new AgentCard("weather", "http://a2a.local/weather", "2.1.0", true,
                        List.of(new Skill("forecast", "天气预报", "查询城市天气并给出建议",
                                List.of("weather", "forecast"))), "天气预报代理")));

        /** 远端任务：真实 A2A 中由对方服务端持久化，这里教学化简为内存对象。 */
        private static final class RemoteTask {
            final String id;
            final String contextId;
            final String agent;
            final String task;
            String state = "submitted";
            String stateMessage;
            String reply;
            String followUp;
            int beats = 0;

            RemoteTask(String id, String contextId, String agent, String task) {
                this.id = id;
                this.contextId = contextId;
                this.agent = agent;
                this.task = task;
            }
        }

        private final Map<String, RemoteTask> tasks = new LinkedHashMap<>();
        private int seq = 0;

        public Impl(ChatModel model) {
            this.model = model;
        }

        @Override
        public String name() {
            return "Step14 · A2A 协作（Agent Card 名片发现 · message/send · tasks/get 轮询 · input-required 多轮）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();
            if (messages.isEmpty()) {
                messages.add(Message.system(SYSTEM_PROMPT));
            }
            messages.add(Message.user(input));

            // 标准循环：模型要么返回 tool_calls（发现/发起/轮询），要么返回 content（最终回答）
            for (int round = 1; round <= MAX_ROUNDS; round++) {
                ChatResponse reply = model.chat(messages, TOOLS);

                if (!reply.hasToolCalls()) {
                    if (reply.content().isBlank()) {
                        continue;
                    }
                    lastTrace.add(Map.of("type", "final", "label", "最终回答",
                            "detail", "汇总远端协作结果，向用户作答"));
                    messages.add(Message.assistant(reply.content()));
                    return reply.content();
                }

                messages.add(Message.assistantToolCalls(reply.toolCalls()));
                for (ToolCall call : reply.toolCalls()) {
                    Object args = parseArgs(call.argumentsJson());
                    String result = switch (call.name()) {
                        case "a2a_discover" -> discover();
                        case "a2a_send" -> send(Json.str(args, "agent"), Json.str(args, "task"),
                                Json.str(args, "taskId"), Json.str(args, "message"));
                        case "a2a_get" -> poll(Json.str(args, "taskId"));
                        default -> Json.write(Map.of("error", "unknown tool: " + call.name()));
                    };
                    lastTrace.add(Map.of("type", "observation", "label", "回执按 tool_call_id 回填",
                            "detail", result));
                    messages.add(Message.tool(call.id(), result));
                }
            }
            return "已达最大轮数（" + MAX_ROUNDS + "），为防失控提前终止。";
        }

        // ---------------------------------------------------------- a2a_discover

        /** 拉取目录里所有代理的名片（生产中 GET /.well-known/agent-card.json）。 */
        private String discover() {
            List<Map<String, Object>> cards = DIRECTORY.values().stream().map(A2AAgent.Impl::cardJson).toList();
            lastTrace.add(Map.of("type", "action", "label", "GET /.well-known/agent-card.json · 名片发现",
                    "detail", "拉取 " + cards.size() + " 份 Agent Card：translator（中英互译） · weather（天气预报）"));
            return Json.write(cards);
        }

        // ---------------------------------------------------------- message/send

        /**
         * message/send：首次发起（agent + task）或多轮续写（taskId + message）。
         * 发起后任务进入 working —— 发起方只认 task_id，不关心对方内部实现。
         */
        private String send(String agent, String task, String taskId, String message) {
            // 多轮续写：input-required 之后带同一 task_id 补发信息（真实协议：同 contextId 续 Message）
            if (taskId != null && !taskId.isBlank()) {
                RemoteTask t = tasks.get(taskId);
                if (t == null) {
                    lastTrace.add(Map.of("type", "guard", "label", "message/send · 任务不存在",
                            "detail", "taskId=" + taskId + " 未找到（应答 TaskNotFoundError）"));
                    return errorEnvelope("TaskNotFoundError", taskId);
                }
                t.followUp = message == null ? "" : message;
                t.state = "working";
                t.stateMessage = "已收到补充信息，继续处理";
                t.beats = 0;
                lastTrace.add(Map.of("type", "action", "label", "message/send · 多轮续写（同 taskId + contextId）",
                        "detail", "→ " + t.agent + "  补充信息=" + t.followUp));
                lastTrace.add(Map.of("type", "thought", "label", "状态机 · input-required → working",
                        "detail", "任务 " + t.id + " 携带补充信息继续执行"));
                return envelope("result", Map.of("task", taskJson(t)));
            }

            AgentCard card = DIRECTORY.get(agent);
            if (card == null) {
                lastTrace.add(Map.of("type", "guard", "label", "message/send · 路由失败",
                        "detail", "目录中没有 " + agent + "，请先 a2a_discover"));
                return Json.write(Map.of("error", "unknown agent: " + agent + "（请先发现名片）"));
            }
            RemoteTask t = new RemoteTask("task-" + String.format("%03d", ++seq) + "-" + uuid4(),
                    "ctx-" + seq, agent, task);
            tasks.put(t.id, t);
            lastTrace.add(Map.of("type", "action", "label", "message/send · 发起协作（JSON-RPC 2.0 信封）",
                    "detail", "→ " + card.name() + " @ " + card.url() + "  task=" + task));
            lastTrace.add(Map.of("type", "thought", "label", "状态机 · submitted → working",
                    "detail", "对方已受理 task_id=" + t.id + "（信封标准化：发起方只认 task_id，不关心对方内部实现）"));
            t.state = "working";
            t.stateMessage = "远端代理处理中";
            return envelope("result", Map.of("task", taskJson(t)));
        }

        // ---------------------------------------------------------- tasks/get

        /**
         * tasks/get：轮询任务状态。模拟长任务 —— 每轮询一次前进一拍，
         * 两拍后远端真正调用一次自己的模型产出结果（体现「对方有自己的大脑」）。
         */
        private String poll(String taskId) {
            RemoteTask t = tasks.get(taskId);
            if (t == null) {
                lastTrace.add(Map.of("type", "guard", "label", "tasks/get · 任务不存在",
                        "detail", "taskId=" + taskId + " 未找到（应答 TaskNotFoundError）"));
                return errorEnvelope("TaskNotFoundError", taskId);
            }
            t.beats++;
            AgentCard card = DIRECTORY.get(t.agent);
            lastTrace.add(Map.of("type", "tool", "label", "tasks/get · 轮询 " + t.id,
                    "detail", "当前状态=" + t.state + "（第 " + t.beats + " 拍）"));

            if ("completed".equals(t.state)) {
                return envelope("result", Map.of("task", taskJson(t)));
            }

            // input-required 演示：天气代理缺城市时停下要信息（真实 A2A 的多轮/人在回路状态）
            if ("working".equals(t.state) && "weather".equals(t.agent)
                    && !mentionsCity(t.task) && !mentionsCity(t.followUp)) {
                t.state = "input-required";
                t.stateMessage = "请问要查询哪个城市的天气？";
                lastTrace.add(Map.of("type", "thought", "label", "状态机 · working → input-required",
                        "detail", "对方缺少关键信息，停下提问 —— 客户端应带同一 taskId 续发消息"));
                return envelope("result", Map.of("task", taskJson(t)));
            }

            if (t.beats < 2) {
                t.stateMessage = "远端代理处理中（进度 " + t.beats + "/2）";
                return envelope("result", Map.of("task", taskJson(t)));
            }

            // 长任务收尾：远端调用自己的模型产出结果（对方有自己的模型与工具）
            String reply = runRemotely(card, t);
            t.state = "completed";
            t.reply = reply;
            lastTrace.add(Map.of("type", "thought", "label", "状态机 · working → completed",
                    "detail", "task_id=" + t.id + " 完成，结果以 artifact 回传：" + reply));
            return envelope("result", Map.of("task", taskJson(t)));
        }

        // ---------------------------------------------------------- 远端代理（模拟对端服务端）

        /** 远端代理真的调用一次自己的模型 —— A2A 的对方是完整 Agent，不是函数。 */
        private String runRemotely(AgentCard card, RemoteTask t) {
            if (card == null) {
                return "（远端无此代理）";
            }
            List<Message> remote = new ArrayList<>();
            String persona = switch (card.name()) {
                case "translator" -> "你是远端翻译代理 translator（经 A2A 独立进程对外服务，有自己的模型）。"
                        + "只输出翻译结果本身，不要解释。";
                case "weather" -> "你是远端天气代理 weather（经 A2A 独立进程对外服务，有自己的模型与数据）。"
                        + "基于模拟数据回答：明日晴，22-28℃，东南风 3 级。给出一句话天气结论与户外建议，不超过 40 字。";
                default -> "你是远端代理。";
            };
            remote.add(Message.system(persona));
            String content = t.followUp == null ? t.task : t.task + "（用户补充：" + t.followUp + "）";
            remote.add(Message.user(content));

            String reply = "";
            for (int attempt = 1; attempt <= 3 && reply.isBlank(); attempt++) {
                reply = model.chat(remote).trim();
            }
            lastTrace.add(Map.of("type", "tool", "label", "远端代理运行",
                    "detail", card.name() + " 用自己的模型处理：「" + content + "」"));
            return reply.isBlank() ? "（远端未返回有效内容，可重试）" : reply;
        }

        // ---------------------------------------------------------- 协议线格式

        /** JSON-RPC 2.0 响应信封：{jsonrpc, id, result:{task:{...}}}。 */
        private String envelope(String resultKey, Map<String, Object> result) {
            Map<String, Object> body = Json.obj();
            body.put("jsonrpc", "2.0");
            body.put("id", "req-" + ++reqSeq);
            body.put(resultKey, result);
            return Json.write(body);
        }

        private String errorEnvelope(String code, String detail) {
            return envelope("error", Map.of("code", "TaskNotFound", "message", detail));
        }

        /** Task 对象：真实协议形态 —— kind/id/contextId/status{state,message}/artifacts。 */
        private Map<String, Object> taskJson(RemoteTask t) {
            Map<String, Object> status = Json.obj();
            status.put("state", t.state);
            if (t.stateMessage != null) {
                status.put("message", Map.of("role", "agent",
                        "parts", List.of(Map.of("type", "text", "text", t.stateMessage))));
            }
            Map<String, Object> task = Json.obj();
            task.put("kind", "task");
            task.put("id", t.id);
            task.put("contextId", t.contextId);
            task.put("status", status);
            if ("completed".equals(t.state) && t.reply != null) {
                task.put("artifacts", List.of(Map.of(
                        "artifactId", "art-" + t.id,
                        "name", "result",
                        "parts", List.of(Map.of("type", "text", "text", t.reply)))));
            }
            return task;
        }

        /** 名片 JSON：真实协议字段 —— name/url/version/capabilities/skills{id,tags}/description。 */
        private static Map<String, Object> cardJson(AgentCard c) {
            Map<String, Object> card = Json.obj();
            card.put("name", c.name());
            card.put("url", c.url());
            card.put("version", c.version());
            card.put("capabilities", Map.of("streaming", c.streaming(), "pushNotifications", false));
            card.put("skills", c.skills().stream().map(s -> {
                Map<String, Object> m = Json.obj();
                m.put("id", s.id());
                m.put("name", s.name());
                m.put("description", s.description());
                m.put("tags", s.tags());
                return m;
            }).toList());
            card.put("description", c.description());
            return card;
        }

        // ---------------------------------------------------------- 工具清单（标准 Function Calling）

        /** 按 OpenAI tools 协议构造一个工具的 JSON Schema。 */
        private static Map<String, Object> tool(String name, String desc, Map<String, Object> params) {
            Map<String, Object> fn = Json.obj();
            fn.put("name", name);
            fn.put("description", desc);
            fn.put("parameters", params);
            Map<String, Object> schema = Json.obj();
            schema.put("type", "function");
            schema.put("function", fn);
            return schema;
        }

        private static final List<Map<String, Object>> TOOLS = List.of(
                tool("a2a_discover", "发现外部代理：拉取所有 Agent Card 名片（GET /.well-known/agent-card.json），"
                        + "协作前先发现，按名片 skills 选择代理。", Map.of("type", "object", "properties", Map.of())),
                tool("a2a_send", "向远端代理发起或续写任务（JSON-RPC message/send）。首次发起填 agent + task；"
                        + "对方 input-required 时补充信息：填 taskId + message，同一任务多轮续写。",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "agent", Map.of("type", "string", "description", "首次发起：目标代理名（来自名片）"),
                                        "task", Map.of("type", "string", "description", "首次发起：任务描述"),
                                        "taskId", Map.of("type", "string", "description", "续写：原任务 id"),
                                        "message", Map.of("type", "string", "description", "续写：补充给对方的信息")),
                                "required", List.of())),
                tool("a2a_get", "轮询任务状态（tasks/get）：working/input-required 需继续轮询或续写，completed 返回 artifact 结果。",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "taskId", Map.of("type", "string", "description", "任务 id")),
                                "required", List.of("taskId"))));

        private static final int MAX_ROUNDS = 10;

        private static final String SYSTEM_PROMPT = """
                你是 ToyAgent 主代理，可通过 A2A 协议与外部代理协作（对方是跨进程的独立代理，有自己的模型与工具）。
                流程：先 a2a_discover 发现名片；按名片 skills 选择代理，a2a_send 发起任务拿到 task_id（状态 working）；
                用 a2a_get 轮询直到 completed 拿到结果。
                若返回 input-required，说明对方需要补充信息：对话里已有答案就直接用 a2a_send 的 taskId+message 续写，
                没有则先向用户提问。拿到 completed 结果后用自然语言向用户汇总。""";

        // ---------------------------------------------------------- 通用

        private static boolean mentionsCity(String text) {
            if (text == null || text.isBlank()) return false;
            for (String city : List.of("北京", "上海", "广州", "深圳", "杭州", "成都", "西安", "南京")) {
                if (text.contains(city)) return true;
            }
            return false;
        }

        private static String uuid4() {
            return UUID.randomUUID().toString().substring(0, 4);
        }

        private Object parseArgs(String argsJson) {
            try {
                return Json.parse(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            } catch (Exception e) {
                return Json.obj();
            }
        }

        @Override
        public void reset() {
            messages.clear();
            tasks.clear();
            seq = 0;
            reqSeq = 0;
        }

        @Override
        public List<Map<String, Object>> trace() {
            return List.copyOf(lastTrace);
        }
    }

    /** 本地直接测试入口：java -cp target/classes cn.xiaofuge.ai.agent.step14.A2AAgent [你的问题] */
    public static void main(String[] args) {
        cn.xiaofuge.ai.llm.ChatModel model = cn.xiaofuge.ai.llm.Models.openAiOrNull();
        if (model == null) model = cn.xiaofuge.ai.llm.Models.mock("demo");
        String input = args.length > 0 ? String.join(" ", args) : "发现一下附近的代理，然后让翻译代理把「你好，智能体」翻译成英文";
        System.out.println("[输入] " + input);
        System.out.println("[输出] " + new Impl(model).chat(input));
    }
}
