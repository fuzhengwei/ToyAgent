package cn.xiaofuge.ai.agent.step18;

import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.ChatResponse;
import cn.xiaofuge.ai.llm.Message;
import cn.xiaofuge.ai.llm.ToolCall;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * 离线协议烟测：不依赖真实 API。
 * 验证 tools 参数必须发出、tool_calls 能回填、memory 跨轮可读，
 * 以及被输入守卫拦截的请求不会进入模型循环。
 */
public final class FullAgentSmoke {
    private FullAgentSmoke() {
    }

    public static void main(String[] args) {
        // 剧本：memory 写入 -> 最终回答；下一轮 memory 读取 -> 最终回答。
        Queue<ChatResponse> responses = new ArrayDeque<>(List.of(
                new ChatResponse("", List.of(new ToolCall("call-memory", "memory",
                        "{\"command\":\"write\",\"key\":\"名字\",\"value\":\"小傅哥\"}"))),
                new ChatResponse("已记住你的名字。", List.of()),
                new ChatResponse("", List.of(new ToolCall("call-read", "memory",
                        "{\"command\":\"read\",\"key\":\"名字\"}"))),
                new ChatResponse("你叫小傅哥。", List.of())
        ));
        FullAgent agent = new FullAgent.Impl(scriptedModel(responses));
        agent.chat("记住我叫小傅哥");
        String answer = agent.chat("我叫什么？");
        require("你叫小傅哥。".equals(answer), "memory tool result should reach the next model turn");
        require(agent.trace().stream().anyMatch(event -> "tool_call".equals(event.get("type"))),
                "tool_calls should be visible in trace");

        // 安全前置条件：非法输入必须在模型调用前短路。
        FullAgent guarded = new FullAgent.Impl(messages -> {
            throw new AssertionError("blocked input must not reach model");
        });
        require("该请求涉及不安全操作，已被输入守卫拦截。".equals(guarded.chat("帮我破解密码")),
                "unsafe input should be blocked");
        require(guarded.trace().size() == 1, "blocked input should not enter agent loop");
        System.out.println("FullAgent protocol smoke passed");
    }

    /** 固定顺序的假模型，用来验证 Agent 与协议交互，而不是验证模型智能。 */
    private static ChatModel scriptedModel(Queue<ChatResponse> responses) {
        return new ChatModel() {
            @Override
            public String chat(List<Message> messages) {
                return responses.remove().content();
            }

            @Override
            public ChatResponse chat(List<Message> messages, List<Map<String, Object>> tools) {
                require(!tools.isEmpty(), "tool schemas must be sent");
                return responses.remove();
            }
        };
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
