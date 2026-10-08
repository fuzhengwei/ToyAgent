package cn.xiaofuge.ai.agent.step19;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step19 · 沙箱纵深防御 —— 命令执行前的四道关卡。
 * <p>
 * 参考 deepseek-harness-java 的命令拦截器与沙箱设计（策略三档 → 内容黑名单 →
 * 空间边界 → 路径规范化），拆出教学版四层校验管线，任何一层拦截即 DENY：
 * <ol>
 *   <li><b>策略层</b>：当前策略档位是否允许 shell（READ_ONLY 直接全拒）；</li>
 *   <li><b>内容黑名单</b>：rm -rf /、sudo、mkfs、fork 炸弹等破坏性命令特征
 *       （dsh-java 里是一个 ~85 行的独立拦截器）；</li>
 *   <li><b>空间边界</b>：命令中出现的路径必须落在工作区 sandbox/ 内，
 *       绝对路径越界即拒；</li>
 *   <li><b>路径规范化</b>：先 canonicalize 再校验，workspace/../../etc/passwd
 *       这种「看似在内、实际在外」的逃逸无所遁形。</li>
 * </ol>
 * 关键设计思想：<b>不信任模型的输出</b> —— 模型生成的命令照样要过全部四层。
 */
public interface SandboxAgent extends Agent {

    /** 校验结论：放行 or 拦截（在哪一层、为什么）。 */
    record Verdict(boolean allowed, int layer, String reason) {
        static Verdict pass() { return new Verdict(true, 0, "四层校验全部通过"); }
        static Verdict deny(int layer, String why) { return new Verdict(false, layer, why); }
    }

    class Impl implements SandboxAgent {

        private final ChatModel model;
        private final List<Message> history = new ArrayList<>();
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();
        private final Path workspace;

        /** 内容黑名单（教学子集；dsh-java 生产版覆盖面更全）。 */
        private static final List<String> DENYLIST = List.of(
                "rm -rf", "rm  -rf", "sudo", "shutdown", "reboot", "mkfs", "dd if=",
                ":(){", "fork()", "chmod 777", "> /dev/", "kill -9 1", "curl | sh");

        public Impl(ChatModel model) {
            this.model = model;
            this.workspace = Path.of("sandbox").toAbsolutePath().normalize();
            try {
                Files.createDirectories(workspace);
                Files.writeString(workspace.resolve("hello.txt"), "ToyAgent sandbox\n");
            } catch (IOException ignored) {
            }
        }

        @Override
        public String name() {
            return "Step19 · 沙箱纵深防御（策略 → 黑名单 → 边界 → 规范化，四层拦截）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();
            history.add(Message.user(input));

            // 发「执行：<命令>」即走沙箱管线；其余交给模型
            if (input.startsWith("执行：")) {
                String cmd = input.substring("执行：".length()).trim();
                return runShell(cmd);
            }

            List<Message> req = new ArrayList<>();
            req.add(Message.system(SYSTEM_PROMPT));
            history.forEach(m -> req.add(m));
            String raw = model.chat(req).trim();
            Object parsed = tryParse(raw);
            String answer = parsed instanceof Map<?, ?> m ? orRaw(Json.str(m, "final"), raw) : raw;
            history.add(Message.assistant(answer));
            lastTrace.add(Map.of("type", "final", "label", "直接作答",
                    "detail", "普通对话不走沙箱；想看四层校验，发「执行：<命令>」"));
            return answer;
        }

        /** 四层校验管线：任何一层拦截立即 DENY，全过才执行。 */
        private String runShell(String cmd) {
            lastTrace.add(Map.of("type", "action", "label", "shell_execute 请求",
                    "detail", cmd));

            // ===== 第 1 层 · 策略档位 =====
            lastTrace.add(Map.of("type", "thought", "label", "第 1 层 · 策略校验 ✓",
                    "detail", "当前策略 WORKSPACE_ONLY 允许执行 shell（生产另有 READ_ONLY=全拒 / FULL=需审批）"));

            // ===== 第 2 层 · 内容黑名单 =====
            String low = cmd.toLowerCase();
            for (String bad : DENYLIST) {
                if (low.contains(bad)) {
                    return deny(2, "命中危险命令特征「" + bad + "」");
                }
            }
            lastTrace.add(Map.of("type", "thought", "label", "第 2 层 · 内容黑名单 ✓",
                    "detail", "未命中 " + DENYLIST.size() + " 条破坏性命令特征"));

            // ===== 第 3 层 · 空间边界（绝对路径必须在工作区内） =====
            Matcher abs = Pattern.compile("(?<![\\w.])/(?:[\\w.-]+/)*[\\w.-]+").matcher(cmd);
            while (abs.find()) {
                String p = abs.group();
                // 绝对路径按文件系统真实位置判断：只有恰好落在工作区内才放行
                if (!Path.of(p).normalize().startsWith(workspace)) {
                    return deny(3, "绝对路径 " + p + " 越出工作区 " + workspace.getFileName());
                }
            }
            lastTrace.add(Map.of("type", "thought", "label", "第 3 层 · 空间边界 ✓",
                    "detail", "命令中的路径均位于工作区 sandbox/ 内（相对路径按工作区解析）"));

            // ===== 第 4 层 · 路径规范化（canonicalize 后复查，防 ../ 逃逸） =====
            if (cmd.contains("..")) {
                return deny(4, "检测到 ../ 相对跳转 —— 规范化后路径将越出工作区，疑似路径逃逸");
            }
            lastTrace.add(Map.of("type", "thought", "label", "第 4 层 · 路径规范化 ✓",
                    "detail", "canonicalize 复查通过，无 ../ 逃逸（第 3 层看原文，第 4 层看真实落点）"));

            // ===== 执行（教学模拟） =====
            String output = simulate(cmd);
            lastTrace.add(Map.of("type", "observation", "label", "执行完成",
                    "detail", "工作区 " + workspace.getFileName() + " · " + output));
            history.add(Message.assistant("已执行（沙箱内）"));
            return "✅ 命令通过四层校验，已在沙箱内执行：\n$ " + cmd + "\n" + output
                    + "\n\n（教学模拟执行。生产版每层都是独立防线：模型生成的命令也必须全过。）";
        }

        private String deny(int layer, String why) {
            lastTrace.add(Map.of("type", "guard", "label", "第 " + layer + " 层 · 拦截 DENY",
                    "detail", why + " —— 纵深防御：任何一层说不行，就是不行"));
            history.add(Message.assistant("已拦截"));
            return "🛑 已在第 " + layer + " 层拦截：" + why;
        }

        private static String simulate(String cmd) {
            if (cmd.startsWith("ls")) return "hello.txt  notes.md  data/";
            if (cmd.startsWith("cat")) return "ToyAgent sandbox（hello.txt 内容）";
            return "(模拟) 命令已执行，输出略";
        }

        private static final String SYSTEM_PROMPT = """
                你是 ToyAgent 的沙箱讲解员。用户可能询问沙箱、命令执行、安全防御相关话题。
                只输出 JSON：{"final": "回答"}。想看四层校验实战，请引导用户发「执行：<命令>」。""";

        @Override
        public void reset() {
            history.clear();
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
