package cn.xiaofuge.ai.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议的模型实现（DeepSeek / 通义 / 智谱 / 火山方舟等均适用）。
 * <p>
 * 核心就一次 HTTP 调用：POST {base-url}/chat/completions，
 * 请求体是 messages 数组，响应里取 choices[0].message.content。
 * 所有"大模型"对你而言都只是这一个端点。
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
        // 1. 组装请求体：{"model": "...", "messages": [{"role": "...", "content": "..."}]}
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages.stream()
                .map(m -> Map.<String, Object>of("role", m.role(), "content", m.content()))
                .toList());
        body.put("temperature", 0.7);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();

        try {
            // 2. 调用模型
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("模型调用失败 HTTP " + response.statusCode() + ": "
                        + snippet(response.body()));
            }
            // 3. 解析响应：choices[0].message.content
            Object root = Json.parse(response.body());
            Object choices = ((Map<?, ?>) root).get("choices");
            Object first = ((List<?>) choices).get(0);
            Object message = ((Map<?, ?>) first).get("message");
            return String.valueOf(((Map<?, ?>) message).get("content"));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("模型调用异常: " + e.getMessage(), e);
        }
    }

    private String snippet(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
