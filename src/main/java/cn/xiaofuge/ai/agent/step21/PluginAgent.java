package cn.xiaofuge.ai.agent.step21;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;
import cn.xiaofuge.ai.agent.step15.ToolDefinition;
import cn.xiaofuge.ai.agent.step15.ToolRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

/**
 * Step21 · 插件机制 —— 把「一批工具」打包成可安装、可卸载的插件。
 * <p>
 * 与 Step15 的关系：Step15 解决「工具怎么注册」，本步解决「工具从哪来」——
 * 插件是工具的动态载体：安装插件 = 批量注册，卸载插件 = 批量调用 disposer。
 * 对应 deepseek-harness-java 的 JavaPluginLoader + PluginToolBridgeService。
 * <p>
 * 四个关键设计（与 dsh-java 一一对应）：
 * <ol>
 *   <li>契约 SPI：{@link AgentPlugin} 接口（id/onStart/onStop/tools），等价 dsh-java 的 JavaHarnessPlugin + PluginManifest；</li>
 *   <li>隔离加载：独立 {@link URLClassLoader}，插件类与宿主类互不污染，卸载时必须 close()；</li>
 *   <li>生命周期：install → onStart → 收集工具 → 批量注册 →（卸载）批量注销 → onStop → close；</li>
 *   <li>工具桥接：插件的 ToolDefinition 注册进 {@link ToolRegistry}，模型立即「看见」——
 *       注册项的 disposer 按 pluginId 归档，即 dsh-java 的 PluginToolBridgeService.pluginDisposers。</li>
 * </ol>
 * 试试本场景的本地指令：
 * <pre>
 *   安装插件                —— 释放插件包 → 隔离加载 → 批量注册 2 个工具
 *   查看工具清单            —— 注册表当前内容（模型看到的清单）
 *   掷一次硬币 / 运行多久了  —— 调用插件带来的工具
 *   卸载插件                —— 批量注销 + onStop + 关闭 ClassLoader
 *   再看看工具清单          —— 插件工具已消失，宿主回到零工具
 * </pre>
 */
public interface PluginAgent extends Agent {

    /** 插件契约：插件作者唯一需要实现的东西（dsh-java 的 JavaHarnessPlugin 简化版）。 */
    interface AgentPlugin {
        /** 插件唯一标识，卸载时靠它批量回收注册项。 */
        String id();
        /** 插件版本。 */
        String version();
        /** 生命周期：安装/激活后由宿主调用一次。 */
        default void onStart() {}
        /** 生命周期：卸载前由宿主调用一次，释放插件自有资源。 */
        default void onStop() {}
        /** 插件带来的工具：宿主逐个注册进 ToolRegistry。 */
        List<ToolDefinition> tools();
    }

    class Impl implements PluginAgent {

        private static final Path PLUGIN_DIR = Path.of("plugins", "step21");
        private static final String ENTRY = "cn.xiaofuge.ai.agent.step21.PluginAgent$TimePlugin";
        /** 演示插件的全部类文件：宿主把它们释放到 plugins/ 目录，模拟用户「放入插件包」。 */
        private static final String[] PLUGIN_CLASSES = {
                "cn/xiaofuge/ai/agent/step21/PluginAgent$TimePlugin.class",
                "cn/xiaofuge/ai/agent/step21/PluginAgent$TimePlugin$UptimeTool.class",
                "cn/xiaofuge/ai/agent/step21/PluginAgent$TimePlugin$CoinTool.class"};

        private record Loaded(AgentPlugin plugin, URLClassLoader classLoader,
                              List<String> toolNames, List<Runnable> disposers) {}

        private final ChatModel model;
        private final ToolRegistry registry = new ToolRegistry();
        private final Instant startedAt = Instant.now();
        /** 已安装插件：pluginId → 已加载实例与回收资源。 */
        private final Map<String, Loaded> installed = new LinkedHashMap<>();
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        public Impl(ChatModel model) {
            this.model = model;
        }

        @Override
        public String name() {
            return "Step21 · 插件机制（JavaPluginLoader 隔离加载）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();
            lastTrace.add(Map.of("type", "tool", "label", "注册表就绪",
                    "detail", "宿主内置 0 个工具 —— 能力全部由插件装进来（已安装 " + installed.size() + " 个插件）"));

            // ---- 本地指令 1：安装插件 ----
            if (input.contains("安装插件") || input.contains("装载插件")) {
                return install();
            }

            // ---- 本地指令 2：卸载插件 ----
            if (input.contains("卸载插件") || input.contains("移除插件")) {
                return uninstall();
            }

            // ---- 本地指令 3：查看工具清单 ----
            if (input.contains("查看工具") || input.contains("工具清单")) {
                String list = installed.isEmpty() ? "（无）" : String.join("、", installed.keySet());
                lastTrace.add(Map.of("type", "prompt", "label", "工具清单 promptCatalog()",
                        "detail", registry.isEmpty() ? "（注册表为空）" : registry.promptCatalog().trim()));
                return "已安装插件：" + list + "\n当前注册表共 " + registry.size() + " 个工具：\n"
                        + (registry.isEmpty() ? "（空）" : registry.promptCatalog())
                        + "\n插件注册的工具对模型完全透明 —— 它只看得到清单，不知道工具是内置的还是插件带来的。";
            }

            // ---- 常规对话：清单进提示词 → 模型决策 → 注册表执行 ----
            List<Message> messages = new ArrayList<>();
            messages.add(Message.system(SYSTEM_PROMPT_PREFIX + (registry.isEmpty() ? "（当前没有任何工具）" : registry.promptCatalog())
                    + "\n决策规则：\n- 需要工具时，只输出 JSON：{\"tool\": \"工具名\", \"arguments\": {}}\n"
                    + "- 不需要工具时，直接用自然语言回答，不要输出 JSON。"));
            messages.add(Message.user(input));

            for (int round = 1; round <= 2; round++) {
                String raw = model.chat(messages).trim();
                Object decision = tryParse(raw);

                if (decision instanceof Map<?, ?> && Json.str(decision, "tool") != null) {
                    String toolName = Json.str(decision, "tool");
                    lastTrace.add(Map.of("type", "action", "label", "模型发起工具调用",
                            "detail", toolName + " " + Json.write(((Map<?, ?>) decision).get("arguments"))));

                    var tool = registry.lookup(toolName);
                    String result;
                    if (tool.isEmpty()) {
                        result = "未知工具: " + toolName + "（插件未安装，或已被卸载）";
                        lastTrace.add(Map.of("type", "guard", "label", "注册表未命中",
                                "detail", "卸载插件后其工具随之消失 —— 模型看不见，也调不到"));
                    } else {
                        try {
                            result = tool.get().execute(Map.of());
                        } catch (Exception e) {
                            result = "工具执行失败: " + e.getMessage();
                        }
                        lastTrace.add(Map.of("type", "observation", "label", "注册表执行 " + toolName,
                                "detail", result));
                    }

                    messages.add(Message.assistant(raw));
                    messages.add(Message.user("工具 " + toolName + " 执行结果: " + result));
                    continue;
                }

                lastTrace.add(Map.of("type", "final", "label", "最终回答",
                        "detail", "模型基于插件注册的工具完成决策"));
                return raw;
            }
            return "已达最大轮数，循环终止。";
        }

        /** 安装：释放插件包 → 隔离加载 → onStart → 收集工具 → 批量注册。 */
        private String install() {
            if (!installed.isEmpty()) {
                return "已有插件在运行：" + String.join("、", installed.keySet()) + "。先卸载再安装其他插件。";
            }
            try {
                // 1. 释放插件包（真实场景：用户下载 JAR 放入 plugins/ 目录）
                materializePluginPackage();
                lastTrace.add(Map.of("type", "tool", "label", "插件包落盘 " + PLUGIN_DIR,
                        "detail", "TimePlugin.class + plugin.properties（entrypoint 声明入口类，等价 dsh-java 的 META-INF/plugin.yaml）"));

                // 2. 独立 ClassLoader 隔离加载：parent 指向宿主，插件类只从 plugins/ 目录来
                URLClassLoader cl = new URLClassLoader(
                        new URL[]{PLUGIN_DIR.toUri().toURL()}, PluginAgent.class.getClassLoader());
                try {
                    Class<?> entry = Class.forName(ENTRY, true, cl);
                    AgentPlugin plugin = (AgentPlugin) entry.getDeclaredConstructor().newInstance();
                    plugin.onStart();
                    lastTrace.add(Map.of("type", "action", "label", "URLClassLoader 隔离加载",
                            "detail", "入口 " + ENTRY + " 实例化成功，onStart() 已调用 —— 类与宿主隔离，卸载时 close() 回收"));

                    // 3. 工具桥接：逐个注册，disposer 按 pluginId 归档（dsh-java: pluginDisposers）
                    List<String> toolNames = new ArrayList<>();
                    List<Runnable> disposers = new ArrayList<>();
                    for (ToolDefinition tool : plugin.tools()) {
                        disposers.add(registry.register(tool));
                        toolNames.add(tool.name());
                    }
                    lastTrace.add(Map.of("type", "observation", "label", "工具桥接 batch register",
                            "detail", toolNames + " 已注册进 ToolRegistry，disposers 按 pluginId 归档 —— 卸载即批量注销"));

                    installed.put(plugin.id(), new Loaded(plugin, cl, toolNames, disposers));
                    return "插件 " + plugin.id() + " v" + plugin.version() + " 安装成功，带来 " + toolNames.size()
                            + " 个工具：\n" + registry.promptCatalog()
                            + "\n现在可以直接对话使用（如「掷一次硬币」「运行多久了」），模型自动看见新工具。";
                } catch (Exception e) {
                    closeQuietly(cl);
                    return "插件加载失败：" + e.getMessage();
                }
            } catch (IOException e) {
                return "插件包落盘失败：" + e.getMessage();
            }
        }

        /** 卸载：批量注销工具 → onStop → 关闭 ClassLoader。 */
        private String uninstall() {
            if (installed.isEmpty()) {
                return "当前没有已安装的插件。先试试「安装插件」。";
            }
            StringBuilder sb = new StringBuilder();
            installed.forEach((id, loaded) -> {
                loaded.disposers().forEach(Runnable::run);
                loaded.plugin().onStop();
                closeQuietly(loaded.classLoader());
                lastTrace.add(Map.of("type", "guard", "label", "卸载 " + id,
                        "detail", loaded.toolNames() + " 已批量注销，onStop() 已调用，ClassLoader 已关闭"));
                sb.append(loaded.toolNames()).append(" ");
            });
            installed.clear();
            return "插件已卸载，工具 " + sb + "全部注销。注册表回到 " + registry.size()
                    + " 个工具 —— 插件机制的三步回收：批量 disposer → onStop → close ClassLoader。\n"
                    + "现在再让模型掷硬币，它会说工具不存在 —— 卸载即消失。";
        }

        /** 把类路径里的演示插件类释放到 plugins/ 目录，并写入迷你清单。 */
        private void materializePluginPackage() throws IOException {
            Files.createDirectories(PLUGIN_DIR);
            for (String cls : PLUGIN_CLASSES) {
                try (InputStream in = PluginAgent.class.getResourceAsStream("/" + cls)) {
                    if (in == null) throw new IOException("类资源缺失: " + cls);
                    Path target = PLUGIN_DIR.resolve(cls.substring(cls.lastIndexOf('/') + 1));
                    try (OutputStream out = Files.newOutputStream(target)) {
                        in.transferTo(out);
                    }
                }
            }
            Properties manifest = new Properties();
            manifest.setProperty("id", "time-plugin");
            manifest.setProperty("version", "1.0.0");
            manifest.setProperty("entrypoint", ENTRY);
            try (OutputStream out = Files.newOutputStream(PLUGIN_DIR.resolve("plugin.properties"))) {
                manifest.store(out, "mini plugin manifest (dsh-java 用 META-INF/plugin.yaml)");
            }
        }

        private static void closeQuietly(URLClassLoader cl) {
            try {
                cl.close();
            } catch (IOException ignored) {}
        }

        private static final String SYSTEM_PROMPT_PREFIX = """
                你是一个可以调用工具的智能体。可用工具清单（由注册表自动生成，工具由插件提供）：
                """;

        @Override
        public void reset() {
            installed.forEach((id, loaded) -> {
                loaded.disposers().forEach(Runnable::run);
                loaded.plugin().onStop();
                closeQuietly(loaded.classLoader());
            });
            installed.clear();
            registry.clear();
        }

        private Object tryParse(String raw) {
            try {
                return Json.parse(raw.replaceFirst("^```\\w*\\s*", "").replaceFirst("```\\s*$", "").trim());
            } catch (Exception e) {
                return raw;
            }
        }

        @Override
        public List<Map<String, Object>> trace() {
            return List.copyOf(lastTrace);
        }
    }

    // ------------------------------------------------------------------ 演示插件：安装后带来 2 个工具

    /**
     * 演示插件 TimePlugin —— 如果它有 main 函数的人生，这就是「插件作者」写的全部代码：
     * 实现 AgentPlugin 契约，onStart 打个招呼，tools() 返回带来的工具。
     * 安装时它会被释放到 plugins/ 目录、经独立 ClassLoader 加载 —— 与宿主隔离。
     */
    static class TimePlugin implements AgentPlugin {
        private boolean started;

        @Override public String id() { return "time-plugin"; }
        @Override public String version() { return "1.0.0"; }

        @Override public void onStart() { started = true; }
        @Override public void onStop() { started = false; }

        @Override
        public List<ToolDefinition> tools() {
            return List.of(new UptimeTool(Instant.now()), new CoinTool());
        }

        /** 工具 1：宿主进程运行时长 —— 插件工具也能反查宿主状态（经 parent ClassLoader）。 */
        record UptimeTool(Instant startedAt) implements ToolDefinition {
            @Override public String name() { return "uptime"; }
            @Override public String description() { return "查询宿主进程已运行时长"; }
            @Override public Map<String, String> parameters() { return Map.of(); }
            @Override public String execute(Map<String, Object> args) {
                long sec = Duration.between(startedAt, Instant.now()).getSeconds();
                long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
                return "宿主已运行 " + h + " 小时 " + m + " 分 " + s + " 秒";
            }
        }

        /** 工具 2：掷硬币 —— 一个纯粹由插件带来的趣味能力。 */
        record CoinTool() implements ToolDefinition {
            private static final Random R = new Random();
            @Override public String name() { return "coin_flip"; }
            @Override public String description() { return "掷一次硬币，返回正面或反面"; }
            @Override public Map<String, String> parameters() { return Map.of(); }
            @Override public String execute(Map<String, Object> args) {
                return R.nextBoolean() ? "正面" : "反面";
            }
        }
    }
}
