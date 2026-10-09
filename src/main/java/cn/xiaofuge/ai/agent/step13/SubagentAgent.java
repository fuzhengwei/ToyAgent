package cn.xiaofuge.ai.agent.step13;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step13 · 子代理 —— 主智能体的「可派遣分身」（对应 dsh-java 的
 * SubagentRegistry / SubagentTool / SpawnInProcessProvider / ForkInProcessProvider）。
 * <p>
 * 主代理不必亲自做所有事：把子任务委托给带<strong>独立上下文</strong>的子代理，结果回填主会话。
 * 两种派遣方式：
 * <ul>
 *   <li><b>spawn</b>（SpawnInProcessProvider）：全新实例，只有职责提示词，看不到父会话 —— 适合无状态专项任务；</li>
 *   <li><b>fork</b>（ForkInProcessProvider）：复制父会话上下文再出发 —— 适合「接着当前话题继续做」。</li>
 * </ul>
 * 与 Step12 多智能体的区别：Step12 是固定流水线（规划→研究→写作→审查），角色间结构化协作；
 * 本步是<strong>主代理动态派遣</strong>—— 模型自己决定派谁、派几个、带不带上下文，即 Claude Code 的 Task 工具。
 * <p>
 * 试试本场景的对话（体会 spawn 与 fork 的上下文差异）：
 * <pre>
 *   记住：我最喜欢紫色
 *   fork 一个子代理，让它写一句贺词      —— 子代理继承父会话，贺词里带紫色
 *   再 spawn 一个子代理写贺词           —— 全新实例，贺词里没有紫色
 *   派个研究员调研一下 Agent 趋势       —— spawn 专项任务
 * </pre>
 */
public interface SubagentAgent extends Agent {

    class Impl implements SubagentAgent {

        /** 子代理档案：职责提示词（真实场景里每个子代理还可挂自己的工具集）。 */
        private record ChildSpec(String specialty) {}

        private static final Map<String, ChildSpec> CHILDREN = new LinkedHashMap<>(Map.of(
                "researcher", new ChildSpec("你是调研子代理。根据任务输出 3 条要点式调研结论，不写代码。"),
                "writer", new ChildSpec("你是写作子代理。根据任务输出一段 50 字以内的短文，不解释。")));

        private final ChatModel model;
        /** 父会话上下文：fork 时被子代理继承。 */
        private final List<String> parentTranscript = new ArrayList<>();
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        public Impl(ChatModel model) {
            this.model = model;
        }

        @Override
        public String name() {
            return "Step13 · 子代理（spawn 独立上下文 / fork 继承上下文）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();
            parentTranscript.add("用户: " + input);
            lastTrace.add(Map.of("type", "tool", "label", "主代理就绪",
                    "detail", "可派遣子代理：" + CHILDREN.keySet() + "（父会话上下文 " + parentTranscript.size() + " 条）"));

            List<Message> messages = new ArrayList<>();
            String ctx = parentTranscript.isEmpty() ? ""
                    : "\n【父会话上下文】\n" + String.join("\n", parentTranscript);
            messages.add(Message.system(SYSTEM_PROMPT_PREFIX + ctx));
            messages.add(Message.user(input));

            for (int round = 1; round <= 3; round++) {
                String raw = model.chat(messages).trim();
                Object decision = tryParse(raw);

                if (decision instanceof Map<?, ?> && Json.str(decision, "tool") != null) {
                    String name = Json.str(decision, "name");
                    String mode = Json.str(decision, "mode");
                    String task = Json.str(decision, "task");
                    lastTrace.add(Map.of("type", "action", "label", "主代理派遣子代理",
                            "detail", "subagent=" + name + "  mode=" + mode + "  task=" + task));

                    String result = dispatch(name, mode, task);
                    lastTrace.add(Map.of("type", "observation", "label", "子代理返回",
                            "detail", result));

                    messages.add(Message.assistant(raw));
                    messages.add(Message.user("子代理 " + name + "(" + mode + ") 执行结果: " + result));
                    continue;
                }

                lastTrace.add(Map.of("type", "final", "label", "最终回答",
                        "detail", "主代理汇总子代理结果，向用户作答"));
                parentTranscript.add("助手: " + raw);
                return raw;
            }
            return "已达最大轮数，循环终止。";
        }

        /** 派遣：spawn 给全新上下文；fork 复制父会话再出发。 */
        private String dispatch(String name, String mode, String task) {
            ChildSpec spec = CHILDREN.get(name);
            if (spec == null) {
                return "没有叫 " + name + " 的子代理。可用：" + CHILDREN.keySet();
            }
            boolean fork = "fork".equalsIgnoreCase(mode);

            List<Message> childMessages = new ArrayList<>();
            StringBuilder sys = new StringBuilder("【子代理身份】").append(spec.specialty());
            if (fork) {
                sys.append("\n【继承的父会话上下文】\n").append(String.join("\n", parentTranscript));
            } else {
                sys.append("\n（spawn 模式：全新实例，看不到父会话）");
            }
            childMessages.add(Message.system(sys.toString()));
            childMessages.add(Message.user(task));

            String report = model.chat(childMessages).trim();
            lastTrace.add(Map.of("type", "tool", "label", "子代理运行（" + mode + "）",
                    "detail", name + " 收到任务「" + task + "」，上下文 " + (fork ? "继承父会话 " + parentTranscript.size() + " 条" : "全新（0 条历史）")));
            return report;
        }

        private static final String SYSTEM_PROMPT_PREFIX = """
                你是主智能体，可以把子任务委托给子代理。可用子代理：
                - researcher：调研汇总
                - writer：文案写作
                派遣方式：spawn = 全新实例（无父会话上下文）；fork = 继承父会话上下文。
                需要委托时只输出 JSON：{"tool": "dispatch_subagent", "name": "researcher", "mode": "spawn", "task": "子任务描述"}
                不需要委托时，直接用自然语言回答，不要输出 JSON。
                """;

        @Override
        public void reset() {
            parentTranscript.clear();
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
