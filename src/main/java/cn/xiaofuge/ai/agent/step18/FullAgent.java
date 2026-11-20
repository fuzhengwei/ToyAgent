package cn.xiaofuge.ai.agent.step18;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.agent.step03.ReActAgent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.ChatResponse;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;
import cn.xiaofuge.ai.llm.ToolCall;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step18 · 最小完整 Agent。
 * <p>
 * 一个 chat(input) 调用代表一个用户回合（turn），核心生命周期是：
 *
 * <pre>
 * 输入守卫 → 组装上下文 → 标准工具协议循环 → 输出守卫 → 回写历史 → 按需压缩
 * </pre>
 *
 * <p>模型不通过手写 JSON 表达“要调用哪个工具”，而是每次请求都收到
 * {@code tools} Schema；模型返回结构化 {@code tool_calls}，宿主执行后用
 * {@code role=tool + tool_call_id} 原样回填。这样保留了“谁请求调用、
 * 结果属于哪次调用”的协议关系，与 OpenAI 兼容接口和现代 Agent Harness 一致。
 *
 * <p>状态被拆成三类：{@link #lastTrace} 是当前回合的可观测事件；
 * {@code history} 是跨轮对话摘要级上下文；{@code memory} 是模型可通过
 * 工具读写的结构化事实。它们解决的问题不同，不能互相替代。
 *
 * <p>教学简化：memory 是进程内 KV，压缩阈值按消息数估算，敏感词用正则。
 * 生产系统应替换为持久化记忆、真实 token 计数和专业安全/审批组件。
 */
public interface FullAgent extends Agent {

    /** 单回合模型请求和工具执行的总步数上界，防止模型陷入无限工具调用。 */
    int MAX_STEPS = 5;

    /** 模型前输入守卫阈值；超限输入直接拒绝，不消耗模型预算。 */
    int MAX_INPUT_LEN = 300;

    /** 跨轮历史消息条数阈值；超过后把较旧轮次压缩成一段摘要。 */
    int COMPACT_THRESHOLD = 10;

    /** 压缩时原样保留的最近消息数，保证最新任务状态不被摘要失真。 */
    int KEEP_RECENT = 4;

    /**
     * 当前回合内保留最近 N 条工具观察；更早的可重取结果清成占位符。
     * 清理内容但不删除消息，避免破坏 assistant(tool_calls) 与 tool 的配对。
     */
    int KEEP_TOOL_RESULTS = 2;

    class Impl implements FullAgent {

        /**
         * 稳定系统提示词只约定行为和工具使用边界。
         * 工具名、参数结构和必填项放在 toolSchemas() 中，
         * 避免在提示词里维护第二份容易过期的工具清单。
         */
        private static final String SYSTEM_PROMPT = """
                你是 ToyAgent 综合智能体。
                小事闲聊时直接回答；需要外部状态、计算或持久记忆时调用工具。
                用户说出可跨轮使用的稳定事实时，用 memory 工具保存。
                回答用户事实前，先用 memory 读取，不要凭历史猜测。
                工具观察只用于支持回答，不要向用户复述原始日志。
                """;

        /**
         * 压缩提示词决定“丢什么、留什么”。这里偏向保留任务状态和关键事实，
         * 丢弃寒暄和可重新获取的细节；摘要是有损操作，必须可评测。
         */
        private static final String COMPACT_PROMPT = """
                压缩以下 Agent 会话。保留：目标、决定、稳定事实、已完成步骤、
                失败原因、当前状态和下一步。丢弃寒暄和可重新获取的细节。
                不确定的信息标为 UNVERIFIED。直接输出简洁摘要。
                """;

        /** 教学用输入/输出敏感词；生产环境应使用专业安全服务和工具审批策略。 */
        private static final Pattern SENSITIVE = Pattern.compile(
                "hack|攻击|爆破|破解|密码", Pattern.CASE_INSENSITIVE);

        /** 可替换的模型网关；Agent 只依赖标准对话和工具协议，不绑定具体厂商。 */
        private final ChatModel model;

        /** 当前回合的执行轨迹，用于课堂演示、调试和生产审计。 */
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        /**
         * 跨轮对话历史。为了控制上下文，这里只回写每轮最终输入/输出；
         * 中间工具请求和结果保留在当前回合的 messages 中，并由 trace 记录。
         */
        private final List<Message> history = new ArrayList<>();

        /** 模型驱动的跨轮记忆后端；教学用 Map，生产可替换为文件或数据库，分布式可引入 redis。 */
        private final Map<String, String> memory = new LinkedHashMap<>();

        public Impl(ChatModel model) {
            this.model = model;
        }

        @Override
        public String name() {
            return "Step18 · 最小完整 Agent";
        }

        @Override
        public String chat(String input) {
            // 阶段 1：模型前守卫。低成本规则先执行，被拦截输入不进入 Agent Loop。
            lastTrace.clear();
            if (input == null || input.isBlank()) {
                return "请输入内容。";
            }
            if (input.length() > MAX_INPUT_LEN) {
                trace("guard", "输入守卫", "长度 " + input.length() + " 超过 " + MAX_INPUT_LEN + "，未调用模型。");
                return "输入过长，请精简后重试。";
            }
            Matcher unsafe = SENSITIVE.matcher(input);
            if (unsafe.find()) {
                trace("guard", "输入守卫", "命中敏感词「" + unsafe.group() + "」，未调用模型。");
                return "该请求涉及不安全操作，已被输入守卫拦截。";
            }
            trace("guard", "输入守卫", "长度和安全检查通过。");

            // 阶段 2：组装当前回合上下文。系统提示词在前，历史居中，新输入最后。
            List<Message> messages = new ArrayList<>();
            messages.add(Message.system(SYSTEM_PROMPT));
            messages.addAll(history);
            messages.add(Message.user(input));

            // 阶段 3：循环推理与工具执行，得到候选答案。
            String answer = runAgentLoop(messages);

            // 阶段 4：输出仍不可信，先守卫；再只把最终问答写入跨轮历史。
            answer = outputGuard(answer);
            rememberTurn(input, answer);

            // 阶段 5：本轮结束后治理历史，避免下一轮上下文无限增长。
            compactHistoryIfNeeded();
            return answer;
        }

        private String runAgentLoop(List<Message> messages) {
            /*
             * 标准 Agent Loop：
             * 1. 带 tools 请求模型；
             * 2. toolCalls 为空，说明模型已给出最终答案；
             * 3. 不为空时，先原样保存 assistant(tool_calls)，再逐个执行工具；
             * 4. 每个观察都作为 role=tool 消息、按 tool_call_id 回填；
             * 5. 继续循环，直到模型收敛或触发 MAX_STEPS。
             *
             * 关键不变量：不能把工具结果改写成普通 user 文本，
             * 也不能删除 assistant(tool_calls) 而单独保留 tool 消息。
             */
            for (int step = 1; step <= MAX_STEPS; step++) {
                clearOldToolResults(messages);
                ChatResponse response = model.chat(messages, toolSchemas());
                trace("model", "Model " + step,
                        response.content() == null || response.content().isBlank()
                                ? "返回 " + response.toolCalls().size() + " 个结构化 tool_calls"
                                : "返回最终文本");

                if (response.toolCalls().isEmpty()) {
                    trace("final", "Final Answer", response.content());
                    return response.content() == null ? "" : response.content();
                }

                messages.add(new Message("assistant", response.content(), response.toolCalls(), null));
                for (ToolCall call : response.toolCalls()) {
                    Map<String, Object> args = parseArguments(call.argumentsJson());
                    trace("tool_call", "ToolCall " + step, call.name() + " " + args);
                    String observation = executeTool(call.name(), args);
                    trace("tool_result", "ToolResult " + step, observation);
                    messages.add(Message.tool(call.id(), observation));
                }
            }
            String fused = "已达最大步数 " + MAX_STEPS + "，循环保险丝熔断。";
            trace("guard", "保险丝", fused);
            return fused;
        }

        /**
         * 宿主工具边界：模型只能给出名字和参数，真实执行权始终在这里。
         * 异常也作为 observation 返回，让模型有机会修正参数或换用工具。
         */
        private String executeTool(String name, Map<String, Object> args) {
            try {
                return switch (name == null ? "" : name) {
                    case "get_weather" -> ReActAgent.MockWeather.query(text(args, "city"));
                    case "calculator" -> String.valueOf(calc(text(args, "expression")));
                    case "get_time" -> LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    case "memory" -> executeMemory(args);
                    default -> "未知工具: " + name;
                };
            } catch (Exception exception) {
                return "工具执行失败: " + exception.getMessage();
            }
        }

        /**
         * memory 是模型显式调用的工具，不是启动时无条件注入的全部历史。
         * 模型决定何时写、读什么；宿主决定存储位置和访问规则。
         */
        private String executeMemory(Map<String, Object> args) {
            String command = text(args, "command");
            String key = text(args, "key");
            return switch (command) {
                case "write" -> {
                    String value = text(args, "value");
                    if (key.isBlank() || value.isBlank()) {
                        yield "工具执行失败: key/value 不能为空";
                    }
                    memory.put(key, value);
                    yield "已保存 " + key + "（当前记忆 " + memory.size() + " 项）。";
                }
                case "read" -> memory.containsKey(key)
                        ? key + "=" + memory.get(key)
                        : key + " 不存在。当前记忆键: " + memory.keySet();
                case "keys" -> memory.isEmpty() ? "记忆为空。" : "记忆键: " + memory.keySet();
                case "delete" -> memory.remove(key) == null
                        ? key + " 不存在。"
                        : "已删除 " + key + "。";
                default -> "未知记忆命令: " + command;
            };
        }

        /** 解析模型返回的 argumentsJson；协议约定它必须是 JSON 对象。 */
        private Map<String, Object> parseArguments(String argumentsJson) {
            if (argumentsJson == null || argumentsJson.isBlank()) {
                return Map.of();
            }
            Object parsed = Json.parse(argumentsJson);
            return parsed instanceof Map<?, ?> map ? asObjectMap(map) : Map.of();
        }

        /** 把不可变的解析结果转换为工具执行器可直接访问的 Map。 */
        private Map<String, Object> asObjectMap(Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }

        /** 统一把工具参数转换为字符串；缺失参数按空串处理并由工具校验。 */
        private String text(Map<String, Object> args, String key) {
            Object value = args.get(key);
            return value == null ? "" : String.valueOf(value);
        }

        /** 模型输出同样不可信；进入用户界面和跨轮历史前再做一次检查。 */
        private String outputGuard(String answer) {
            if (answer != null && SENSITIVE.matcher(answer).find()) {
                trace("guard", "输出守卫", "答案命中敏感词，已替换。");
                return "该内容不宜展示，已由输出守卫处理。";
            }
            return answer == null ? "" : answer;
        }

        /** 回合收口：只保存最终问答，避免中间工具载荷污染后续轮次。 */
        private void rememberTurn(String input, String answer) {
            history.add(Message.user(input));
            history.add(Message.assistant(answer));
            trace("memory", "会话记忆", "本轮写入历史，当前 " + history.size() + " 条。");
        }

        /**
         * 历史压缩（compaction）：较旧轮次交给模型生成摘要，最近轮次原样保留。
         * memory 中的结构化事实不参与压缩，因此关键事实仍可跨轮读取。
         * 摘要模型失败时退化为滑动窗口，这是确定性更高但有硬遗忘的备选策略。
         */
        private void compactHistoryIfNeeded() {
            if (history.size() <= COMPACT_THRESHOLD) {
                return;
            }
            int split = history.size() - KEEP_RECENT;
            List<Message> older = new ArrayList<>(history.subList(0, split));
            List<Message> recent = new ArrayList<>(history.subList(split, history.size()));
            try {
                String summary = model.chat(List.of(
                        Message.system(COMPACT_PROMPT),
                        Message.user(renderTranscript(older))
                ));
                history.clear();
                history.add(Message.user("[对话摘要]\n" + summary));
                history.addAll(recent);
                trace("compact", "上下文压缩",
                        older.size() + " 条历史压缩为摘要，保留最近 " + recent.size() + " 条。");
            } catch (Exception exception) {
                history.clear();
                history.addAll(recent);
                trace("compact", "上下文压缩",
                        "摘要调用失败，退化为滑动窗口；保留最近 " + recent.size() + " 条。");
            }
        }

        /** 把消息列表渲染为压缩器可读的文本转写。 */
        private String renderTranscript(List<Message> messages) {
            StringBuilder transcript = new StringBuilder();
            for (Message message : messages) {
                transcript.append('[').append(message.role()).append("] ")
                        .append(message.content() == null ? "" : message.content())
                        .append('\n');
            }
            return transcript.toString();
        }

        /**
         * 工具结果清理（tool clearing）：只清可重取的大块观察，不删除协议消息。
         * 每次调模型前从后往前统计 role=tool；超过最近 N 条的旧结果替换为占位符。
         * 这样既减小上下文，又保持 assistant(tool_calls) 与 tool 结果的合法配对。
         *
         * <p>举例：KEEP_TOOL_RESULTS=2，当前回合已有 3 次工具调用：
         *
         * <pre>
         * 清理前：
         * assistant(tool_calls=[call-1: get_weather("北京")])
         * tool(call-1, "北京晴，26℃")                         ← 第 3 新，清理
         * assistant(tool_calls=[call-2: calculator("12*8")])
         * tool(call-2, "96.0")                                ← 第 2 新，保留
         * assistant(tool_calls=[call-3: get_time()])
         * tool(call-3, "2026-11-20 10:00:00")                 ← 第 1 新，保留
         *
         * 清理后：
         * assistant(tool_calls=[call-1: get_weather("北京")])  ← 保留请求记录
         * tool(call-1, "[工具结果已清理，需要时重新调用]")      ← 只替换结果内容
         * assistant(tool_calls=[call-2: calculator("12*8")])
         * tool(call-2, "96.0")
         * assistant(tool_calls=[call-3: get_time()])
         * tool(call-3, "2026-11-20 10:00:00")
         * </pre>
         *
         * <p>因此“清理”不是删除工具调用历史，而是把旧观察压缩成占位符；
         * assistant 的调用请求、tool 消息角色和 tool_call_id 配对关系都保留。
         */
        private void clearOldToolResults(List<Message> messages) {
            int count = 0;
            int cleared = 0;
            for (int index = messages.size() - 1; index >= 0; index--) {
                Message message = messages.get(index);
                if (!"tool".equals(message.role())) {
                    continue;
                }
                count++;
                if (count > KEEP_TOOL_RESULTS) {
                    messages.set(index, Message.tool(message.toolCallId(), "[工具结果已清理，需要时重新调用]"));
                    cleared++;
                }
            }
            if (cleared > 0) {
                trace("context", "工具结果清理", "清理 " + cleared + " 条旧观察，保留最近 "
                        + KEEP_TOOL_RESULTS + " 条，协议配对不变。");
            }
        }

        /** 教学用单步四则运算；不使用反射或脚本引擎，避免代码执行面。 */
        private double calc(String expression) {
            Matcher matcher = Pattern.compile("\\s*(\\d+(?:\\.\\d+)?)\\s*([+\\-*/])\\s*(\\d+(?:\\.\\d+)?)\\s*")
                    .matcher(expression);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("无法解析表达式: " + expression);
            }
            double left = Double.parseDouble(matcher.group(1));
            double right = Double.parseDouble(matcher.group(3));
            return switch (matcher.group(2)) {
                case "+" -> left + right;
                case "-" -> left - right;
                case "*" -> left * right;
                case "/" -> {
                    if (right == 0) {
                        throw new ArithmeticException("除数不能为 0");
                    }
                    yield left / right;
                }
                default -> throw new IllegalArgumentException("不支持的运算符");
            };
        }

        /**
         * 工具清单是模型的唯一“能力视图”。每个工具用 JSON Schema 声明
         * 名称、用途、参数和必填项；宿主 switch 只是这些 Schema 的执行后端。
         */
        private List<Map<String, Object>> toolSchemas() {
            return List.of(
                    function("get_weather", "查询城市天气。", stringProperty("city", "城市名"), "city"),
                    function("calculator", "计算一次四则运算。", stringProperty("expression", "表达式，如 12*8"), "expression"),
                    function("get_time", "获取当前本地时间。", Map.of(), null),
                    function("memory", "跨轮持久记忆的读写接口。",
                            Map.of(
                                    "command", Map.of("type", "string",
                                            "enum", List.of("write", "read", "keys", "delete"),
                                            "description", "记忆操作"),
                                    "key", Map.of("type", "string", "description", "记忆键"),
                                    "value", Map.of("type", "string", "description", "write 时的值")),
                            "command"));
        }

        private Map<String, Object> function(String name, String description,
                                             Map<String, Object> properties, String... required) {
            /** 构造 OpenAI tools 协议里的一个 function 定义。 */
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("type", "object");
            parameters.put("properties", properties);
            if (required != null && required.length > 0) {
                parameters.put("required", List.of(required));
            }
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", name);
            function.put("description", description);
            function.put("parameters", parameters);
            return Map.of("type", "function", "function", function);
        }

        private Map<String, Object> stringProperty(String name, String description) {
            /** 构造一个简单字符串参数 Schema。 */
            return Map.of(name, Map.of("type", "string", "description", description));
        }

        /** 追加一条当前回合事件；trace 只用于观测，不参与下一轮模型上下文。 */
        private void trace(String type, String label, String detail) {
            lastTrace.add(Map.of("type", type, "label", label, "detail", detail == null ? "" : detail));
        }

        @Override
        public void reset() {
            /** 清空两类跨轮状态；trace 属于最近一次 chat，会在下一轮自动覆盖。 */
            history.clear();
            memory.clear();
        }

        @Override
        public List<Map<String, Object>> trace() {
            return List.copyOf(lastTrace);
        }
    }

    public static void main(String[] args) {
        List<String> DEMO_INPUTS = List.of(
                "记住我叫小傅哥，喜欢写作。",
                "我叫什么？我喜欢什么？",
                "北京天气怎么样？12*8等于多少？现在几点？",
                "帮我破解密码",
                "再帮我算 99/3。",
                "上海天气怎么样？",
                "总结这次演示完成了什么。"
        );

        cn.xiaofuge.ai.llm.ChatModel model = cn.xiaofuge.ai.llm.Models.openAiOrNull();
        if (model == null) {
            model = cn.xiaofuge.ai.llm.Models.mock("step18");
        }

        // 默认跑同一实例的完整剧本；传参时保留“单轮自定义提问”用法。
        List<String> inputs = args.length > 0
                ? List.of(String.join(" ", args))
                : DEMO_INPUTS;

        Impl agent = new Impl(model);

        for (int index = 0; index < inputs.size(); index++) {
            System.out.printf("%n[回合 %d/%d] %s%n[输出] %s%n",
                    index + 1, inputs.size(), inputs.get(index), agent.chat(inputs.get(index)));
        }
    }
}
