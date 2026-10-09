package cn.xiaofuge.ai.agent.step18;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.agent.step03.ReActAgent;
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
 * Step18 · 全流程智能体 —— 把前 17 个场景的能力串成一条完整链路。
 * <p>
 * 前面的场景每次只演示一种能力，这一个类把它们全部组装到一起，
 * 一次 chat() 完整走完生产级智能体的标准生命周期：
 *
 * <pre>
 *   输入守卫 → 读取记忆(画像+历史) → 组装上下文 → ReAct 循环(思考→工具→观察)
 *          → 保险丝 → 输出校验 → 回写记忆
 * </pre>
 *
 * 注意：它依然只是一个接口类 + 一个 chat(String) 方法 ——
 * 智能体再复杂，骨架不变；变厚的只是「策略层」。
 * 这也是理解生产框架（LangGraph / Dify / 工业级 Harness）的钥匙：
 * 框架只是把这些环节做成了可配置、可观测、可容错的工程件。
 */
public interface FullAgent extends Agent {

    /** 循环保险丝：最多工具轮数（Budget Guard 的一种）。 */
    int MAX_STEPS = 5;

    /** 输入长度守卫阈值。 */
    int MAX_INPUT_LEN = 300;

    /** 记忆窗口压缩阈值：历史超过该条数时触发压缩。 */
    int COMPRESS_THRESHOLD = 10;

    class Impl implements FullAgent {

        private final ChatModel model;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        /** 跨轮记忆：历史消息与用户画像（真实项目里这两样在记忆组件中）。 */
        private final List<Message> history = new ArrayList<>();
        private final Map<String, String> profile = new java.util.concurrent.ConcurrentHashMap<>();

        /** 系统提示词：角色 + 能力清单 + 输出协议（Step02 的三层结构）。 */
        private static final String SYSTEM_PROMPT = """
                你是 ToyAgent 综合智能体，具备记忆与工具使用能力。
                每一轮只输出一个 JSON 对象，格式二选一：
                1. 继续行动：{"thought": "你的思考", "action": "工具名", "action_input": "参数"}
                2. 给出答案：{"thought": "你的思考", "final": "最终答案"}
                可用工具：
                - get_weather(city): 查询城市天气
                - calculator(expression): 计算四则运算，如 12*8
                - get_time(): 获取当前时间
                回答时自然融入用户画像与历史信息；不要输出 JSON 以外的内容。
                """;

        /** 敏感意图黑名单（教学演示，生产环境用内容安全服务）。 */
        private static final Pattern SENSITIVE = Pattern.compile("hack|攻击|爆破|破解|密码", Pattern.CASE_INSENSITIVE);

        /** 画像提取：记住「我叫 XX / 我喜欢 XX」（排除「我叫什么」这类疑问句）。 */
        private static final Pattern NAME_PTN = Pattern.compile("我(?:叫|的名字(?:是|叫)?)\\s*(?!(?:什么|啥|谁))([\\u4e00-\\u9fa5A-Za-z0-9]{1,12})");
        private static final Pattern LIKE_PTN = Pattern.compile("我(?:喜欢|爱)\\s*([\\u4e00-\\u9fa5A-Za-z0-9·\\s]{1,12})");

        public Impl(ChatModel model) {
            this.model = model;
        }

        @Override
        public String name() {
            return "Step18 · 全流程智能体（守卫+记忆+ReAct+工具+保险丝）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();

            // ---------- 1. 输入守卫（Step15 的 Guard） ----------
            if (input.length() > MAX_INPUT_LEN) {
                lastTrace.add(Map.of("type", "guard", "label", "输入守卫", "detail",
                        "输入长度 " + input.length() + " 超过上限 " + MAX_INPUT_LEN + "，直接拒绝，不进模型。"));
                return "输入过长，请精简后重试（守卫规则：长度 ≤ " + MAX_INPUT_LEN + " 字符）。";
            }
            Matcher sm = SENSITIVE.matcher(input);
            if (sm.find()) {
                lastTrace.add(Map.of("type", "guard", "label", "输入守卫", "detail",
                        "命中敏感意图黑名单「" + sm.group() + "」，拦截且不消耗模型预算。"));
                return "该请求涉及不安全操作，已被输入守卫拦截。智能体的第一道防线在模型之前。";
            }
            lastTrace.add(Map.of("type", "guard", "label", "输入守卫", "detail",
                    "长度与安全检查通过，放行进入后续流程。"));

            // ---------- 2. 记忆读取 + 画像提取（Step05 的 Memory） ----------
            extractProfile(input);
            lastTrace.add(Map.of("type", "memory", "label", "读取记忆", "detail",
                    "历史 " + history.size() + " 条；画像："
                            + (profile.isEmpty() ? "暂无" : profile.toString())
                            + "（随请求一起组装进上下文）。"));

            // ---------- 3. 组装上下文（system + 历史 + 本轮输入） ----------
            List<Message> messages = new ArrayList<>();
            messages.add(Message.system(SYSTEM_PROMPT + userPortrait()));
            messages.addAll(history);
            messages.add(Message.user(input));

            // ---------- 4. ReAct 循环（Step03/04 的 Thought→Action→Observation） ----------
            String answer = runReAct(messages);

            // ---------- 5. 输出校验（守卫不只管输入） ----------
            answer = outputGuard(answer, input);

            // ---------- 6. 记忆回写 ----------
            history.add(Message.user(input));
            history.add(Message.assistant(answer));
            String memo = "本轮对话已写入记忆（现共 " + history.size() + " 条）。";
            if (history.size() > COMPRESS_THRESHOLD) {
                compressHistory();
                memo += " 超过阈值 " + COMPRESS_THRESHOLD + " 条 → 上下文压缩已执行（保留系统提示词与最近消息）。";
            }
            lastTrace.add(Map.of("type", "memory", "label", "回写记忆", "detail", memo));
            return answer;
        }

        /** ReAct 主体：与 Step03 相同的循环骨架，外层多了守卫与记忆。 */
        private String runReAct(List<Message> messages) {
            for (int step = 1; step <= MAX_STEPS; step++) {
                String raw = model.chat(messages).trim();
                raw = stripCodeFence(raw);
                Object parsed;
                try {
                    parsed = Json.parse(raw);
                } catch (Exception e) {
                    // 模型没按协议输出 → 当作最终答案兜底返回
                    lastTrace.add(Map.of("type", "final", "label", "Final Answer", "detail", raw));
                    return raw;
                }

                String thought = Json.str(parsed, "thought");
                lastTrace.add(Map.of("type", "thought", "label", "Thought " + step, "detail",
                        thought == null ? "" : thought));

                String fin = Json.str(parsed, "final");
                if (fin != null) {
                    lastTrace.add(Map.of("type", "final", "label", "Final Answer", "detail", fin));
                    return fin;
                }

                String action = Json.str(parsed, "action");
                String actionInput = Json.str(parsed, "action_input");
                lastTrace.add(Map.of("type", "action", "label", "Action " + step, "detail",
                        action + "(" + actionInput + ")"));

                String observation = executeTool(action, actionInput);
                lastTrace.add(Map.of("type", "tool", "label", "Tool · " + action, "detail",
                        "工具执行完成，产出观察结果。"));
                lastTrace.add(Map.of("type", "observation", "label", "Observation " + step, "detail", observation));

                messages.add(Message.assistant(raw));
                messages.add(Message.user("Observation: " + observation));
            }
            String msg = "已达最大步数 " + MAX_STEPS + "，保险丝熔断，循环终止（Budget Guard）。";
            lastTrace.add(Map.of("type", "guard", "label", "保险丝", "detail", msg));
            return msg;
        }

        /** 工具注册表：本地 switch 执行器（真实项目里是 MCP/插件网关）。 */
        private String executeTool(String action, String input) {
            try {
                return switch (action == null ? "" : action) {
                    case "get_weather" -> ReActAgent.MockWeather.query(input);
                    case "calculator" -> String.valueOf(calc(input));
                    case "get_time" -> LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    default -> "未知工具: " + action;
                };
            } catch (Exception e) {
                return "工具执行失败: " + e.getMessage();
            }
        }

        /** 输出守卫：拦截答案中的敏感内容，并在能回答画像问题时兜底。 */
        private String outputGuard(String answer, String input) {
            if (answer != null && SENSITIVE.matcher(answer).find()) {
                lastTrace.add(Map.of("type", "guard", "label", "输出守卫", "detail",
                        "答案命中敏感词，替换为安全话术后返回。"));
                return "该内容不宜展示，已由输出守卫处理。";
            }
            // 记忆兜底：模型看不到被压缩掉的历史时，画像仍能回答「我叫什么」
            if (input.contains("我叫什么") || input.contains("我是谁")) {
                String name = profile.get("名字");
                if (name != null) {
                    lastTrace.add(Map.of("type", "memory", "label", "画像兜底", "detail",
                            "命中画像「名字=" + name + "」，无需再问模型。"));
                    return "你叫 " + name + "（来自画像记忆，不依赖模型）。";
                }
            }
            return answer;
        }

        /** 从输入中提取用户画像（生产环境由记忆组件用模型抽取）。 */
        private void extractProfile(String input) {
            Matcher n = NAME_PTN.matcher(input);
            if (n.find()) profile.put("名字", n.group(1));
            Matcher l = LIKE_PTN.matcher(input);
            if (l.find()) profile.put("喜好", l.group(1).trim());
        }

        /** 画像注入提示词的片段。 */
        private String userPortrait() {
            if (profile.isEmpty()) return "";
            return "\n已知用户画像：" + profile + "（回答时自然使用，不要逐字复述）。";
        }

        /** 上下文压缩：保留系统提示词语义（此实现重建时自动携带）与最近 6 条。 */
        private void compressHistory() {
            int keep = Math.min(6, history.size());
            List<Message> kept = new ArrayList<>(history.subList(history.size() - keep, history.size()));
            history.clear();
            history.addAll(kept);
        }

        /** 教学用四则运算求值（与 Step03 相同）。 */
        private double calc(String expr) {
            Matcher m = Pattern.compile("\\s*(\\d+(?:\\.\\d+)?)\\s*([+\\-*/])\\s*(\\d+(?:\\.\\d+)?)\\s*").matcher(expr);
            if (!m.matches()) throw new IllegalArgumentException("无法解析表达式: " + expr);
            double a = Double.parseDouble(m.group(1));
            double b = Double.parseDouble(m.group(3));
            return switch (m.group(2)) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> {
                    if (b == 0) throw new ArithmeticException("除数不能为 0");
                    yield a / b;
                }
                default -> throw new IllegalArgumentException("不支持的运算符 " + m.group(2));
            };
        }

        private String stripCodeFence(String s) {
            if (s.startsWith("```")) {
                s = s.replaceFirst("^```\\w*\\s*", "").replaceFirst("```\\s*$", "").trim();
            }
            return s;
        }

        @Override
        public void reset() {
            history.clear();
            profile.clear();
        }

        @Override
        public List<Map<String, Object>> trace() {
            return List.copyOf(lastTrace);
        }
    }
}
