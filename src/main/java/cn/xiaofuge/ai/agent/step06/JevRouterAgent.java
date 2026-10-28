package cn.xiaofuge.ai.agent.step06;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.JevModel;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;
import cn.xiaofuge.ai.llm.Models;
import cn.xiaofuge.ai.agent.step03.ReActAgent.MockWeather;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step06+ · Jev 决策模型路由 —— 把「意图判断」从大模型手里拿走。
 * <p>
 * <b>Jev 是什么？</b><br>
 * Jev 是 TypeSafe 的 System One（系统一）决策模型，与聊天大模型（System Two）
 * 的分工是：LLM 生成给人看的文本，Jev 生成给程序用的判断——
 * "Decisions, not strings"，只回答你框死的选择题，且每个答案带校准过的置信度。
 * 协议细节、三种题型（choice / noul / score）与 HTTP 对接实现见
 * {@link cn.xiaofuge.ai.llm.JevModel}；配置读取见 {@link Models#jevOrNull()}。
 * <p>
 * <b>为什么路由场景天然适合 Jev？</b><br>
 * Agent 循环的第一行代码往往就是一道判断："用户这句话要干什么？"
 * 让聊天 LLM 来判有四个坑：可能返回带围栏的代码块；没有"我不确定"，
 * 永远给你一个看起来很确定的答案；没有概率可打日志；判错了你不知道。
 * Jev 把这道判断从推理路径上前置移出——高置信时不再付一整轮 LLM 推理的
 * 延迟与成本，判断可校准、可留痕、可回放。
 * <p>
 * <b>本场景的编排（对比 {@link RouterAgent} 的三处升级）：</b>
 * <ol>
 *   <li>意图识别不再让 LLM 猜 JSON，改为调 Jev 决策端口（choice 题型）；</li>
 *   <li>新增<b>置信度门控</b>：confidence ≥ {@link #CONFIDENCE_GATE} 直接采信路由；
 *       低于阈值不硬猜，显式回落到 LLM 判断（兜底路径在 trace 里可见、可审计）；</li>
 *   <li>决策端口故障/未配置时同样回落原路径——企业级纪律：任何失败都不新增失败面。</li>
 * </ol>
 * trace() 会展示每次的概率分布，前端可以直接对比"Jev 判断"与"LLM 猜测"。
 */
public interface JevRouterAgent extends Agent {

    /**
     * 置信度门控阈值：Jev 的 confidence 达到它才"自动执行"，否则回落 LLM 判断。
     * 阈值不该拍脑袋——拿自己的真实输入标定后再锁死（教学演示用 0.75）。
     */
    double CONFIDENCE_GATE = 0.75;

    class Impl implements JevRouterAgent {

        /** 兜底判断 + 闲聊对话仍走聊天大模型——Jev 管判断，LLM 管生成。 */
        private final cn.xiaofuge.ai.llm.ChatModel model;
        /** Jev 决策端口；null 表示未配置 key，全部判断回落 LLM。 */
        private final JevModel jev;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        /** LLM 兜底判断用的提示词——与 RouterAgent 相同口径，回落时判断结果口径一致。 */
        private static final String FALLBACK_PROMPT = """
                你是意图识别路由器。分析用户输入，只输出 JSON：
                {"intent": "weather|math|chat", "reason": "一句话理由"}
                - weather: 查询天气
                - math: 四则运算
                - chat: 其他一切对话
                """;

        public Impl(cn.xiaofuge.ai.llm.ChatModel model) {
            this(model, Models.jevOrNull());
        }

        Impl(cn.xiaofuge.ai.llm.ChatModel model, JevModel jev) {
            this.model = model;
            this.jev = jev;
        }

        @Override
        public String name() {
            return "Step06+ · Jev 决策模型路由（System One）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();

            // 1. 意图判断：优先走 Jev 决策端口，失败或低置信时回落 LLM
            JevModel.Decision decision = null;
            String jevError = null;
            if (jev == null) {
                jevError = "未配置 jev-api-key";
            } else {
                try {
                    decision = jevDecide(input);
                } catch (Exception e) {
                    jevError = "Jev 调用失败：" + e.getMessage();
                }
            }

            String intent;
            if (decision != null) {
                // 2. 置信度门控：答案告诉你"是什么"，置信度告诉你"该不该动手"
                lastTrace.add(Map.of(
                        "type", "intent",
                        "label", "Jev 决策（System One）",
                        "detail", decision.choice()
                                + " · confidence " + String.format("%.2f", decision.confidence())
                                + " · probabilities " + decision.probabilities()));
                if (decision.confidence() >= CONFIDENCE_GATE) {
                    lastTrace.add(Map.of(
                            "type", "action",
                            "label", "置信度门控",
                            "detail", "confidence " + String.format("%.2f", decision.confidence())
                                    + " ≥ " + CONFIDENCE_GATE + " → 直接采信，跳过 LLM 推理"));
                    intent = decision.choice();
                } else {
                    lastTrace.add(Map.of(
                            "type", "action",
                            "label", "置信度门控",
                            "detail", "confidence " + String.format("%.2f", decision.confidence())
                                    + " < " + CONFIDENCE_GATE + " → 不硬猜，回落 LLM 判断"));
                    intent = llmJudge(input);
                }
            } else {
                // 决策端口故障/未配置 → 回落原路径，不新增失败面
                lastTrace.add(Map.of(
                        "type", "intent",
                        "label", "Jev 不可用",
                        "detail", (jevError == null ? "未知原因" : jevError) + " → 回落 LLM 意图识别"));
                intent = llmJudge(input);
            }

            // 3. 按意图路由到处理器 —— 与 RouterAgent 相同的分发骨架
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

        /**
         * 调 Jev 决策端口做意图判断：choice 题型，闭集三选一。
         * <p>
         * criteria 就是判据——"描述即路由"，改描述即可改善判断质量，零代码。
         * 实际生产里还可以在同一次请求里打包"要不要写权限（noul）、难度分档（score）"
         * 等更多问题（见 {@link JevModel#evaluate}），加问题几乎不增加耗时。
         */
        private JevModel.Decision jevDecide(String input) {
            Map<String, Object> criteria = Json.obj();
            criteria.put("weather", "查询天气");
            criteria.put("math", "四则运算或计算");
            criteria.put("chat", "其他一切对话");
            return jev.choice(input, "intent", "用户想让助手做什么？", criteria);
        }

        /** LLM 兜底判断：口径与 RouterAgent 完全一致（猜 JSON + 解析失败落 chat）。 */
        private String llmJudge(String input) {
            String raw = model.chat(List.of(
                    Message.system(FALLBACK_PROMPT),
                    Message.user(input))).trim();
            String intent = null;
            try {
                Object parsed = Json.parse(raw
                        .replaceFirst("^```\\w*\\s*", "")
                        .replaceFirst("```\\s*$", "")
                        .trim());
                intent = Json.str(parsed, "intent");
            } catch (Exception ignored) {
            }
            lastTrace.add(Map.of(
                    "type", "intent",
                    "label", "LLM 兜底判断",
                    "detail", (intent == null ? "chat" : intent) + "（无概率、无置信度，靠祈祷）"));
            return intent == null ? "chat" : intent;
        }

        // ------------------------------------------------------------- 处理器（与 RouterAgent 相同）

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

        @Override
        public List<Map<String, Object>> trace() {
            return List.copyOf(lastTrace);
        }
    }

    /**
     * 本地直接测试入口：
     * java -cp target/classes cn.xiaofuge.ai.agent.step06.JevRouterAgent [你的问题]
     * <p>
     * Jev 配置来自 config.properties（jev-url / jev-api-key / jev-model），
     * 未配置 key 时自动回落 LLM 判断并打印提示。
     */
    public static void main(String[] args) {
        cn.xiaofuge.ai.llm.ChatModel model = cn.xiaofuge.ai.llm.Models.openAiOrNull();
        if (model == null) model = cn.xiaofuge.ai.llm.Models.mock("step06");
        Impl agent = new Impl(model);
        String input = args.length > 0 ? String.join(" ", args) : "帮我看看明天上海天气怎么样";
        System.out.println("[输入] " + input);
        System.out.println("[输出] " + agent.chat(input));
        System.out.println("[轨迹]");
        for (Map<String, Object> step : agent.trace()) {
            System.out.println("  · " + step.get("label") + " —— " + step.get("detail"));
        }
    }
}
