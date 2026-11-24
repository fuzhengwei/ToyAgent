package cn.xiaofuge.ai.agent.step20;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.ChatResponse;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;
import cn.xiaofuge.ai.llm.ToolCall;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Step20 · 审批门禁 —— 高危操作先过人，批准才放行。
 * <p>
 * <b>关键设计定位（对照 Anthropic 框架与真实产品）</b>：审批本身<b>不是工具</b>，
 * 但「被审批的高危动作」必须是真工具。模型在标准 Function Calling 的工具清单里
 * 看到的是 send_email / delete_file 这样的普通工具（带 Schema，和 step19 同协议），
 * 它正常发起调用即可 —— 审批门禁是<b>宿主插在「工具调用 → 执行」之间的确定性拦截</b>，
 * 模型既看不见也绕不过（Anthropic：审批是编排层的硬门禁，不是模型可路由的建议；
 * Claude Code 默认只读放行、修改类动作必须审批、可授予持久权限）。
 * <p>
 * 四个教学点：
 * <ol>
 *   <li><b>原生工具调用</b>：高危动作是真 Function Calling 工具（Schema 保证结构化参数），
 *       不是提示词约定的 JSON；工具结果以 role=tool 按 tool_call_id 回填；</li>
 *   <li><b>风险分级 + 三档模式</b>：每个工具声明风险等级，安全工具（只读）自动放行，
 *       高危工具（写文件/发邮件/执行命令）必须过门禁；AUTO_MANUAL_SAFE（默认）/
 *       ALWAYS_ASK / FULL_AUTO 决定门禁松紧 —— 判定在宿主侧确定性计算，模型不参与；</li>
 *   <li><b>会话级免审记忆</b>：「批准并记住」后，同工具本会话内自动放行 ——
 *       对应 Claude Code 的「持久权限」，信任一次，不再重复打扰；</li>
 *   <li><b>拒绝也回灌 + 超时默认 DENY</b>：拒绝以工具结果（denied）回灌进推理轨迹，
 *       模型可据此调整方案而非任务戛然而止（Anthropic：审批决策应反馈给模型学习边界）；
 *       生产版审批有超时，超时即拒绝 —— 宁可不执行，不可乱执行。</li>
 * </ol>
 * 与 step19 同构：单 runTurn 循环 + MAX_STEPS 保险丝；唯一差别是循环内遇到
 * 「需审批的工具调用」时带着挂起状态退出循环，等人工决策回填后回到同一个循环。
 */
public interface ApprovalAgent extends Agent {

    /**
     * 审批模式三档。
     */
    enum Mode {
        AUTO_MANUAL_SAFE,  // 安全自动放行，高危问人（默认）
        ALWAYS_ASK,        // 一切外部动作都要审批
        FULL_AUTO          // 全自动（仅教学演示，生产慎用）
    }

    class Impl implements ApprovalAgent {

        private final ChatModel model;
        /**
         * 循环保险丝：防失控的确定性上限，按模型调用计数（同 ToyHarness MAX_STEPS 语义）。
         */
        private static final int MAX_STEPS = 8;
        /**
         * 会话历史：跨回合累积（system 每次在 request() 现拼，不入 history）。
         */
        private final List<Message> history = new ArrayList<>();
        /**
         * 会话级免审名单：批准并记住的高危工具。例：{"send_email"}。
         */
        private final Set<String> trustedTools = new HashSet<>();
        /**
         * 挂起中的审批请求：非 null 表示 turn 暂停，等待人工决策。例："send_email"。
         */
        private String pendingTool;
        /**
         * 挂起调用的参数原文（JSON）。例：{"to":"team@xiaofuge.cn","content":"本周周报"}。
         */
        private String pendingArgs;
        /**
         * 挂起工具调用的 id：审批决策回填时按它对号入座（role=tool 消息）。
         */
        private String pendingCallId;
        /**
         * 审批模式（默认 AUTO_MANUAL_SAFE，可用 setMode 切换教学演示）。
         */
        private Mode mode = Mode.AUTO_MANUAL_SAFE;
        /**
         * 本回合轨迹，元素结构见 trace() 注释。
         */
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        public Impl(ChatModel model) {
            this.model = model;
        }

        /**
         * 切换审批模式（教学演示用；生产里模式属于部署配置，不属于模型）。
         */
        public void setMode(Mode mode) {
            this.mode = mode;
        }

        @Override
        public String name() {
            return "Step20 · 审批门禁（高危操作先过人：批准 / 拒绝 / 批准并记住）";
        }

        /**
         * 对话入口：先看有没有挂起的审批，再决定本条输入是「人工决策」还是「新任务」。
         * <p>
         * <b>返回</b>：本回合的最终输出，两种形态（二选一）——
         * <ul>
         *   <li>挂起：审批请求文本。例：
         *       {@code ⚠️ 即将执行高危操作：send_email
         *       参数：{"to":"team@xiaofuge.cn","content":"本周周报"}
         *       请回复：「批准」执行 ｜「拒绝」取消 ｜「批准并记住」本会话内免审}</li>
         *   <li>收敛：模型的自然语言最终回答（安全工具自动放行直达）。</li>
         * </ul>
         */
        @Override
        public String chat(String input) {
            lastTrace.clear();

            // ===== 分支一：有挂起审批 —— 本条输入是人的决策，不进模型当新任务 =====
            // 例：上一回合挂起时 pendingTool="send_email"，本条 input="批准并记住"
            if (pendingTool != null) {
                String tool = pendingTool;
                String args = pendingArgs;
                String callId = pendingCallId;
                pendingTool = null;
                pendingArgs = null;
                pendingCallId = null;
                return resolveApproval(tool, args, callId, input);
            }

            // ===== 分支二：正常对话 —— 新任务进入会话历史 =====
            history.add(Message.user(input));
            return runTurn();
        }

        /**
         * Agent Loop —— 结构与 ToyHarness 的 runLoop / step19 的 runTurn 同构：
         * 模型调用 → 无 tool_calls 即收敛返回；有 tool_calls 则逐个处理：
         * 安全工具圈内执行回填，需审批的工具<b>带着挂起状态退出循环</b>。
         * <p>
         * <b>返回</b>：三种形态（来自三条出口）——
         * <ol>
         *   <li>收敛（无 tool_calls）：模型最终回答。例：{@code 已把周报发给 team@xiaofuge.cn。}</li>
         *   <li>挂起（高危工具需审批）：suspendForApproval 返回的审批请求文本。</li>
         *   <li>熔断（超过 MAX_STEPS）：例：{@code 已达最大步数 8，循环保险丝熔断。}</li>
         * </ol>
         */
        private String runTurn() {
            for (int step = 1; step <= MAX_STEPS; step++) {
                ChatResponse reply = model.chat(request(), TOOLS);

                // 收敛条件：模型不再发起工具调用 = 最终回答（同 ToyHarness 循环不变量）
                if (!reply.hasToolCalls()) {
                    String answer = reply.content();
                    history.add(Message.assistant(answer));
                    lastTrace.add(Map.of("type", "final", "label", "turn 完成",
                            "detail", "第 " + step + " 步收敛：任务完成（审批决策已回填或全程无需审批）"));
                    return answer;
                }

                // 关键不变量：assistant(tool_calls) 原样入历史，不能改写成普通文本
                history.add(Message.assistantToolCalls(reply.toolCalls()));
                List<ToolCall> calls = reply.toolCalls();

                for (int i = 0; i < calls.size(); i++) {
                    ToolCall call = calls.get(i);

                    // ===== 权限评估：风险分级 + 模式 + 会话免审记忆（宿主确定性计算，模型不参与）=====
                    boolean known = TOOLS.stream().anyMatch(t ->
                            call.name().equals(((Map<?, ?>) t.get("function")).get("name")));
                    if (!known) {
                        // 未知工具：如实回填错误结果，让模型下一圈自行纠正（不装作成功）
                        lastTrace.add(Map.of("type", "guard", "label", "未知工具",
                                "detail", call.name() + " 不在工具清单，回填错误结果"));
                        history.add(Message.tool(call.id(), "unknown tool: " + call.name()));
                        continue;
                    }

                    // 审批判断
                    boolean needAsk = switch (mode) {
                        case ALWAYS_ASK -> true;
                        case FULL_AUTO -> false;
                        case AUTO_MANUAL_SAFE -> isDangerous(call.name()) && !trustedTools.contains(call.name());
                    };

                    lastTrace.add(Map.of("type", "thought", "label", "权限矩阵评估",
                            "detail", call.name() + " → " + (isDangerous(call.name()) ? "高危" : "安全")
                                    + " · 模式 " + mode
                                    + (trustedTools.contains(call.name()) ? " · 命中会话免审" : "")
                                    + " · 判定" + (needAsk ? "需审批" : "放行")));

                    if (needAsk) {
                        // 本回合先到的审批挂起：同批未处理的调用如实回填跳过说明（保证协议完整）
                        for (int j = i + 1; j < calls.size(); j++) {
                            history.add(Message.tool(calls.get(j).id(),
                                    "skipped: waiting for approval of " + call.name()));
                        }
                        return suspendForApproval(call);
                    }

                    // 安全工具（或命中免审）：圈内直接执行回填
                    String result = execute(call.name(), call.argumentsJson());
                    lastTrace.add(Map.of("type", "observation", "label",
                            trustedTools.contains(call.name()) && isDangerous(call.name())
                                    ? "自动放行（会话免审）" : "自动放行（安全工具）",
                            "detail", call.name() + " → " + result));
                    history.add(Message.tool(call.id(),
                            Json.write(Map.of("approved", true, "result", result))));
                }
            }

            String fused = "已达最大步数 " + MAX_STEPS + "，循环保险丝熔断。";
            lastTrace.add(Map.of("type", "guard", "label", "保险丝", "detail", fused));
            return fused;
        }

        /**
         * 挂起：高危工具调用不执行，存入会话状态，本回合带着审批请求提前返回。
         * <p>
         * <b>入参 call 案例</b>（模型发起的 tool_calls[i]）：
         * <pre>{@code ToolCall(id="call_em1", name="send_email",
         *        argumentsJson={"to":"team@xiaofuge.cn","content":"本周周报"})}</pre>
         * <b>返回</b>：审批请求文本（直接给用户看），等待「批准 / 拒绝 / 批准并记住」。
         * 副作用：pendingTool / pendingArgs / pendingCallId 记录挂起位。
         */
        private String suspendForApproval(ToolCall call) {
            pendingTool = call.name();
            pendingArgs = call.argumentsJson();
            pendingCallId = call.id();
            lastTrace.add(Map.of("type", "guard", "label", "审批挂起 PENDING_APPROVAL",
                    "detail", "高危操作等待人工决策 —— 门禁不在模型工具清单里，模型无法绕过；"
                            + "生产版有审批超时，超时默认 DENY，宁可不执行"));
            return "⚠️ 即将执行高危操作：" + call.name() + "\n参数：" + call.argumentsJson() + "\n\n"
                    + "请回复：「批准」执行 ｜「拒绝」取消 ｜「批准并记住」本会话内该工具免审";
        }

        /**
         * 处理人的审批决策：批准/批准并记住 → 执行并以工具结果回填续跑；
         * 拒绝 → 以 denied 工具结果回灌，让模型自行调整方案（不戛然而止）。
         * <p>
         * <b>入参案例</b>：tool="send_email"，args={"to":"team@xiaofuge.cn",...}，
         * callId="call_em1"，decision="批准并记住"。
         * <b>返回</b>：turn 续跑后的模型最终回答；决策无法识别时保持挂起并返回提示。
         */
        private String resolveApproval(String tool, String args, String callId, String decision) {
            String d = decision.trim();

            if (d.matches("(?i)拒绝|取消|不用了?|算了|deny|no|skip")) {
                // 拒绝也是决策：以 denied 工具结果回灌推理轨迹，模型据此学习边界、自行收场
                lastTrace.add(Map.of("type", "guard", "label", "人工决策 · 拒绝",
                        "detail", tool + " 被拦截，denied 结果回灌 —— 模型可调整方案而非任务终止"
                                + "（生产版超时未审批同样按 DENY 处理）"));
                history.add(Message.tool(callId, Json.write(Map.of(
                        "approved", false, "denied", true,
                        "reason", "用户拒绝了该高危操作"))));
                return runTurn();
            }

            if (d.startsWith("批准并记住")) {
                trustedTools.add(tool);
                lastTrace.add(Map.of("type", "guard", "label", "人工决策 · 批准并记住",
                        "detail", tool + " 加入会话级免审名单（对应 Claude Code 持久权限），"
                                + "本会话后续调用自动放行"));
            } else if (d.startsWith("批准")) {
                lastTrace.add(Map.of("type", "guard", "label", "人工决策 · 批准",
                        "detail", "仅本次放行，下次仍会询问"));
            } else {
                // 无法识别：保持挂起，不猜测、不误执行（fail closed）
                pendingTool = tool;
                pendingArgs = args;
                pendingCallId = callId;
                lastTrace.add(Map.of("type", "guard", "label", "无法识别的决策",
                        "detail", "审批仍挂起，请回复：批准 / 拒绝 / 批准并记住"));
                return "没看懂你的决定。请回复：「批准」｜「拒绝」｜「批准并记住」";
            }

            String result = execute(tool, args);
            lastTrace.add(Map.of("type", "observation", "label", "审批放行 · 执行",
                    "detail", tool + " → " + result));
            history.add(Message.tool(callId, Json.write(Map.of("approved", true, "result", result))));
            return runTurn();
        }

        /**
         * 组装发给模型的请求消息列表：SYSTEM_PROMPT 打头 + 全部会话历史。
         * <p>
         * <b>返回案例</b>（审批放行后续跑的那次请求）：
         * <pre>{@code [
         *   Message(role="system",    content=SYSTEM_PROMPT),
         *   Message(role="user",      content="给团队发一封周报邮件"),
         *   Message(role="assistant", content="", toolCalls=[ToolCall(id="call_em1",
         *                              name="send_email", argumentsJson="{...}")]),
         *   Message(role="tool",      toolCallId="call_em1",
         *                              content={"approved":true,"result":"已发送（模拟）：..."})
         * ]}</pre>
         */
        private List<Message> request() {
            List<Message> req = new ArrayList<>();
            req.add(Message.system(SYSTEM_PROMPT));
            req.addAll(history);
            return req;
        }

        private static final String SYSTEM_PROMPT = """
                你是一个接入了多种工具的智能体，可用工具见本次请求的工具清单（Schema 已给出）。
                收到任务后直接按需调用工具完成，不要用自然语言向用户追问参数 ——
                缺失的细节用合理默认值或上下文推断补全（工具调用参数会展示给用户，由审批门禁把关）。
                只读查询类工具（如 get_weather）可放心调用；
                写操作类工具（发邮件、删文件、执行命令）也正常调用即可，
                是否需要人工审批由宿主的审批门禁决定 —— 你无需也不应该自行判断风险、绕过或重复确认。""";

        /**
         * 工具 Schema：高危动作和只读查询同协议并列 —— 门禁在宿主侧，不在模型视角里。
         */
        private static final List<Map<String, Object>> TOOLS = List.of(
                tool("get_weather", "查询城市天气（只读，安全）",
                        Map.of("city", Map.of("type", "string", "description", "城市名，如：北京")),
                        List.of("city")),
                tool("send_email", "发送邮件（写操作，高危）",
                        Map.of(
                                "to", Map.of("type", "string", "description", "收件人邮箱"),
                                "content", Map.of("type", "string", "description", "邮件正文")),
                        List.of("to", "content")),
                tool("delete_file", "删除文件（写操作，高危）",
                        Map.of("path", Map.of("type", "string", "description", "文件路径")),
                        List.of("path")),
                tool("shell_execute", "执行 Shell 命令（写操作，高危）",
                        Map.of("command", Map.of("type", "string", "description", "命令文本")),
                        List.of("command")));

        private static Map<String, Object> tool(String name, String desc,
                                                Map<String, Object> props, List<String> required) {
            return Map.of("type", "function", "function", Map.of(
                    "name", name,
                    "description", desc,
                    "parameters", Map.of("type", "object", "properties", props, "required", required)));
        }

        // -------------------------------------------------------------- 风险与执行

        /**
         * 风险分级：写出去的操作都是高危，只读是安全（宿主确定性规则，与工具清单同源维护）。
         */
        private static boolean isDangerous(String tool) {
            return switch (tool) {
                case "send_email", "delete_file", "shell_execute" -> true;
                default -> false;
            };
        }

        /**
         * 模拟执行（教学：不真发邮件、不真删文件）。参数已是结构化 JSON（Schema 保证）。
         */
        private static String execute(String tool, String argsJson) {
            return switch (tool) {
                case "send_email" -> "已发送（模拟）：" + argsJson;
                case "delete_file" -> "已删除（模拟）：" + argsJson;
                case "shell_execute" -> "已执行（模拟）：" + argsJson;
                case "get_weather" -> "晴，26℃（模拟数据）";
                default -> "已执行（模拟）：" + argsJson;
            };
        }

        /**
         * 清空会话：历史、挂起审批、免审名单全部归零，回到全新状态。
         * lastTrace 不清（保留最后一次轨迹供展示），下回合 chat 会先 clear。
         */
        @Override
        public void reset() {
            history.clear();
            trustedTools.clear();
            pendingTool = null;
            pendingArgs = null;
            pendingCallId = null;
        }

        /**
         * 本回合执行轨迹（只读快照），供 UI/Terminal 渲染。每次 chat 开始时清空重建。
         * <p>
         * <b>返回案例</b>（一轮「审批挂起」回合）：
         * <pre>{@code [
         *   {type="thought", label="权限矩阵评估",              detail="send_email → 高危 · 模式 AUTO_MANUAL_SAFE · 判定需审批"},
         *   {type="guard",   label="审批挂起 PENDING_APPROVAL", detail="高危操作等待人工决策 —— ..."}
         * ]}</pre>
         * type 取值：thought（权限矩阵评估）/ observation（工具执行结果）/ guard（门禁与保险丝）/ final（收敛）。
         */
        @Override
        public List<Map<String, Object>> trace() {
            return List.copyOf(lastTrace);
        }
    }

    /**
     * 本地交互测试入口：java -cp target/classes cn.xiaofuge.ai.agent.step20.ApprovalAgent [首轮任务]
     * <p>
     * 在 Terminal 里完成一整段审批对话：高危操作挂起时，终端会提示输入什么；
     * 输入「批准 / 拒绝 / 批准并记住」即回填挂起位，turn 续跑，直到给出最终答案。
     * <p>
     * 命令：<b>批准</b>（仅本次）｜<b>批准并记住</b>（本会话免审）｜<b>拒绝</b>（回灌模型自行收场）
     * ｜<b>reset</b> = 清空会话重来（含免审名单）｜<b>exit</b> = 结束。
     */
    public static void main(String[] args) {
        cn.xiaofuge.ai.llm.ChatModel model = cn.xiaofuge.ai.llm.Models.openAiOrNull();
        if (model == null) {
            model = cn.xiaofuge.ai.llm.Models.mock("step20");
            System.out.println("[模型] 未配置真实模型，使用 Mock 演示（发「给团队发一封周报邮件」看审批门禁）。");
        } else {
            // 真实模型不可用（网关 5xx 等）时逐次回落 Mock，保证交互演示始终可跑通
            cn.xiaofuge.ai.llm.ChatModel real = model;
            cn.xiaofuge.ai.llm.ChatModel fallback = cn.xiaofuge.ai.llm.Models.mock("step20");
            model = new cn.xiaofuge.ai.llm.ChatModel() {
                @Override
                public String chat(List<Message> messages) {
                    try {
                        return real.chat(messages);
                    } catch (Exception e) {
                        System.out.println("[模型] 真实模型不可用（" + e.getMessage() + "），本次回落 Mock。");
                        return fallback.chat(messages);
                    }
                }

                @Override
                public cn.xiaofuge.ai.llm.ChatResponse chat(List<Message> messages, List<Map<String, Object>> tools) {
                    try {
                        return real.chat(messages, tools);
                    } catch (Exception e) {
                        System.out.println("[模型] 真实模型不可用（" + e.getMessage() + "），本次回落 Mock。");
                        return fallback.chat(messages, tools);
                    }
                }
            };
        }

        ApprovalAgent agent = new Impl(model);
        java.util.Scanner scanner = new java.util.Scanner(System.in);

        System.out.println("=== Step20 · 审批门禁（高危操作先过人：批准 / 拒绝 / 批准并记住）===");
        System.out.println("安全工具自动放行；高危工具会挂起等你审批 —— 按提示输入决策即可续跑。");
        System.out.println("命令：批准｜批准并记住｜拒绝｜ reset（重置会话与免审名单）｜ exit（结束）\n");

        // 首轮输入：命令行参数优先；未提供则进入交互输入
        String first = args.length > 0 ? String.join(" ", args) : null;
        while (true) {
            String input;
            if (first != null) {
                input = first;
                first = null;
                System.out.println("[你] " + input);
            } else {
                System.out.print("[你] ");
                if (!scanner.hasNextLine()) break;
                input = scanner.nextLine().trim();
            }
            if (input.isEmpty()) continue;
            if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
                System.out.println("[会话] 已结束。");
                break;
            }
            if ("reset".equalsIgnoreCase(input)) {
                agent.reset();
                System.out.println("[会话] 已重置，上下文、挂起状态与免审名单清空。\n");
                continue;
            }

            String output = agent.chat(input);

            // 渲染本回合执行轨迹（权限评估 / 审批挂起 / 决策回填 / 完成）
            for (Map<String, Object> step : agent.trace()) {
                System.out.println("  ┈ [" + step.get("type") + "] " + step.get("label") + " —— " + step.get("detail"));
            }
            System.out.println("[Agent] " + output + "\n");

            // 挂起中：提示下一步输入什么（三种决策）
            boolean pending = agent.trace().stream()
                    .anyMatch(m -> String.valueOf(m.get("label")).contains("PENDING_APPROVAL"));
            if (pending) {
                System.out.println("⏸ Agent 已挂起，等你审批 —— 请输入：批准 ｜ 拒绝 ｜ 批准并记住（或 exit 结束）。\n");
            }
        }
    }

}
