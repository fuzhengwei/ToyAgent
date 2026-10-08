package cn.xiaofuge.ai.agent.step15;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step15 · 工具注册表 —— 用「协议 + 注册表」取代写死的 switch。
 * <p>
 * 与 Step04 的三点不同：
 * <ol>
 *   <li>工具实现 {@link ToolDefinition} 协议，注册进 {@link ToolRegistry}；</li>
 *   <li>系统提示词里的工具清单由注册表自动生成 —— 注册即生效、注销即消失；</li>
 *   <li>执行器只做 lookup + execute，不再认识任何具体工具。</li>
 * </ol>
 * 试试本场景的本地指令：
 * <pre>
 *   查看工具清单          —— 看注册表当前内容（也是模型每次看到的清单）
 *   卸载：get_time        —— 用注销器摘除工具，再问时间模型就说"没有该工具了"
 * </pre>
 */
public interface RegistryAgent extends Agent {

    class Impl implements RegistryAgent {

        private final ChatModel model;
        private final ToolRegistry registry = new ToolRegistry();
        /** 每个工具的注销器：插件热卸载时宿主靠它回收注册项。 */
        private final Map<String, Runnable> disposers = new HashMap<>();
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

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
            return "Step15 · 工具注册表（ToolDefinition 协议）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();
            lastTrace.add(Map.of("type", "tool", "label", "注册表就绪",
                    "detail", "当前 " + registry.size() + " 个工具，清单由注册表自动生成，无需改提示词代码"));

            // ---- 本地指令 1：查看工具清单（不经过模型，直接看注册表） ----
            if (input.contains("查看工具") || input.contains("工具清单")) {
                lastTrace.add(Map.of("type", "prompt", "label", "工具清单 promptCatalog()",
                        "detail", registry.promptCatalog().trim()));
                return "当前注册表中共有 " + registry.size() + " 个工具：\n" + registry.promptCatalog()
                        + "\n这份清单就是模型每次对话时在系统提示词里「看见」的内容 —— 注册即生效，卸载即消失。";
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
                        + "\n现在再问相关问题，模型将「看不见」这个工具。";
            }

            // ---- 常规对话：清单进提示词 → 模型决策 → 注册表执行 ----
            List<Message> messages = new ArrayList<>();
            messages.add(Message.system(SYSTEM_PROMPT_PREFIX + registry.promptCatalog()
                    + "\n决策规则：\n- 需要工具时，只输出 JSON：{\"tool\": \"工具名\", \"arguments\": {\"参数名\": \"值\"}}\n"
                    + "- 不需要工具时，直接用自然语言回答，不要输出 JSON。"));
            messages.add(Message.user(input));

            // 最多 2 轮：工具调用 → 结果回填 → 最终回答（与 Step04 同构，但执行走注册表）
            for (int round = 1; round <= 2; round++) {
                String raw = model.chat(messages).trim();
                Object decision = tryParse(raw);

                if (decision instanceof Map<?, ?> && Json.str(decision, "tool") != null) {
                    String toolName = Json.str(decision, "tool");
                    lastTrace.add(Map.of("type", "action", "label", "模型发起工具调用",
                            "detail", toolName + " " + Json.write(((Map<?, ?>) decision).get("arguments"))));

                    // 核心差异：执行器不认识任何具体工具，只查注册表
                    var tool = registry.lookup(toolName);
                    String result;
                    if (tool.isEmpty()) {
                        result = "未知工具: " + toolName + "。可用清单：\n" + registry.promptCatalog();
                        lastTrace.add(Map.of("type", "guard", "label", "注册表未命中",
                                "detail", "模型只能调用注册表里存在的工具 —— 卸载后的工具自然不可调用"));
                    } else {
                        try {
                            Object args = Json.parse(Json.write(((Map<?, ?>) decision).get("arguments")));
                            result = tool.get().execute(toMap(args));
                        } catch (Exception e) {
                            result = "工具执行失败: " + e.getMessage();
                        }
                        lastTrace.add(Map.of("type", "observation", "label", "注册表执行 " + toolName,
                                "detail", result));
                    }

                    messages.add(Message.assistant(raw));
                    messages.add(Message.user("工具 " + toolName + " 执行结果: " + result));
                    continue;
                }

                // 模型直接给出自然语言回答
                lastTrace.add(Map.of("type", "final", "label", "最终回答",
                        "detail", "模型基于注册表提供的工具完成决策"));
                return raw;
            }
            return "已达最大轮数，循环终止。";
        }

        private static final String SYSTEM_PROMPT_PREFIX = """
                你是一个可以调用工具的智能体。可用工具清单（由注册表自动生成）：
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

    // ------------------------------------------------------------------ 内置工具：三个 ToolDefinition 实现

    /** 天气查询工具：实现协议四件套，即插即用。 */
    record WeatherTool() implements ToolDefinition {
        private static final Map<String, String> DATA = Map.of(
                "北京", "晴，26℃", "上海", "多云，28℃", "广州", "雷阵雨，31℃",
                "深圳", "阵雨，30℃", "杭州", "晴，27℃", "成都", "阴，22℃");

        @Override public String name() { return "get_weather"; }
        @Override public String description() { return "查询指定城市的实时天气"; }
        @Override public Map<String, String> parameters() { return Map.of("city", "string，城市名，如 北京"); }
        @Override public String execute(Map<String, Object> args) {
            String city = String.valueOf(args.getOrDefault("city", "")).trim();
            return DATA.getOrDefault(city, "未收录城市 " + city + " 的数据");
        }
    }

    /** 四则运算工具。 */
    record CalculatorTool() implements ToolDefinition {
        @Override public String name() { return "calculator"; }
        @Override public String description() { return "计算四则运算，如 12*8"; }
        @Override public Map<String, String> parameters() { return Map.of("expression", "string，算式，如 12*8"); }
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
        @Override public Map<String, String> parameters() { return Map.of(); }
        @Override public String execute(Map<String, Object> args) {
            return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
    }
}
