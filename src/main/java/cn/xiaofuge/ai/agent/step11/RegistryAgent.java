package cn.xiaofuge.ai.agent.step11;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.ChatResponse;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;
import cn.xiaofuge.ai.llm.ToolCall;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step11 · 工具注册表 —— 用「协议 + 注册表」取代写死的 switch。
 * <p>
 * 与 Step04 的三点不同：
 * <ol>
 *   <li>Step04 的工具清单手写成常量、执行写死在 switch；这里工具实现
 *       {@link ToolDefinition} 协议，注册进 {@link ToolRegistry}；</li>
 *   <li>请求体 tools 字段由注册表动态生成（{@link ToolRegistry#toolSchemas()}）——
 *       注册即生效、注销即消失，模型能调用什么完全由注册表当前内容决定；</li>
 *   <li>执行器只做 lookup + execute，不再认识任何具体工具 ——
 *       模型发来 tool_calls，按名字查注册表执行，结果按 tool_call_id 回填。</li>
 * </ol>
 * 对话主循环与 Step04 同构，走的是<b>同一条真实 Function Calling 链路</b>：
 * 请求带 tools（JSON Schema）→ 模型返回结构化 tool_calls → 注册表执行 →
 * role=tool 消息回填 → 模型给出最终回答。没有任何"提示词里写工具清单、
 * 让模型自由文本输出 JSON 再手工解析"的教学化简。
 * <p>
 * 试试本场景的本地指令：
 * <pre>
 *   查看工具清单          —— 看注册表当前内容（也是模型每次在 tools 字段里"看见"的清单）
 *   卸载：get_time        —— 用注销器摘除工具，再问时间模型就调不到它了
 * </pre>
 */
public interface RegistryAgent extends Agent {

    class Impl implements RegistryAgent {

        private final ChatModel model;
        private final ToolRegistry registry = new ToolRegistry();
        /** 每个工具的注销器：插件热卸载时宿主靠它回收注册项。 */
        private final Map<String, Runnable> disposers = new HashMap<>();
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        private static final int MAX_ROUNDS = 5;

        public Impl(ChatModel model) {
            this.model = model;
            registerDefaults();
        }

        /** 注册内置工具：注册这个动作本身，就是本场景的主角。 */
        private void registerDefaults() {
            disposers.put("get_weather", registry.register(new WeatherTool()));
            disposers.put("calculator", registry.register(new CalculatorTool()));
            disposers.put("get_time", registry.register(new TimeTool()));
        }

        @Override
        public String name() {
            return "Step11 · 工具注册表（ToolDefinition 协议）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();
            lastTrace.add(Map.of("type", "tool", "label", "注册表就绪",
                    "detail", "当前 " + registry.size() + " 个工具，tools 字段由注册表动态生成，无需改提示词代码"));

            // ---- 本地指令 1：查看工具清单（不经过模型，直接看注册表） ----
            if (input.contains("查看工具") || input.contains("工具清单")) {
                lastTrace.add(Map.of("type", "prompt", "label", "工具清单 promptCatalog()",
                        "detail", registry.promptCatalog().trim()));
                return "当前注册表中共有 " + registry.size() + " 个工具：\n" + registry.promptCatalog()
                        + "\n每次对话时，这份清单会经 toolSchema() 组装成 OpenAI tools 字段随请求发给模型"
                        + " —— 注册即生效，卸载即消失。";
            }

            // ---- 本地指令 2：卸载工具（演示注销器） ----
            if (input.startsWith("卸载：") || input.startsWith("卸载:")) {
                String name = input.substring(3).trim();
                Runnable d = disposers.remove(name);
                if (d == null) {
                    return "工具 " + name + " 不在注册表中，无法卸载。当前清单：\n" + registry.promptCatalog();
                }
                d.run();
                lastTrace.add(Map.of("type", "guard", "label", "注销器执行 disposer.run()",
                        "detail", "工具 " + name + " 已从注册表摘除，剩余 " + registry.size() + " 个"));
                return "已卸载工具 " + name + "。注销器（disposer）就是注册时返回的 Runnable —— "
                        + "插件停止/卸载时，宿主正是靠它回收工具、系统提示词等注册项。\n当前清单：\n" + registry.promptCatalog()
                        + "\n下一次请求的 tools 字段里将不再出现这个工具，模型自然调不到它。";
            }

            // ---- 常规对话：注册表生成 tools → 模型结构化 tool_calls → 注册表执行 → 回填 ----
            // 提示词只负责人设：工具清单交给协议的 tools 字段，不写进提示词
            List<Message> messages = new ArrayList<>();
            messages.add(Message.system(SYSTEM_PROMPT));
            messages.add(Message.user(input));

            for (int round = 1; round <= MAX_ROUNDS; round++) {
                // 核心差异：tools 字段每次都由注册表现场生成 —— 注册表就是模型的能力边界
                ChatResponse reply = model.chat(messages, registry.toolSchemas());

                if (!reply.hasToolCalls()) {
                    lastTrace.add(Map.of("type", "final", "label", "模型给出最终回答",
                            "detail", "自然语言收尾，本轮无需更多工具"));
                    return reply.content();
                }

                // 协议保证的结构化调用请求：assistant 消息原样回传，工具结果按 tool_call_id 对号入座
                messages.add(Message.assistantToolCalls(reply.toolCalls()));
                for (ToolCall call : reply.toolCalls()) {
                    lastTrace.add(Map.of("type", "action", "label", "模型发起 tool_calls",
                            "detail", call.name() + " " + call.argumentsJson()));

                    // 执行器不认识任何具体工具，只查注册表 —— 卸载后的工具自然不可调用
                    var tool = registry.lookup(call.name());
                    String result;
                    if (tool.isEmpty()) {
                        result = "未知工具: " + call.name() + "。可用清单：\n" + registry.promptCatalog();
                        lastTrace.add(Map.of("type", "guard", "label", "注册表未命中",
                                "detail", "模型只能调用注册表里存在的工具"));
                    } else {
                        try {
                            Object args = Json.parse(call.argumentsJson() == null ? "{}" : call.argumentsJson());
                            result = tool.get().execute(toMap(args));
                        } catch (Exception e) {
                            result = "工具执行失败: " + e.getMessage();
                        }
                        lastTrace.add(Map.of("type", "observation", "label", "注册表执行 " + call.name(),
                                "detail", result));
                    }
                    messages.add(Message.tool(call.id(), result));
                }
            }
            return "已达最大工具轮数（" + MAX_ROUNDS + "），为防失控提前终止。";
        }

        private static final String SYSTEM_PROMPT = """
                你是一个可以调用工具的智能体。可用工具以 tools 字段随请求提供；
                需要实时数据或精确计算时调用工具，拿到结果后用自然语言回答用户。
                """;

        @Override
        public void reset() {
            registry.clear();
            disposers.clear();
            registerDefaults();
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> toMap(Object o) {
            Map<String, Object> m = new HashMap<>();
            if (o instanceof Map<?, ?> mm) m.putAll((Map<? extends String, ?>) mm);
            return m;
        }

        @Override
        public List<Map<String, Object>> trace() {
            return List.copyOf(lastTrace);
        }
    }

    // ------------------------------------------------------------------ 内置工具：三个 ToolDefinition 实现

    /** 天气查询工具：实现协议四件套，即插即用。 */
    record WeatherTool() implements ToolDefinition {
        private static final Map<String, String> DATA = Map.of(
                "北京", "晴，26℃", "上海", "多云，28℃", "广州", "雷阵雨，31℃",
                "深圳", "阵雨，30℃", "杭州", "晴，27℃", "成都", "阴，22℃");

        @Override public String name() { return "get_weather"; }
        @Override public String description() { return "查询指定城市的实时天气"; }
        @Override public Map<String, Object> parameters() {
            return Map.of("city", Map.of("type", "string", "description", "城市名，如：北京"));
        }
        @Override public String execute(Map<String, Object> args) {
            String city = String.valueOf(args.getOrDefault("city", "")).trim();
            return DATA.getOrDefault(city, "未收录城市 " + city + " 的数据");
        }
    }

    /** 四则运算工具。 */
    record CalculatorTool() implements ToolDefinition {
        @Override public String name() { return "calculator"; }
        @Override public String description() { return "计算四则运算，如 12*8"; }
        @Override public Map<String, Object> parameters() {
            return Map.of("expression", Map.of("type", "string", "description", "四则运算表达式，如 12*8"));
        }
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

    /** 时间工具。 */
    record TimeTool() implements ToolDefinition {
        @Override public String name() { return "get_time"; }
        @Override public String description() { return "获取当前时间"; }
        @Override public Map<String, Object> parameters() { return Map.of(); }
        @Override public String execute(Map<String, Object> args) {
            return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
    }

    /** 本地直接测试入口：java -cp target/classes cn.xiaofuge.ai.agent.step11.RegistryAgent [你的问题] */
    public static void main(String[] args) {
        cn.xiaofuge.ai.llm.ChatModel model = cn.xiaofuge.ai.llm.Models.openAiOrNull();
        boolean realModel = model != null;
        if (!realModel) model = cn.xiaofuge.ai.llm.Models.mock("demo");
        String input = args.length > 0 ? String.join(" ", args) : "帮我查一下北京天气，然后推荐穿搭";

        Impl agent = new Impl(model);
        String answer = agent.chat(input);

        System.out.println("[模型] " + (realModel
                ? "真实 LLM（OpenAI 兼容协议，配置见 config.properties）"
                : "Mock 演示模型（未检测到可用 LLM 配置，回退到离线演示）"));
        System.out.println("[输入] " + input);

        // 注册表里现在有什么，模型在 tools 字段里就能看到什么 —— 这就是本场景的主角
        System.out.println("\n[注册表] 当前注册的工具（即随请求发出的 tools 清单）：");
        System.out.println(agent.registry.promptCatalog().trim()
                .replaceAll("(?m)^- ", "  · "));

        System.out.println("\n[输出] " + answer);

        // 回放本轮 Function Calling 链路：模型调了哪些工具、注册表执行出了什么结果
        System.out.println("\n[执行轨迹] 本轮工具调用链路：");
        for (Map<String, Object> step : agent.trace()) {
            String label = String.valueOf(step.get("label"));
            String detail = String.valueOf(step.get("detail")).replaceAll("\\n+", "  ⏎  ");
            System.out.println("  -> [" + step.get("type") + "] " + label + "：\n     " + detail);
        }
    }
}
