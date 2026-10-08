package cn.xiaofuge.ai.agent.step10;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Step10 · 多智能体协作 —— 从一个大脑到一支团队。
 * <p>
 * 单个智能体上下文有限、职责混杂。多智能体模式按角色拆分：
 * 规划者（Planner）拆解任务 → 专家（Worker）各司其职 →
 * 审查员（Reviewer）质量把关 → 主持者（Orchestrator）汇总交付。
 * <p>
 * 每个角色都是一个独立的 ChatAgent 实例（不同的系统提示词），
 * 协作的本质是：结构化的消息在角色之间流动。
 */
public interface MultiAgent extends Agent {

    class Impl implements MultiAgent {

        private final ChatModel model;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        public Impl(ChatModel model) {
            this.model = model;
        }

        @Override
        public String name() {
            return "Step10 · 多智能体协作（规划-执行-审查）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();

            // 1. 规划者：拆解任务
            String planRaw = model.chat(List.of(
                    Message.system("""
                            你是规划者（Planner）。把用户任务拆解为三个角色的执行计划，只输出 JSON：
                            {"plan": [{"role": "研究员", "task": "..."}, {"role": "写手", "task": "..."}, {"role": "审查员", "task": "..."}]}
                            """),
                    Message.user(input))).trim();
            Object planObj = tryParse(planRaw);
            List<?> plan = planObj instanceof Map<?, ?> m ? (List<?>) m.get("plan") : List.of();
            lastTrace.add(Map.of(
                    "type", "agent",
                    "label", "🧭 规划者",
                    "detail", "产出 " + plan.size() + " 步计划：" + describe(plan)));

            // 2. 研究员：收集素材
            String notes = model.chat(List.of(
                    Message.system("你是研究员（Researcher），围绕任务输出 3 条以内研究要点，每条一句话。"),
                    Message.user(input)));
            lastTrace.add(Map.of("type", "agent", "label", "🔬 研究员", "detail", clip(notes, 60)));

            // 3. 写手：基于素材成稿
            String draft = model.chat(List.of(
                    Message.system("""
                            你是写手（Writer）。基于研究笔记写一段 100 字以内的短文，主题如下。
                            研究笔记: %s
                            """.formatted(notes)),
                    Message.user(input)));
            lastTrace.add(Map.of("type", "agent", "label", "✍️ 写手", "detail", clip(draft, 60)));

            // 4. 审查员：质量把关
            String reviewRaw = model.chat(List.of(
                    Message.system("""
                            你是审查员（Reviewer）。审查短文，只输出 JSON：
                            {"pass": true/false, "comment": "一句话点评"}
                            """),
                    Message.user(draft))).trim();
            Object review = tryParse(reviewRaw);
            boolean pass = Boolean.parseBoolean(String.valueOf(((Map<?, ?>) review).get("pass")));
            String comment = Json.str(review, "comment");
            lastTrace.add(Map.of(
                    "type", pass ? "guard" : "warn",
                    "label", pass ? "✅ 审查员：通过" : "⚠️ 审查员：打回",
                    "detail", comment == null ? "" : comment));

            // 5. 主持者汇总交付
            String finalAnswer = pass
                    ? draft
                    : model.chat(List.of(
                            Message.system("根据审查意见修改短文，直接输出修改后的版本。"),
                            Message.user("原文: " + draft + "\n审查意见: " + comment)));
            lastTrace.add(Map.of("type", "final", "label", "🏁 主持者汇总", "detail", "协作链路：规划 → 研究 → 写作 → 审查 → 交付"));
            return finalAnswer;
        }

        private String describe(List<?> plan) {
            StringBuilder sb = new StringBuilder();
            for (Object o : plan) {
                if (o instanceof Map<?, ?> m) {
                    sb.append(String.valueOf(m.get("role"))).append("→");
                }
            }
            if (sb.length() > 0) sb.setLength(sb.length() - 1);
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
}
