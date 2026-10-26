package cn.xiaofuge.ai.llm;

import java.util.List;

/**
 * 一条对话消息。role 取值：system / user / assistant / tool。
 * <p>
 * 在最朴素的「角色 + 文本」之上，补充标准 Function Calling 协议的两个字段：
 * <ul>
 *   <li>assistant 消息可携带 toolCalls —— 模型"请求执行工具"；</li>
 *   <li>tool 消息带 toolCallId —— 工具结果按 id 回填给模型。</li>
 * </ul>
 * 这就是 OpenAI tools / tool_calls 协议里消息的全部形态。
 */
public record Message(String role, String content, List<ToolCall> toolCalls, String toolCallId) {

    /** 兼容旧签名：普通文本消息（system / user / assistant）。 */
    public Message(String role, String content) {
        this(role, content, null, null);
    }

    public static Message system(String content) {
        return new Message("system", content);
    }

    public static Message user(String content) {
        return new Message("user", content);
    }

    public static Message assistant(String content) {
        return new Message("assistant", content);
    }

    /** assistant 消息携带工具调用请求（模型说：请帮我执行这些工具）。 */
    public static Message assistantToolCalls(List<ToolCall> calls) {
        return new Message("assistant", "", calls, null);
    }

    /** 工具结果回填消息：按 tool_call_id 对号入座。 */
    public static Message tool(String toolCallId, String content) {
        return new Message("tool", content, null, toolCallId);
    }
}
