package cn.xiaofuge.ai.agent.step22;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Step22 · 事件溯源 —— 会话即日志，日志即状态。
 * <p>
 * 参考 deepseek-harness-java 的 Session Event Log：16 种领域事件 + JSONL
 * append-only 存储 + 回放投影。教学版拆出三件套：
 * <ol>
 *   <li><b>事件追加</b>：会话中发生的每件事（用户发言/Agent 回答/回合完成）
 *       都是一条不可变事件，append 到 JSONL 文件，永不改写历史；</li>
 *   <li><b>回放投影</b>：每次对话前从日志重放全部事件，重建出会话上下文 ——
 *       Agent 的「记忆」不是内存里的 List，而是<b>从日志投影出来的视图</b>；
 *       服务重启后状态从日志完整恢复（试试：聊两轮 → 重启服务 → 还记得你名字）；</li>
 *   <li><b>审计能力</b>：因为历史=事件流，天然获得审计、时间旅行、fork 会话等
 *       衍生能力（dsh-java 的子代理 fork 就是复制事件前缀）。</li>
 * </ol>
 * 事件日志在 events/step22-events.jsonl，可直接打开查看每一条。
 */
public interface EventSourcedAgent extends Agent {

    /** 事件类型（教学子集；dsh-java 有 16 种 sealed 领域事件）。 */
    String EV_SESSION = "SESSION_STARTED";
    String EV_USER = "USER_MESSAGE_APPENDED";
    String EV_AGENT = "AGENT_REPLY_APPENDED";
    String EV_TURN = "TURN_COMPLETED";

    class Impl implements EventSourcedAgent {

        private final ChatModel model;
        private final Path logFile = Path.of("events", "step22-events.jsonl");
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        public Impl(ChatModel model) {
            this.model = model;
        }

        @Override
        public String name() {
            return "Step22 · 事件溯源（JSONL 事件日志 + 回放投影重建会话）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();
            try {
                // ===== 1. 回放：从日志重建会话状态 =====
                List<Map<String, Object>> log = replay();
                lastTrace.add(Map.of("type", "thought", "label", "事件回放投影",
                        "detail", "从 " + logFile + " 重放 " + log.size() + " 条事件，重建会话上下文"
                                + (log.isEmpty() ? "（空日志 → 新会话）" : "")));

                // ===== 2. 追加本轮事件 =====
                if (log.isEmpty()) append(EV_SESSION, Map.of("scenario", "step22"));
                append(EV_USER, Map.of("content", input));

                // ===== 3. 上下文 = 投影结果（不是内存 List！）=====
                List<Message> context = project(replay());
                List<Message> req = new ArrayList<>();
                req.add(Message.system(SYSTEM_PROMPT + "（当前事件日志共 " + log.size() + " 条）"));
                context.forEach(m -> req.add(m));

                String raw = model.chat(req).trim();
                Object parsed = tryParse(raw);
                String answer = parsed instanceof Map<?, ?> m ? orRaw(Json.str(m, "final"), raw) : raw;

                append(EV_AGENT, Map.of("content", answer));
                append(EV_TURN, Map.of("events", log.size() + 3));
                lastTrace.add(Map.of("type", "final", "label", "本轮事件已落盘",
                        "detail", "追加 4 条事件（含会话首条）：USER → AGENT_REPLY → TURN_COMPLETED。"
                                + "日志 append-only 永不改写，历史即事件流"));
                return answer;
            } catch (IOException e) {
                return "事件日志读写失败：" + e.getMessage();
            }
        }

        /** 回放：逐行读 JSONL（append-only，所以重放顺序 = 发生顺序）。 */
        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> replay() throws IOException {
            ensureFile();
            List<Map<String, Object>> log = new ArrayList<>();
            for (String line : Files.readAllLines(logFile)) {
                if (line.isBlank()) continue;
                try {
                    log.add((Map<String, Object>) Json.parse(line));
                } catch (Exception ignored) {
                    // 损坏行跳过 —— append-only + 校验和是生产标配，教学从简
                }
            }
            return log;
        }

        /** 投影：事件流 → 会话上下文（只挑 USER / AGENT 两类，其余事件服务审计）。 */
        private List<Message> project(List<Map<String, Object>> log) {
            List<Message> history = new ArrayList<>();
            for (Map<String, Object> e : log) {
                String type = String.valueOf(e.get("type"));
                String content = String.valueOf(((Map<?, ?>) e.get("payload")).get("content"));
                switch (type) {
                    case EV_USER -> history.add(Message.user(content));
                    case EV_AGENT -> history.add(Message.assistant(content));
                    default -> { /* SESSION_STARTED / TURN_COMPLETED 不进上下文，但留在日志里 */ }
                }
            }
            return history;
        }

        /** 追加一条事件：seq 单调递增，一行一条 JSON。 */
        private void append(String type, Map<String, Object> payload) throws IOException {
            ensureFile();
            long seq = Files.readAllLines(logFile).size() + 1;
            Map<String, Object> event = Json.obj();
            event.put("seq", seq);
            event.put("ts", System.currentTimeMillis());
            event.put("type", type);
            event.put("payload", payload);
            Files.writeString(logFile, Json.write(event) + "\n",
                    java.nio.file.StandardOpenOption.APPEND);
        }

        private void ensureFile() throws IOException {
            if (!Files.exists(logFile)) {
                Files.createDirectories(logFile.getParent());
                Files.createFile(logFile);
            }
        }

        private static final String SYSTEM_PROMPT = """
                你是一个跑在事件溯源架构上的智能体：你的每次对话都会作为事件写入 JSONL 日志，
                每轮开始前从日志回放重建记忆。像正常助手一样回答即可（记住用户告诉你的信息）。
                只输出 JSON：{"final": "回答"}。""";

        @Override
        public void reset() {
            lastTrace.clear();
            try {
                Files.deleteIfExists(logFile);
            } catch (IOException ignored) {
            }
        }

        private static String orRaw(String v, String raw) {
            return v == null || v.isBlank() ? raw : v;
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
}
