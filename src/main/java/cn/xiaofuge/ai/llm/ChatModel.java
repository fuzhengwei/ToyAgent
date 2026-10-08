package cn.xiaofuge.ai.llm;

import java.util.List;

/**
 * 模型抽象：智能体的"大脑"。
 * <p>
 * 智能体 = 模型 + 策略。本接口把"模型"收敛为一个最朴素的签名：
 * 给我一串消息，我还你一段文本。至于背后是 OpenAI、DeepSeek
 * 还是一个用于教学演示的 Mock，智能体并不关心。
 */
public interface ChatModel {

    /** 输入一组对话消息，输出模型回复文本。 */
    String chat(List<Message> messages);
}
