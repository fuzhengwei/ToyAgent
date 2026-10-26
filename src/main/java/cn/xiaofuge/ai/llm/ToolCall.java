package cn.xiaofuge.ai.llm;

/**
 * 模型发起的一次工具调用（OpenAI tool_calls 协议里的最小单元）。
 * <p>
 * 模型不再"猜着"输出 JSON 文本，而是由服务端保证返回结构：
 * id 用于结果回填时对号入座，name 指定工具，argumentsJson 是参数原文。
 */
public record ToolCall(String id, String name, String argumentsJson) {
}
