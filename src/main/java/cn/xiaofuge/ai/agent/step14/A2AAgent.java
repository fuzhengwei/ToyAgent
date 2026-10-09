package cn.xiaofuge.ai.agent.step14;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Step14 · A2A 协作 —— 代理之间的「标准化网络协议」
 * （对应 deepseek-harness-java 的 A2AController / AgentCard / CollaborationController）。
 * <p>
 * Step12/23 的多代理都活在<strong>同一个进程</strong>里，主代理直接 new 出子代理。
 * A2A（Agent-to-Agent）解决的是<strong>跨进程、跨主人</strong>的协作：
 * <ul>
 *   <li><b>名片发现</b>（Agent Card）：每个代理对外公布一份 JSON 名片 —— 我是谁、住在哪个 URL、会什么技能；</li>
 *   <li><b>标准化请求</b>：协作不靠猜接口，靠统一信封 —— 任务描述 + task_id + 状态机
 *       （submitted → working → completed/failed）；</li>
 *   <li><b>异步回执</b>：发起方拿到 task_id 即可离开，结果异步取回。</li>
 * </ul>
 * 与 Step13 子代理的区别：子代理是<strong>自家分身</strong>（进程内、共享宿主模型）；
 * A2A 是<strong>外部同行</strong>（网络调用、对方有自己的模型与工具），类比：请同事帮忙 vs 外包给合作公司。
 * <p>
 * 试试本场景的对话：
 * <pre>
 *   发现一下附近的代理                    —— 拉取两张 Agent Card
 *   让翻译代理把「你好，智能体」翻译成英文  —— a2a_send 委派，返回 task_id + 回执
 *   问问天气代理明天适合户外运动吗         —— 按 skill 路由到天气代理
 * </pre>
 */
public interface A2AAgent extends Agent {

    /**
     * Agent Card（名片）：A2A 协议里的发现单元 ——
     * 生产中托管在 https://host/.well-known/agent.json，这里教学化简为 record。
     */
    record AgentCard(String name, String url, String version, List<String> skills, String description) {}

    class Impl implements A2AAgent {

        private final ChatModel model;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();
        /** 本地目录：模拟网络上已注册的两个外部代理（真实实现是 HTTP 拉取名片）。 */
        private static final Map<String, AgentCard> DIRECTORY = new LinkedHashMap<>(Map.of(
                "translator", new AgentCard("translator", "http://a2a.local/translator", "1.0.0",
                        List.of("translate"), "中英互译代理"),
                "weather", new AgentCard("weather", "http://a2a.local/weather", "2.1.0",
                        List.of("forecast"), "天气预报代理")));
        /** 已发起的协作任务：id → 描述（生产中由 CollaborationController 持久化并轮询状态）。 */
        private final Map<String, String> tasks = new LinkedHashMap<>();
        private int seq = 0;

        public Impl(ChatModel model) {
            this.model = model;
        }

        @Override
        public String name() {
            return "Step14 · A2A 协作（Agent Card 名片发现 · task_id 信封 · 异步回执）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();
            List<Message> messages = new ArrayList<>();
            messages.add(Message.system(SYSTEM_PROMPT));
            messages.add(Message.user(input));

            for (int round = 1; round <= 3; round++) {
                String raw = model.chat(messages).trim();
                Object decision = tryParse(raw);

                if (decision instanceof Map<?, ?> && Json.str(decision, "tool") != null) {
                    String tool = Json.str(decision, "tool");
                    String result = "a2a_discover".equals(tool) ? discover() : send(
                            Json.str(decision, "agent"), Json.str(decision, "task"));
                    messages.add(Message.assistant(raw));
                    messages.add(Message.user("工具执行结果: " + result));
                    continue;
                }

                lastTrace.add(Map.of("type", "final", "label", "最终回答",
                        "detail", "汇总协作结果，向用户作答"));
                return raw;
            }
            return "已达最大轮数，循环终止。";
        }

        /** a2a_discover：拉取目录里所有代理的名片（生产中 GET /.well-known/agent.json）。 */
        private String discover() {
            lastTrace.add(Map.of("type", "action", "label", "a2a_discover · 名片发现",
                    "detail", "拉取 " + DIRECTORY.size() + " 份 Agent Card（模拟网络请求）"));
            StringBuilder sb = new StringBuilder("[");
            int i = 0;
            for (AgentCard c : DIRECTORY.values()) {
                if (i++ > 0) sb.append(", ");
                sb.append("\n  {\"name\": \"").append(c.name()).append("\", \"url\": \"").append(c.url())
                        .append("\", \"version\": \"").append(c.version())
                        .append("\", \"skills\": ").append(c.skills()).append("}");
            }
            String cards = sb.append("\n]").toString();
            lastTrace.add(Map.of("type", "observation", "label", "收到名片",
                    "detail", "translator（中英互译） · weather（天气预报）"));
            return cards;
        }

        /** a2a_send：按名片路由委派请求，模拟 submitted → completed 状态机并回执。 */
        private String send(String agent, String task) {
            AgentCard card = DIRECTORY.get(agent);
            if (card == null) {
                lastTrace.add(Map.of("type", "guard", "label", "a2a_send · 路由失败",
                        "detail", "目录中没有 " + agent + "，请先 a2a_discover"));
                return "{\"error\": \"unknown agent: " + agent + "\"}";
            }
            String taskId = "task-" + String.format("%03d", ++seq) + "-" + UUID.randomUUID().toString().substring(0, 4);
            tasks.put(taskId, agent + ": " + task);

            lastTrace.add(Map.of("type", "action", "label", "a2a_send · 发起协作",
                    "detail", "→ " + card.name() + " @ " + card.url() + "  task=" + task));
            lastTrace.add(Map.of("type", "thought", "label", "状态机 · submitted → working",
                    "detail", "对方已受理 task_id=" + taskId + "（信封标准化：发起方只认 task_id，不关心对方内部实现）"));

            String reply = handleRemotely(card, task);
            lastTrace.add(Map.of("type", "observation", "label", "回执 · completed",
                    "detail", "task_id=" + taskId + " 已完成：" + reply));
            return "{\"task_id\": \"" + taskId + "\", \"status\": \"completed\", \"result\": \"" + reply + "\"}";
        }

        /** 模拟远端代理按 skill 处理（真实实现：POST JSON-RPC / HTTP task 请求）。 */
        private static String handleRemotely(AgentCard card, String task) {
            return switch (card.name()) {
                case "translator" -> "翻译结果（模拟远端模型）：Hello, Agent.";
                case "weather" -> "明日晴，22-28℃，东南风 3 级，适合户外运动（模拟远端数据）";
                default -> "（模拟远端响应）";
            };
        }

        private static final String SYSTEM_PROMPT = """
                你是 ToyAgent 主代理，可通过 A2A 协议与外部代理协作。协作前先发现名片。
                查看目录时只输出 JSON：{"tool": "a2a_discover"}
                委派任务时只输出 JSON：{"tool": "a2a_send", "agent": "translator|weather", "task": "任务描述"}
                翻译类任务派给 translator，天气类任务派给 weather。拿到结果后用自然语言回答。""";

        @Override
        public void reset() {
            tasks.clear();
            seq = 0;
        }

        private Object tryParse(String raw) {
            try {
                return Json.parse(raw.replaceFirst("^```\\w*\\s*", "").replaceFirst("```\\s*$", "").trim());
            } catch (Exception e) {
                return raw;
            }
        }

        @Override
        public List<Map<String, Object>> trace() {
            return List.copyOf(lastTrace);
        }
    }
}
