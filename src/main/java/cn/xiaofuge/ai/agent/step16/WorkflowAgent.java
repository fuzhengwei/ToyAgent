package cn.xiaofuge.ai.agent.step16;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.agent.step03.ReActAgent.MockWeather;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.ChatResponse;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;
import cn.xiaofuge.ai.llm.ToolCall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step16 · 工作流智能体 —— LangGraph 式的状态机编排。
 * <p>
 * ReAct 是"模型说了算"的自由循环，但很多业务需要"流程说了算"的
 * 确定性编排。把智能体拆成节点（Node）与边（Edge）：
 * 每个节点是一段能力（分类/工具/问答/润色），条件边决定流转，
 * 状态（State）在节点间传递 —— 这就是 LangGraph 的核心思想，
 * 也是生产系统中"可控性"与"灵活性"的平衡点。
 * <p>
 * 节点与模型的对接全部走标准 Function Calling 协议：
 * <ul>
 *   <li>classify 路由节点 —— 注册 {@code choose_route} 结构化选择工具
 *       （与 Step10 的 choose_skill 同构），不再让模型输出自由文本 JSON；</li>
 *   <li>tool 工具节点 —— get_weather / calculator 以 JSON Schema 进 tools 字段，
 *       模型经 tool_calls 决定调哪个、参数怎么填。节点只执行<b>一轮</b>、不循环 ——
 *       这是工作流与 ReAct 的本质区别：节点内不允许自由转圈，转不转由流程说了算。</li>
 * </ul>
 */
public interface WorkflowAgent extends Agent {

    class Impl implements WorkflowAgent {

        private final ChatModel model;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

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

        /** 路由节点的结构化选择工具：让模型选分支，而不是猜它输出的 JSON 文本。 */
        private static final List<Map<String, Object>> ROUTER_TOOLS = List.of(
                tool("choose_route", "根据用户输入从工作流分支中选择下一跳",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "next", Map.of("type", "string",
                                                "description", "下一跳节点",
                                                "enum", List.of("faq", "tool")),
                                        "reason", Map.of("type", "string", "description", "一句话理由")),
                                "required", List.of("next"))));

        /** 工具节点的工具清单：随请求发给模型，模型能调什么由这份清单决定。 */
        private static final List<Map<String, Object>> TOOLS = List.of(
                tool("get_weather", "查询指定城市的实时天气",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "city", Map.of("type", "string", "description", "城市名，如：北京")),
                                "required", List.of("city"))),
                tool("calculator", "计算四则运算表达式",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "expression", Map.of("type", "string", "description", "四则运算表达式，如 12*8")),
                                "required", List.of("expression"))));

        public Impl(ChatModel model) {
            this.model = model;
        }

        @Override
        public String name() {
            return "Step16 · 工作流状态机（LangGraph 思想）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();

            // State：在节点之间流转的共享状态
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("input", input);
            state.put("answer", "");
            state.put("observation", "");

            // 固定入口：classify 节点
            runNode("classify", state);

            // 条件边：按分类结果路由
            String next = String.valueOf(state.getOrDefault("next", "faq"));
            lastTrace.add(Map.of(
                    "type", "edge",
                    "label", "条件边",
                    "detail", "classify --" + next + "--> " + (next.equals("tool") ? "tool 节点" : "faq 节点")));

            runNode(next, state);
            runNode("polish", state);   // 汇合点：统一润色
            lastTrace.add(Map.of(
                    "type", "edge",
                    "label", "END",
                    "detail", "执行路径: classify → " + next + " → polish → END"));

            return String.valueOf(state.get("answer"));
        }

        /** 节点执行器：每个节点在 State 上读输入、写产出。 */
        private void runNode(String node, Map<String, Object> state) {
            String input = String.valueOf(state.get("input"));
            String nodePrompt = "[节点:" + node + "] ";
            switch (node) {
                case "classify" -> {
                    // 路由节点：choose_route 结构化选择 —— 服务端保证结构，不猜自由文本 JSON
                    ChatResponse routed = model.chat(List.of(
                            Message.system(nodePrompt + "你是意图分类器。调用 choose_route 选择下一个节点。"),
                            Message.user(input)), ROUTER_TOOLS);
                    String next = "faq";
                    String reason = "模型未发起路由调用，回退 faq";
                    if (routed.hasToolCalls()) {
                        for (ToolCall call : routed.toolCalls()) {
                            if ("choose_route".equals(call.name())) {
                                Object args = Json.parse(call.argumentsJson() == null ? "{}" : call.argumentsJson());
                                String want = Json.str(args, "next");
                                if ("tool".equals(want) || "faq".equals(want)) next = want;
                                reason = String.valueOf(Json.str(args, "reason"));
                            }
                        }
                    }
                    state.put("next", next);
                    state.put("route_reason", reason);
                    lastTrace.add(Map.of("type", "node", "label", "🔹 节点 classify", "detail", "路由决策 → " + next + "（" + reason + "）"));
                }
                case "tool" -> {
                    // 工具节点：单轮 Function Calling —— 模型决定调哪个工具、填什么参数；
                    // 节点只执行一轮、不循环，转不转由流程说了算（与 ReAct 的本质区别）。
                    ChatResponse reply = model.chat(List.of(
                            Message.system(nodePrompt + "你是工具节点。根据用户输入调用合适的工具（get_weather 或 calculator）。"),
                            Message.user(input)), TOOLS);
                    String observation;
                    if (reply.hasToolCalls()) {
                        StringBuilder sb = new StringBuilder();
                        for (ToolCall call : reply.toolCalls()) {
                            lastTrace.add(Map.of("type", "tool", "label", "发起 tool_calls", "detail", call.name() + " " + call.argumentsJson()));
                            sb.append(executeTool(call.name(), call.argumentsJson())).append("；");
                        }
                        observation = sb.toString();
                    } else {
                        observation = reply.content();   // 模型判断无需工具，直接给说明
                    }
                    state.put("observation", observation);
                    lastTrace.add(Map.of("type", "node", "label", "🔹 节点 tool", "detail", observation));
                }
                case "faq" -> {
                    // 纯生成节点：不需要工具，不传 tools 字段
                    String answer = model.chat(List.of(
                            Message.system(nodePrompt + "你是 FAQ 问答节点，直接简洁回答常见问题。"),
                            Message.user(input)));
                    state.put("answer", answer);
                    lastTrace.add(Map.of("type", "node", "label", "🔹 节点 faq", "detail", clip(answer, 40)));
                }
                case "polish" -> {
                    // 汇合润色节点：tool 分支的 observation 在这里转为最终回答
                    String obs = String.valueOf(state.get("observation"));
                    String base = obs.isEmpty()
                            ? String.valueOf(state.get("answer"))
                            : "根据查询结果：" + obs;
                    String answer = model.chat(List.of(
                            Message.system(nodePrompt + """
                                    你是润色节点。基于前序节点产出整理最终回答，保持事实不变、表达自然、50 字以内。
                                    前序产出: %s
                                    """.formatted(base)),
                            Message.user(input)));
                    state.put("answer", answer);
                    lastTrace.add(Map.of("type", "node", "label", "🔹 节点 polish", "detail", "最终回答已生成"));
                }
                default -> lastTrace.add(Map.of("type", "warn", "label", "⚠️ 未知节点", "detail", node));
            }
        }

        /** 工具注册表：本场景 2 个本地工具，执行器就是一个 switch。 */
        private String executeTool(String tool, String argsJson) {
            try {
                Object args = Json.parse(argsJson == null ? "{}" : argsJson);
                return switch (tool) {
                    case "get_weather" -> MockWeather.query(Json.str(args, "city"));
                    case "calculator" -> {
                        Matcher m = Pattern.compile("\\s*(\\d+(?:\\.\\d+)?)\\s*([+\\-*/])\\s*(\\d+(?:\\.\\d+)?)\\s*")
                                .matcher(String.valueOf(Json.str(args, "expression")));
                        if (!m.matches()) yield "无法解析表达式";
                        double a = Double.parseDouble(m.group(1));
                        double b = Double.parseDouble(m.group(3));
                        yield switch (m.group(2)) {
                            case "+" -> String.valueOf(a + b);
                            case "-" -> String.valueOf(a - b);
                            case "*" -> String.valueOf(a * b);
                            case "/" -> b == 0 ? "除数不能为 0" : String.valueOf(a / b);
                            default -> "不支持的运算符";
                        };
                    }
                    default -> "未知工具: " + tool;
                };
            } catch (Exception e) {
                return "工具执行失败: " + e.getMessage();
            }
        }

        private String clip(String s, int n) {
            String one = s.replaceAll("\\s+", " ").trim();
            return one.length() > n ? one.substring(0, n) + "…" : one;
        }

        @Override
        public List<Map<String, Object>> trace() {
            return List.copyOf(lastTrace);
        }
    }

    /** 本地直接测试入口：java -cp target/classes cn.xiaofuge.ai.agent.step16.WorkflowAgent [你的问题] */
    public static void main(String[] args) {
        cn.xiaofuge.ai.llm.ChatModel model = cn.xiaofuge.ai.llm.Models.openAiOrNull();
        if (model == null) model = cn.xiaofuge.ai.llm.Models.mock("demo");
        String input = args.length > 0 ? String.join(" ", args) : "查一下杭州天气，再算一下 12*8";
        System.out.println("[输入] " + input);
        System.out.println("[输出] " + new Impl(model).chat(input));
    }
}
