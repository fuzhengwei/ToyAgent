package cn.xiaofuge.ai.llm;

import java.util.List;

/**
 * 模型的一次完整回复：要么是自然语言 content，要么是结构化 toolCalls。
 * <p>
 * 对应 OpenAI 协议中 choices[0].message 的两种形态：
 * 有 tool_calls 时代表"模型请求执行工具"，否则代表"最终回答"。
 */
public record ChatResponse(String content, List<ToolCall> toolCalls) {

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
