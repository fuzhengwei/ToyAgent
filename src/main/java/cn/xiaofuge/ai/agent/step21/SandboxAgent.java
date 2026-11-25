package cn.xiaofuge.ai.agent.step21;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.ChatResponse;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;
import cn.xiaofuge.ai.llm.ToolCall;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Step21 · 沙箱纵深防御 —— 命令执行前的四道关卡（模型在环版）。
 * <p>
 * <b>架构定位（对照 Anthropic 官方文档）</b>：与 step19/20 同构 —— shell_execute 是真
 * Function Calling 工具，模型发起调用、宿主校验执行、verdict 以 role=tool 回填。
 * 沙箱与审批（step20）是<b>组合关系</b>：门禁管「该不该做」（权力），沙箱管「能碰什么」
 * （能力边界）—— Anthropic 官方原文："Sandboxed commands still go through the
 * regular permission flow"。
 * <p>
 * 四层校验管线（任何一层拦截即 DENY，verdict 回灌模型可修正重试）：
 * <ol>
 *   <li><b>策略层</b>：READ_ONLY 档全拒（对应 Claude Code Manual mode 只读起步）；
 *       WORKSPACE_ONLY / FULL 放行进入后续层 —— 注意 FULL 只是免审批，边界层照样生效；</li>
 *   <li><b>边界层（主力）</b>：命令中每个路径 token 都做<b>真规范化</b> ——
 *       相对路径 workspace.resolve(p).normalize()、绝对路径与 ~ 展开后逐一
 *       startsWith(workspace) 校验，越界即拒（对应官方 OS 边界：写仅限工作目录 +
 *       显式白名单，其余 deny）；</li>
 *   <li><b>黑名单层（辅助，如实标注）</b>：rm -rf、sudo 等破坏性特征 ——
 *       字符串黑名单<b>可被参数交换、变量间接引用等手段绕过</b>，防呆不防绕过，
 *       只能当辅助；主力永远是第 2 层的确定性边界与生产里的 OS 沙箱
 *       （macOS Seatbelt / Linux bubblewrap）；</li>
 *   <li><b>网络与凭据层</b>：网络命令默认全拒（官方语义：network allowlist 默认为空，
 *       出网须经代理逐域放行）；凭据类路径（~/.ssh、.aws、.env）一律 deny
 *       （官方语义：credentials denyRead）。</li>
 * </ol>
 * 关键设计：<b>不信任模型的输出</b> —— 模型生成的命令照样全过四层；
 * 拦截不是终点，verdict（层号 + 原因）回灌推理轨迹，模型可修正命令后再提交，
 * 人看到的是「拦截 → 修正 → 放行/再拦」的完整过程（透明原则）。
 */
public interface SandboxAgent extends Agent {

    /** 策略档位（对应 Claude Code 权限模式：Manual 只读起步 / 工作区边界 / 全自动）。 */
    enum Mode {
        READ_ONLY,       // 只读：禁止一切命令执行
        WORKSPACE_ONLY,  // 默认：工作区边界内的命令放行
        FULL             // 全自动：免审批，但边界层照样生效（生产慎用）
    }

    /** 校验结论：放行（含输出）or 拦截（层号 + 原因）。以 role=tool 回灌给模型。 */
    record Verdict(boolean allowed, int layer, String detail) {
        static Verdict pass(String output) { return new Verdict(true, 0, output); }
        static Verdict deny(int layer, String why) { return new Verdict(false, layer, why); }
    }

    class Impl implements SandboxAgent {

        private final ChatModel model;
        /** 循环保险丝：防失控的确定性上限（同 ToyHarness / step19/20 语义）。 */
        private static final int MAX_STEPS = 8;
        /** 会话历史：user / assistant(toolCalls) / tool(verdict) / assistant 最终回答。 */
        private final List<Message> history = new ArrayList<>();
        /** 策略档位（属部署配置，教学固定 WORKSPACE_ONLY —— 第 1 层只做确定性判定）。 */
        private Mode mode = Mode.WORKSPACE_ONLY;
        /** 沙箱工作区：所有路径 token 规范化后必须落在这里。 */
        private final Path workspace = Path.of("sandbox").toAbsolutePath().normalize();
        /** 本回合轨迹，元素结构见 trace() 注释。 */
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        /**
         * 内容黑名单（第 3 层·辅助）：只防呆，不防绕过 ——
         * 「rm -r -f」（拆开参数）、「eval $CMD」（变量间接引用）、base64 编码管道都能绕过它，
         * 这正是它只能当辅助层的原因；主力是第 2 层确定性边界与生产里的 OS 沙箱。
         */
        private static final List<String> DENYLIST = List.of(
                "rm -rf", "rm -fr", "rm  -rf", "sudo", "shutdown", "reboot", "mkfs", "dd if=",
                ":(){", "fork()", "chmod 777", "> /dev/", "kill -9 1", "curl | sh");

        /** 网络命令（第 4 层）：allowlist 默认为空 → 一律拒（token 精确匹配防误伤）。 */
        private static final List<String> NETWORK_CMDS =
                List.of("curl", "wget", "ssh", "scp", "ping", "nc", "telnet", "ftp");

        /** 凭据类路径特征（第 4 层）：官方 denyRead 语义（~/.ssh、~/.aws 等）。 */
        private static final List<String> CREDENTIAL_HINTS =
                List.of(".ssh", ".aws", "credentials", "id_rsa", ".env", ".netrc");

        public Impl(ChatModel model) {
            this.model = model;
            try {
                Files.createDirectories(workspace);
                Files.writeString(workspace.resolve("hello.txt"), "ToyAgent sandbox\n");
                Files.writeString(workspace.resolve("notes.md"), "# 教学笔记\n- 四层校验\n- 纵深防御\n");
            } catch (IOException ignored) {
                // 工作区初始化失败不阻断启动（校验层依然工作，只是演示素材少了）
            }
        }

        /** 当前执行策略说明（启动横幅用）：优先真沙箱，回落与否由每条命令的 trace 如实标注。 */
        public String executionMode() {
            return "三级回落链：①Seatbelt（macOS，sandbox-exec，Claude Code 同款内核边界）"
                    + "→ ②Docker 容器（Windows/Linux，--read-only + 仅工作区卷可写 + --network none）"
                    + "→ ③模拟（无任何真沙箱时兜底）；每条命令的 trace 会标注实际走了哪条";
        }

        @Override
        public String name() {
            return "Step21 · 沙箱纵深防御（策略 → 边界规范化 → 黑名单 → 网络凭据，四层拦截）";
        }

        /**
         * 对话入口：新任务进入会话历史，模型自主决定是否调用 shell_execute 工具；
         * 宿主以四层沙箱管线校验模型给出的命令，verdict 回灌续跑。
         * <p>
         * <b>返回</b>：本回合最终回答（收敛）或保险丝熔断提示。
         */
        @Override
        public String chat(String input) {
            lastTrace.clear();
            history.add(Message.user(input));
            return runTurn();
        }

        /**
         * Agent Loop —— 与 ToyHarness runLoop / step19/20 同构：
         * 模型调用 → 无 tool_calls 即收敛；有 tool_calls 逐个校验执行、回填 verdict 后进下一圈。
         * <p>
         * <b>返回</b>：两种形态 ——
         * <ul>
         *   <li>收敛：模型基于 verdict 给出的最终回答，例：
         *       {@code 沙箱里目前有 hello.txt、notes.md 和 data/。}</li>
         *   <li>熔断：{@code 已达最大步数 8，循环保险丝熔断。}</li>
         * </ul>
         */
        private String runTurn() {
            for (int step = 1; step <= MAX_STEPS; step++) {
                ChatResponse reply = model.chat(request(), TOOLS);

                // 收敛条件：模型不再发起工具调用 = 最终回答（同 ToyHarness 循环不变量）
                if (!reply.hasToolCalls()) {
                    String answer = reply.content();
                    history.add(Message.assistant(answer));
                    lastTrace.add(Map.of("type", "final", "label", "turn 完成",
                            "detail", "第 " + step + " 步收敛：命令执行结果（或拦截 verdict）已回灌，模型给出最终回答"));
                    return answer;
                }

                // 关键不变量：assistant(tool_calls) 原样入历史，不能改写成普通文本
                history.add(Message.assistantToolCalls(reply.toolCalls()));
                List<ToolCall> calls = reply.toolCalls();

                for (ToolCall call : calls) {
                    if (!"shell_execute".equals(call.name())) {
                        // 未知工具：如实回填错误结果，让模型下一圈自行纠正（不装作成功）
                        lastTrace.add(Map.of("type", "guard", "label", "未知工具",
                                "detail", call.name() + " 不在工具清单，回填错误结果"));
                        history.add(Message.tool(call.id(), "unknown tool: " + call.name()));
                        continue;
                    }

                    // ===== 四层沙箱管线：校验的是模型的输出，任何一层拦截即 DENY =====
                    String cmd = extractCommand(call);
                    Verdict v = pipeline(cmd);

                    if (v.allowed()) {
                        lastTrace.add(Map.of("type", "observation", "label", "四层校验通过 · 执行",
                                "detail", "$ " + cmd + " → " + v.detail()));
                    } else {
                        lastTrace.add(Map.of("type", "guard", "label",
                                "第 " + v.layer() + " 层 · 拦截 DENY",
                                "detail", cmd + " → " + v.detail() + " —— verdict 将回灌，模型可修正重试"));
                    }

                    // verdict 以 role=tool 按 tool_call_id 回灌 —— 拦截也是信息，不是终点
                    // 例（放行）：{"allowed":true,"output":"hello.txt  notes.md"}
                    // 例（拦截）：{"allowed":false,"layer":2,"reason":"路径 /etc/passwd 越出工作区"}
                    Map<String, Object> result = v.allowed()
                            ? Map.of("allowed", true, "output", v.detail())
                            : Map.of("allowed", false, "layer", v.layer(), "reason", v.detail());
                    history.add(Message.tool(call.id(), Json.write(result)));
                }
            }

            String fused = "已达最大步数 " + MAX_STEPS + "，循环保险丝熔断。";
            lastTrace.add(Map.of("type", "guard", "label", "保险丝", "detail", fused));
            return fused;
        }

        /**
         * 四层校验管线（对照 Anthropic 沙箱语义的白名单式设计）。
         * <p>
         * <b>入参案例</b>：cmd="cat /etc/passwd"。
         * <b>返回</b>：Verdict —— 全过则 pass(输出)，任一层拦截则 deny(层号, 原因)。
         */
        private Verdict pipeline(String cmd) {
            lastTrace.add(Map.of("type", "action", "label", "shell_execute 请求",
                    "detail", "$ " + cmd));

            // ===== 第 1 层 · 策略档位 =====
            if (mode == Mode.READ_ONLY) {
                return Verdict.deny(1, "当前策略 READ_ONLY：只读模式禁止一切命令执行"
                        + "（对应 Claude Code Manual mode 只读起步）");
            }
            lastTrace.add(Map.of("type", "thought", "label", "第 1 层 · 策略校验 ✓",
                    "detail", "当前策略 " + mode + " 允许进入沙箱校验"
                            + (mode == Mode.FULL ? "（FULL 只是免审批，后续边界层照样生效）" : "")));

            // ===== 第 2 层 · 空间边界 + 真规范化（主力防线）=====
            // 每个路径 token 都 canonicalize：相对路径 resolve 到工作区、~ 展开、绝对路径原样，
            // 然后逐一 startsWith(workspace) —— 「看似在内、实际在外」的逃逸无所遁形
            for (String token : cmd.split("[\\s\"']")) {
                if (!token.contains("/") || token.startsWith("-")) {
                    continue;
                }
                Path real = resolveRealPath(token);
                if (!real.normalize().startsWith(workspace)) {
                    return Verdict.deny(2, "路径 " + token + " 规范化后落在工作区之外（"
                            + real.normalize() + "）—— 边界层拒绝，对应官方 filesystem.allowWrite 白名单语义");
                }
            }
            lastTrace.add(Map.of("type", "thought", "label", "第 2 层 · 边界规范化 ✓",
                    "detail", "全部路径 token canonicalize 后均落在工作区 " + workspace.getFileName() + "/ 内"
                            + "（相对路径 resolve、~ 展开、绝对路径逐一核对）"));

            // ===== 第 3 层 · 内容黑名单（辅助：防呆不防绕过）=====
            String low = cmd.toLowerCase();
            for (String bad : DENYLIST) {
                if (low.contains(bad)) {
                    return Verdict.deny(3, "命中危险命令特征「" + bad + "」—— 注意：黑名单是辅助层，"
                            + "可被参数换序/变量间接引用绕过，主力是第 2 层边界与生产里的 OS 沙箱");
                }
            }
            lastTrace.add(Map.of("type", "thought", "label", "第 3 层 · 黑名单 ✓（辅助层）",
                    "detail", "未命中 " + DENYLIST.size() + " 条特征 —— 防呆设计，不承诺完备"));

            // ===== 第 4 层 · 网络与凭据 =====
            for (String token : cmd.split("[\\s\"']")) {
                String t = token.toLowerCase();
                if (NETWORK_CMDS.contains(t)) {
                    return Verdict.deny(4, "网络命令 " + token + " 被拒 —— 沙箱网络 allowlist 默认为空，"
                            + "出网须经代理逐域放行（官方 network.allowedDomains 语义）");
                }
                for (String cred : CREDENTIAL_HINTS) {
                    if (t.contains(cred)) {
                        return Verdict.deny(4, "路径 " + token + " 疑似凭据文件 —— 凭据一律 denyRead，"
                                + "永不进入上下文（官方 credentials denyRead 语义）");
                    }
                }
            }
            lastTrace.add(Map.of("type", "thought", "label", "第 4 层 · 网络与凭据 ✓",
                    "detail", "无网络命令（allowlist 为空）、无凭据路径（denyRead）"));

            // ===== 执行（工作区内真实只读操作 + 模拟兜底）=====
            return Verdict.pass(execute(cmd));
        }

        /**
         * 把命令里的路径 token 解析为文件系统真实位置：
         * ~ 展开 home；绝对路径原样；相对路径 resolve 到工作区 —— 再由调用方 normalize + startsWith 核对。
         */
        private Path resolveRealPath(String token) {
            if (token.startsWith("~")) {
                return Path.of(token.replaceFirst("^~", System.getProperty("user.home")));
            }
            if (token.startsWith("/")) {
                return Path.of(token);
            }
            return workspace.resolve(token);
        }

        /** 从工具调用参数里取命令文本；参数解析失败时退回原文（后续层照常校验）。 */
        private String extractCommand(ToolCall call) {
            try {
                Map<?, ?> args = (Map<?, ?>) Json.parse(call.argumentsJson());
                Object c = args.get("command");
                return c == null ? call.argumentsJson() : String.valueOf(c);
            } catch (Exception e) {
                return call.argumentsJson();
            }
        }

        /**
         * 执行：优先放進真沙箱（macOS Seatbelt —— Claude Code 同款机制），
         * 无 Seatbelt 的平台（Windows / Linux）回落 Docker（Windows 装 Docker Desktop 即可），
         * 都不可用时回落模拟。三级链保证任何环境都能演示，能套真边界时一定套真边界。
         * <p>
         * 生产对应：Java 进程只是「策略层」（决定边界划多大），真正的强制在内核/容器 ——
         * 即使命令是 base64 编码后管道给 sh 的（字符串层面看不出来想干什么），
         * 越界写的系统调用也会被内核直接拒绝。策略层 + 内核强制 = 完整沙箱。
         */
        private String execute(String cmd) {
            String real = execInSeatbelt(cmd);
            if (real != null) {
                return real;
            }
            real = execInDocker(cmd);
            if (real != null) {
                return real;
            }
            lastTrace.add(Map.of("type", "guard", "label", "执行模式 · 模拟回落",
                    "detail", "无可用真沙箱（Seatbelt 仅 macOS；Docker 未安装或守护进程未启动）——"
                            + "回落模拟执行；macOS 直接运行即走 Seatbelt，"
                            + "Windows / Linux 启动 Docker Desktop / dockerd 后重跑即走容器沙箱"));
            return execSimulated(cmd);
        }

        /**
         * 真沙箱执行：sandbox-exec -f profile.sh /bin/sh -c &lt;cmd&gt;，工作目录 = workspace。
         * 返回 null 表示环境无法套沙箱（嵌套沙箱 / 非 macOS），调用方回落模拟。
         * 命令因内核拒绝而失败时，把内核回执（Operation not permitted）如实返回 —— 这正是教学价值所在。
         */
        private String execInSeatbelt(String cmd) {
            if (!System.getProperty("os.name", "").contains("Mac")) {
                return null;
            }
            Path profile = null;
            Path outFile = null;
            try {
                profile = writeSeatbeltProfile();
                outFile = Files.createTempFile("sbx-out", ".txt");
                ProcessBuilder pb = new ProcessBuilder(
                        "sandbox-exec", "-f", profile.toString(), "/bin/sh", "-c", cmd);
                pb.directory(workspace.toFile());
                pb.redirectOutput(outFile.toFile());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return null;
                }
                String out = Files.readString(outFile).trim();
                int code = p.exitValue();
                // sandbox-exec 本身启动失败（嵌套沙箱等环境限制）→ 回落模拟
                if (code == 71 && out.contains("sandbox_apply")) {
                    return null;
                }
                lastTrace.add(Map.of("type", "guard", "label", "真实沙箱 · Seatbelt",
                        "detail", "内核强制边界：默认禁写，仅工作区可写；网络禁用；凭据目录禁读 ——"
                                + "命令「想做」与「能做」从此分离（exit=" + code + "）"));
                return "(Seatbelt 真沙箱 · exit=" + code + ")"
                        + (out.isEmpty() ? "" : "\n" + out);
            } catch (Exception e) {
                return null;
            } finally {
                try {
                    if (profile != null) Files.deleteIfExists(profile);
                    if (outFile != null) Files.deleteIfExists(outFile);
                } catch (Exception ignored) {
                }
            }
        }

        /** 生成 Seatbelt profile（Claude Code sandboxing 文档同款语义）：默认放行、禁写、工作区例外、禁网、凭据禁读。 */
        private Path writeSeatbeltProfile() throws Exception {
            String home = System.getProperty("user.home");
            String profile = """
                    (version 1)
                    (allow default)
                    (deny file-write*)
                    (allow file-write* (subpath "%s"))
                    (deny network*)
                    (deny file-read* (subpath "%s/.ssh") (subpath "%s/.aws"))
                    """.formatted(workspace.toAbsolutePath(), home, home);
            Path path = Files.createTempFile("toyagent-sandbox", ".sb");
            Files.writeString(path, profile);
            return path;
        }

        /** Docker 可用性（惰性探针，整个进程只探测一次）：守护进程在线 + 可响应，即认为可用。 */
        private volatile Boolean dockerOk;

        /**
         * 容器沙箱执行（Windows / Linux 的等价路径）：docker run 容器内跑命令，语义与 Seatbelt 版对齐 ——
         * <ul>
         *   <li>--read-only：容器根文件系统只读（对应 deny file-write*）；</li>
         *   <li>-v workspace:/ws：唯一可写卷 = 工作区（对应 allow file-write* 白名单）；</li>
         *   <li>--network none：断网（对应 deny network*，allowlist 为空）；</li>
         *   <li>宿主凭据（~/.ssh、~/.aws）根本不在容器内 —— 比禁读更彻底。</li>
         * </ul>
         * 返回 null 表示 Docker 不可用（未安装 / 守护进程未启动 / 镜像缺失 / 超时），调用方继续回落。
         */
        private String execInDocker(String cmd) {
            if (!probeDocker()) {
                return null;
            }
            Path outFile = null;
            try {
                outFile = Files.createTempFile("sbx-docker", ".txt");
                ProcessBuilder pb = new ProcessBuilder(
                        "docker", "run", "--rm", "--network", "none", "--read-only",
                        "-v", workspace.toAbsolutePath() + ":/ws", "-w", "/ws",
                        "alpine:3", "/bin/sh", "-c", cmd);
                pb.redirectOutput(outFile.toFile());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                if (!p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return null;
                }
                String out = Files.readString(outFile).trim();
                int code = p.exitValue();
                // 守护进程中途失联 / 镜像缺失等环境问题 → 回落（命令本身失败不算，要如实回灌）
                if (code != 0 && (out.contains("Cannot connect to the Docker daemon")
                        || out.contains("Unable to find image")
                        || out.contains("docker: error"))) {
                    return null;
                }
                lastTrace.add(Map.of("type", "guard", "label", "真实沙箱 · Docker",
                        "detail", "容器强制边界：根文件系统只读，唯一可写卷 = 工作区 /ws；"
                                + "--network none 禁网；宿主凭据不进容器 —— "
                                + "命令「想做」与「能做」从此分离（exit=" + code + "）"));
                return "(Docker 真沙箱 · exit=" + code + ")"
                        + (out.isEmpty() ? "" : "\n" + out);
            } catch (Exception e) {
                return null;
            } finally {
                try {
                    if (outFile != null) Files.deleteIfExists(outFile);
                } catch (Exception ignored) {
                }
            }
        }

        /** 探针：docker info 能否在 5 秒内正常应答。结果缓存，避免每条命令都探测。 */
        private boolean probeDocker() {
            Boolean cached = dockerOk;
            if (cached != null) {
                return cached;
            }
            boolean ok;
            try {
                ProcessBuilder pb = new ProcessBuilder("docker", "info", "--format", "ok");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    ok = false;
                } else {
                    String out = new String(p.getInputStream().readAllBytes()).trim();
                    ok = p.exitValue() == 0 && out.contains("ok");
                }
            } catch (Exception e) {
                ok = false;
            }
            dockerOk = ok;
            return ok;
        }

        /** 回落：工作区内 ls / cat 走真实文件系统（只读、已在边界内），其余模拟。 */
        private String execSimulated(String cmd) {
            try {
                if (cmd.startsWith("ls")) {
                    try (Stream<Path> files = Files.list(workspace)) {
                        return "真实目录列表：" + files.map(p -> p.getFileName().toString())
                                .sorted().reduce((a, b) -> a + "  " + b).orElse("(空)");
                    }
                }
                if (cmd.startsWith("cat")) {
                    String[] parts = cmd.split("\\s+");
                    if (parts.length > 1) {
                        Path target = resolveRealPath(parts[1]).normalize();
                        if (target.startsWith(workspace) && Files.exists(target)) {
                            return "真实文件内容：" + Files.readString(target).trim();
                        }
                    }
                }
            } catch (Exception ignored) {
                // 真实读取失败 → 落到模拟输出
            }
            return "(模拟) 命令已执行，输出略";
        }

        /**
         * 组装发给模型的请求消息列表：SYSTEM_PROMPT 打头 + 全部会话历史。
         * <p>
         * <b>返回案例</b>（一次拦截-修正后的续跑请求）：
         * <pre>{@code [
         *   Message(role="system",    content=SYSTEM_PROMPT),
         *   Message(role="user",      content="看看 /etc/passwd 里有什么"),
         *   Message(role="assistant", content="", toolCalls=[shell_execute("cat /etc/passwd")]),
         *   Message(role="tool",      content={"allowed":false,"layer":2,"reason":"路径 /etc/passwd 越出工作区…"}),
         *   ...模型据此修正命令，继续循环
         * ]}</pre>
         */
        private List<Message> request() {
            List<Message> req = new ArrayList<>();
            req.add(Message.system(SYSTEM_PROMPT));
            req.addAll(history);
            return req;
        }

        private static final String SYSTEM_PROMPT = """
                你是接入了沙箱 shell 工具（shell_execute）的智能体，用命令完成用户的任务。
                每条命令都会经宿主四层校验（策略 / 路径边界 / 黑名单 / 网络凭据），
                沙箱边界只覆盖工作区 sandbox/。被拦截时工具结果会给出层号和原因 ——
                请据此修正命令后重新提交（例如改用 sandbox/ 内的相对路径），不要编造执行结果。""";

        /** shell_execute 工具 Schema：结构由服务端保证，「约束」写进 description。 */
        private static final List<Map<String, Object>> TOOLS = List.of(
                Map.of("type", "function", "function", Map.of(
                        "name", "shell_execute",
                        "description", "在沙箱工作区（sandbox/）内执行 shell 命令。"
                                + "命令会经四层校验：策略档位、路径边界（规范化后必须在 sandbox/ 内）、"
                                + "危险特征黑名单、网络与凭据禁令。被拦截时结果会说明层号与原因。",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "command", Map.of(
                                                "type", "string",
                                                "description", "要执行的 shell 命令，路径请使用 sandbox/ 内的相对路径")),
                                "required", List.of("command")))));

        /** 清空会话：历史归零，回到全新状态（策略档位保留，属部署配置）。 */
        @Override
        public void reset() {
            history.clear();
        }

        /**
         * 本回合执行轨迹（只读快照），供 UI/Terminal 渲染。每次 chat 开始时清空重建。
         * <p>
         * <b>返回案例</b>（一轮「拦截-修正」回合）：
         * <pre>{@code [
         *   {type="action",  label="shell_execute 请求",      detail="$ cat /etc/passwd"},
         *   {type="guard",   label="第 2 层 · 拦截 DENY",     detail="路径 /etc/passwd 越出工作区…"},
         *   {type="observation", label="四层校验通过 · 执行",  detail="$ ls sandbox → …"},
         *   {type="final",   label="turn 完成",               detail="…"}
         * ]}</pre>
         * type 取值：action（命令提交）/ thought（各层通过）/ guard（拦截与保险丝）/
         * observation（执行结果）/ final（收敛）。
         */
        @Override
        public List<Map<String, Object>> trace() {
            return List.copyOf(lastTrace);
        }
    }

    /**
     * 本地演示入口：java -cp target/classes cn.xiaofuge.ai.agent.step21.SandboxAgent [任务1 [任务2 ...]]
     * <p>
     * 无需用户输入，自动跑一组固定对话剧本，覆盖沙箱管线的四条路径：
     * ① 安全命令放行 ② 边界拦截 → verdict 回灌 → 模型修正重试 ③ 高危删除被拦 ④ 网络禁令拦截。
     * 也可传入自定义任务逐个执行。每回合渲染执行轨迹（各层打勾 / 拦截 / 执行 / 收敛）。
     */
    public static void main(String[] args) {
        cn.xiaofuge.ai.llm.ChatModel model = cn.xiaofuge.ai.llm.Models.openAiOrNull();
        if (model == null) {
            model = cn.xiaofuge.ai.llm.Models.mock("step21");
            System.out.println("[模型] 未配置真实模型，使用 Mock 演示。");
        }

        SandboxAgent agent = new Impl(model);

        System.out.println("=== Step21 · 沙箱纵深防御（策略 → 边界规范化 → 黑名单 → 网络凭据）===");
        System.out.println("[执行] " + ((Impl) agent).executionMode());
        System.out.println("模型发起的每条命令都要过四层校验 —— 拦截会回灌，模型可修正重试。\n");

        // 固定演示剧本：四条路径正好对应四层的教学意图（可被命令行参数覆盖）
        String[] demos = args.length > 0 ? args : new String[]{
                "看看沙箱里有什么文件",                 // ① 安全命令 → 四层全过 → 执行
                "看看 /etc/passwd 里有什么",            // ② 第 2 层边界拦截 → 模型修正重试
                "帮我把沙箱里的 hello.txt 用 rm -fr 删掉", // ③ 第 3 层黑名单拦截（防呆层）
                "用 curl 下载 example.com 的网页"        // ④ 第 4 层网络禁令拦截
        };

        for (int i = 0; i < demos.length; i++) {
            String task = demos[i];
            System.out.println("──────────────────────────────────────── 剧本 " + (i + 1) + "/" + demos.length);
            System.out.println("[你] " + task);
            String output = agent.chat(task);
            for (Map<String, Object> step : agent.trace()) {
                System.out.println("  ┈ [" + step.get("type") + "] " + step.get("label") + " —— " + step.get("detail"));
            }
            System.out.println("[Agent] " + output + "\n");
        }
        System.out.println("=== 演示结束 ===");
    }
}
