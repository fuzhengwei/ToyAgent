package cn.xiaofuge.ai;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.agent.step01.ChatAgent;
import cn.xiaofuge.ai.agent.step02.PromptAgent;
import cn.xiaofuge.ai.agent.step03.ReActAgent;
import cn.xiaofuge.ai.agent.step04.ToolCallAgent;
import cn.xiaofuge.ai.agent.step05.MemoryAgent;
import cn.xiaofuge.ai.agent.step06.RouterAgent;
import cn.xiaofuge.ai.agent.step07.McpAgent;
import cn.xiaofuge.ai.agent.step08.SkillAgent;
import cn.xiaofuge.ai.agent.step09.RagAgent;
import cn.xiaofuge.ai.agent.step10.MultiAgent;
import cn.xiaofuge.ai.agent.step11.LoopAgent;
import cn.xiaofuge.ai.agent.step12.WorkflowAgent;
import cn.xiaofuge.ai.agent.step13.FullAgent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Models;
import cn.xiaofuge.ai.llm.OpenAiChatModel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * ToyAgent 服务入口。
 * <p>
 * 使用 JDK 自带 HttpServer（零依赖）做三件事：
 * 1. 托管 web/ 目录下的静态页面（测试界面）；
 * 2. 暴露 /api/{stepId}/chat 与 /api/{stepId}/reset，把 12 个场景接入页面；
 * 3. 模型管理：/api/model 支持「页面配置 → 持久化 → 热切换」。
 * <p>
 * 启动：java cn.xiaofuge.ai.Application（默认端口 8099，可用 TOY_AGENT_PORT 覆盖）
 */
public final class Application {

    /** 场景注册表，模型切换时整体原子重建。 */
    private static volatile Map<String, Agent> agents = new LinkedHashMap<>();

    public static void main(String[] args) throws IOException {
        rebuildAgents();

        int port = Integer.parseInt(System.getenv().getOrDefault("TOY_AGENT_PORT", "8099"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/", Application::dispatch);
        server.start();

        System.out.println("==============================================");
        System.out.println("  ToyAgent v1.0  启动成功");
        System.out.println("  模型模式: " + (Models.isRealModel() ? "真实模型 " + Models.modelName() : "Mock 模型（未配置 api-key）"));
        System.out.println("  测试页面: http://localhost:" + port);
        System.out.println("==============================================");
    }

    /** 按当前配置装配 12 个场景：真实模型优先，无 Key 则装配 Mock 模型。 */
    private static synchronized void rebuildAgents() {
        Map<String, Agent> map = new LinkedHashMap<>();
        ChatModel real = Models.openAiOrNull();
        map.put("step01", new ChatAgent.Impl(real != null ? real : Models.mock("step01")));
        map.put("step02", new PromptAgent.Impl(real != null ? real : Models.mock("step02")));
        map.put("step03", new ReActAgent.Impl(real != null ? real : Models.mock("step03")));
        map.put("step04", new ToolCallAgent.Impl(real != null ? real : Models.mock("step04")));
        map.put("step05", new MemoryAgent.Impl(real != null ? real : Models.mock("step05")));
        map.put("step06", new RouterAgent.Impl(real != null ? real : Models.mock("step06")));
        map.put("step07", new McpAgent.Impl(real != null ? real : Models.mock("step07")));
        map.put("step08", new SkillAgent.Impl(real != null ? real : Models.mock("step08")));
        map.put("step09", new RagAgent.Impl(real != null ? real : Models.mock("step09")));
        map.put("step10", new MultiAgent.Impl(real != null ? real : Models.mock("step10")));
        map.put("step11", new LoopAgent.Impl(real != null ? real : Models.mock("step11")));
        map.put("step12", new WorkflowAgent.Impl(real != null ? real : Models.mock("step12")));
        map.put("step13", new FullAgent.Impl(real != null ? real : Models.mock("step13")));
        agents = map;
        System.out.println("[ToyAgent] 场景已装配，模型模式: " + (Models.isRealModel() ? Models.modelName() : "mock"));
    }

    // ------------------------------------------------------------------ 路由

    private static void dispatch(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        try {
            if (path.startsWith("/api/")) {
                handleApi(exchange, path);
            } else {
                handleStatic(exchange, path);
            }
        } catch (Exception e) {
            try {
                respond(exchange, 500, errorJson("服务内部错误: " + e.getMessage()));
            } catch (IOException ignored) {
                // 响应已不可写，忽略
            }
        } finally {
            exchange.close();
        }
    }

    /**
     * API 路由：
     * POST /api/{stepId}/chat | POST /api/{stepId}/reset
     * GET  /api/config | GET /api/model | POST /api/model | POST /api/model/test | POST /api/model/mock
     */
    private static void handleApi(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod();

        if (path.equals("/api/config") && "GET".equals(method)) {
            respond(exchange, 200, Json.write(Map.of(
                    "mode", Models.isRealModel() ? "real" : "mock",
                    "model", Models.modelName())));
            return;
        }

        // ---------------- 模型管理 ----------------
        if (path.equals("/api/model")) {
            if ("GET".equals(method)) {
                Map<String, Object> m = Json.obj();
                m.put("mode", Models.isRealModel() ? "real" : "mock");
                m.put("baseUrl", Models.baseUrl());
                m.put("apiKeyMasked", Models.maskedKey());
                m.put("model", Models.isRealModel() ? Models.modelName() : "");
                respond(exchange, 200, Json.write(m));
                return;
            }
            if ("POST".equals(method)) {
                String body = readBody(exchange);
                try {
                    Object req = Json.parse(body);
                    Models.update(Json.str(req, "baseUrl"), Json.str(req, "apiKey"), Json.str(req, "model"));
                    rebuildAgents();
                    respond(exchange, 200, okJson("模型配置已保存并生效: " + Models.modelName()));
                } catch (Exception e) {
                    respond(exchange, 400, errorJson("配置解析失败: " + e.getMessage()));
                }
                return;
            }
        }

        if (path.equals("/api/model/test") && "POST".equals(method)) {
            String body = readBody(exchange);
            try {
                Object req = Json.parse(body);
                String baseUrl = orDefault(Json.str(req, "baseUrl"), Models.baseUrl());
                String apiKey = orDefault(Json.str(req, "apiKey"), "");
                String model = orDefault(Json.str(req, "model"), "gpt-3.5-turbo");
                OpenAiChatModel tester = Models.build(baseUrl, apiKey, model);
                String reply = tester.chat(List.of(cn.xiaofuge.ai.llm.Message.user("回复：pong")));
                respond(exchange, 200, Json.write(Map.of("ok", true, "reply", reply)));
            } catch (Exception e) {
                respond(exchange, 200, Json.write(Map.of("ok", false, "error", String.valueOf(e.getMessage()))));
            }
            return;
        }

        if (path.equals("/api/model/mock") && "POST".equals(method)) {
            Models.useMock();
            rebuildAgents();
            respond(exchange, 200, okJson("已切换为 Mock 演示模型"));
            return;
        }

        // ---------------- 场景交互 ----------------
        String[] parts = path.split("/");
        if (parts.length != 4) {
            respond(exchange, 404, errorJson("路径不存在: " + path));
            return;
        }
        String stepId = parts[2];
        String action = parts[3];
        Agent agent = agents.get(stepId);
        if (agent == null) {
            respond(exchange, 404, errorJson("场景不存在: " + stepId));
            return;
        }

        if ("reset".equals(action)) {
            agent.reset();
            respond(exchange, 200, Json.write(Map.of("ok", true)));
            return;
        }

        if (!"chat".equals(action) || !"POST".equals(method)) {
            respond(exchange, 405, errorJson("不支持的请求: " + method + " " + path));
            return;
        }

        // 解析请求体 {"message": "..."}
        String message;
        try {
            message = Json.str(Json.parse(readBody(exchange)), "message");
        } catch (Exception e) {
            message = null;
        }
        if (message == null || message.isBlank()) {
            respond(exchange, 400, errorJson("message 不能为空"));
            return;
        }

        long start = System.currentTimeMillis();
        try {
            String answer = agent.chat(message);
            long ms = System.currentTimeMillis() - start;
            Map<String, Object> resp = Json.obj();
            resp.put("id", stepId);
            resp.put("name", agent.name());
            resp.put("answer", answer);
            resp.put("trace", agent.trace());
            resp.put("ms", ms);
            respond(exchange, 200, Json.write(resp));
        } catch (Exception e) {
            respond(exchange, 500, errorJson("智能体执行失败: " + e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ 静态资源

    private static void handleStatic(HttpExchange exchange, String path) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, errorJson("仅支持 GET"));
            return;
        }
        String rel = path.equals("/") ? "index.html" : path.substring(1);
        Path webRoot = Path.of("web").toAbsolutePath().normalize();
        Path target = webRoot.resolve(rel).normalize();

        // 防目录穿越
        if (!target.startsWith(webRoot) || !Files.exists(target) || Files.isDirectory(target)) {
            // SPA 兜底：未知路径回到 index.html
            if (!rel.contains(".")) {
                target = webRoot.resolve("index.html");
            } else {
                respond(exchange, 404, "404 Not Found: " + rel);
                return;
            }
        }

        String contentType = switch (rel.substring(rel.lastIndexOf('.') + 1)) {
            case "html" -> "text/html; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "js" -> "application/javascript; charset=utf-8";
            case "json" -> "application/json; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "ico" -> "image/x-icon";
            default -> "application/octet-stream";
        };
        byte[] bytes = Files.readAllBytes(target);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        // 每次回源校验，避免浏览器启发式缓存导致改版后页面不更新
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ------------------------------------------------------------------ 响应工具

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String orDefault(String v, String def) {
        return (v == null || v.isBlank()) ? def : v;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String errorJson(String msg) {
        Map<String, Object> m = Json.obj();
        m.put("error", msg);
        return Json.write(m);
    }

    private static String okJson(String msg) {
        Map<String, Object> m = Json.obj();
        m.put("ok", true);
        m.put("message", msg);
        return Json.write(m);
    }
}
