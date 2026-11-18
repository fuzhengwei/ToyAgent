package cn.xiaofuge.ai.agent.step17;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.agent.step11.ToolRegistry;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.ChatResponse;
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
 * 参考 deepseek-harness-java 的 ReactLoopAgent（994 行）拆出的教学骨架，四个核心机制：
 * <ol>
 *   <li><b>turn/step 两级循环</b>：一次 chat() = 一个 turn；turn 内的 for 是 step 循环。
 *       与 Step03 的区别：工具执行完不结束回合，而是<b>续步</b>（mid-turn continuation）——
 *       循环只能以 TurnEndReason 收场；</li>
 *   <li><b>原生工具协议</b>：工具经 Step11 的 ToolRegistry 注册，请求时
 *       {@code registry.toolSchemas()} 直接进 tools 字段（标准 Function Calling），
 *       模型回结构化 tool_calls，结果按 tool_call_id 对号入座——
 *       "清单生成"与"执行"同源于一份注册表，运行时不认识任何具体工具；</li>
 *   <li><b>上下文裁剪 trimToBudget</b>：每次调模型前估算 token（≈字符数/4），
 *       超预算就从最老的非系统消息开始丢弃（保底留最近 {@link #KEEP_MIN} 条）。
 *       注意裁剪以"协议配对"为单位：携带 tool_calls 的 assistant 消息与其 tool 结果
 *       必须同生共死，否则历史里会留下孤儿 tool 消息——真实 API 会直接拒绝；</li>
 *   <li><b>步数保险丝</b>：单回合最多 {@link #MAX_STEPS_PER_TURN} 步，超限以
 *       TurnEndReason.MaxSteps 收场（发「死循环测试」可亲眼看到保险丝起跳）。</li>
 * </ol>
 */
public interface RuntimeAgent extends Agent {

    /** 单回合步数保险丝（dsh-java 同款思想，生产值通常为 50）。
     *  <p>生产对标：OpenAI Agents SDK 的 max_turns（默认 10，超限抛 MaxTurnsExceeded）；
     *  Vercel AI SDK 的 stopWhen: stepCountIs(20)；Claude Code 无人值守的 --max-turns 与
     *  --max-budget-usd —— Anthropic 官方五问之一："什么会停下它？" */
    int MAX_STEPS_PER_TURN = 6;
    /** 上下文 token 预算（教学值调小以便演示，约 4 轮触发；估算规则：token ≈ 字符数 / 4）。
     *  <p>生产对标：Codex 的 auto_compact_limit（超限自动压缩）；
     *  Claude Code 约 70% 窗口预算触发自动压缩。区别见 trimToBudget 处的策略选型注释。 */
    int BUDGET_TOKENS = 100;
    /** 裁剪时保底保留的最近消息条数 —— 对应 Anthropic 压缩保留清单：
     *  决策、路径、计划、关键错误必须留下，不能把"任务当前状态"裁没。 */
    int KEEP_MIN = 4;

    class Impl implements RuntimeAgent {

        private final ChatModel model;
        private final ToolRegistry registry = new ToolRegistry();
        /** 跨对话的会话历史：运行时的上下文管理正是作用在它上面。 */
        private final List<Message> history = new ArrayList<>();
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();
        private String lastAnswer = "";

        public Impl(ChatModel model) {
            this.model = model;
            registerDefaultTools();
        }

        private void registerDefaultTools() {
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

            // ===== 机制 3：调模型前先做上下文预算裁剪 =====
            trimToBudget();

            // ===== 机制 1：turn 内的 step 循环，只能以 TurnEndReason 收场 =====
            TurnEndReason reason = runTurn();

            // 回合结束原因入轨迹 —— 下游（前端展示/审计/续跑决策）都需要知道"为什么停"
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

        /** 一个 turn：for 循环执行 step，直到出现结束原因。 */
        private TurnEndReason runTurn() {
            for (long step = 1; step <= MAX_STEPS_PER_TURN; step++) {
                // ---- 组装请求：系统提示词 + 会话历史（裁剪后） ----
                List<Message> req = new ArrayList<>();
                req.add(Message.system(SYSTEM_PROMPT));
                req.addAll(history);

                // ---- 机制 2：工具清单由注册表生成，直接进 tools 字段 ----
                ChatResponse reply;
                try {
                    reply = model.chat(req, registry.toolSchemas());
                } catch (Exception e) {
                    return new TurnEndReason.Error("模型调用失败: " + e.getMessage());
                }

                // ---- 无 tool_calls：最终回答，回合以 Completed 收场 ----
                if (!reply.hasToolCalls()) {
                    lastAnswer = reply.content();
                    history.add(Message.assistant(reply.content()));
                    return new TurnEndReason.Completed("模型给出最终答案");
                }

                // ---- 有 tool_calls：续步（不结束回合）----
                // assistant 消息原样回传，结果按 tool_call_id 对号入座，循环继续
                history.add(Message.assistantToolCalls(reply.toolCalls()));
                for (var call : reply.toolCalls()) {
                    lastTrace.add(Map.of("type", "tool", "label", "tool_calls " + step,
                            "detail", call.name() + " " + call.argumentsJson()));

                    String observation;
                    try {
                        observation = registry.lookup(call.name())
                                .map(t -> {
                                    try {
                                        return t.execute(toArgs(call.argumentsJson()));
                                    } catch (Exception e) {
                                        return "工具执行失败: " + e.getMessage();
                                    }
                                })
                                .orElse("未知工具: " + call.name() + "。可用清单以 tools 字段声明为准。");
                    } catch (Exception e) {
                        observation = "工具执行异常: " + e.getMessage();
                    }
                    lastTrace.add(Map.of("type", "observation", "label", "Observation " + step, "detail", observation));
                    history.add(Message.tool(call.id(), observation));
                }
                trimToBudget();
            }
            lastAnswer = "已达最大步数 " + MAX_STEPS_PER_TURN + "，本回合被保险丝强制终止。"
                    + "生产系统的 ReAct 循环必须有这层兜底（dsh-java 默认 50 步，OpenAI Agents SDK 抛 MaxTurnsExceeded）。";
            return new TurnEndReason.MaxSteps(MAX_STEPS_PER_TURN);
        }

        // ===== 机制 3：上下文预算裁剪（trimToBudget）=====
        //
        // 【为什么必须裁】Anthropic《Effective context engineering for AI agents》提出
        //  context rot（上下文腐烂）：token 越多，模型对窗口内信息的召回精度越低——
        //  上下文是"边际收益递减的有限资源"，指导原则是
        //  「找到最小的高信号 token 集合，最大化达成目标的概率」。
        //  即：裁剪的目的不是省 token，是把模型的注意力留给此刻最该看的东西。
        //
        // 【策略选型】OpenAI 官方 Cookbook《Session Memory》把两大策略的约束讲透：
        //  · Trimming（本类采用的）：确定性、零延迟、可复现，最近内容逐字保真；
        //    代价是"突然遗忘"远期约束 —— 适合局部性强的任务（近期步骤远比远期历史重要）。
        //  · Summarization（生产产品普遍采用）：保长程记忆，但有摘要漂移与延迟成本。
        //  官方实测（ARC-AGI-3）：rolling truncation 换成 compaction 后，
        //  同一模型分数 3 倍、输出 token 少 6 倍 —— 丢弃式裁剪是有损捷径。
        //
        // 【生产对标】Codex：超过 auto_compact_limit 自动调 /responses/compact 压缩；
        //  Claude Code：约 70% 预算触发自动压缩，且压缩是"安全网"而非规划策略；
        //  Anthropic context editing：清除"陈旧的、可重新获取的"工具结果，但保留调用记录。

        /** 估算 token：教学化近似 —— token ≈ 字符数 / 4（dsh-java 同款估算）。
         *  生产必须用真实 tokenizer：对中文该估算误差可达 2~4 倍（约 1 字 ≈ 1~2 token）。 */
        private static int estimateTokens(List<Message> msgs) {
            return msgs.stream().mapToInt(m -> m.content().length() / 4).sum();
        }

        /**
         * 裁剪规则逐条（每条都对应一条官方经验）：
         * <ul>
         *   <li><b>时机</b>——每次调模型前检查，超预算才动手（压缩要主动做，
         *       不能等撞墙：context rot 在到达硬上限之前就开始降解精度）；</li>
         *   <li><b>方向</b>——从最老的消息开始丢（ trimming 语义：有用上下文是局部的，
         *       远期历史价值低于近期工具结果；系统提示词不在 history 里，天然免疫）；</li>
         *   <li><b>保底</b>——KEEP_MIN 条最近消息绝不丢弃（Anthropic 的压缩保留清单：
         *       必须留下决策、文件路径、当前计划、关键错误——防止把"任务状态"裁没）；</li>
         *   <li><b>协议完整性</b>——携带 tool_calls 的 assistant 消息与其 role=tool
         *       结果必须同组丢弃（孤儿 tool 消息会被真实 API 直接拒绝；
         *       对标 Anthropic tool-result clearing："删结果、留调用记录"的严格版）；</li>
         *   <li><b>可观测</b>——每次裁剪写入 trace（compress 事件），丢弃多少、剩多少、
         *       现估多少全部可见——不可观测的上下文管理等于没管理。</li>
         * </ul>
         */
        private void trimToBudget() {
            if (estimateTokens(history) <= BUDGET_TOKENS || history.size() <= KEEP_MIN) return;
            // 从最老的消息开始丢（历史里没有系统消息，系统提示词在请求时单独加）
            int drop = 0;
            while (estimateTokens(history) > BUDGET_TOKENS && history.size() > KEEP_MIN) {
                Message dropped = history.remove(0);
                drop++;
                // 协议配对完整性：若丢弃的是携带 tool_calls 的 assistant 消息，
                // 其 role=tool 的结果必须一起丢弃——否则历史里留下"孤儿 tool 消息"，
                // 真实 API 会拒绝这种上下文。裁剪单位是"配对"，不是"单条"。
                if (dropped.toolCalls() != null && !dropped.toolCalls().isEmpty()) {
                    while (!history.isEmpty() && "tool".equals(history.get(0).role())) {
                        history.remove(0);
                        drop++;
                    }
                }
            }
            lastTrace.add(Map.of("type", "compress", "label", "上下文裁剪 trimToBudget",
                    "detail", "预估 token 超过预算 " + BUDGET_TOKENS + "，丢弃最早 " + drop
                            + " 条消息（tool_calls 配对整组丢弃），保留最近 " + history.size()
                            + " 条，现估 " + estimateTokens(history) + " tokens"));
        }

        private static final String SYSTEM_PROMPT = """
                你是一个运行在 turn/step 两级循环之上的智能体。
                需要外部信息时调用 tools 字段中声明的工具；信息足够时直接用自然语言给出最终回答。
                """;

        @Override
        public void reset() {
            history.clear();
            registry.clear();
            registerDefaultTools();
        }

        // ------------------------------------------------------------------ 工具

        record WeatherTool() implements cn.xiaofuge.ai.agent.step11.ToolDefinition {
            private static final Map<String, String> DATA = Map.of(
                    "北京", "晴，26℃", "上海", "多云，28℃", "广州", "雷阵雨，31℃",
                    "深圳", "阵雨，30℃", "杭州", "晴，27℃", "成都", "阴，22℃");

            @Override public String name() { return "get_weather"; }
            @Override public String description() { return "查询指定城市的实时天气"; }
            @Override public Map<String, Object> parameters() {
                return Map.of("city", Map.of("type", "string", "description", "城市名"));
            }
            @Override public String execute(Map<String, Object> args) {
                String city = String.valueOf(args.getOrDefault("city", "")).trim();
                return DATA.getOrDefault(city, "未收录城市 " + city + " 的数据");
            }
        }

        record CalculatorTool() implements cn.xiaofuge.ai.agent.step11.ToolDefinition {
            @Override public String name() { return "calculator"; }
            @Override public String description() { return "计算四则运算，如 12*8"; }
            @Override public Map<String, Object> parameters() {
                return Map.of("expression", Map.of("type", "string", "description", "算式"));
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

        /** 永远回显的工具：专供「死循环测试」演示保险丝。 */
        record EchoTool() implements cn.xiaofuge.ai.agent.step11.ToolDefinition {
            @Override public String name() { return "echo"; }
            @Override public String description() { return "回显输入文本（演示用）"; }
            @Override public Map<String, Object> parameters() {
                return Map.of("text", Map.of("type", "string", "description", "要回显的内容"));
            }
            @Override public String execute(Map<String, Object> args) {
                return "echo: " + args.getOrDefault("text", "");
            }
        }

        // ------------------------------------------------------------------ 工具方法

        /** tool_calls 的 arguments JSON → 执行参数 Map（协议字段到工具入参的翻译层）。 */
        @SuppressWarnings("unchecked")
        private static Map<String, Object> toArgs(String argumentsJson) {
            Object parsed = Json.parse(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
            return parsed instanceof Map<?, ?> m ? new HashMap<>((Map<String, Object>) m) : new HashMap<>();
        }

        @Override
        public List<Map<String, Object>> trace() {
            return List.copyOf(lastTrace);
        }
    }

    /** 本地直接测试入口：java -cp target/classes cn.xiaofuge.ai.agent.step17.RuntimeAgent [你的问题] */
    public static void main(String[] args) {
        cn.xiaofuge.ai.llm.ChatModel model = cn.xiaofuge.ai.llm.Models.openAiOrNull();
        if (model == null) model = cn.xiaofuge.ai.llm.Models.mock("demo");
        String input = args.length > 0 ? String.join(" ", args) : "查一下杭州天气";
        System.out.println("[输入] " + input);
        System.out.println("[输出] " + new Impl(model).chat(input));
    }
}
