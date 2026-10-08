package cn.xiaofuge.ai.agent.step01;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.Message;

import java.util.List;

/**
 * Step01 · 对话智能体 —— 智能体的最小 MVP。
 * <p>
 * 一个接口，一个方法，没有策略、没有工具、没有记忆。
 * 它证明了一件事：所谓智能体，起点只是「把用户的话交给模型，把模型的话还给用户」。
 * 后续 11 个场景，都是往这个最小骨架里加入一种能力。
 */
public interface ChatAgent extends Agent {

    /** 最小 MVP 实现：每轮对话独立，输入 → 模型 → 输出。 */
    class Impl implements ChatAgent {

        private final ChatModel model;

        public Impl(ChatModel model) {
            this.model = model;
        }

        @Override
        public String name() {
            return "Step01 · 对话智能体（最小 MVP）";
        }

        @Override
        public String chat(String input) {
            // 全部实现就这一行核心逻辑
            return model.chat(List.of(Message.user(input)));
        }
    }
}
