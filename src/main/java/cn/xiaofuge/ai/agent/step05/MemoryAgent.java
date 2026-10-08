package cn.xiaofuge.ai.agent.step05;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Step05 · 记忆智能体 —— 让对话"有来有回"。
 * <p>
 * Step01 每轮都是失忆的。本场景引入短期记忆：把历史消息一并放进
 * 上下文窗口发给模型。当历史超过阈值时，用"摘要压缩"保护窗口 ——
 * 滑动窗口 + 摘要，就是上下文工程（Context Engineering）的基础盘。
 * <p>
 * 记忆的本质不是数据库，而是：每一次请求，都带上该带的历史。
 */
public interface MemoryAgent extends Agent {

    /** 保留的最大历史轮数（一条 user + 一条 assistant 算一轮）。 */
    int MAX_ROUNDS = 4;

    class Impl implements MemoryAgent {

        private final ChatModel model;
        private final List<Message> history = new ArrayList<>();
        private volatile String summary = "";
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        private static final String SYSTEM_PROMPT = """
                你是一个有记忆的聊天智能体。请基于"对话历史 + 压缩摘要 + 新输入"回答，
                用户提到的个人信息（如名字）要在后续对话中保持一致。
                """;

        public Impl(ChatModel model) {
            this.model = model;
        }

        @Override
        public String name() {
            return "Step05 · 记忆系统（多轮记忆 + 上下文压缩）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();
            history.add(Message.user(input));

            // 历史超限 → 压缩：把最旧的两轮合并为一条摘要
            if (history.size() > MAX_ROUNDS * 2) {
                compress();
            }

            // 组装上下文：系统提示词 + 摘要 + 历史
            List<Message> context = new ArrayList<>();
            context.add(Message.system(SYSTEM_PROMPT));
            if (!summary.isEmpty()) {
                context.add(Message.system("【历史摘要】" + summary));
            }
            context.addAll(history);

            lastTrace.add(Map.of(
                    "type", "memory",
                    "label", "记忆窗口",
                    "detail", "携带 " + history.size() + " 条历史消息" + (summary.isEmpty() ? "" : " + 1 条压缩摘要")));

            String answer = model.chat(context);
            history.add(Message.assistant(answer));

            lastTrace.add(Map.of(
                    "type", "final",
                    "label", "回答完成",
                    "detail", "本条回答同时看到：系统提示词" + (summary.isEmpty() ? "" : " + 摘要") + " + 全部历史 + 新输入"));
            return answer;
        }

        /** 上下文压缩：把最旧两轮摘成一句话，历史重新滑窗。 */
        private void compress() {
            Message old1 = history.remove(0);
            Message old2 = history.remove(0);
            String digest = clip(old1.content()) + "；随后助手回答了 " + clip(old2.content());
            summary = summary.isEmpty() ? digest : summary + " → " + digest;
            lastTrace.add(Map.of(
                    "type", "compress",
                    "label", "触发上下文压缩",
                    "detail", "最旧 2 轮已摘要化，摘要长度 " + summary.length() + " 字符"));
        }

        private String clip(String s) {
            String one = s.replaceAll("\\s+", " ").trim();
            return one.length() > 40 ? one.substring(0, 40) + "…" : one;
        }

        @Override
        public void reset() {
            history.clear();
            summary = "";
        }

        @Override
        public List<Map<String, Object>> trace() {
            return List.copyOf(lastTrace);
        }
    }
}
