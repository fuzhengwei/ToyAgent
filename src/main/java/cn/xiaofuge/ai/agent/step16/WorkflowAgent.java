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
 * 确定性编排。LangGraph 的三个一等公民这里都齐了：
 * <ul>
 *   <li><b>节点（Node）</b>——一段能力（分类/工具/问答/润色），注册进节点表；</li>
 *   <li><b>边（Edge）</b>——固定边直连下一跳，条件边由路由器读 State 决定分支；</li>
 *   <li><b>状态（State）</b>——在节点之间流转的共享数据。</li>
 * </ul>
 * 关键设计：<b>图是数据，执行器是通用的</b>。chat() 不硬编码任何拓扑，
 * 只是一个从 START 沿边走到 END 的 walker —— 想加节点、改流程，
 * 只动 buildGraph() 一处，执行器零改动。这才是"流程说了算"的本意。
 * <p>
 * 节点与模型的对接走标准 Function Calling 协议：
 * <ul>
 *   <li>classify 路由节点 —— 注册 {@code choose_route} 结构化选择工具
 *       （与 Step10 的 choose_skill 同构），不再让模型输出自由文本 JSON；</li>
 *   <li>tool 工具节点 —— get_weather / calculator 以 JSON Schema 进 tools 字段，
 *       模型经 tool_calls 决定调哪个、参数怎么填。节点只执行<b>一轮</b>、不循环 ——
 *       节点内不允许自由转圈，转不转由图的拓扑说了算。</li>
 * </ul>
 * 图允许成环（LangGraph 的循环能力），所以执行器同样需要步数保险丝。
 */
public interface WorkflowAgent extends Agent {

    /** 节点执行次数上限：图成环时的保险丝（真实图运行器的标配）。 */
    int MAX_HOPS = 10;

    class Impl implements WorkflowAgent {

        private final ChatModel model;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        // ---------------- 图的三个组成部分：节点表、固定边表、条件边表 ----------------

        /** 节点表：名字 → 节点函数（读 State、写 State）。 */
        private final Map<String, NodeFn> nodes = new LinkedHashMap<>();
        /** 固定边表：from → to，无条件直达。 */
        private final Map<String, String> edges = new LinkedHashMap<>();
        /** 条件边表：from → 路由器（读 State 返回下一跳）。 */
        private final Map<String, Router> conditionalEdges = new LinkedHashMap<>();

        private final String start;
        private static final String END = "__END__";

        /** 节点函数：每个节点在 State 上读输入、写产出。 */
        interface NodeFn {
            void run(Map<String, Object> state);
        }

        /** 条件边路由器：读 State，返回下一跳节点名。 */
        interface Router {
            String next(Map<String, Object> state);
        }

        // ---------------- 工具协议（标准 Function Calling） ----------------

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

        /** 路由节点的结构化选择工具：让模型选分支。 */
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
            this.start = "classify";
            buildGraph();
        }

        /**
         * 声明式建图：拓扑即数据。
         * 想加一个节点、改一条分支、甚至把图改成带环的，只改这里 —— 执行器 chat() 一行不动。
         */
        private void buildGraph() {
            nodes.put("classify", this::classifyNode);
            nodes.put("tool", this::toolNode);
            nodes.put("faq", this::faqNode);
            nodes.put("polish", this::polishNode);

            conditionalEdges.put("classify", state -> String.valueOf(state.getOrDefault("next", "faq")));
            edges.put("tool", "polish");
            edges.put("faq", "polish");
            edges.put("polish", END);
        }

        @Override
        public String name() {
            return "Step16 · 工作流状态机（LangGraph 思想）";
        }

        /**
         * 图执行器：从 START 沿边走到 END，与任何具体业务解耦。
         * 每一跳：执行节点 → 找下一跳（条件边优先，固定边兜底）→ 保险丝盯着跳数。
         */
        @Override
        public String chat(String input) {
            lastTrace.clear();

            // State：在节点之间流转的共享状态
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("input", input);
            state.put("answer", "");
            state.put("observation", "");

            String current = start;
            List<String> path = new ArrayList<>();
            for (int hop = 1; current != null && !END.equals(current); hop++) {
                if (hop > MAX_HOPS) {
                    lastTrace.add(Map.of("type", "guard", "label", "🔥 保险丝熔断",
                            "detail", "图执行超过 " + MAX_HOPS + " 跳，疑似成环失控，强制终止"));
                    return String.valueOf(state.get("answer"));
                }

                NodeFn node = nodes.get(current);
                if (node == null) {
                    lastTrace.add(Map.of("type", "warn", "label", "⚠️ 未知节点", "detail", current + "（路由到了未注册的节点）"));
                    return String.valueOf(state.get("answer"));
                }

                node.run(state);
                path.add(current);

                // 找下一跳：条件边优先（模型路由），固定边兜底（拓扑直达）
                Router router = conditionalEdges.get(current);
                if (router != null) {
                    String next = router.next(state);
                    lastTrace.add(Map.of("type", "edge", "label", "条件边",
                            "detail", current + " --" + next + "--> " + (END.equals(next) ? "END" : next + " 节点")));
                    current = next;
                } else {
                    String next = edges.get(current);
                    if (next != null && !END.equals(next)) {
                        lastTrace.add(Map.of("type", "edge", "label", "固定边", "detail", current + " --> " + next));
                    }
                    current = next;
                }
            }

            lastTrace.add(Map.of(
                    "type", "edge",
                    "label", "END",
                    "detail", "执行路径: " + String.join(" → ", path) + " → END"));

            return String.valueOf(state.get("answer"));
        }

        // ---------------- 四个节点函数：只读写 State，对图的拓扑一无所知 ----------------

        private String prompt(String node) {
            return "[节点:" + node + "] ";
        }

        /** 路由节点：choose_route 结构化选择 —— 服务端保证结构，不猜自由文本 JSON。 */
        private void classifyNode(Map<String, Object> state) {
            ChatResponse routed = model.chat(List.of(
                    Message.system(prompt("classify") + "你是意图分类器。调用 choose_route 选择下一个节点。"),
                    Message.user(String.valueOf(state.get("input")))), ROUTER_TOOLS);
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

        /**
         * 工具节点：单轮 Function Calling —— 模型决定调哪个工具、填什么参数；
         * 节点只执行一轮、不循环，转不转由图的拓扑说了算（与 ReAct 的本质区别）。
         */
        private void toolNode(Map<String, Object> state) {
            ChatResponse reply = model.chat(List.of(
                    Message.system(prompt("tool") + "你是工具节点。根据用户输入调用合适的工具（get_weather 或 calculator）。"),
                    Message.user(String.valueOf(state.get("input")))), TOOLS);
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

        /** FAQ 节点：纯生成，不需要工具，不传 tools 字段。 */
        private void faqNode(Map<String, Object> state) {
            String answer = model.chat(List.of(
                    Message.system(prompt("faq") + "你是 FAQ 问答节点，直接简洁回答常见问题。"),
                    Message.user(String.valueOf(state.get("input")))));
            state.put("answer", answer);
            lastTrace.add(Map.of("type", "node", "label", "🔹 节点 faq", "detail", clip(answer, 40)));
        }

        /** 汇合润色节点：tool 分支的 observation 在这里转为最终回答。 */
        private void polishNode(Map<String, Object> state) {
            String obs = String.valueOf(state.get("observation"));
            String base = obs.isEmpty()
                    ? String.valueOf(state.get("answer"))
                    : "根据查询结果：" + obs;
            String answer = model.chat(List.of(
                    Message.system(prompt("polish") + """
                            你是润色节点。基于前序节点产出整理最终回答，保持事实不变、表达自然、50 字以内。
                            前序产出: %s
                            """.formatted(base)),
                    Message.user(String.valueOf(state.get("input")))));
            state.put("answer", answer);
            lastTrace.add(Map.of("type", "node", "label", "🔹 节点 polish", "detail", "最终回答已生成"));
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
