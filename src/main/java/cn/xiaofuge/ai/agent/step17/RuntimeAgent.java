package cn.xiaofuge.ai.agent.step17;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.agent.step11.ToolDefinition;
import cn.xiaofuge.ai.agent.step11.ToolRegistry;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step17 · ReAct 运行时 —— turn/step 两级循环 + 上下文裁剪 + TurnEndReason。
 * <p>
 * 参考 deepseek-harness-java 的 ReactLoopAgent（994 行）拆出的教学骨架，三个核心机制：
 * <ol>
 *   <li><b>turn/step 两级循环</b>：一次 chat() = 一个 turn；turn 内的 while 是 step 循环。
 *       与 Step03 的区别：工具执行完不结束回合，而是<b>续步</b>（mid-turn continuation）——
 *       循环只能以 TurnEndReason 收场；</li>
 *   <li><b>上下文裁剪 truncateToBudget</b>：每次调模型前估算 token（≈字符数/4），
 *       超预算就从最老的非系统消息开始丢弃（保底留最近 4 条）。多轮对话后
 *       在轨迹里能亲眼看到「上下文裁剪」事件；</li>
 *   <li><b>步数保险丝</b>：单回合最多 {@link #MAX_STEPS_PER_TURN} 步，超限以
 *       TurnEndReason.MaxSteps 收场（发「死循环测试」可亲眼看到保险丝起跳）。</li>
 * </ol>
 * 工具直接复用 Step11 的 ToolRegistry —— 运行时与工具协议天然解耦。
 */
public interface RuntimeAgent extends Agent {

    /** 单回合步数保险丝（dsh-java 同款思想，生产值通常为 50）。 */
    int MAX_STEPS_PER_TURN = 6;
    /** 上下文 token 预算（教学值调小以便演示，约 4 轮触发；估算规则：token ≈ 字符数 / 4）。 */
    int BUDGET_TOKENS = 100;
    /** 裁剪时保底保留的最近消息条数。 */
    int KEEP_MIN = 4;

    class Impl implements RuntimeAgent {

        private final ChatModel model;
        private final ToolRegistry registry = new ToolRegistry();
        /** 跨对话的会话历史：运行时的上下文管理正是作用在它上面。 */
        private final List<Message> history = new ArrayList<>();
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        public Impl(ChatModel model) {
            this.model = model;
            registry.register(new WeatherTool());
            registry.register(new CalculatorTool());
            registry.register(new EchoTool());
        }

        @Override
        public String name() {
            return "Step17 · ReAct 运行时（turn/step + 上下文裁剪 + TurnEndReason）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();
            history.add(Message.user(input));

            // ===== 机制 2：调模型前先做上下文预算裁剪 =====
            trimToBudget();

            // ===== 机制 1：turn 内的 step 循环，只能以 TurnEndReason 收场 =====
            TurnEndReason reason = runTurn(input);

            // 回合结束原因入轨迹，并作为 user 消息回写历史（下一轮模型可见）
            String label;
            boolean fused = reason instanceof TurnEndReason.MaxSteps;
            if (reason instanceof TurnEndReason.Completed c) {
                label = "Turn 结束 · COMPLETED —— " + c.summary();
            } else if (reason instanceof TurnEndReason.MaxSteps m) {
                label = "Turn 结束 · MAX_STEPS —— 达到保险丝 " + m.steps() + " 步，强制收敛";
            } else {
                label = "Turn 结束 · ERROR —— " + ((TurnEndReason.Error) reason).message();
            }
            lastTrace.add(Map.of("type", fused ? "guard" : "final",
                    "label", label, "detail", "结束原因以 sealed 类型 TurnEndReason 统一表达，instanceof 模式匹配逐一处理"));
            return lastAnswer;
        }

        private String lastAnswer = "";

        /** 一个 turn：while 循环执行 step，直到出现结束原因。 */
        private TurnEndReason runTurn(String input) {
            for (long step = 1; step <= MAX_STEPS_PER_TURN; step++) {
                // ---- 组装请求：系统提示词 + 会话历史（裁剪后） ----
                List<Message> req = new ArrayList<>();
                req.add(Message.system(SYSTEM_PROMPT + registry.promptCatalog()
                        + "\n决策规则：\n- 需要工具时，只输出 JSON："
                        + "{\"thought\": \"思考\", \"action\": \"工具名\", \"action_input\": \"参数\"}\n"
                        + "- 信息足够时，只输出 JSON：{\"thought\": \"思考\", \"final\": \"最终答案\"}\n"
                        + "不要输出 JSON 以外的内容。"));
                history.forEach(m -> req.add(m));

                String raw;
                try {
                    raw = model.chat(req).trim();
                } catch (Exception e) {
                    return new TurnEndReason.Error("模型调用失败: " + e.getMessage());
                }
                Object parsed = tryParse(raw);
                lastTrace.add(Map.of("type", "thought", "label", "Step " + step + " · 思考",
                        "detail", firstLine(str(parsed, "thought"), raw)));

                if (!(parsed instanceof Map<?, ?>)) {
                    // 模型失约：按协议约定视为最终答案
                    lastAnswer = raw;
                    history.add(Message.assistant(raw));
                    return new TurnEndReason.Completed("模型直接给出答案");
                }

                String fin = str(parsed, "final");
                if (fin != null) {
                    lastAnswer = fin;
                    history.add(Message.assistant(raw));
                    return new TurnEndReason.Completed("模型给出最终答案");
                }

                // ---- 工具调用：执行后续步（不结束回合，这就是"续步"） ----
                String action = str(parsed, "action");
                String actionInput = str(parsed, "action_input");
                lastTrace.add(Map.of("type", "action", "label", "Step " + step + " · 行动",
                        "detail", action + "(" + actionInput + ")"));

                String observation = registry.lookup(action)
                        .map(t -> {
                            try {
                                return t.execute(Map.of("city", nz(actionInput), "expression", nz(actionInput), "text", nz(actionInput)));
                            } catch (Exception e) {
                                return "工具执行失败: " + e.getMessage();
                            }
                        })
                        .orElse("未知工具: " + action + "。可用清单：\n" + registry.promptCatalog());
                lastTrace.add(Map.of("type", "observation", "label", "Step " + step + " · 观察", "detail", observation));

                // 观察结果进历史 —— 下一 step 模型自然"看见"，循环继续
                history.add(Message.assistant(raw));
                history.add(Message.user("Observation: " + observation));
                trimToBudget();
            }
            lastAnswer = "已达最大步数 " + MAX_STEPS_PER_TURN + "，本回合被保险丝强制终止。"
                    + "生产系统的 ReAct 循环必须有这层兜底（dsh-java 默认 50 步）。";
            return new TurnEndReason.MaxSteps(MAX_STEPS_PER_TURN);
        }

        // ===== 机制 2：上下文预算裁剪（truncateToBudget，~15 行核心逻辑） =====

        /** 估算 token：教学化近似 —— token ≈ 字符数 / 4（dsh-java 同款估算）。 */
        private static int estimateTokens(List<Message> msgs) {
            return msgs.stream().mapToInt(m -> m.content().length() / 4).sum();
        }

        private void trimToBudget() {
            if (estimateTokens(history) <= BUDGET_TOKENS || history.size() <= KEEP_MIN) return;
            // 从最老的消息开始丢（历史里没有系统消息，系统提示词在请求时单独加）
            int drop = 0;
            while (estimateTokens(history) > BUDGET_TOKENS && history.size() > KEEP_MIN) {
                history.remove(0);
                drop++;
            }
            lastTrace.add(Map.of("type", "compress", "label", "上下文裁剪 truncateToBudget",
                    "detail", "预估 token 超过预算 " + BUDGET_TOKENS + "，丢弃最早 " + drop
                            + " 条消息，保留最近 " + history.size() + " 条，现估 "
                            + estimateTokens(history) + " tokens"));
        }

        private static final String SYSTEM_PROMPT = """
                你是一个使用 ReAct 模式的智能体，运行在 turn/step 两级循环之上。
                可用工具（由注册表生成）：
                """;

        @Override
        public void reset() {
            history.clear();
            registry.clear();
            registry.register(new WeatherTool());
            registry.register(new CalculatorTool());
            registry.register(new EchoTool());
        }

        // ------------------------------------------------------------------ 工具

        record WeatherTool() implements ToolDefinition {
            private static final Map<String, String> DATA = Map.of(
                    "北京", "晴，26℃", "上海", "多云，28℃", "广州", "雷阵雨，31℃",
                    "深圳", "阵雨，30℃", "杭州", "晴，27℃", "成都", "阴，22℃");

            @Override public String name() { return "get_weather"; }
            @Override public String description() { return "查询指定城市的实时天气"; }
            @Override public Map<String, String> parameters() { return Map.of("city", "string，城市名"); }
            @Override public String execute(Map<String, Object> args) {
                String city = String.valueOf(args.getOrDefault("city", "")).trim();
                return DATA.getOrDefault(city, "未收录城市 " + city + " 的数据");
            }
        }

        record CalculatorTool() implements ToolDefinition {
            @Override public String name() { return "calculator"; }
            @Override public String description() { return "计算四则运算，如 12*8"; }
            @Override public Map<String, String> parameters() { return Map.of("expression", "string，算式"); }
            @Override public String execute(Map<String, Object> args) {
                Matcher m = Pattern.compile("\\s*(\\d+(?:\\.\\d+)?)\\s*([+\\-*/])\\s*(\\d+(?:\\.\\d+)?)\\s*")
                        .matcher(String.valueOf(args.getOrDefault("expression", "")));
                if (!m.matches()) return "无法解析表达式";
                double a = Double.parseDouble(m.group(1));
                double b = Double.parseDouble(m.group(3));
                return switch (m.group(2)) {
                    case "+" -> String.valueOf(a + b);
                    case "-" -> String.valueOf(a - b);
                    case "*" -> String.valueOf(a * b);
                    case "/" -> b == 0 ? "除数不能为 0" : String.valueOf(a / b);
                    default -> "不支持的运算符";
                };
            }
        }

        /** 永远回显的工具：专供「死循环测试」演示保险丝。 */
        record EchoTool() implements ToolDefinition {
            @Override public String name() { return "echo"; }
            @Override public String description() { return "回显输入文本（演示用）"; }
            @Override public Map<String, String> parameters() { return Map.of("text", "string，要回显的内容"); }
            @Override public String execute(Map<String, Object> args) {
                return "echo: " + args.getOrDefault("text", "");
            }
        }

        // ------------------------------------------------------------------ 工具方法

        private static String str(Object parsed, String key) {
            return parsed instanceof Map<?, ?> m ? (String) m.get(key) : null;
        }

        private static String nz(String s) {
            return s == null ? "" : s;
        }

        private static String firstLine(String a, String b) {
            String s = (a == null || a.isBlank()) ? b : a;
            int i = s.indexOf('\n');
            return i > 0 ? s.substring(0, i) : s;
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
