package cn.xiaofuge.ai.agent.step19;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Step19 · 人工介入 —— ask_user_question：把「异步的人」翻译成「可等待的答复」。
 * <p>
 * 参考 deepseek-harness-java 的 ask_user_question 工具（CompletableFuture 阻塞-唤醒）。
 * ToyAgent 是同步 HTTP 架构，教学版用同一思想的另一种实现：<b>会话挂起 + 续答</b>——
 * <ol>
 *   <li>模型发现关键信息缺失时，调用 ask_user_question「工具」，turn 挂起，
 *       问题持久化在会话状态里返回给用户；</li>
 *   <li>用户的下一条消息不再进模型，而是作为<b>人工答复</b>回填挂起的工具位，
 *       turn 恢复继续执行。</li>
 * </ol>
 * 生产版的差别只在「等待」的实现：dsh-java 用 Future 阻塞住执行线程等人，
 * 教学版把等待摊开成两次请求 —— 人机协同的语义完全一致。
 */
public interface AskAgent extends Agent {

    class Impl implements AskAgent {

        private final ChatModel model;
        private final List<Message> history = new ArrayList<>();
        /** 挂起中的问题：非 null 表示 turn 暂停，等待人工答复。 */
        private String pendingQuestion;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        public Impl(ChatModel model) {
            this.model = model;
        }

        @Override
        public String name() {
            return "Step19 · 人工介入（ask_user_question：提问挂起 → 答复续跑）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();

            // ===== 分支一：有挂起问题 —— 本条输入是「人工答复」，不进模型 =====
            if (pendingQuestion != null) {
                String q = pendingQuestion;
                pendingQuestion = null;
                lastTrace.add(Map.of("type", "observation", "label", "人工答复回填",
                        "detail", "挂起问题「" + q + "」收到答复：" + input
                                + "（生产版是 CompletableFuture 阻塞-唤醒，教学版把等待摊开成两次请求）"));
                history.add(Message.user("人工答复：" + input));

                String answer = complete(q);
                history.add(Message.assistant(answer));
                return answer;
            }

            // ===== 分支二：正常对话 —— 模型可发起 ask_user_question =====
            history.add(Message.user(input));
            List<Message> req = new ArrayList<>();
            req.add(Message.system(SYSTEM_PROMPT));
            history.forEach(m -> req.add(m));

            String raw = model.chat(req).trim();
            Object parsed = tryParse(raw);

            String question = parsed instanceof Map<?, ?> m ? Json.str(m, "ask") : null;
            if (question != null) {
                // 模型主动向人提问：挂起 turn，问题存入会话状态
                pendingQuestion = question;
                lastTrace.add(Map.of("type", "action", "label", "调用 ask_user_question",
                        "detail", "turn 挂起，等待人工答复 —— 「异步的人」被翻译成了「可等待的工具结果」"));
                lastTrace.add(Map.of("type", "guard", "label", "会话挂起 PENDING_ASK",
                        "detail", "问题已持久化在会话状态（重启不丢），下一条消息将作为答复回填"));
                return question + "\n\n（直接回复即可，我会带着你的答复继续任务。）";
            }

            String answer = parsed instanceof Map<?, ?> m2 ? orRaw(Json.str(m2, "final"), raw) : raw;
            lastTrace.add(Map.of("type", "final", "label", "直接作答",
                    "detail", "信息完整，无需人工介入"));
            history.add(Message.assistant(answer));
            return answer;
        }

        /** 答复回填后，带着完整信息让模型继续完成任务（turn 恢复）。 */
        private String complete(String question) {
            List<Message> req = new ArrayList<>();
            req.add(Message.system(SYSTEM_PROMPT + "\n（此前你曾向用户提问「" + question
                    + "」，用户已答复，见最后一条人工答复。现在信息完整，直接完成任务并作答。）"));
            history.forEach(m -> req.add(m));
            String raw = model.chat(req).trim();
            Object parsed = tryParse(raw);
            String fin = parsed instanceof Map<?, ?> m ? Json.str(m, "final") : null;
            lastTrace.add(Map.of("type", "final", "label", "turn 恢复 · 完成",
                    "detail", "人工答复注入上下文，任务继续执行 —— 人机协同闭环"));
            return parsed instanceof Map<?, ?> m ? orRaw(fin, raw) : raw;
        }

        private static final String SYSTEM_PROMPT = """
                你是一个懂得「何时该问人」的智能体。
                当任务缺少关键信息（如预订缺少出发地、时间），不要猜测，主动向人提问：
                只输出 JSON：{"ask": "你的问题（含可选选项）"}
                信息完整时直接完成任务，只输出 JSON：{"final": "最终答案"}。不要输出 JSON 以外内容。""";

        @Override
        public void reset() {
            history.clear();
            pendingQuestion = null;
        }

        private static String orRaw(String v, String raw) {
            return v == null || v.isBlank() ? raw : v;
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
