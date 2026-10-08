package cn.xiaofuge.ai.agent.step06;

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
 * Step06 · 意图路由智能体 —— Agent 的大脑：先识别意图，再决策走向。
 * <p>
 * 复杂系统不会把所有问题都丢给一个循环：先让模型做一次"意图识别"
 * （输出 JSON 分类），再由决策中枢把请求路由到不同处理器 ——
 * 天气走工具、算式走计算器、闲聊走对话。这正是 Agent Loop 中
 * Router/Dispatcher 的原型，也是多智能体系统编排的前置能力。
 */
public interface RouterAgent extends Agent {

    class Impl implements RouterAgent {

        private final ChatModel model;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        private static final String ROUTER_PROMPT = """
                你是意图识别路由器。分析用户输入，只输出 JSON：
                {"intent": "weather|math|chat", "reason": "一句话理由"}
                - weather: 查询天气
                - math: 四则运算
                - chat: 其他一切对话
                """;

        public Impl(ChatModel model) {
            this.model = model;
        }

        @Override
        public String name() {
            return "Step06 · 意图识别与决策中枢（路由）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();

            // 1. 意图识别
            String raw = model.chat(List.of(
                    Message.system(ROUTER_PROMPT),
                    Message.user(input))).trim();
            Object decision = tryParse(raw);
            String intent = Json.str(decision, "intent");
            if (intent == null) intent = "chat";
            lastTrace.add(Map.of(
                    "type", "intent",
                    "label", "意图识别",
                    "detail", intent + "（" + (Json.str(decision, "reason") == null ? "" : Json.str(decision, "reason")) + "）"));

            // 2. 按意图路由到不同处理器
            return switch (intent) {
                case "weather" -> {
                    String city = extractCity(input);
                    lastTrace.add(Map.of("type", "action", "label", "路由 → 天气工具", "detail", "get_weather(" + city + ")"));
                    String result = MockWeather.query(city);
                    lastTrace.add(Map.of("type", "observation", "label", "工具返回", "detail", result));
                    yield "「" + city + "」现在" + result + "。";
                }
                case "math" -> {
                    lastTrace.add(Map.of("type", "action", "label", "路由 → 计算器", "detail", input));
                    yield "计算结果：" + calc(input) + "。";
                }
                default -> {
                    lastTrace.add(Map.of("type", "action", "label", "路由 → 对话模型", "detail", "无工具直答"));
                    yield model.chat(List.of(
                            Message.system("你是一个友好的中文聊天助手。"),
                            Message.user(input)));
                }
            };
        }

        private String extractCity(String text) {
            for (String city : List.of("北京", "上海", "广州", "深圳", "杭州", "成都")) {
                if (text.contains(city)) return city;
            }
            return "北京";
        }

        private String calc(String expr) {
            Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([+\\-*/])\\s*(\\d+(?:\\.\\d+)?)").matcher(expr);
            if (!m.find()) return "未识别的算式";
            double a = Double.parseDouble(m.group(1));
            double b = Double.parseDouble(m.group(3));
            double r = switch (m.group(2)) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> b == 0 ? Double.NaN : a / b;
                default -> Double.NaN;
            };
            return Double.isNaN(r) ? "无法计算" : (r == Math.floor(r) ? String.valueOf((long) r) : String.valueOf(r));
        }

        private Object tryParse(String raw) {
            try {
                return Json.parse(raw.replaceFirst("^```\\w*\\s*", "").replaceFirst("```\\s*$", "").trim());
            } catch (Exception e) {
                return Json.obj();
            }
        }

        @Override
        public List<Map<String, Object>> trace() {
            return List.copyOf(lastTrace);
        }
    }
}
