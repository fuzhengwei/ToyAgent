package cn.xiaofuge.ai.agent.step24;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step24 · Hooks 钩子体系 —— 挂在工具调用生命周期上的「插件化拦截点」
 * （对应 deepseek-harness-java 的 HookService / HookMatcher / HookOutputMerger）。
 * <p>
 * 工具执行前后并不是「裸奔」的：宿主在两个生命周期点暴露钩子位，任何符合签名的钩子都能挂上去：
 * <ol>
 *   <li><b>before</b>：工具执行前 —— 可<strong>拦截</strong>（DENY）或<strong>改写参数</strong>；</li>
 *   <li><b>after</b>：工具执行后 —— 可<strong>改写输出</strong>（脱敏、过滤、追加告警）。</li>
 </ol>
 * 三个要素：{@link Hook}（钩子本体）+ 匹配规则（挂在哪类工具上，即 HookMatcher）+
 * 输出合并（多个 after 钩子按注册顺序依次加工，即 HookOutputMerger）。
 * <p>
 * 关键设计思想：<b>横切逻辑与工具解耦</b> —— 审计、脱敏、风控都不写进工具代码，
 * 挂钩即生效，摘钩即消失；工具作者对此无感知。
 * <p>
 * 试试本场景的对话：
 * <pre>
 *   查询：张三                          —— after 脱敏钩子把手机号/邮箱打码
 *   发送：给 13812345678 发 密码是123456 —— before 风控钩子直接拦截
 *   钩子：关  →  查询：张三              —— 摘掉钩子，明文裸奔，对比出钩子的价值
 *   钩子：开                            —— 挂回钩子
 *   审计日志                            —— before 审计钩子记下的每一次调用
 * </pre>
 */
public interface HooksAgent extends Agent {

    /**
     * 钩子本体：name 标识、phase 挂载点（before/after）、match 匹配的工具名（* = 全部）。
     * handler 返回 null = 不干预；返回 "DENY:原因" = 拦截；否则用返回值替换原内容。
     */
    record Hook(String name, String phase, List<String> match, Handler handler) {
        @FunctionalInterface
        interface Handler {
            String apply(String tool, String payload);
        }
    }

    class Impl implements HooksAgent {

        private final ChatModel model;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();
        /** before 审计钩子的落点：生产中是审计库 / SIEM，教学中是内存列表。 */
        private final List<String> auditLog = new ArrayList<>();
        private final List<Hook> hooks = new ArrayList<>();
        private volatile boolean enabled = true;

        public Impl(ChatModel model) {
            this.model = model;
            installDefaultHooks();
        }

        /** 出厂自带三条钩子：审计（before/全部）、风控（before/发短信）、脱敏（after/全部）。 */
        private void installDefaultHooks() {
            hooks.add(new Hook("审计", "before", List.of("*"), (tool, payload) -> {
                auditLog.add(LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
                        + "  " + tool + "  args=" + payload);
                return null; // 审计只记录，不干预
            }));
            hooks.add(new Hook("风控-敏感外发", "before", List.of("send_sms"), (tool, payload) ->
                    payload.contains("密码") || payload.contains("验证码")
                            ? "DENY:短信内容含「密码/验证码」等敏感信息，禁止外发"
                            : null));
            hooks.add(new Hook("脱敏", "after", List.of("*"), (tool, payload) -> {
                String masked = Pattern.compile("(1[3-9])\\d{9}").matcher(payload).replaceAll("$1*********");
                masked = Pattern.compile("([\\w.]+)@([\\w.]+)").matcher(masked).replaceAll(m ->
                        m.group(1).charAt(0) + "***@" + m.group(2));
                return masked.equals(payload) ? null : masked;
            }));
        }

        @Override
        public String name() {
            return "Step24 · Hooks 钩子体系（before 拦截/改写 · after 脱敏/审计，挂钩即生效）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();

            // —— 钩子管理指令（宿主能力，不经过模型） ——
            if (input.startsWith("钩子：")) {
                enabled = input.contains("开");
                lastTrace.add(Map.of("type", "action", "label", enabled ? "钩子已全部挂载" : "钩子已全部摘除",
                        "detail", "当前 " + hooks.size() + " 条钩子（审计/风控/脱敏），enabled=" + enabled));
                return enabled ? "✅ 钩子已挂回：" + hookList() : "⚠️ 钩子已全部摘除 —— 工具现在裸奔，注意对比接下来的输出。";
            }
            if (input.contains("审计日志")) {
                lastTrace.add(Map.of("type", "observation", "label", "审计日志（before 钩子写入）",
                        "detail", auditLog.isEmpty() ? "暂无记录" : String.join("\n", auditLog)));
                return "📋 审计日志（before 钩子记录）：\n" + (auditLog.isEmpty() ? "（暂无）" : String.join("\n", auditLog));
            }

            // —— 工具调用指令 → 走钩子管线 ——
            if (input.startsWith("查询：")) {
                return runPipeline("query_user", Json.write(Map.of("name", input.substring(3).trim())), """
                        {"name": "张三", "phone": "13812345678", "email": "zhangsan@example.com", "level": "VIP"}""");
            }
            if (input.startsWith("发送：")) {
                String payload = input.substring(3).trim();
                Matcher mobile = Pattern.compile("1[3-9]\\d{9}").matcher(payload);
                String phone = mobile.find() ? mobile.group() : "13800000000";
                return runPipeline("send_sms", Json.write(Map.of("phone", phone.isEmpty() ? "13800000000" : phone,
                        "message", payload)), "短信已提交网关，回执 ok");
            }

            // —— 普通对话 → 模型讲解 ——
            List<Message> req = new ArrayList<>();
            req.add(Message.system(SYSTEM_PROMPT));
            req.add(Message.user(input));
            String raw = model.chat(req).trim();
            Object parsed = tryParse(raw);
            String answer = parsed instanceof Map<?, ?> m && Json.str(m, "final") != null
                    ? Json.str(m, "final") : raw;
            lastTrace.add(Map.of("type", "final", "label", "讲解回答",
                    "detail", "试试「查询：张三」「发送：给 13812345678 发 密码是123456」「钩子：关」"));
            return answer;
        }

        /** 钩子管线：before 链（审计/风控）→ 工具执行 → after 链（脱敏），任何 before 拦截即终止。 */
        private String runPipeline(String tool, String args, String toolResult) {
            lastTrace.add(Map.of("type", "action", "label", "工具调用 " + tool,
                    "detail", "参数 " + args));

            // ===== before 钩子链 =====
            String payload = args;
            for (Hook h : matchHooks("before", tool)) {
                String out = h.handler().apply(tool, payload);
                if (out != null && out.startsWith("DENY:")) {
                    lastTrace.add(Map.of("type", "guard", "label", "before · " + h.name() + " → 拦截",
                            "detail", out.substring(5) + " —— 钩子在不改工具代码的前提下拦下调用"));
                    return "🛑 工具 " + tool + " 被 before 钩子「" + h.name() + "」拦截：" + out.substring(5);
                }
                if (out != null) {
                    lastTrace.add(Map.of("type", "thought", "label", "before · " + h.name() + " → 改写参数",
                            "detail", payload + "  →  " + out));
                    payload = out;
                } else {
                    lastTrace.add(Map.of("type", "thought", "label", "before · " + h.name() + " → 放行",
                            "detail", "未干预（如审计钩子：只记录不改动）"));
                }
            }

            // ===== 工具执行（教学模拟） =====
            lastTrace.add(Map.of("type", "observation", "label", "工具执行完成",
                    "detail", toolResult));

            // ===== after 钩子链（输出合并：按注册顺序依次加工） =====
            String output = toolResult;
            for (Hook h : matchHooks("after", tool)) {
                String out = h.handler().apply(tool, output);
                if (out != null) {
                    lastTrace.add(Map.of("type", "thought", "label", "after · " + h.name() + " → 改写输出",
                            "detail", "输出已加工（" + h.name() + "）"));
                    output = out;
                }
            }
            return "🔗 钩子管线执行完毕：\n最终输出：" + output
                    + (enabled ? "\n（before 审计+风控 → 工具 → after 脱敏，按注册顺序依次生效）" : "");
        }

        /** HookMatcher：按挂载点 + 工具名匹配；enabled=false 时整条链路直通。 */
        private List<Hook> matchHooks(String phase, String tool) {
            if (!enabled) return List.of();
            return hooks.stream()
                    .filter(h -> h.phase().equals(phase))
                    .filter(h -> h.match().contains("*") || h.match().contains(tool))
                    .toList();
        }

        private String hookList() {
            StringBuilder sb = new StringBuilder();
            for (Hook h : hooks)
                sb.append(h.phase()).append("·").append(h.name()).append("(match=").append(h.match()).append(") ");
            return sb.toString().trim();
        }

        private static final String SYSTEM_PROMPT = """
                你是 ToyAgent 的钩子讲解员。用户可能询问钩子（Hooks）、拦截、脱敏、审计等话题。
                只输出 JSON：{"final": "回答"}。请引导用户试用：「查询：张三」「发送：给 13812345678 发 密码是123456」「钩子：关」「审计日志」。""";

        @Override
        public void reset() {
            auditLog.clear();
            enabled = true;
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
