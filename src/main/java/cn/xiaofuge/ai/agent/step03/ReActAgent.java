package cn.xiaofuge.ai.agent.step03;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step03 · ReAct 智能体 —— 让模型学会"边想边做"。
 * <p>
 * ReAct = Reasoning + Acting。模型不再一次作答，而是输出结构化的
 * Thought（思考）→ Action（行动）→ 等待 Observation（观察结果），
 * 智能体执行工具后把结果喂回模型，循环直到模型给出 Final Answer。
 * 这是几乎所有智能体框架（LangChain、Agent Loop）的内核。
 */
public interface ReActAgent extends Agent {

    int MAX_STEPS = 5;

    class Impl implements ReActAgent {

        private final ChatModel model;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        /** 用系统提示词约定 ReAct 的输出协议（JSON 形式，便于解析）。 */
        private static final String SYSTEM_PROMPT = """
                你是一个使用 ReAct 模式解决问题的智能体。
                每一轮只输出一个 JSON 对象，格式二选一：
                1. 继续行动：{"thought": "你的思考", "action": "工具名", "action_input": "参数"}
                2. 给出答案：{"thought": "你的思考", "final": "最终答案"}
                可用工具：
                - get_weather(city): 查询城市天气
                - calculator(expression): 计算四则运算，如 12*8
                - get_time(): 获取当前时间
                不要输出 JSON 以外的内容。
                """;

        public Impl(ChatModel model) {
            this.model = model;
        }

        @Override
        public String name() {
            return "Step03 · ReAct 智能体（思考-行动-观察循环）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();
            List<Message> messages = new ArrayList<>();
            messages.add(Message.system(SYSTEM_PROMPT));
            messages.add(Message.user(input));

            for (int step = 1; step <= MAX_STEPS; step++) {
                // 1. 模型思考并决策
                String raw = model.chat(messages).trim();
                raw = stripCodeFence(raw);
                Object parsed;
                try {
                    parsed = Json.parse(raw);
                } catch (Exception e) {
                    // 模型没按协议输出 → 视为最终答案直接返回
                    lastTrace.add(Map.of("type", "final", "label", "Final Answer", "detail", raw));
                    return raw;
                }

                String thought = Json.str(parsed, "thought");
                lastTrace.add(Map.of("type", "thought", "label", "Thought " + step, "detail", thought == null ? "" : thought));

                // 2. 有 final → 循环结束
                String fin = Json.str(parsed, "final");
                if (fin != null) {
                    lastTrace.add(Map.of("type", "final", "label", "Final Answer", "detail", fin));
                    return fin;
                }

                // 3. 有 action → 执行工具，把观察结果喂回模型
                String action = Json.str(parsed, "action");
                String actionInput = Json.str(parsed, "action_input");
                lastTrace.add(Map.of("type", "action", "label", "Action " + step, "detail", action + "(" + actionInput + ")"));

                String observation = executeTool(action, actionInput);
                lastTrace.add(Map.of("type", "observation", "label", "Observation " + step, "detail", observation));

                messages.add(Message.assistant(raw));
                messages.add(Message.user("Observation: " + observation));
            }
            String msg = "已达最大步数 " + MAX_STEPS + "，循环终止（生产系统必须有这个保险丝）。";
            lastTrace.add(Map.of("type", "guard", "label", "达到最大步数", "detail", msg));
            return msg;
        }

        /** 工具注册表：本场景仅 3 个本地工具，执行器就是一个 switch。 */
        private String executeTool(String action, String input) {
            try {
                return switch (action == null ? "" : action) {
                    case "get_weather" -> MockWeather.query(input);
                    case "calculator" -> String.valueOf(calc(input));
                    case "get_time" -> LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    default -> "未知工具: " + action;
                };
            } catch (Exception e) {
                return "工具执行失败: " + e.getMessage();
            }
        }

        /** 教学用四则运算求值（仅支持 a op b）。 */
        private double calc(String expr) {
            Matcher m = Pattern.compile("\\s*(\\d+(?:\\.\\d+)?)\\s*([+\\-*/])\\s*(\\d+(?:\\.\\d+)?)\\s*").matcher(expr);
            if (!m.matches()) throw new IllegalArgumentException("无法解析表达式: " + expr);
            double a = Double.parseDouble(m.group(1));
            String op = m.group(2);
            double b = Double.parseDouble(m.group(3));
            return switch (op) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> {
                    if (b == 0) throw new ArithmeticException("除数不能为 0");
                    yield a / b;
                }
                default -> throw new IllegalArgumentException("不支持的运算符 " + op);
            };
        }

        private String stripCodeFence(String s) {
            if (s.startsWith("```")) {
                s = s.replaceFirst("^```\\w*\\s*", "").replaceFirst("```\\s*$", "").trim();
            }
            return s;
        }

        @Override
        public List<Map<String, Object>> trace() {
            return List.copyOf(lastTrace);
        }
    }

    /** 本地 Mock 天气数据。 */
    final class MockWeather {
        private static final Map<String, String> DATA = Map.of(
                "北京", "晴，26℃", "上海", "多云，28℃", "广州", "雷阵雨，31℃",
                "深圳", "阵雨，30℃", "杭州", "晴，27℃", "成都", "阴，22℃");

        public static String query(String city) {
            String key = (city == null ? "" : city.trim());
            return DATA.getOrDefault(key, "未收录城市 " + key + " 的数据");
        }
    }
}
