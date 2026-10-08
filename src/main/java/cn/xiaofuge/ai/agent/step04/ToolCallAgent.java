package cn.xiaofuge.ai.agent.step04;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;
import cn.xiaofuge.ai.agent.step03.ReActAgent.MockWeather;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step04 · 工具调用（Function Calling）—— 给模型装上"手脚"。
 * <p>
 * 与 Step03 手写 ReAct 循环不同，本场景展示标准的工具调用交互：
 * 1. 在提示词中声明工具清单（名称、参数、用途）—— 即最简 Tool Schema；
 * 2. 模型判断需要工具时，返回结构化调用请求 {"tool": "...", "arguments": {...}}；
 * 3. 智能体执行工具，把结果作为新消息回填，再次请求模型生成最终回答。
 * <p>
 * 生产中 OpenAI 的 tools/tool_calls 协议、MCP 协议，本质都是这三步。
 */
public interface ToolCallAgent extends Agent {

    class Impl implements ToolCallAgent {

        private final ChatModel model;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        private static final String SYSTEM_PROMPT = """
                你是一个可以调用工具的智能体。可用工具：
                - get_weather(city): 查询指定城市的实时天气
                - calculator(expression): 计算四则运算，如 12*8

                决策规则：
                - 需要工具时，只输出 JSON：{"tool": "工具名", "arguments": {"参数名": "值"}}
                - 不需要工具时，直接用自然语言回答，不要输出 JSON。
                """;

        public Impl(ChatModel model) {
            this.model = model;
        }

        @Override
        public String name() {
            return "Step04 · 工具调用（Function Calling）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();
            List<Message> messages = new ArrayList<>();
            messages.add(Message.system(SYSTEM_PROMPT));
            messages.add(Message.user(input));

            // 第一轮：模型决定"要不要用工具"
            String first = model.chat(messages).trim();
            Object decision = tryParse(first);

            if (decision instanceof Map<?, ?> && Json.str(decision, "tool") != null) {
                String tool = Json.str(decision, "tool");
                String args = Json.write(((Map<?, ?>) decision).get("arguments"));
                lastTrace.add(Map.of("type", "tool", "label", "模型发起工具调用", "detail", tool + " " + args));

                // 第二步：执行工具
                String result = execute(tool, args);
                lastTrace.add(Map.of("type", "observation", "label", "工具返回结果", "detail", result));

                // 第三步：结果回填，让模型基于事实作答
                messages.add(Message.assistant(first));
                messages.add(Message.user("工具 " + tool + " 的执行结果: " + result));
                String answer = model.chat(messages);
                lastTrace.add(Map.of("type", "final", "label", "基于工具结果生成回答", "detail", "模型拿到 Observation 后组织最终回答"));
                return answer;
            }

            // 模型选择不调用工具，直接回答
            lastTrace.add(Map.of("type", "final", "label", "无需工具，直接回答", "detail", "模型判断自身知识足够"));
            return first;
        }

        private Object tryParse(String raw) {
            try {
                return Json.parse(raw.replaceFirst("^```\\w*\\s*", "").replaceFirst("```\\s*$", "").trim());
            } catch (Exception e) {
                return raw;
            }
        }

        private String execute(String tool, String argsJson) {
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

        @Override
        public List<Map<String, Object>> trace() {
            return List.copyOf(lastTrace);
        }
    }
}
