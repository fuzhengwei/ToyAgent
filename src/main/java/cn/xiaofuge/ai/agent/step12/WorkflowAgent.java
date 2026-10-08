package cn.xiaofuge.ai.agent.step12;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;
import cn.xiaofuge.ai.agent.step03.ReActAgent.MockWeather;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step12 · 工作流智能体 —— LangGraph 式的状态机编排。
 * <p>
 * ReAct 是"模型说了算"的自由循环，但很多业务需要"流程说了算"的
 * 确定性编排。把智能体拆成节点（Node）与边（Edge）：
 * 每个节点是一段能力（分类/工具/问答/润色），条件边决定流转，
 * 状态（State）在节点间传递 —— 这就是 LangGraph 的核心思想，
 * 也是生产系统中"可控性"与"灵活性"的平衡点。
 */
public interface WorkflowAgent extends Agent {

    class Impl implements WorkflowAgent {

        private final ChatModel model;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        public Impl(ChatModel model) {
            this.model = model;
        }

        @Override
        public String name() {
            return "Step12 · 工作流状态机（LangGraph 思想）";
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
                    // 意图分类节点：模型输出路由决策
                    String raw = model.chat(List.of(
                            Message.system(nodePrompt + """
                                    你是意图分类器，只输出 JSON：{"next": "faq|tool", "reason": "一句话"}
                                    涉及天气或四则运算 → tool，其他 → faq
                                    """),
                            Message.user(input))).trim();
                    Object parsed = tryParse(raw);
                    String next = Json.str(parsed, "next");
                    state.put("next", next == null ? "faq" : next);
                    lastTrace.add(Map.of("type", "node", "label", "🔹 节点 classify", "detail", "路由决策 → " + state.get("next")));
                }
                case "tool" -> {
                    Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([+\\-*/])\\s*(\\d+(?:\\.\\d+)?)").matcher(input);
                    String observation;
                    if (m.find()) {
                        double a = Double.parseDouble(m.group(1));
                        double b = Double.parseDouble(m.group(3));
                        double r = switch (m.group(2)) {
                            case "+" -> a + b;
                            case "-" -> a - b;
                            case "*" -> a * b;
                            case "/" -> b == 0 ? Double.NaN : a / b;
                            default -> Double.NaN;
                        };
                        observation = "计算结果 " + (Double.isNaN(r) ? "失败" : r == Math.floor(r) ? String.valueOf((long) r) : String.valueOf(r));
                    } else {
                        String city = input.contains("上海") ? "上海" : input.contains("杭州") ? "杭州"
                                : input.contains("成都") ? "成都" : "北京";
                        observation = "「" + city + "」" + MockWeather.query(city);
                    }
                    state.put("observation", observation);
                    lastTrace.add(Map.of("type", "node", "label", "🔹 节点 tool", "detail", observation));
                }
                case "faq" -> {
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

        private Object tryParse(String raw) {
            try {
                return Json.parse(raw.replaceFirst("^```\\w*\\s*", "").replaceFirst("```\\s*$", "").trim());
            } catch (Exception e) {
                return Json.obj();
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
}
