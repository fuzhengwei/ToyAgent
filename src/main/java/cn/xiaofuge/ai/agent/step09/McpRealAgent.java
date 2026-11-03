package cn.xiaofuge.ai.agent.step09;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Step09+ · MCP 真实对接 —— 把教学版的协议换成真网络。
 * <p>
 * {@link McpAgent} 用两个嵌套接口在进程内模拟了 MCP；本类把"模拟"替换为
 * <strong>真实的 JSON-RPC 2.0 over HTTP+SSE 传输</strong>，对接百度 AppBuilder
 * 的 AI 搜索 MCP Server。协议骨架完全一致：tools/list（发现）+ tools/call（调用），
 * 只是Client 与 Server 之间换成了标准报文：
 * <pre>
 *   GET  /mcp/sse          → 建立 SSE 长连接，Server 下发 endpoint 事件（消息回传地址）
 *   POST /mcp/messages     → initialize / notifications/initialized / tools/list / tools/call
 *   SSE  event: message    → JSON-RPC 响应从这条长连接异步推回
 * </pre>
 * 整个客户端零依赖：SSE 读流用 BufferedReader，HTTP 用 JDK HttpClient，
 * JSON 编解码复用 {@link Json} —— 100 行左右就能写出一个能用的 MCP Client。
 */
public interface McpRealAgent extends Agent {

    class Impl implements McpRealAgent {

        private final ChatModel model;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        public Impl(ChatModel model) {
            this.model = model;
        }

        @Override
        public String name() {
            return "Step09+ · MCP 真实对接（百度 AI 搜索）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();

            // 教学版与本类的唯一分水岭：McpClient 换成 SseMcpClient，其余流程零改动
            // Key 不入库（GitHub Push Protection 会拦截）：配在 config.properties，
            // 申请自己的 key：https://console.bce.baidu.com/iam/#/iam/apikey/list
            try (SseMcpClient client = new SseMcpClient(resolveSseUrl())) {

                // 1. 建连 + 握手：GET /sse → endpoint 事件 → initialize → initialized
                Map<?, ?> serverInfo = client.initialize();
                lastTrace.add(Map.of(
                        "type", "mcp",
                        "label", "SSE 建连 + initialize 握手",
                        "detail", "Server: " + Json.str(serverInfo, "name") + " v" + Json.str(serverInfo, "version")
                                + "（endpoint 由 SSE event 下发）"));

                // 2. tools/list：向真实 Server 拉取工具清单
                List<Map<String, String>> tools = client.listTools();
                lastTrace.add(Map.of(
                        "type", "mcp",
                        "label", "tools/list 工具发现",
                        "detail", "真实 Server 返回 " + tools.size() + " 个工具: " +
                                tools.stream().map(t -> t.get("name")).reduce((a, b) -> a + ", " + b).orElse("")));

                StringBuilder toolDocs = new StringBuilder();
                for (Map<String, String> t : tools) {
                    toolDocs.append("- ").append(t.get("name"))
                            .append("(参数: ").append(t.get("parameters")).append(")")
                            .append(": ").append(clip(String.valueOf(t.get("description")), 200)).append("\n");
                }

                // 3. 模型决策：调工具，还是直接回答（与教学版相同的提示词骨架）
                String decidePrompt = """
                        你是可以调用 MCP 工具的智能体。可用工具（来自远程 MCP Server）：
                        %s
                        需要工具时只输出 JSON：{"method": "tools/call", "params": {"name": "工具名", "arguments": {"参数": "值"}}}
                        否则直接自然语言回答。
                        """.formatted(toolDocs);
                String raw = model.chat(List.of(Message.system(decidePrompt), Message.user(input))).trim();
                Object decision = tryParse(raw);

                if (decision instanceof Map<?, ?> && "tools/call".equals(Json.str(decision, "method"))) {
                    Map<?, ?> params = (Map<?, ?>) ((Map<?, ?>) decision).get("params");
                    String name = String.valueOf(params.get("name"));
                    String args = Json.write(params.get("arguments"));
                    lastTrace.add(Map.of("type", "tool", "label", "tools/call 工具调用", "detail", name + " " + clip(args, 80)));

                    // 4. 经标准 JSON-RPC 调用远程工具（响应从 SSE 流异步推回）
                    String result = client.callTool(name, args);
                    lastTrace.add(Map.of("type", "observation", "label", "Server 返回（SSE 推回）", "detail", clip(result, 600)));

                    String answer = model.chat(List.of(
                            Message.system("基于工具返回的事实回答，简洁准确，引用来源链接。"),
                            Message.user(input + "\n工具结果: " + result)));
                    lastTrace.add(Map.of("type", "final", "label", "生成回答", "detail",
                            "同一套 Agent 骨架：教学版换进程内接口，本场景换真实网络，代码几乎不变"));
                    return answer;
                }

                lastTrace.add(Map.of("type", "final", "label", "直接回答", "detail", "本次无需工具"));
                return raw;
            } catch (Exception e) {
                lastTrace.add(Map.of("type", "final", "label", "MCP 异常", "detail", clip(String.valueOf(e.getMessage()), 300)));
                return "MCP 真实调用失败: " + e.getMessage();
            }
        }

        private Object tryParse(String raw) {
            try {
                return Json.parse(raw.replaceFirst("^```\\w*\\s*", "").replaceFirst("```\\s*$", "").trim());
            } catch (Exception e) {
                return Json.obj();
            }
        }

        /** MCP Server 端点（Key 不入库，GitHub Push Protection 会拦截源码里的密钥）。 */
        private static final String DEFAULT_SSE_BASE = "http://appbuilder.baidu.com/v2/ai_search/mcp/sse";

        /**
         * SSE 地址装配优先级：
         * 1. config.properties 的 mcp-search-api-key —— 拼接到默认端点（推荐）；
         * 2. config.properties 的 mcp-search-url —— 完整地址整体覆盖；
         * 3. 环境变量 MCP_SEARCH_URL —— 容器化部署用；
         * 4. 都没有则返回裸端点（Server 返回 401，提示配置 Key）。
         */
        private static String resolveSseUrl() {
            try {
                Properties props = new Properties();
                if (Files.exists(Path.of("config.properties"))) {
                    try (InputStream in = Files.newInputStream(Path.of("config.properties"))) {
                        props.load(in);
                    }
                }
                String apiKey = props.getProperty("mcp-search-api-key");
                if (apiKey != null && !apiKey.isBlank()) return DEFAULT_SSE_BASE + "?api_key=" + apiKey.trim();
                String fullUrl = props.getProperty("mcp-search-url");
                if (fullUrl != null && !fullUrl.isBlank()) return fullUrl.trim();
            } catch (Exception ignored) {
            }
            String env = System.getenv("MCP_SEARCH_URL");
            return env == null || env.isBlank() ? DEFAULT_SSE_BASE : env.trim();
        }

        private static String clip(String s, int n) {
            if (s == null) return "";
            return s.length() > n ? s.substring(0, n) + "…" : s;
        }

        @Override
        public List<Map<String, Object>> trace() {
            return List.copyOf(lastTrace);
        }
    }

    // ------------------------------------------------------------------ 真实 MCP Client（SSE 传输）

    /**
     * 真实的 MCP Client：JSON-RPC 2.0 over HTTP+SSE（MCP 早期标准传输，
     * 百度 AppBuilder / 各大开放平台普遍支持）。
     * <p>
     * 职责链：GET 建立 SSE 长连接 → 读取 endpoint 事件拿到消息回传地址 →
     * 所有请求 POST 到该地址 → 响应从 SSE 流异步推回，按 JSON-RPC id 配对。
     */
    final class SseMcpClient implements AutoCloseable {

        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        private final URI sseUri;
        private final InputStream eventStream;
        private final Thread reader;

        /** Server 在 endpoint 事件中下发的消息回传地址（带 session_id）。 */
        private volatile URI messageUri;
        private final CountDownLatch endpointLatch = new CountDownLatch(1);

        /** id -> 已到达的 JSON-RPC 响应；id -> 等待中的闩锁。 */
        private final Map<Integer, Object> responses = new ConcurrentHashMap<>();
        private final Map<Integer, CountDownLatch> pending = new ConcurrentHashMap<>();
        private final AtomicInteger nextId = new AtomicInteger(1);
        private volatile String lastError;

        /** 打开 SSE 长连接并等待 endpoint 事件（最多 15 秒）。 */
        public SseMcpClient(String sseUrl) throws Exception {
            this.sseUri = URI.create(sseUrl);
            HttpRequest request = HttpRequest.newBuilder(sseUri)
                    .header("Accept", "text/event-stream")
                    .header("Cache-Control", "no-cache")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<InputStream> resp = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("SSE 建连失败 HTTP " + resp.statusCode());
            }
            this.eventStream = resp.body();
            this.reader = new Thread(this::readLoop, "mcp-sse-reader");
            this.reader.setDaemon(true);
            this.reader.start();
            if (!endpointLatch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待 endpoint 事件超时（15s）");
            }
        }

        /** 后台线程：逐行解析 SSE（event:/data: 行 + 空行分发），注释行(: ping)忽略。 */
        private void readLoop() {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(eventStream, StandardCharsets.UTF_8))) {
                String event = "message";
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith(":")) continue;              // SSE 注释 / 心跳
                    if (line.startsWith("event:")) {
                        event = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        if (!data.isEmpty()) data.append('\n');
                        data.append(line.substring(5).trim());
                    } else if (line.isEmpty() && !data.isEmpty()) { // 空行 = 事件分发
                        dispatch(event, data.toString());
                        event = "message";
                        data.setLength(0);
                    }
                }
            } catch (Exception e) {
                lastError = e.getMessage();
            }
        }

        private void dispatch(String event, String data) {
            if ("endpoint".equals(event)) {
                // 相对路径（/v2/ai_search/mcp/messages?session_id=...）按 SSE 地址解析成绝对地址
                messageUri = sseUri.resolve(data);
                endpointLatch.countDown();
                return;
            }
            if (!"message".equals(event)) return;
            try {
                Object root = Json.parse(data);
                Object id = ((Map<?, ?>) root).get("id");
                if (id instanceof Number n) {
                    responses.put(n.intValue(), root);
                    CountDownLatch latch = pending.remove(n.intValue());
                    if (latch != null) latch.countDown();
                }
                // 无 id 的是 Server 主动通知，本场景直接忽略
            } catch (Exception ignored) {
            }
        }

        /** MCP 握手：initialize → notifications/initialized。返回 serverInfo。 */
        public Map<?, ?> initialize() throws Exception {
            Map<String, Object> clientInfo = Json.obj();
            clientInfo.put("name", "toyagent");
            clientInfo.put("version", "1.0");
            Map<String, Object> params = Json.obj();
            params.put("protocolVersion", "2024-11-05");
            params.put("capabilities", Json.obj());
            params.put("clientInfo", clientInfo);
            Object result = request("initialize", params, Duration.ofSeconds(30));
            notify("notifications/initialized");
            Object info = ((Map<?, ?>) result).get("serverInfo");
            return info instanceof Map<?, ?> m ? m : Json.obj();
        }

        /** tools/list：返回 [{name, description, parameters}]。parameters 从 inputSchema 提炼必填项。 */
        public List<Map<String, String>> listTools() throws Exception {
            Object result = request("tools/list", Json.obj(), Duration.ofSeconds(30));
            List<Map<String, String>> tools = new ArrayList<>();
            Object list = ((Map<?, ?>) result).get("tools");
            if (list instanceof List<?> ls) {
                for (Object item : ls) {
                    Map<?, ?> t = (Map<?, ?>) item;
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("name", Json.str(t, "name"));
                    m.put("description", Json.str(t, "description"));
                    m.put("parameters", summarizeSchema(t.get("inputSchema")));
                    tools.add(m);
                }
            }
            return tools;
        }

        /** inputSchema 太长，提炼成 "query(必填)、model" 这样的摘要放进提示词。 */
        private static String summarizeSchema(Object schema) {
            if (!(schema instanceof Map<?, ?> s)) return "";
            List<String> parts = new ArrayList<>();
            Object required = s.get("required");
            List<String> req = new ArrayList<>();
            if (required instanceof List<?> rl) rl.forEach(r -> req.add(String.valueOf(r)));
            if (s.get("properties") instanceof Map<?, ?> props) {
                for (Object key : props.keySet()) {
                    String k = String.valueOf(key);
                    parts.add(req.contains(k) ? k + "(必填)" : k);
                }
            }
            return String.join("、", parts);
        }

        /** tools/call：拼接 content[].text 作为工具结果。 */
        public String callTool(String name, String argsJson) throws Exception {
            Map<String, Object> params = Json.obj();
            params.put("name", name);
            params.put("arguments", Json.parse(argsJson == null || argsJson.isBlank() ? "{}" : argsJson));
            Object result = request("tools/call", params, Duration.ofSeconds(120));
            Map<?, ?> r = (Map<?, ?>) result;
            StringBuilder sb = new StringBuilder();
            if (r.get("content") instanceof List<?> contents) {
                for (Object c : contents) {
                    String text = Json.str(c, "text");
                    if (text != null) sb.append(text).append('\n');
                }
            }
            String out = sb.toString().trim();
            return Boolean.TRUE.equals(r.get("isError")) ? "工具执行出错: " + out : out;
        }

        /** 发送 JSON-RPC 请求并按 id 等待 SSE 推回的响应，返回 result 字段。 */
        private Object request(String method, Object params, Duration timeout) throws Exception {
            int id = nextId.getAndIncrement();
            CountDownLatch latch = new CountDownLatch(1);
            pending.put(id, latch);
            post(Json.write(rpc(id, method, params)));
            if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("MCP 响应超时(" + timeout.toSeconds() + "s): " + method
                        + (lastError == null ? "" : "，连接状态: " + lastError));
            }
            Object resp = responses.remove(id);
            Object error = ((Map<?, ?>) resp).get("error");
            if (error != null) throw new IllegalStateException("JSON-RPC 错误: " + Json.write(error));
            return ((Map<?, ?>) resp).get("result");
        }

        /** 通知（无 id、无响应）：如 notifications/initialized。 */
        private void notify(String method) throws Exception {
            Map<String, Object> m = Json.obj();
            m.put("jsonrpc", "2.0");
            m.put("method", method);
            post(Json.write(m));
        }

        private static Map<String, Object> rpc(int id, String method, Object params) {
            Map<String, Object> m = Json.obj();
            m.put("jsonrpc", "2.0");
            m.put("id", id);
            m.put("method", method);
            m.put("params", params);
            return m;
        }

        /** 唯一的出站动作：POST JSON-RPC 报文到 endpoint 下发的回传地址，202 即受理。 */
        private void post(String body) throws Exception {
            if (messageUri == null) throw new IllegalStateException("endpoint 尚未就绪");
            HttpRequest request = HttpRequest.newBuilder(messageUri)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 202 && resp.statusCode() != 200) {
                throw new IllegalStateException("MCP 消息发送失败 HTTP " + resp.statusCode() + ": " + resp.body());
            }
        }

        @Override
        public void close() {
            try {
                eventStream.close(); // 关流后 reader 线程自然退出
            } catch (Exception ignored) {
            }
        }
    }

    /** 本地直接测试入口：java -cp target/classes cn.xiaofuge.ai.agent.step09.McpRealAgent [你的问题] */
    public static void main(String[] args) {
        cn.xiaofuge.ai.llm.ChatModel model = cn.xiaofuge.ai.llm.Models.openAiOrNull();
        if (model == null) model = cn.xiaofuge.ai.llm.Models.mock("step09-real");
        String input = args.length > 0 ? String.join(" ", args) : "搜索下小傅哥";
        System.out.println("[输入] " + input);
        System.out.println("[输出] " + new Impl(model).chat(input));
    }
}
