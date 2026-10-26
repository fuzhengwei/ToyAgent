package cn.xiaofuge.ai.llm;

import java.util.List;
import java.util.Map;

/**
 * 模型抽象：智能体的"大脑"。
 * <p>
 * 智能体 = 模型 + 策略。本接口把"模型"收敛为两个最朴素的签名：
 * 纯文本对话，以及带工具清单的标准 Function Calling 对话。
 * 至于背后是 OpenAI、DeepSeek 还是教学用的 Mock，智能体并不关心。
 */
public interface ChatModel {

    /** 输入一组对话消息，输出模型回复文本。 */
    String chat(List<Message> messages);

    /**
     * 标准 Function Calling：请求体携带工具 Schema（OpenAI tools 字段），
     * 模型以结构化 tool_calls 返回调用请求，而非自由文本。
     * 默认实现退化为纯文本（不支持工具的模型/模型实现走这条路）。
     *
     * <p>协议本质就是一次 HTTP 调用，用 curl 直接体验（先看不带工具的裸对话）：
     * <pre>
     * curl http://127.0.0.1:8777/v1/chat/completions \
     *   -H "Content-Type: application/json" \
     *   -H "Authorization: Bearer sk-waliapi-4f8895c43a6c4bc1ae6173fd0915c0e9" \
     *   -d '{
     *     "model": "deepseek-v4-flash",
     *     "messages": [{"role": "user", "content": "Hello!"}]
     *   }'
     * </pre>
     *
     * <p><b>工具怎么传参</b> —— 在请求体里加一个 tools 数组，每个工具用
     * JSON Schema 声明「名称 + 用途 + 参数结构」，模型据此决定何时调用、参数怎么填：
     * <pre>
     * curl http://127.0.0.1:8777/v1/chat/completions \
     *   -H "Content-Type: application/json" \
     *   -H "Authorization: Bearer sk-waliapi-4f8895c43a6c4bc1ae6173fd0915c0e9" \
     *   -d '{
     *     "model": "deepseek-v4-flash",
     *     "messages": [{"role": "user", "content": "北京天气怎么样？"}],
     *     "tools": [{
     *       "type": "function",
     *       "function": {
     *         "name": "get_weather",
     *         "description": "查询指定城市的实时天气",
     *         "parameters": {
     *           "type": "object",
     *           "properties": {"city": {"type": "string", "description": "城市名，如：北京"}},
     *           "required": ["city"]
     *         }
     *       }
     *     }]
     *   }'
     * </pre>
     *
     * <p>模型判断需要工具时，不再返回 content，而是返回结构化调用请求：
     * <pre>
     * "message": {
     *   "role": "assistant",
     *   "tool_calls": [{
     *     "id": "call_abc123",
     *     "type": "function",
     *     "function": {"name": "get_weather", "arguments": "{\"city\": \"北京\"}"}
     *   }]
     * }
     * </pre>
     *
     * <p>Agent 执行工具后，把上一轮 assistant(tool_calls) 原样留在 messages 里，
     * 再追加一条 role=tool 消息（带 tool_call_id 对号入座），重新请求模型：
     * <pre>
     * "messages": [
     *   {"role": "user",      "content": "北京天气怎么样？"},
     *   {"role": "assistant", "tool_calls": [{"id": "call_abc123", "type": "function",
     *                                        "function": {"name": "get_weather", "arguments": "{\"city\": \"北京\"}"}}]},
     *   {"role": "tool",      "tool_call_id": "call_abc123", "content": "晴，26℃"}
     * ]
     * </pre>
     * 模型拿到事实后返回普通 content，即最终回答 —— 循环可重复，直到不再发起 tool_calls。
     */
    default ChatResponse chat(List<Message> messages, List<Map<String, Object>> tools) {
        return new ChatResponse(chat(messages), List.of());
    }

}
