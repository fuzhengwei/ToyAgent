package cn.xiaofuge.ai.llm;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 浏览器级模型作用域。
 * <p>
 * 每个人在页面上配置的模型保存在各自浏览器的 localStorage 里，
 * 每次对话请求随身携带（不落盘、不进服务端全局配置）。
 * 服务端用 ThreadLocal 让配置只在「本次请求」内生效，
 * 多人共用同一个 ToyAgent 服务时互不干扰、互不覆盖。
 * <p>
 * 实现：场景装配时用 {@link #wrap} 把每个场景的默认模型包一层，
 * 请求线程带配置 → 用请求里的配置构造/复用模型；没带 → 走服务端默认。
 */
public final class ModelScope {

    /** 一次对话请求携带的模型配置（来自浏览器 localStorage）。 */
    public record Config(String baseUrl, String apiKey, String model, boolean mock) {
    }

    private static final ThreadLocal<Config> CURRENT = new ThreadLocal<>();

    /** 按配置构造的模型缓存（key = baseUrl|key|model），避免每次对话重复建连。 */
    private static final Map<String, ChatModel> CACHE = new ConcurrentHashMap<>();

    /** 把场景默认模型包一层：请求带配置时用请求里的，否则走服务端默认。 */
    public static ChatModel wrap(ChatModel fallback, String stepId) {
        return messages -> {
            Config c = CURRENT.get();
            if (c == null) {
                return fallback.chat(messages);
            }
            if (c.mock()) {
                // 浏览器要 Mock：默认模型本身就是 Mock 则直接复用（保留场景剧本），否则补一个对应场景的 Mock
                if (fallback instanceof MockChatModel) return fallback.chat(messages);
                return Models.mock(stepId).chat(messages);
            }
            // 未填 Key 时回退服务端默认 Key（Key 只留在服务端，不下发页面）
            String key = (c.apiKey() == null || c.apiKey().isBlank()) ? Models.serverApiKey() : c.apiKey();
            String cacheKey = c.baseUrl() + "|" + key + "|" + c.model();
            return CACHE
                    .computeIfAbsent(cacheKey, k -> Models.build(c.baseUrl(), key, c.model()))
                    .chat(messages);
        };
    }

    /** 绑定本次请求的模型配置（请求结束务必调用 {@link #clear}，线程池会复用线程）。 */
    public static void bind(Config c) {
        CURRENT.set(c);
    }

    /** 清除线程级配置。 */
    public static void clear() {
        CURRENT.remove();
    }
}
