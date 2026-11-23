package cn.xiaofuge.ai.agent.step19;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.ChatResponse;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;
import cn.xiaofuge.ai.llm.ToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Step19 · 人工介入 —— ask_user_question：把「异步的人」翻译成「可等待的工具结果」。
 * <p>
 * 参考 deepseek-harness-java 的 AskUserTool：标准 Function Calling 工具，
 * 模型在<b>工具清单</b>里看到 ask_user_question（含 Schema 与「何时用」的描述），
 * 缺关键信息时自主发起调用。ToyAgent 是同步 HTTP 架构，教学版把「等待」摊开成两次请求：
 * <ol>
 *   <li>模型调用 ask_user_question「工具」，turn 挂起，问题存入会话状态返回给用户；</li>
 *   <li>用户的下一条消息不再进模型，而是作为<b>工具结果</b>（role=tool，按 tool_call_id
 *       对号入座）回填挂起位，turn 恢复继续执行。</li>
 * </ol>
 * 与旧版提示词硬编码 JSON 协议相比，工具化的收益：结构由服务端 Schema 保证、
 * 「何时问/何时不问」写进 description 引导模型、模型无法输出协议外格式。
 * 生产版的差别只在「等待」的实现：dsh-java 用 Future 阻塞住执行线程等人，
 * 教学版把等待摊开成两次请求 —— 人机协同的语义完全一致。
 */
public interface AskAgent extends Agent {

    class Impl implements AskAgent {

        private final ChatModel model;
        /** 循环保险丝：防失控的确定性上限，按模型调用计数（同 ToyHarness MAX_STEPS 语义）。 */
        private static final int MAX_STEPS = 8;
        /**
         * 会话历史：跨回合累积的对话消息（不含开头的 system，system 在 request() 里每次现拼）。
         * 增长案例：用户任务 → assistant(tool_calls) → tool(答复) → assistant(最终回答)，四种角色都可能出现。
         */
        private final List<Message> history = new ArrayList<>();
        /**
         * 挂起中的问题：非 null 表示 turn 暂停，等待人工答复。
         * 案例："请问从哪个城市出发？（可选：北京 / 上海 / 广州）"
         */
        private String pendingQuestion;
        /** 挂起工具调用的 id：答复回填时按它对号入座（role=tool 消息）。案例："call_abc123"。 */
        private String pendingCallId;
        /** 本回合轨迹，元素结构见 trace() 注释。 */
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        public Impl(ChatModel model) {
            this.model = model;
        }

        @Override
        public String name() {
            return "Step19 · 人工介入（ask_user_question 工具：提问挂起 → 答复续跑）";
        }

        /**
         * 对话入口：先看有没有挂起的提问，再决定本条输入是「人工答复」还是「新任务」。
         * <p>
         * <b>返回</b>：本回合的最终输出，两种形态（二选一）——
         * <ul>
         *   <li>挂起：给用户看的问题文本，等待人工答复。例如：
         *       {@code 请问从哪个城市出发？（可选：北京 / 上海 / 广州）
         *       <br><br>（直接回复即可，我会带着你的答复继续任务。）}</li>
         *   <li>收敛：模型的自然语言最终回答。例如：{@code 已为您预订周四上午上海→北京的 CA1851 航班。}</li>
         * </ul>
         */
        @Override
        public String chat(String input) {
            lastTrace.clear();

            // ===== 分支一：有挂起工具调用 —— 本条输入是「人工答复」，不进模型当新任务 =====
            // 例：上一回合挂起时 pendingQuestion="请问从哪个城市出发？..."，本条 input="上海"
            if (pendingQuestion != null) {
                String q = pendingQuestion;
                String callId = pendingCallId;
                pendingQuestion = null;
                pendingCallId = null;

                // 拒绝也是答复：用户不提供信息，工具结果如实记录 declined，模型自行决定如何收场
                boolean declined = input.matches("(?i)拒绝|取消|不用了?|算了|skip|no");
                if (declined) {
                    lastTrace.add(Map.of("type", "observation", "label", "人工答复 · 拒绝",
                            "detail", "用户拒绝提供「" + q + "」所需信息 —— 工具结果记 declined，模型自行收场"));
                } else {
                    lastTrace.add(Map.of("type", "observation", "label", "工具结果回填",
                            "detail", "挂起调用「" + q + "」收到答复：" + input
                                    + "（生产版是 CompletableFuture 阻塞-唤醒，教学版把等待摊开成两次请求）"));
                }
                // 人的答复作为工具结果按 tool_call_id 回填 —— 与 fs_read 等工具结果同一协议地位
                // 例（正常答复 input="上海"）：role="tool", toolCallId="call_abc123",
                //   content={"answers":[{"question":"请问从哪个城市出发？...","answer":"上海"}]}
                // 例（拒绝 input="拒绝"）：content={"answers":[{"question":"请问从哪个城市出发？...","declined":true}]}
                Map<String, Object> answer = declined
                        ? Map.of("question", q, "declined", true)
                        : Map.of("question", q, "answer", input);
                history.add(Message.tool(callId, Json.write(Map.of("answers", List.of(answer)))));
            } else {
                // ===== 分支二：正常对话 —— 新任务进入会话历史 =====
                // 例：input="帮我订一张周四去北京的机票" → history 追加 Message(role="user", content=input)
                history.add(Message.user(input));
            }

            // 两个入口汇入同一个循环：结构对齐 ToyHarness 的 runLoop（一个循环 + 保险丝）
            return runTurn();
        }

        /**
         * Agent Loop —— 结构与 ToyHarness 的 runLoop 同构：
         * 模型调用 → 无 tool_calls 即收敛返回；有 tool_calls 则回填观察再进下一圈。
         * 唯一差别：ask_user_question 不在圈内执行，而是<b>带着挂起状态退出循环</b>——
         * 生产版（dsh-java）这里会阻塞在 Future 上等人，教学版把等待摊开成两次请求。
         * <p>
         * <b>返回</b>：三种形态（来自三条出口）——
         * <ol>
         *   <li>收敛（无 tool_calls）：模型最终回答。例：{@code 已为您预订周四上海→北京的 CA1851 航班。}</li>
         *   <li>挂起（ask_user_question）：suspendOn 返回的问题文本。例：
         *       {@code 请问从哪个城市出发？（可选：北京 / 上海 / 广州）\n\n（直接回复即可，我会带着你的答复继续任务。）}</li>
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
                            "detail", "第 " + step + " 步收敛：信息完整（或人工答复已回填），人机协同闭环"));
                    return answer;
                }

                // 关键不变量：assistant(tool_calls) 原样入历史，不能改写成普通文本
                history.add(Message.assistantToolCalls(reply.toolCalls()));
                ToolCall call = reply.toolCalls().get(0);

                if ("ask_user_question".equals(call.name())) {
                    // 本回合只处理一个提问，其余调用如实回填跳过说明（保证协议完整）
                    for (int i = 1; i < reply.toolCalls().size(); i++) {
                        history.add(Message.tool(reply.toolCalls().get(i).id(),
                                "skipped: one ask_user_question per turn"));
                    }
                    return suspendOn(call);
                }

                // 未知工具：如实回填错误结果，让模型下一圈自行纠正（不装作成功）
                lastTrace.add(Map.of("type", "guard", "label", "未知工具 " + step,
                        "detail", call.name() + " 不在工具清单，回填错误结果"));
                history.add(Message.tool(call.id(), "unknown tool: " + call.name()));
            }

            String fused = "已达最大步数 " + MAX_STEPS + "，循环保险丝熔断。";
            lastTrace.add(Map.of("type", "guard", "label", "保险丝", "detail", fused));
            return fused;
        }

        /**
         * 挂起：解析工具参数中的问题，存入会话状态，本回合带着问题提前返回。
         * <p>
         * <b>入参 call 案例</b>（模型发起的 tool_calls[0]）：
         * <pre>{@code
         * ToolCall(
         *   id = "call_abc123",
         *   name = "ask_user_question",
         *   argumentsJson = {"questions":[{"question":"请问从哪个城市出发？",
         *                                  "options":["北京","上海","广州"]}]}
         * )}</pre>
         * <b>返回</b>：拼装后的问题文本（直接给用户看），等待答复。完整案例：
         * <pre>{@code 请问从哪个城市出发？（可选：北京 / 上海 / 广州）
         *
         * （直接回复即可，我会带着你的答复继续任务。）}</pre>
         * 同时产生两个副作用：
         * <ul>
         *   <li>pendingQuestion = "请问从哪个城市出发？（可选：北京 / 上海 / 广州）"</li>
         *   <li>pendingCallId   = "call_abc123" —— 下一条用户消息回填时按它对号入座</li>
         * </ul>
         * 解析失败时的降级案例：argumentsJson 不是合法 JSON → question 退回
         * {@code ask_user_question {"questions":[...]}}（工具名 + 参数原文），仍然照常挂起。
         */
        private String suspendOn(ToolCall call) {
            // 兜底初值：解析失败也能给出可读文本（工具名 + 参数原文）
            String question = call.name() + " " + call.argumentsJson();
            List<String> options = List.of();
            try {
                Map<?, ?> args = (Map<?, ?>) Json.parse(call.argumentsJson());
                Object qs = args.get("questions");
                if (qs instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
                    question = String.valueOf(first.get("question"));
                    if (first.get("options") instanceof List<?> opts) {
                        options = opts.stream().map(String::valueOf).toList();
                    }
                }
            } catch (Exception ignored) {
                // 参数解析失败时退回原始参数文本
            }
            if (!options.isEmpty()) {
                question = question + "（可选：" + String.join(" / ", options) + "）";
            }

            pendingQuestion = question;
            pendingCallId = call.id();
            lastTrace.add(Map.of("type", "action", "label", "调用 ask_user_question 工具",
                    "detail", "标准 Function Calling：模型自主发起结构化 tool_calls —— turn 挂起，"
                            + "「异步的人」被翻译成「可等待的工具结果」"));
            lastTrace.add(Map.of("type", "guard", "label", "会话挂起 PENDING_ASK",
                    "detail", "问题与 tool_call_id 已存入会话状态（教学版随进程存活，未落盘），"
                            + "下一条消息将作为工具结果回填"));
            return question + "\n\n（直接回复即可，我会带着你的答复继续任务。）";
        }

        /**
         * 组装发给模型的请求消息列表：SYSTEM_PROMPT 打头 + 全部会话历史。
         * <p>
         * <b>返回案例</b>（一轮提问挂起后的第二次请求）：
         * <pre>{@code [
         *   Message(role="system",   content=SYSTEM_PROMPT),
         *   Message(role="user",     content="帮我订一张周四去北京的机票"),
         *   Message(role="assistant", content="", toolCalls=[ToolCall(id="call_abc123",
         *                             name="ask_user_question", argumentsJson="{...}")]),
         *   Message(role="tool",     toolCallId="call_abc123",
         *                            content="{\"answers\":[{\"question\":\"请问从哪个城市出发？\",\"answer\":\"上海\"}]}")
         * ]}</pre>
         * 注意：system 每次都重新拼在最前，history 只存对话过程消息。
         */
        private List<Message> request() {
            List<Message> req = new ArrayList<>();
            req.add(Message.system(SYSTEM_PROMPT));
            req.addAll(history);
            return req;
        }

        private static final String SYSTEM_PROMPT = """
                你是一个懂得「何时该问人」的智能体。
                当任务缺少关键信息（如预订缺少出发地、时间），不要猜测，调用 ask_user_question 工具向人提问；
                只要仍有信息缺失，每一轮都必须用 ask_user_question 工具提问（一次一个问题），不要用自然语言向用户追问；
                信息完整时直接用自然语言完成任务并作答。""";

        /** ask_user_question 的工具 Schema：结构由服务端保证，「何时用」写进 description。 */
        private static final List<Map<String, Object>> TOOLS = List.of(
                Map.of("type", "function", "function", Map.of(
                        "name", "ask_user_question",
                        "description", "当任务缺少关键信息（缺失参数、需要确认或选择）时，"
                                + "先向用户提问再继续。宁可问人，不要瞎猜缺失的参数。",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "questions", Map.of(
                                                "type", "array",
                                                "description", "要问用户的问题列表",
                                                "items", Map.of(
                                                        "type", "object",
                                                        "properties", Map.of(
                                                                "question", Map.of(
                                                                        "type", "string",
                                                                        "description", "具体问题，含可选选项"),
                                                                "options", Map.of(
                                                                        "type", "array",
                                                                        "items", Map.of("type", "string"),
                                                                        "description", "可选：给用户的选择项")
                                                        ),
                                                        "required", List.of("question")
                                                ))
                                ),
                                "required", List.of("questions")
                        ))));

        /**
         * 清空会话：历史、挂起问题、挂起调用 id 全部归零，回到全新状态。
         * lastTrace 不清（保留最后一次轨迹供展示），下回合 chat 会先 clear。
         */
        @Override
        public void reset() {
            history.clear();
            pendingQuestion = null;
            pendingCallId = null;
        }

        /**
         * 本回合执行轨迹（只读快照），供 UI/Terminal 渲染。每次 chat 开始时清空重建。
         * <p>
         * <b>返回案例</b>（一轮「提问挂起」回合）：
         * <pre>{@code [
         *   {type="action", label="调用 ask_user_question 工具", detail="标准 Function Calling：..."},
         *   {type="guard",  label="会话挂起 PENDING_ASK",        detail="问题与 tool_call_id 已存入会话状态..."}
         * ]}</pre>
         * type 取值：action（调了工具）/ observation（工具结果回填）/ guard（协议与保险丝）/ final（收敛）。
         */
        @Override
        public List<Map<String, Object>> trace() {
            return List.copyOf(lastTrace);
        }
    }

    /**
     * 本地交互测试入口：java -cp target/classes cn.xiaofuge.ai.agent.step19.AskAgent [首轮任务]
     * <p>
     * 在 Terminal 里完成一整段人机协同对话：Agent 提问挂起时，终端会提示输入什么；
     * 输入答复（或「拒绝」）即回填挂起位，turn 续跑，直到给出最终答案。
     * <p>
     * 命令：<b>拒绝</b> = 不提供信息，让 Agent 自行处理；<b>reset</b> = 清空会话重来；<b>exit</b> = 结束。
     */
    public static void main(String[] args) {
        cn.xiaofuge.ai.llm.ChatModel model = cn.xiaofuge.ai.llm.Models.openAiOrNull();
        if (model == null) {
            model = cn.xiaofuge.ai.llm.Models.mock("step19");
            System.out.println("[模型] 未配置真实模型，使用 Mock 演示（发「帮我订一张去北京的机票」看人工介入）。");
        } else {
            // 真实模型不可用（网关 5xx 等）时逐次回落 Mock，保证交互演示始终可跑通
            cn.xiaofuge.ai.llm.ChatModel real = model;
            cn.xiaofuge.ai.llm.ChatModel fallback = cn.xiaofuge.ai.llm.Models.mock("step19");
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

        AskAgent agent = new Impl(model);
        java.util.Scanner scanner = new java.util.Scanner(System.in);

        System.out.println("=== Step19 · 人工介入（ask_user_question 工具：提问挂起 → 答复续跑）===");
        System.out.println("Agent 缺关键信息时会调工具向你提问并挂起 —— 按提示输入答复即可续跑。");
        System.out.println("命令：拒绝（不提供信息）｜ reset（重置会话）｜ exit（结束）\n");

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
                System.out.println("[会话] 已重置，上下文与挂起状态清空。\n");
                continue;
            }

            String output = agent.chat(input);

            // 渲染本回合执行轨迹（提问挂起 / 答复回填 / 完成）
            for (Map<String, Object> step : agent.trace()) {
                System.out.println("  ┈ [" + step.get("type") + "] " + step.get("label") + " —— " + step.get("detail"));
            }
            System.out.println("[Agent] " + output + "\n");

            // 挂起中：提示下一步输入什么（答复继续，或拒绝/退出）
            boolean pending = agent.trace().stream()
                    .anyMatch(m -> String.valueOf(m.get("label")).contains("PENDING_ASK"));
            if (pending) {
                System.out.println("⏸ Agent 已挂起，等你审批/答复 —— 请直接输入你的答复内容；");
                System.out.println("   输入「拒绝」表示不提供信息（Agent 会自行决定怎么办），或输入 exit 结束。\n");
            }
        }
    }
}
