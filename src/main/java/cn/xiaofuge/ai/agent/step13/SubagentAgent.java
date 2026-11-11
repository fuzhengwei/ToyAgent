package cn.xiaofuge.ai.agent.step13;

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

/**
 * Step13 · 子代理 —— 主智能体的「可派遣分身」（对应 dsh-java 的
 * SubagentRegistry / SubagentTool / SpawnInProcessProvider / ForkInProcessProvider）。
 * <p>
 * 主代理不必亲自做所有事：把子任务委托给带<strong>独立上下文</strong>的子代理，结果回填主会话。
 * 两种派遣方式：
 * <ul>
 *   <li><b>spawn</b>（SpawnInProcessProvider）：全新实例，只有职责提示词，看不到父会话 —— 适合无状态专项任务；</li>
 *   <li><b>fork</b>（ForkInProcessProvider）：复制父会话消息列表再出发 —— 适合「接着当前话题继续做」。</li>
 * </ul>
 * 派遣本身就是一个<b>标准 Function Calling 工具</b>（与 Step04 同一协议）：请求体携带
 * tools 字段声明 dispatch_subagent 的 JSON Schema，模型以结构化 tool_calls 返回「派谁、怎么派、派什么活」，
 * 子代理结果以 role=tool 消息（带 tool_call_id）回填 —— Claude Code 的 Task 工具正是这个形态。
 * 与 Step12 多智能体的区别：Step12 是固定流水线（规划→研究→写作→审查），角色间结构化协作；
 * 本步是<strong>主代理动态派遣</strong>—— 模型自己决定派谁、派几个、带不带上下文。
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
        /**
         * 父会话消息列表（含 system）：主代理的完整协议对话历史 ——
         * assistant(tool_calls) 与 role=tool 成对出现；fork 时被子代理整表复制。
         */
        private final List<Message> parentMessages = new ArrayList<>();
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        /** 提示词只负责人设与回答策略 —— 子代理清单与派遣参数格式交给协议的 tools 字段。 */
        private static final String SYSTEM_PROMPT = """
                你是主智能体，可以把子任务委托给子代理。需要委托时调用 dispatch_subagent 工具；
                子代理结果已按 tool_call_id 回填后，汇总并用自然语言回答用户，不要重复派遣同一任务。
                注意：task 参数只描述任务本身。选 spawn 时严禁把父会话中的任何信息（偏好、历史、结论）写进 task
                —— spawn 的意义就是全新实例，经 task 泄密会破坏上下文隔离；只有 fork 才能继承父会话上下文。
                """;

        /** dispatch_subagent 的 JSON Schema：子代理清单由 Schema 的 enum 声明，模型只能从这里选。 */
        private static Map<String, Object> dispatchTool() {
            Map<String, Object> fn = Json.obj();
            fn.put("name", "dispatch_subagent");
            fn.put("description", "把子任务派遣给一个子代理执行。spawn=全新实例（看不到父会话）；"
                    + "fork=复制父会话消息列表（接着当前话题继续做）。");
            fn.put("parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "name", Map.of("type", "string", "enum", List.copyOf(CHILDREN.keySet()),
                                    "description", "子代理名称"),
                            "mode", Map.of("type", "string", "enum", List.of("spawn", "fork"),
                                    "description", "派遣方式：spawn 全新上下文 / fork 继承父会话"),
                            "task", Map.of("type", "string", "description", "交给子代理的任务描述")),
                    "required", List.of("name", "mode", "task")));

            Map<String, Object> schema = Json.obj();
            schema.put("type", "function");
            schema.put("function", fn);
            return schema;
        }

        /** 工具清单：随每次请求发给模型 —— 派遣方式与参数完全由协议保证。 */
        private static final List<Map<String, Object>> TOOLS = List.of(dispatchTool());

        private static final int MAX_ROUNDS = 4;

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
            if (parentMessages.isEmpty()) {
                parentMessages.add(Message.system(SYSTEM_PROMPT));
            }
            parentMessages.add(Message.user(input));
            lastTrace.add(Map.of("type", "tool", "label", "主代理就绪",
                    "detail", "请求携带 tools: dispatch_subagent Schema，可派遣：" + CHILDREN.keySet()
                            + "（父会话消息 " + (parentMessages.size() - 1) + " 条）"));

            // 标准循环：模型要么返回 tool_calls（派遣子代理），要么返回 content（最终回答）
            for (int round = 1; round <= MAX_ROUNDS; round++) {
                ChatResponse reply = model.chat(parentMessages, TOOLS);

                if (!reply.hasToolCalls()) {
                    // 防御：路由型网关并非每次都命中支持工具的模型，空回答重试而不是原样返回
                    if (reply.content().isBlank()) {
                        lastTrace.add(Map.of("type", "tool", "label", "模型返回空回答，重试",
                                "detail", "未发起派遣且内容为空（round " + round + "）"));
                        continue;
                    }
                    lastTrace.add(Map.of("type", "final", "label", "最终回答",
                            "detail", "主代理汇总子代理结果，向用户作答"));
                    parentMessages.add(Message.assistant(reply.content()));
                    return reply.content();
                }

                // 协议保证的结构化派遣：assistant 消息原样留在历史里，结果按 id 对号入座
                parentMessages.add(Message.assistantToolCalls(reply.toolCalls()));
                for (ToolCall call : reply.toolCalls()) {
                    Object args = parseArgs(call.argumentsJson());
                    String childName = Json.str(args, "name");
                    String mode = Json.str(args, "mode");
                    String task = Json.str(args, "task");
                    lastTrace.add(Map.of("type", "action", "label", "主代理派遣子代理",
                            "detail", "subagent=" + childName + "  mode=" + mode + "  task=" + task));

                    String result = dispatch(childName, mode, task);
                    lastTrace.add(Map.of("type", "observation", "label", "子代理结果按 tool_call_id 回填",
                            "detail", result));
                    parentMessages.add(Message.tool(call.id(), result));
                }
            }
            return "已达最大派遣轮数（" + MAX_ROUNDS + "），为防失控提前终止。";
        }

        /**
         * 派遣：spawn 给全新上下文；fork 复制父会话消息列表（跳过主代理 system，
         * 换上子代理自己的身份，user/assistant/tool 的角色结构原样保留）。
         */
        private String dispatch(String childName, String mode, String task) {
            ChildSpec spec = CHILDREN.get(childName);
            if (spec == null) {
                return "没有叫 " + childName + " 的子代理。可用：" + CHILDREN.keySet();
            }
            boolean fork = "fork".equalsIgnoreCase(mode);

            List<Message> childMessages = new ArrayList<>();
            String sys = "【子代理身份】" + spec.specialty()
                    + (fork ? "" : "\n（spawn 模式：全新实例，看不到父会话）");
            childMessages.add(Message.system(sys));
            if (fork) {
                // 复制到本轮尚未回填结果的 assistant(tool_calls) 之前 —— 子代理看到的是完整已闭环的历史
                childMessages.addAll(parentMessages.subList(1, parentMessages.size() - 1));
            }
            childMessages.add(Message.user(task));

            // 子代理单轮应答；keyless 路由网关可能命中 reasoning 模型导致 content 为空，有界重试
            String report = "";
            for (int attempt = 1; attempt <= 3 && report.isBlank(); attempt++) {
                report = model.chat(childMessages).trim();
            }
            if (report.isBlank()) {
                report = "（子代理未返回有效内容，网关未命中可用模型，可重试）";
            }
            lastTrace.add(Map.of("type", "tool", "label", "子代理运行（" + mode + "）",
                    "detail", childName + " 收到任务「" + task + "」，上下文 "
                            + (fork ? "继承父会话消息列表 " + (childMessages.size() - 2) + " 条" : "全新（0 条历史）")));
            return report;
        }

        @Override
        public void reset() {
            parentMessages.clear();
        }

        private Object parseArgs(String argsJson) {
            try {
                return Json.parse(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            } catch (Exception e) {
                return Json.obj();
            }
        }

        @Override
        public List<Map<String, Object>> trace() {
            return List.copyOf(lastTrace);
        }
    }

    /** 本地直接测试入口：java -cp target/classes cn.xiaofuge.ai.agent.step13.SubagentAgent [你的问题] */
    public static void main(String[] args) {
        cn.xiaofuge.ai.llm.ChatModel model = cn.xiaofuge.ai.llm.Models.openAiOrNull();
        if (model == null) model = cn.xiaofuge.ai.llm.Models.mock("demo");
        String input = args.length > 0 ? String.join(" ", args) : "把任务拆给子智能体：调研 Java 21 新特性并总结";
        System.out.println("[输入] " + input);
        System.out.println("[输出] " + new Impl(model).chat(input));
    }

}
