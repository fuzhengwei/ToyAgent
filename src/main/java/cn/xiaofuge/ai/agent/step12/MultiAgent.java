package cn.xiaofuge.ai.agent.step12;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step12 · 多智能体协作 —— 从一个大脑到一支团队。
 * <p>
 * 真实产品（Codex / Claude Code / WorkBuddy）的多智能体设计高度一致，核心就三条：
 * <ol>
 *   <li><b>角色是「数据」不是「代码」</b> —— Codex 用 {@code .codex/agents/*.toml}、
 *       Claude Code 用 {@code .claude/agents/*.md} 声明角色（名字 + 职责 + 系统提示词 +
 *       工具白名单），主流程只认注册表，加角色不用改协作流程；</li>
 *   <li><b>上下文隔离</b> —— 每个角色在全新上下文里干活，搜索结果、草稿、日志等
 *       中间产物留在子代理内部，只把「结论摘要」交回主线。官方说法是防
 *       context rot（上下文腐化）：主会话只装需求与决策，不装噪声；</li>
 *   <li><b>质量门禁 + 循环护栏</b> —— 审查员是独立角色（Codex 的 reviewer 挂只读沙箱），
 *       不通过就打回重做，但最多 N 轮（loop guardrail），防止「写手 ⇄ 审查员」无限打转。</li>
 * </ol>
 * 本步用一个 ~60 行的「注册表 + 流水线」复刻这三点：
 * <pre>规划者 → 研究员 → 写手 ⇄ 审查员（最多 2 轮）→ 主持者汇总</pre>
 * 与 Step13 的分界：这里是<b>结构化流水线</b>（角色固定、顺序固定）；
 * Step13 是<b>动态派遣</b>（主代理自己决定派谁、派几个 —— 即 Claude Code 的 Task 工具）。
 */
public interface MultiAgent extends Agent {

    /**
     * 角色档案 —— 对应真实工具里的一个角色配置文件。
     * Codex 的 .toml 还带 model（按任务选模型）与 sandbox权限；教学版先只留最核心的提示词。
     */
    record AgentSpec(String key, String emoji, String system) {}

    class Impl implements MultiAgent {

        /**
         * 角色注册表 —— 等价于 .codex/agents/ 或 .claude/agents/ 目录。
         * 加一个角色 = 加一行 put，协作流程零改动。
         */
        private static final Map<String, AgentSpec> ROLES = new LinkedHashMap<>();
        static {
            ROLES.put("planner", new AgentSpec("planner", "🧭", """
                    你是规划者（Planner）。把用户任务拆解为三个角色的执行计划，只输出 JSON：
                    {"plan": [{"role": "研究员", "task": "..."}, {"role": "写手", "task": "..."}, {"role": "审查员", "task": "..."}]}
                    """));
            ROLES.put("researcher", new AgentSpec("researcher", "🔬",
                    "你是研究员（Researcher）。围绕任务输出 3 条以内研究要点，每条一句话。"));
            ROLES.put("writer", new AgentSpec("writer", "✍️",
                    "你是写手（Writer）。根据任务与素材写一段 100 字以内的短文，直接输出短文。"));
            ROLES.put("reviewer", new AgentSpec("reviewer", "🔍", """
                    你是审查员（Reviewer）。审查短文，只输出 JSON：
                    {"pass": true/false, "comment": "一句话点评"}
                    """));
        }

        /** 循环护栏：审查最多 2 轮 —— 真实工具里的 max_iterations / kill criteria。 */
        private static final int MAX_REVIEW_ROUNDS = 2;

        private final ChatModel model;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        public Impl(ChatModel model) {
            this.model = model;
        }

        @Override
        public String name() {
            return "Step12 · 多智能体协作（注册表 · 流水线 · 护栏）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();
            lastTrace.add(Map.of("type", "tool", "label", "🧩 加载角色注册表",
                    "detail", "已声明 " + ROLES.size() + " 个角色：" + String.join("、", ROLES.keySet())
                            + "（真实工具里这就是 .codex/agents/ 或 .claude/agents/ 下的配置文件）"));

            // 1. 规划者：拆解任务 —— 注意每次派遣都是全新上下文，角色之间互相看不到对方的过程
            Object planObj = tryParse(dispatch("planner", input));
            List<?> plan = planObj instanceof Map<?, ?> m && m.get("plan") instanceof List<?> l ? l : List.of();
            lastTrace.add(Map.of(
                    "type", "agent",
                    "label", "🧭 规划者",
                    "detail", "产出 " + plan.size() + " 步计划：" + describe(plan)));

            // 2. 研究员：收集素材 —— 搜索与整理的噪声留在自己的上下文里，回传的只是要点摘要
            String notes = dispatch("researcher", input);
            lastTrace.add(Map.of("type", "agent", "label", "🔬 研究员（独立上下文）",
                    "detail", clip(notes, 60)));

            // 3. 写手：基于素材成稿 —— 只拿到「研究笔记」这份摘要，而不是研究员的完整过程
            String draft = dispatch("writer", "任务: " + input + "\n研究笔记: " + notes);
            lastTrace.add(Map.of("type", "agent", "label", "✍️ 写手",
                    "detail", clip(draft, 60)));

            // 4. 审查员 + 循环护栏：不通过就打回写手重做，最多 MAX_REVIEW_ROUNDS 轮
            boolean pass = false;
            for (int round = 1; ; round++) {
                Object review = tryParse(dispatch("reviewer", draft));
                pass = Boolean.parseBoolean(String.valueOf(asMap(review).get("pass")));
                String c = Json.str(review, "comment");
                lastTrace.add(Map.of(
                        "type", pass ? "guard" : "warn",
                        "label", pass ? "✅ 审查员：通过（第 " + round + " 轮）" : "⚠️ 审查员：打回（第 " + round + " 轮）",
                        "detail", c == null ? "" : c));
                if (pass || round == MAX_REVIEW_ROUNDS) break;

                draft = dispatch("writer", "请按审查意见修改短文。\n原文: " + draft + "\n审查意见: " + Json.str(review, "comment"));
                lastTrace.add(Map.of("type", "agent", "label", "✍️ 写手修订",
                        "detail", "带着审查意见重写：" + clip(draft, 60)));
            }

            // 5. 主持者汇总：只把最终结论交回用户，子代理的全部中间过程对用户不可见
            lastTrace.add(Map.of("type", "final", "label", "🏁 主持者汇总",
                    "detail", (pass ? "一次过审" : "已达护栏上限，带审查意见交付")
                            + "；链路：规划 → 研究 → 写作 ⇄ 审查 → 交付"));
            return draft;
        }

        /**
         * 派遣一个角色 —— 多智能体的最小执行单元：
         * 全新消息列表 = 全新上下文（上下文隔离），只把文本结论带回主线（摘要回传）。
         * 真实工具在此基础上再加：工具白名单、按角色选模型、并行派遣。
         */
        private String dispatch(String key, String task) {
            AgentSpec spec = ROLES.get(key);
            return model.chat(List.of(Message.system(spec.system()), Message.user(task))).trim();
        }

        private Map<?, ?> asMap(Object o) {
            return o instanceof Map<?, ?> m ? m : Map.of();
        }

        private String describe(List<?> plan) {
            StringBuilder sb = new StringBuilder();
            for (Object o : plan) {
                if (o instanceof Map<?, ?> m) {
                    sb.append(String.valueOf(m.get("role"))).append("→");
                }
            }
            if (!sb.isEmpty()) sb.setLength(sb.length() - 1);
            return sb.toString();
        }

        private Object tryParse(String raw) {
            try {
                return Json.parse(raw.replaceFirst("^```\\w*\\s*", "").replaceFirst("```\\s*$", "").trim());
            } catch (Exception e) {
                return Json.obj();
            }
        }

        private String clip(String s, int n) {
            String one = s.replaceAll("\\s+", " ").trim();
            return one.length() > n ? one.substring(0, n) + "…" : one;
        }

        @Override
        public List<Map<String, Object>> trace() {
            return List.copyOf(lastTrace);
        }
    }

    /** 本地直接测试入口：java -cp target/classes cn.xiaofuge.ai.agent.step12.MultiAgent [你的问题] */
    public static void main(String[] args) {
        cn.xiaofuge.ai.llm.ChatModel model = cn.xiaofuge.ai.llm.Models.openAiOrNull();
        if (model == null) model = cn.xiaofuge.ai.llm.Models.mock("step12");
        String input = args.length > 0 ? String.join(" ", args) : "让多个子智能体协作回答：什么是 AI";
        System.out.println("[输入] " + input);
        System.out.println("[输出] " + new Impl(model).chat(input));
    }

}
