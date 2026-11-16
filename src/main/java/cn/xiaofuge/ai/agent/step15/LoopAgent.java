package cn.xiaofuge.ai.agent.step15;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.agent.step03.ReActAgent.MockWeather;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.ChatResponse;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;
import cn.xiaofuge.ai.llm.ToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p>
 * 工具调用走<b>标准 Function Calling 协议</b>（tools 字段 + 结构化 tool_calls +
 * role=tool 回填），与 Step04/Step10 一致 —— 生产运行时不会从自由文本里"猜"JSON。
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

        /** 提示词只负责人设 —— 工具清单交给协议的 tools 字段，不写进提示词。 */
        private static final String SYSTEM_PROMPT = """
                你是运行在受控运行时里的智能体。需要实时数据或精确计算时调用工具，
                拿到工具结果后再用自然语言回答用户。
                """;

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

        /** 工具清单：随每次请求发给模型 —— 模型能调用什么，完全由这份清单决定。 */
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

            // ---------- Agent Loop（标准 Function Calling） ----------
            List<Message> messages = new ArrayList<>();
            messages.add(Message.system(SYSTEM_PROMPT));
            messages.add(Message.user(input));

            for (int step = 1; step <= MAX_STEPS; step++) {
                ChatResponse reply;
                try {
                    reply = model.chat(messages, TOOLS);
                } catch (Exception e) {
                    lastTrace.add(Map.of("type", "warn", "label", "⚠️ 异常兜底", "detail", "模型调用失败: " + e.getMessage()));
                    return "运行时异常已兜底，请稍后重试。";
                }

                // 模型给出最终回答 → 输出守卫 → 放行
                if (!reply.hasToolCalls()) {
                    return outputGuard(reply.content(), step);
                }

                // 结构化 tool_calls：assistant 消息原样回传，结果按 tool_call_id 对号入座
                messages.add(Message.assistantToolCalls(reply.toolCalls()));
                for (ToolCall call : reply.toolCalls()) {
                    lastTrace.add(Map.of("type", "tool", "label", "tool_calls " + step, "detail", call.name() + " " + call.argumentsJson()));

                    // 工具异常不拖垮循环：降级为错误说明回填给模型
                    String observation;
                    try {
                        observation = execute(call.name(), call.argumentsJson());
                    } catch (Exception e) {
                        observation = "工具执行异常: " + e.getMessage();
                        lastTrace.add(Map.of("type", "warn", "label", "⚠️ 工具异常", "detail", observation));
                    }
                    lastTrace.add(Map.of("type", "observation", "label", "Observation " + step, "detail", observation));
                    messages.add(Message.tool(call.id(), observation));
                }
            }

            // ---------- 保险丝熔断 ----------
            lastTrace.add(Map.of("type", "guard", "label", "🔥 保险丝熔断", "detail", "达到最大步数 " + MAX_STEPS + "，强制终止循环"));
            return "已达到最大执行步数（" + MAX_STEPS + "），为控制成本与风险强制终止。";
        }

        /** 输出守卫：合规检查通过才放行最终回答。 */
        private String outputGuard(String fin, int step) {
            for (String word : BLOCKED) {
                if (fin != null && fin.toLowerCase().contains(word)) {
                    lastTrace.add(Map.of("type", "guard", "label", "⛔ 输出守卫拦截", "detail", "回答命中敏感词，已替换为安全话术"));
                    return "回答未通过输出合规检查，已按安全策略处理。";
                }
            }
            lastTrace.add(Map.of("type", "final", "label", "✅ 输出守卫通过 · Final", "detail", "循环共执行 " + step + " 步"));
            return fin;
        }

        private String execute(String tool, String argsJson) {
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
        }

        @Override
        public List<Map<String, Object>> trace() {
            return List.copyOf(lastTrace);
        }
    }

    /** 本地直接测试入口：java -cp target/classes cn.xiaofuge.ai.agent.step15.LoopAgent [你的问题] */
    public static void main(String[] args) {
        cn.xiaofuge.ai.llm.ChatModel model = cn.xiaofuge.ai.llm.Models.openAiOrNull();
        if (model == null) model = cn.xiaofuge.ai.llm.Models.mock("demo");
        String input = args.length > 0 ? String.join(" ", args) : "查一下杭州天气，顺便算一下 12*8 等于多少";
        System.out.println("[输入] " + input);
        System.out.println("[输出] " + new Impl(model).chat(input));
    }
}
