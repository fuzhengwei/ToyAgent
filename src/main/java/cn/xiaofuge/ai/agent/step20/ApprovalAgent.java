package cn.xiaofuge.ai.agent.step20;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Step20 · 审批门禁 —— 高危操作先过人，批准才放行。
 * <p>
 * 参考 deepseek-harness-java 的运行期审批链路（提交期权限矩阵 + 高危工具人工审批），
 * 拆出四个教学点：
 * <ol>
 *   <li><b>风险分级</b>：每个工具声明风险等级，安全工具（只读）自动放行，
 *       高危工具（写文件/发邮件/执行命令）必须过门禁；</li>
 *   <li><b>三档模式</b>：AUTO_MANUAL_SAFE（本场景默认）/ ALWAYS_ASK / FULL_AUTO，
 *       决定门禁松紧；</li>
 *   <li><b>会话级免审记忆</b>：「批准并记住」后，同工具本会话内自动放行 ——
 *       信任一次，不再重复打扰；</li>
 *   <li><b>超时默认 DENY</b>：生产里审批有超时，超时即拒绝（宁可不执行，不可乱执行）。</li>
 * </ol>
 */
public interface ApprovalAgent extends Agent {

    /** 审批模式三档。 */
    enum Mode {
        AUTO_MANUAL_SAFE,  // 安全自动放行，高危问人（默认）
        ALWAYS_ASK,        // 一切外部动作都要审批
        FULL_AUTO          // 全自动（仅教学演示，生产慎用）
    }

    class Impl implements ApprovalAgent {

        private final ChatModel model;
        private final List<Message> history = new ArrayList<>();
        /** 会话级免审名单：批准并记住的高危工具。 */
        private final Set<String> trustedTools = new HashSet<>();
        /** 挂起中的审批请求。 */
        private String pendingTool;
        private String pendingArgs;
        private Mode mode = Mode.AUTO_MANUAL_SAFE;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        public Impl(ChatModel model) {
            this.model = model;
        }

        @Override
        public String name() {
            return "Step20 · 审批门禁（高危操作先过人：批准 / 拒绝 / 批准并记住）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();

            // ===== 分支一：有挂起审批 —— 本条输入是人的决策，不进模型 =====
            if (pendingTool != null) {
                String tool = pendingTool;
                String args = pendingArgs;
                pendingTool = null;
                return resolveApproval(tool, args, input);
            }

            // ===== 分支二：正常对话 =====
            history.add(Message.user(input));
            return decideAndAct(input);
        }

        private String decideAndAct(String input) {
            List<Message> req = new ArrayList<>();
            req.add(Message.system(SYSTEM_PROMPT));
            history.forEach(m -> req.add(m));

            String raw = model.chat(req).trim();
            Object parsed = tryParse(raw);
            if (!(parsed instanceof Map<?, ?> m)) {
                history.add(Message.assistant(raw));
                lastTrace.add(Map.of("type", "final", "label", "直接作答", "detail", "无工具需求"));
                return raw;
            }

            String tool = Json.str(m, "tool");
            if (tool == null) {
                String answer = orRaw(Json.str(m, "final"), raw);
                history.add(Message.assistant(answer));
                lastTrace.add(Map.of("type", "final", "label", "直接作答", "detail", "无需工具"));
                return answer;
            }

            Object argVal = m.get("arguments");
            String args = argVal == null ? "" : String.valueOf(argVal);
            lastTrace.add(Map.of("type", "action", "label", "模型发起工具调用",
                    "detail", tool + " " + args));

            // ===== 权限评估：风险分级 + 模式 + 会话免审记忆 =====
            boolean dangerous = isDangerous(tool);
            boolean needAsk = mode == Mode.ALWAYS_ASK
                    || (mode == Mode.AUTO_MANUAL_SAFE && dangerous && !trustedTools.contains(tool));
            lastTrace.add(Map.of("type", "thought", "label", "权限矩阵评估",
                    "detail", tool + " → " + (dangerous ? "高危" : "安全")
                            + " · 模式 " + mode + (trustedTools.contains(tool) ? " · 命中会话免审" : "")));

            if (needAsk) {
                pendingTool = tool;
                pendingArgs = args;
                lastTrace.add(Map.of("type", "guard", "label", "审批挂起 PENDING_APPROVAL",
                        "detail", "高危操作等待人工决策。生产版有审批超时 —— 超时默认 DENY，宁可不执行"));
                return "⚠️ 即将执行高危操作：" + tool + "\n参数：" + args + "\n\n"
                        + "请回复：「批准」执行 ｜「拒绝」取消 ｜「批准并记住」本会话内该工具免审";
            }

            String result = execute(tool, args);
            lastTrace.add(Map.of("type", "observation", "label",
                    trustedTools.contains(tool) && dangerous ? "自动放行（会话免审）" : "自动放行（安全工具）",
                    "detail", result));
            return finish(tool, result);
        }

        /** 处理人的审批决策。 */
        private String resolveApproval(String tool, String args, String decision) {
            String d = decision.trim();
            if (d.startsWith("批准并记住")) {
                trustedTools.add(tool);
                lastTrace.add(Map.of("type", "guard", "label", "人工决策 · 批准并记住",
                        "detail", tool + " 加入会话级免审名单，本会话后续调用自动放行"));
            } else if (d.startsWith("批准")) {
                lastTrace.add(Map.of("type", "guard", "label", "人工决策 · 批准",
                        "detail", "仅本次放行，下次仍会询问"));
            } else if (d.startsWith("拒绝")) {
                lastTrace.add(Map.of("type", "guard", "label", "人工决策 · 拒绝",
                        "detail", "高危操作被拦截，任务终止（生产版超时未审批同样按 DENY 处理）"));
                history.add(Message.user("审批结果：" + tool + " 被拒绝"));
                return "已拒绝执行 " + tool + "。操作未发生 —— 审批门禁的意义就在于：人的否决权永远在模型之上。";
            } else {
                pendingTool = tool;
                pendingArgs = args;
                lastTrace.add(Map.of("type", "guard", "label", "无法识别的决策",
                        "detail", "审批仍挂起，请回复：批准 / 拒绝 / 批准并记住"));
                return "没看懂你的决定。请回复：「批准」｜「拒绝」｜「批准并记住」";
            }
            String result = execute(tool, args);
            lastTrace.add(Map.of("type", "observation", "label", "审批放行 · 执行", "detail", result));
            history.add(Message.user("审批结果：" + tool + " 已批准并执行"));
            return finish(tool, result);
        }

        /** 工具执行后，让模型基于结果收尾。 */
        private String finish(String tool, String result) {
            List<Message> req = new ArrayList<>();
            req.add(Message.system(SYSTEM_PROMPT + "\n（工具 " + tool + " 的执行结果：" + result + "。请据此作答。）"));
            history.forEach(m -> req.add(m));
            String raw = model.chat(req).trim();
            Object parsed = tryParse(raw);
            String answer = parsed instanceof Map<?, ?> m ? orRaw(Json.str(m, "final"), raw) : raw;
            history.add(Message.assistant(answer));
            return answer;
        }

        // -------------------------------------------------------------- 风险与执行

        /** 风险分级：写出去的操作都是高危，只读是安全。 */
        private static boolean isDangerous(String tool) {
            return switch (tool) {
                case "send_email", "delete_file", "shell_execute", "publish_article" -> true;
                default -> false;
            };
        }

        /** 模拟执行（教学：不真发邮件、不真删文件）。 */
        private static String execute(String tool, String args) {
            return switch (tool) {
                case "send_email" -> "已发送（模拟）：" + args;
                case "delete_file" -> "已删除（模拟）：" + args;
                case "get_weather" -> "晴，26℃（模拟数据）";
                default -> "已执行（模拟）：" + args;
            };
        }

        private static final String SYSTEM_PROMPT = """
                你是一个接入了多种工具的智能体。可用工具：
                - get_weather(city)：查天气【安全·只读】
                - send_email(to, content)：发邮件【高危·写操作】
                - delete_file(path)：删文件【高危·写操作】
                需要工具时只输出 JSON：{"tool": "工具名", "arguments": "参数摘要"}
                无需工具时只输出 JSON：{"final": "最终答案"}。不要输出 JSON 以外内容。""";

        @Override
        public void reset() {
            history.clear();
            trustedTools.clear();
            pendingTool = null;
            pendingArgs = null;
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
