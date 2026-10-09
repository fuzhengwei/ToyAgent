package cn.xiaofuge.ai.agent.step15;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;
import cn.xiaofuge.ai.agent.step03.ReActAgent.MockWeather;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Step15 · Loop 运行时智能体 —— 生产级 Agent Loop 的骨架。
 * <p>
 * Step03 的 ReAct 循环是"裸奔"的：没有保险丝、没有安全检查。
 * 真实的智能体运行时（Runtime）在循环外面包了一层工程化外壳：
 * <ul>
 *   <li>输入守卫 —— 拦截敏感/恶意请求（Prompt 注入防护）；</li>
 *   <li>最大步数 —— 防止模型陷入死循环烧钱；</li>
 *   <li>输出守卫 —— 对最终回答做合规过滤；</li>
 *   <li>异常兜底 —— 工具失败不拖垮整个循环。</li>
 * </ul>
 * 模型负责聪明，运行时负责可靠。
 */
public interface LoopAgent extends Agent {

    int MAX_STEPS = 4;
    int MAX_INPUT_CHARS = 500;

    class Impl implements LoopAgent {

        private final ChatModel model;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        /** 敏感词黑名单（生产中应为规则引擎 + 分类模型）。 */
        private static final List<String> BLOCKED = List.of("密码", "攻击", "hack", "漏洞", "暴力", "炸药");

        private static final String SYSTEM_PROMPT = """
                你是运行在受控运行时里的智能体。每轮只输出 JSON：
                {"thought": "思考", "action": "get_weather|calculator", "action_input": "参数"}
                或 {"thought": "思考", "final": "最终答案"}
                可用工具：get_weather(city)、calculator(expression)
                """;

        public Impl(ChatModel model) {
            this.model = model;
        }

        @Override
        public String name() {
            return "Step15 · Agent Loop 运行时（守卫 + 保险丝）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();

            // ---------- 输入守卫 ----------
            if (input.length() > MAX_INPUT_CHARS) {
                lastTrace.add(Map.of("type", "guard", "label", "⛔ 输入守卫拦截", "detail", "输入超过 " + MAX_INPUT_CHARS + " 字符上限"));
                return "输入过长，已拦截（防止上下文爆炸与注入攻击面扩大）。";
            }
            for (String word : BLOCKED) {
                if (input.toLowerCase().contains(word)) {
                    lastTrace.add(Map.of("type", "guard", "label", "⛔ 输入守卫拦截", "detail", "命中敏感词: " + word + "（Prompt 注入/安全防护）"));
                    return "该请求涉及受限内容，已被输入守卫拦截。";
                }
            }
            lastTrace.add(Map.of("type", "guard", "label", "🛡️ 输入守卫通过", "detail", "长度 " + input.length() + " 字符，未命中敏感词"));

            // ---------- Agent Loop ----------
            List<Message> messages = new ArrayList<>();
            messages.add(Message.system(SYSTEM_PROMPT));
            messages.add(Message.user(input));

            for (int step = 1; step <= MAX_STEPS; step++) {
                String raw;
                try {
                    raw = model.chat(messages).trim();
                } catch (Exception e) {
                    lastTrace.add(Map.of("type", "warn", "label", "⚠️ 异常兜底", "detail", "模型调用失败: " + e.getMessage()));
                    return "运行时异常已兜底，请稍后重试。";
                }
                Object parsed = tryParse(raw);
                String thought = Json.str(parsed, "thought");
                if (thought != null) {
                    lastTrace.add(Map.of("type", "thought", "label", "Thought " + step, "detail", thought));
                }

                String fin = Json.str(parsed, "final");
                if (fin != null) {
                    // ---------- 输出守卫 ----------
                    for (String word : BLOCKED) {
                        if (fin.toLowerCase().contains(word)) {
                            lastTrace.add(Map.of("type", "guard", "label", "⛔ 输出守卫拦截", "detail", "回答命中敏感词，已替换为安全话术"));
                            return "回答未通过输出合规检查，已按安全策略处理。";
                        }
                    }
                    lastTrace.add(Map.of("type", "final", "label", "✅ 输出守卫通过 · Final", "detail", "循环共执行 " + step + " 步"));
                    return fin;
                }

                String action = Json.str(parsed, "action");
                String actionInput = Json.str(parsed, "action_input");
                lastTrace.add(Map.of("type", "action", "label", "Action " + step, "detail", action + "(" + actionInput + ")"));

                // 工具异常不拖垮循环
                String observation;
                try {
                    observation = execute(action, actionInput);
                } catch (Exception e) {
                    observation = "工具执行异常: " + e.getMessage();
                    lastTrace.add(Map.of("type", "warn", "label", "⚠️ 工具异常", "detail", observation));
                }
                lastTrace.add(Map.of("type", "observation", "label", "Observation " + step, "detail", observation));

                messages.add(Message.assistant(raw));
                messages.add(Message.user("Observation: " + observation));
            }

            lastTrace.add(Map.of("type", "guard", "label", "🔥 保险丝熔断", "detail", "达到最大步数 " + MAX_STEPS + "，强制终止循环"));
            return "已达到最大执行步数（" + MAX_STEPS + "），为控制成本与风险强制终止。";
        }

        private String execute(String action, String input) {
            return switch (action == null ? "" : action) {
                case "get_weather" -> MockWeather.query(input);
                case "calculator" -> {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("(\\d+(?:\\.\\d+)?)\\s*([+\\-*/])\\s*(\\d+(?:\\.\\d+)?)")
                            .matcher(input == null ? "" : input);
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
                default -> "未知工具: " + action;
            };
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
