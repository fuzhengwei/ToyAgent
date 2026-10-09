package cn.xiaofuge.ai.agent.step25;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;
import cn.xiaofuge.ai.llm.Models;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Step25 · CLI 智能体 —— 把智能体装进终端（对应教程 ch18 · CLI Agent）。
 * <p>
 * 形态变了，骨架不变：仍然是 {@code Agent.chat(input)}，只是外面套一层终端 REPL。
 * <ol>
 *   <li>工具侧换执行器：run_command 直接执行 shell 命令，复用 Step21 的沙箱四层防御
 *       （拒绝名单 → 路径边界 → 命令白名单 → 受限工作区）；</li>
 *   <li>工作目录即上下文：受限工作区 sandbox/step25/ 进入系统提示词，像终端会话一样有 cwd；</li>
 *   <li>两种交互模式：交互式 REPL（默认）与非交互单命令 {@code -p "任务"}（Claude Code 同款）。</li>
 * </ol>
 * 试试本场景的本地指令（Web 页面）：
 * <pre>
 *   运行：ls              —— 沙箱内直接执行，轨迹展示四层防御
 *   运行：cat hello.txt   —— 读受限工作区文件
 *   运行：rm -rf /        —— 第①层拒绝名单拦截
 *   运行：cat /etc/passwd —— 第②层路径边界拦截
 *   列出工作区文件        —— 模型自主决策调用 run_command
 * </pre>
 * 终端形态：{@code java -cp target/classes cn.xiaofuge.ai.agent.step25.CliAgent}
 * 或单命令 {@code java -cp target/classes cn.xiaofuge.ai.agent.step25.CliAgent -p "看看工作区里有什么"}
 */
public interface CliAgent extends Agent {

    class Impl implements CliAgent {

        private static final Path WORKSPACE = Path.of("sandbox", "step25");
        /** 第①层：拒绝名单（无论是否在沙箱内，一律拒绝）。 */
        private static final Set<String> DENYLIST = Set.of(
                "rm", "sudo", "shutdown", "reboot", "mkfs", "dd", "kill", "pkill");
        /** 第③层：命令白名单 —— 只放行只读/无害命令。 */
        private static final Set<String> ALLOWLIST = Set.of("ls", "pwd", "echo", "cat", "date", "wc");

        private final ChatModel model;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        public Impl(ChatModel model) {
            this.model = model;
            initWorkspace();
        }

        private void initWorkspace() {
            try {
                Files.createDirectories(WORKSPACE);
                Path hello = WORKSPACE.resolve("hello.txt");
                if (Files.notExists(hello)) {
                    Files.writeString(hello, "你好，这里是 ToyAgent 的受限工作区。\n", StandardCharsets.UTF_8);
                }
            } catch (IOException ignored) {}
        }

        @Override
        public String name() {
            return "Step25 · CLI 智能体（终端 REPL + 沙箱执行器）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();
            lastTrace.add(Map.of("type", "tool", "label", "终端会话就绪",
                    "detail", "工作区 " + WORKSPACE + " 已进入系统提示词（cwd 上下文）"));

            // ---- 本地指令：运行：<cmd>（不经模型，直接看沙箱防御） ----
            if (input.startsWith("运行：") || input.startsWith("运行:")) {
                return execute(input.substring(3).trim());
            }

            // ---- 常规对话：模型自主决策是否调用 run_command ----
            List<Message> messages = new ArrayList<>();
            messages.add(Message.system(SYSTEM_PROMPT_PREFIX.formatted(WORKSPACE)
                    + "\n决策规则：\n- 需要执行命令时，只输出 JSON：{\"tool\": \"run_command\", \"arguments\": {\"command\": \"命令\"}}\n"
                    + "- 不需要命令时，直接用自然语言回答，不要输出 JSON。"));
            messages.add(Message.user(input));

            for (int round = 1; round <= 2; round++) {
                String raw = model.chat(messages).trim();
                Object decision = tryParse(raw);

                if (decision instanceof Map<?, ?> && Json.str(decision, "tool") != null) {
                    Object argsObj = ((Map<?, ?>) decision).get("arguments");
                    String cmd = argsObj instanceof Map<?, ?> m && m.get("command") != null
                            ? String.valueOf(m.get("command")) : "";
                    lastTrace.add(Map.of("type", "action", "label", "模型发起命令执行",
                            "detail", cmd));
                    String result = runGuarded(cmd);
                    messages.add(Message.assistant(raw));
                    messages.add(Message.user("工具 run_command 执行结果: " + result));
                    continue;
                }
                lastTrace.add(Map.of("type", "final", "label", "最终回答",
                        "detail", "CLI 形态：模型基于终端会话上下文完成回答"));
                return raw;
            }
            return "已达最大轮数，循环终止。";
        }

        /** 本地指令入口：同样走四层防御，但直接返回结果。 */
        private String execute(String cmd) {
            String result = runGuarded(cmd);
            return "命令：" + cmd + "\n结果：\n" + result;
        }

        /** 四层防御（与 Step21 同构）：拒绝名单 → 路径边界 → 命令白名单 → 受限工作区。 */
        private String runGuarded(String cmd) {
            if (cmd == null || cmd.isBlank()) return "（空命令）";

            // ① 拒绝名单：整个命令串里出现即拦截
            String lower = cmd.toLowerCase();
            for (String bad : DENYLIST) {
                if (lower.contains(bad)) {
                    lastTrace.add(Map.of("type", "guard", "label", "第①层 · 拒绝名单",
                            "detail", "命中 " + bad + " → 直接拒绝（高危命令无商量余地）"));
                    return "已拦截：命令包含拒绝名单中的 " + bad + "。";
                }
            }
            // ② 路径边界：.. 或绝对路径一律拒绝
            for (String token : cmd.split("\\s+")) {
                if (token.contains("..") || token.startsWith("/")) {
                    lastTrace.add(Map.of("type", "guard", "label", "第②层 · 路径边界",
                            "detail", "token " + token + " 越出受限工作区 → 拒绝"));
                    return "已拦截：路径 " + token + " 越出受限工作区 " + WORKSPACE + "。";
                }
            }
            // ③ 命令白名单：只放行只读/无害命令
            String head = cmd.split("\\s+")[0];
            if (!ALLOWLIST.contains(head)) {
                lastTrace.add(Map.of("type", "guard", "label", "第③层 · 命令白名单",
                        "detail", head + " 不在白名单 " + ALLOWLIST + " 中 → 拒绝"));
                return "已拦截：命令 " + head + " 不在白名单中（教学沙箱只放行 " + ALLOWLIST + "）。";
            }
            // ④ 受限工作区：ProcessBuilder 指定工作目录，命令按空格拆分（不经 shell，无管道/重定向）
            try {
                Process p = new ProcessBuilder(cmd.split("\\s+"))
                        .directory(WORKSPACE.toFile())
                        .redirectErrorStream(true)
                        .start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                p.waitFor(5, TimeUnit.SECONDS);
                lastTrace.add(Map.of("type", "observation", "label", "沙箱执行 " + cmd,
                        "detail", "cwd=" + WORKSPACE + "，退出码 " + p.exitValue()));
                return out.isBlank() ? "（无输出）" : out;
            } catch (Exception e) {
                return "执行失败: " + e.getMessage();
            }
        }

        private static final String SYSTEM_PROMPT_PREFIX = """
                你是一个运行在终端里的 CLI 智能体。当前受限工作区：%s
                可以调用 run_command 执行命令（沙箱受四层防御保护）。
                """;

        @Override
        public void reset() {
            initWorkspace();
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

    // ------------------------------------------------------------------ 终端形态：REPL 与 -p 单命令模式

    /**
     * 终端入口：交互式 REPL（默认）或 -p 单命令模式。
     * 形态是壳，灵魂仍是 chat(input)。
     */
    static void main(String[] args) {
        ChatModel model = Models.isRealModel() ? Models.openAiOrNull() : Models.mock("step25");
        Impl agent = new Impl(model != null ? model : Models.mock("step25"));

        // 非交互单命令模式：java ... CliAgent -p "看看工作区里有什么"
        if (args.length >= 2 && "-p".equals(args[0])) {
            String task = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            System.out.println(agent.chat(task));
            return;
        }

        // 交互式 REPL
        System.out.println("ToyAgent CLI（Step25）—— 输入任务，exit 退出。模型: " + (Models.isRealModel() ? "真实" : "mock"));
        try (Scanner sc = new Scanner(System.in, StandardCharsets.UTF_8)) {
            while (true) {
                System.out.print("你> ");
                if (!sc.hasNextLine()) break;
                String line = sc.nextLine().trim();
                if (line.isEmpty()) continue;
                if ("exit".equalsIgnoreCase(line) || "quit".equalsIgnoreCase(line)) break;
                System.out.println("Agent> " + agent.chat(line));
            }
        }
        System.out.println("Bye.");
    }
}
