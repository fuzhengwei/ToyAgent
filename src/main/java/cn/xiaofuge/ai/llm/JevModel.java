package cn.xiaofuge.ai.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Jev 决策模型（System One）的 HTTP 客户端 —— 与 {@link OpenAiChatModel} 平级的另一个模型端口。
 * <p>
 * <b>Jev 是什么？</b> TypeSafe 推出的 System One 决策模型，名字致敬卡尼曼
 * 《思考，快与慢》里"快而直觉"的系统一。它与聊天大模型的分工一句话概括：
 * <pre>
 *   LLM（System Two）：生成给人看的文本 —— 会写诗、会推理、会废话
 *   Jev（System One）：生成给程序用的判断 —— 只回答你框死的选择题
 * </pre>
 * 官方口号 "Decisions, not strings"——要决策，不要字符串。它不生成任何文本，
 * 只做结构化判断，且每个 choice/score 答案都带<b>校准过的置信度</b>
 * （confidence=0.91 意味着"历史上这类置信度的判断约 91% 是对的"），
 * 让置信度门控成为严肃的工程手段：答案告诉你"是什么"，置信度告诉你"该不该动手"。
 * <p>
 * <b>协议（HTTP 形态，TypeSafe 官方端点为 /v1/systemone）：</b>
 * <pre>
 * POST {jev-url}
 * {"model": "jev-1.14",
 *  "state": "帮我看看明天上海天气怎么样",        // 要判断的材料，纯文本或任意 JSON
 *  "questions": {                              // 问题名自取，答案按同名返回
 *    "intent": {"type": "choice", "instructions": "用户想做什么？",
 *               "criteria": {"weather": "查询天气", ...}}}}
 * // 响应：
 * {"model": "jev-1.14",                        // 实际执行的版本号，应打进日志
 *  "answers": {"intent": {"type": "choice", "choice": "weather",
 *              "confidence": 1.0, "probabilities": {...}}},
 *  "usage": {"input_tokens": ..., "output_tokens": ...}}
 * </pre>
 * <b>三种判断原语（题型）：</b>
 * <ul>
 *   <li>{@code choice} —— N 选一：criteria 是 {选项名: 选项说明}（最多 255 个，
 *       构成闭集）。返回选中项 + 完整概率分布 + 置信度。受约束输出：答案
 *       <b>保证</b>落在选项集合内，schema 外没有东西可返回。</li>
 *   <li>{@code noul} —— 是非判断：criteria 可选（定义"什么算是/什么算否"）。
 *       返回 0..1 的"是"概率——它自己就是概率，所以没有 confidence 字段。</li>
 *   <li>{@code score} —— 分档打分：criteria 是有序数组（2~10 档，下标即等级）。
 *       返回分数（概率加权的小数，天然落在档位之间，别当整数开关用）+
 *       legend 档位表 + 置信度。</li>
 * </ul>
 * state 只发一次，所有问题并行评估、互相隔离——加问题几乎不增加耗时。
 * 计费几乎只按输入 token，输出不计费。
 * <p>
 * <b>生态兼容性：</b>本类只依赖上述协议字段，因此换厂商零适配——
 * TypeSafe 官方、阿里云百炼 decision-model-preview、各类开源复现
 * （jev-local / LitJev 等）都兼容同一套协议，只改 base-url / key / model 三个配置。
 * 配置读取见 {@link Models#jevOrNull()}（config.properties 的 jev-* 三项）。
 */
public final class JevModel {

    /**
     * 一次 choice 判断的结果：选中项 + 置信度 + 概率分布。
     * probabilities 按选项名归一化，可用于展示"它对每个选项各有多确定"。
     */
    public record Decision(String choice, double confidence, Map<String, Double> probabilities) {
    }

    private final String url;
    private final String apiKey;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public JevModel(String url, String apiKey, String model) {
        this.url = url.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.model = model;
    }

    /**
     * 通用求值：state + questions（协议原样）→ answers 映射。
     * <p>
     * questions 的形状即协议里的 questions 字段，例如：
     * <pre>{@code
     * Map<String, Object> intent = Json.obj();
     * intent.put("type", "choice");
     * intent.put("instructions", "用户想做什么？");
     * intent.put("criteria", Map.of("weather", "查询天气", "chat", "其他对话"));
     * Map<String, Object> questions = Json.obj();
     * questions.put("intent", intent);
     * Map<String, Object> answers = jev.evaluate(input, questions);
     * }</pre>
     * 一次调用可以放多个问题，全部并行评估。返回的 answers 按问题名取答案。
     */
    public Map<String, Object> evaluate(String state, Map<String, Object> questions) {
        Map<String, Object> body = Json.obj();
        body.put("model", model);
        body.put("state", state);
        body.put("questions", questions);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();

        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Jev 决策调用失败 HTTP " + response.statusCode() + ": "
                        + snippet(response.body()));
            }
            Object root = Json.parse(response.body());
            Object answers = ((Map<?, ?>) root).get("answers");
            if (!(answers instanceof Map<?, ?> a)) {
                throw new IllegalStateException("Jev 响应缺少 answers 字段");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) a;
            return result;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Jev 决策调用异常: " + e.getMessage(), e);
        }
    }

    /**
     * choice 题型的便捷方法：一次判断"N 选一"。
     * <p>
     * criteria 即 {选项名: 选项说明} 的闭集——说明就是判据，改描述即可
     * 改善判断质量（"描述即路由"），零代码。返回带置信度与概率分布的
     * {@link Decision}，交由上层做置信度门控。
     */
    public Decision choice(String state, String questionName, String instructions,
                           Map<String, Object> criteria) {
        Map<String, Object> question = Json.obj();
        question.put("type", "choice");
        question.put("instructions", instructions);
        question.put("criteria", criteria);

        Map<String, Object> questions = Json.obj();
        questions.put(questionName, question);

        Object answer = evaluate(state, questions).get(questionName);
        if (!(answer instanceof Map<?, ?> a)) {
            throw new IllegalStateException("Jev 响应缺少问题 " + questionName + " 的答案");
        }
        String choice = Json.str(answer, "choice");
        if (choice == null) {
            throw new IllegalStateException("answers." + questionName + " 缺少 choice");
        }
        double confidence = num(a.get("confidence"));

        Map<String, Double> probabilities = new LinkedHashMap<>();
        Object probs = a.get("probabilities");
        if (probs instanceof Map<?, ?> p) {
            for (Map.Entry<?, ?> e : p.entrySet()) {
                probabilities.put(String.valueOf(e.getKey()), num(e.getValue()));
            }
        }
        return new Decision(choice, confidence, probabilities);
    }

    private static double num(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    private String snippet(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
