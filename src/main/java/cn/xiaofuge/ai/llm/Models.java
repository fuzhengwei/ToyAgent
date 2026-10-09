package cn.xiaofuge.ai.llm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 模型工厂：读取/保存配置，产出 ChatModel。
 * <p>
 * 配置优先级：项目根目录 config.properties > 环境变量。
 * 页面上可以通过 /api/model 动态更新配置（持久化回 config.properties），
 * 未配置 api-key 时返回 null，由上层为每个场景装配 MockChatModel。
 */
public final class Models {

    private static String baseUrl;
    private static String apiKey;
    private static String modelName;

    static {
        load();
    }

    private static void load() {
        Properties props = new Properties();
        Path conf = Path.of("config.properties");
        boolean fromFile = false;
        if (Files.exists(conf)) {
            try (InputStream in = Files.newInputStream(conf)) {
                props.load(in);
                fromFile = true;
            } catch (IOException e) {
                System.err.println("[ToyAgent] 读取 config.properties 失败: " + e.getMessage());
            }
        }
        // 优先级：config.properties（非空值）> LLM_* 环境变量 > OPENAI_* 环境变量 > 内置默认
        // 文件里留空（api-key=）不遮蔽环境变量 —— 支持纯 docker run -e 方式配置默认模型
        baseUrl = firstNonBlank(
                props.getProperty("base-url"),
                env("LLM_BASE_URL", null), env("OPENAI_BASE_URL", null),
                "https://api.deepseek.com/v1");
        apiKey = firstNonBlank(
                props.getProperty("api-key"),
                env("LLM_API_KEY", null), env("OPENAI_API_KEY", null),
                "");
        modelName = firstNonBlank(
                props.getProperty("model"),
                env("LLM_MODEL", null), env("OPENAI_MODEL", null),
                "deepseek-chat");
        String keySrc = props.getProperty("api-key");
        boolean keyFromFile = keySrc != null && !keySrc.isBlank();
        if (!apiKey.isBlank()) {
            System.out.println("[ToyAgent] 默认模型: " + modelName + " @ " + baseUrl
                    + (keyFromFile ? "（Key 来自 config.properties）" : "（Key 来自环境变量）"));
        }
    }

    /** 返回第一个非空值；全空则返回最后一个（内置默认）。 */
    private static String firstNonBlank(String... vals) {
        for (int i = 0; i < vals.length - 1; i++) {
            if (vals[i] != null && !vals[i].isBlank()) return vals[i];
        }
        return vals[vals.length - 1];
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    /** 有真实 Key 则返回 OpenAI 兼容模型，否则返回 null（使用 Mock）。 */
    public static OpenAiChatModel openAiOrNull() {
        if (apiKey == null || apiKey.isBlank()) return null;
        return new OpenAiChatModel(baseUrl, apiKey, modelName);
    }

    /** 教学演示用 Mock 模型。 */
    public static MockChatModel mock(String scenario) {
        return new MockChatModel(scenario);
    }

    public static boolean isRealModel() {
        return apiKey != null && !apiKey.isBlank();
    }

    public static String modelName() {
        return isRealModel() ? modelName : "mock-model";
    }

    public static String baseUrl() {
        return baseUrl;
    }

    /** 脱敏后的 Key，用于页面回显。 */
    public static String maskedKey() {
        if (apiKey == null || apiKey.isBlank()) return "";
        if (apiKey.length() <= 10) return "******";
        return apiKey.substring(0, 5) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    /** 页面配置：更新模型并持久化到 config.properties。 */
    public static synchronized void update(String newBaseUrl, String newApiKey, String newModel) {
        if (newBaseUrl != null && !newBaseUrl.isBlank()) baseUrl = newBaseUrl.trim();
        if (newApiKey != null && !newApiKey.isBlank()) apiKey = newApiKey.trim();
        if (newModel != null && !newModel.isBlank()) modelName = newModel.trim();
        save();
    }

    /** 页面切换：回到 Mock 演示模式（清空 Key）。 */
    public static synchronized void useMock() {
        apiKey = "";
        save();
    }

    /** 供"测试连接"临时构造模型，不改动当前配置。 */
    public static OpenAiChatModel build(String baseUrlArg, String keyArg, String modelArg) {
        return new OpenAiChatModel(baseUrlArg, keyArg, modelArg);
    }

    /** 服务端默认 Key（只供服务端内部回填，不下发页面）。 */
    public static String serverApiKey() {
        return apiKey == null ? "" : apiKey;
    }

    private static void save() {
        Properties props = new Properties();
        props.setProperty("base-url", baseUrl == null ? "" : baseUrl);
        props.setProperty("api-key", apiKey == null ? "" : apiKey);
        props.setProperty("model", modelName == null ? "" : modelName);
        try (OutputStream out = Files.newOutputStream(Path.of("config.properties"))) {
            props.store(out, "ToyAgent model config (edited from web UI)");
        } catch (IOException e) {
            System.err.println("[ToyAgent] 保存 config.properties 失败: " + e.getMessage());
        }
    }
}
