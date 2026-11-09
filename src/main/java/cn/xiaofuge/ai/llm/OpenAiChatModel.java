package cn.xiaofuge.ai.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议的模型实现（DeepSeek / 通义 / 智谱 / 火山方舟等均适用）。
 * <p>
 * 核心就一次 HTTP 调用：POST {base-url}/chat/completions。
 * 支持标准 Function Calling：请求体携带 tools（JSON Schema 工具清单），
 * 响应中的 message 要么是 content（最终回答），要么是 tool_calls（结构化调用请求）。
 */
public final class OpenAiChatModel implements ChatModel {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public OpenAiChatModel(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String chat(List<Message> messages) {
        return chat(messages, List.of()).content();
    }

    /**
     * 唯一的 HTTP 出口：无论带不带 tools，走的都是同一个 /chat/completions。
     * 完整的 curl 示例（含工具传参与 role=tool 回填）见 {@link ChatModel#chat}。
     */
    @Override
    public ChatResponse chat(List<Message> messages, List<Map<String, Object>> tools) {
        // 1. 组装请求体：model + messages + tools（工具清单以 JSON Schema 声明）
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages.stream().map(OpenAiChatModel::toWire).toList());
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }
        body.put("temperature", 0.7);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        // 免鉴权网关（如 Kilo free）：api-key 留空时不发 Authorization 头，发了无效 token 反而 401
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        HttpRequest request = builder.build();

        try {
            // 2. 调用模型
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("模型调用失败 HTTP " + response.statusCode() + ": "
                        + snippet(response.body()));
            }
            // 3. 解析 choices[0].message：content 与 tool_calls 二选一（或并存）
            Object root = Json.parse(response.body());
            Object message = ((Map<?, ?>) ((List<?>) ((Map<?, ?>) root).get("choices")).get(0)).get("message");

            Object contentObj = ((Map<?, ?>) message).get("content");
            String content = contentObj == null ? "" : String.valueOf(contentObj);

            List<ToolCall> calls = new ArrayList<>();
            Object rawCalls = ((Map<?, ?>) message).get("tool_calls");
            if (rawCalls instanceof List<?> list) {
                for (Object item : list) {
                    Map<?, ?> call = (Map<?, ?>) item;
                    Map<?, ?> fn = (Map<?, ?>) call.get("function");
                    calls.add(new ToolCall(
                            String.valueOf(call.get("id")),
                            Json.str(fn, "name"),
                            Json.str(fn, "arguments")));
                }
            }
            return new ChatResponse(content, List.copyOf(calls));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("模型调用异常: " + e.getMessage(), e);
        }
    }

    /** 按 OpenAI 消息协议序列化：tool_calls / role=tool + tool_call_id 都要走线。 */
    private static Map<String, Object> toWire(Message m) {
        Map<String, Object> wire = Json.obj();
        wire.put("role", m.role());
        if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
            wire.put("tool_calls", m.toolCalls().stream().map(tc -> {
                Map<String, Object> fn = Json.obj();
                fn.put("name", tc.name());
                fn.put("arguments", tc.argumentsJson());
                Map<String, Object> call = Json.obj();
                call.put("id", tc.id());
                call.put("type", "function");
                call.put("function", fn);
                return call;
            }).toList());
            if (m.content() != null && !m.content().isBlank()) {
                wire.put("content", m.content());
            }
        } else {
            wire.put("content", m.content() == null ? "" : m.content());
            if ("tool".equals(m.role())) {
                wire.put("tool_call_id", m.toolCallId());
            }
        }
        return wire;
    }

    private String snippet(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
