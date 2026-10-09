package cn.xiaofuge.ai.agent.step09;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step09 · MCP 智能体 —— 工具的标准化接口。
 * <p>
 * Step04 的工具是"写死在智能体里"的。MCP（Model Context Protocol）
 * 把工具侧拆成一个独立服务：工具在 Server 中统一注册，Client 通过
 * 两个标准方法使用它们 —— tools/list（发现）与 tools/call（调用）。
 * 从此工具与智能体解耦：换智能体不用重写工具，接新工具不用改智能体。
 * <p>
 * 本场景用两个嵌套接口模拟这一协议（真实 MCP 是 JSON-RPC over stdio/HTTP）。
 */
public interface McpAgent extends Agent {

    class Impl implements McpAgent {

        private final ChatModel model;
        private final McpClient client;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        public Impl(ChatModel model) {
            this.model = model;
            // 一个 MCP Server 挂载两个工具，Client 负责协议交互
            McpServer server = new WeatherTimeServer();
            this.client = new McpClient(server);
        }

        @Override
        public String name() {
            return "Step09 · MCP 协议（工具标准化接口）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();

            // 1. 工具发现：从 MCP Server 拉取工具清单，拼进提示词
            List<Map<String, String>> tools = client.listTools();
            lastTrace.add(Map.of(
                    "type", "mcp",
                    "label", "tools/list 工具发现",
                    "detail", "从 MCP Server 动态获取 " + tools.size() + " 个工具: " +
                            tools.stream().map(t -> t.get("name")).reduce((a, b) -> a + ", " + b).orElse("")));

            StringBuilder toolDocs = new StringBuilder();
            for (Map<String, String> t : tools) {
                toolDocs.append("- ").append(t.get("name")).append("(").append(t.get("parameters"))
                        .append("): ").append(t.get("description")).append("\n");
            }

            String decidePrompt = """
                你是可以调用 MCP 工具的智能体。可用工具：
                %s
                需要工具时只输出 JSON：{"method": "tools/call", "params": {"name": "工具名", "arguments": {"参数": "值"}}}
                否则直接自然语言回答。
                """.formatted(toolDocs);

            // 2. 模型决策
            String raw = model.chat(List.of(Message.system(decidePrompt), Message.user(input))).trim();
            Object decision = tryParse(raw);

            if (decision instanceof Map<?, ?> && "tools/call".equals(Json.str(decision, "method"))) {
                Map<?, ?> params = (Map<?, ?>) ((Map<?, ?>) decision).get("params");
                String name = String.valueOf(params.get("name"));
                String args = Json.write(params.get("arguments"));
                lastTrace.add(Map.of("type", "tool", "label", "tools/call 工具调用", "detail", name + " " + args));

                // 3. 经由 MCP Client 标准化调用
                String result = client.callTool(name, args);
                lastTrace.add(Map.of("type", "observation", "label", "Server 返回", "detail", result));

                String answer = model.chat(List.of(
                        Message.system("基于工具返回的事实回答，简洁准确。"),
                        Message.user(input + "\n工具结果: " + result)));
                lastTrace.add(Map.of("type", "final", "label", "生成回答", "detail", "工具与智能体已解耦：Server 换实现，智能体零改动"));
                return answer;
            }

            lastTrace.add(Map.of("type", "final", "label", "直接回答", "detail", "本次无需工具"));
            return raw;
        }

        private Object tryParse(String raw) {
            try {
                return Json.parse(raw.replaceFirst("^```\\w*\\s*", "").replaceFirst("```\\s*$", "").trim());
            } catch (Exception e) {
                return Json.obj();
            }
        }

        @Override
        public List<Map<String, Object>> trace() {
            return List.copyOf(lastTrace);
        }
    }

    // ------------------------------------------------------------------ MCP 协议三件套

    /** 工具描述符：MCP 世界里工具的标准"名片"。 */
    record ToolDescriptor(String name, String description, String parameters) {
    }

    /** MCP Server 接口：工具的标准化宿主（真实实现为独立进程，JSON-RPC 通信）。 */
    interface McpServer {
        List<ToolDescriptor> tools();

        String call(String toolName, String argsJson);
    }

    /** MCP Client：负责 tools/list 与 tools/call 两个标准方法。 */
    record McpClient(McpServer server) {

        List<Map<String, String>> listTools() {
            return server.tools().stream()
                    .map(t -> {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("name", t.name());
                        m.put("description", t.description());
                        m.put("parameters", t.parameters());
                        return m;
                    })
                    .toList();
        }

        String callTool(String name, String argsJson) {
            boolean exists = server.tools().stream().anyMatch(t -> t.name().equals(name));
            if (!exists) return "MCP 错误: 工具不存在 " + name;
            return server.call(name, argsJson);
        }
    }

    /** 示例 Server：挂载天气与时间两个工具。 */
    final class WeatherTimeServer implements McpServer {

        @Override
        public List<ToolDescriptor> tools() {
            return List.of(
                    new ToolDescriptor("get_weather", "查询城市实时天气", "city"),
                    new ToolDescriptor("get_time", "获取当前时间", ""));
        }

        @Override
        public String call(String toolName, String argsJson) {
            try {
                Object args = Json.parse(argsJson == null ? "{}" : argsJson);
                return switch (toolName) {
                    case "get_weather" -> cn.xiaofuge.ai.agent.step03.ReActAgent.MockWeather.query(Json.str(args, "city"));
                    case "get_time" -> LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    default -> "未知工具";
                };
            } catch (Exception e) {
                return "调用失败: " + e.getMessage();
            }
        }
    }
}
