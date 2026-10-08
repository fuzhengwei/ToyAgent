package cn.xiaofuge.ai.agent.step08;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;
import cn.xiaofuge.ai.agent.step03.ReActAgent.MockWeather;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Step08 · Skills 技能智能体 —— 工具的组合与复用。
 * <p>
 * 单个工具解决单步问题，真实任务往往是多步组合。
 * Skill 是"可沉淀、可复用的能力包"，按抽象层级分三层：
 * <ul>
 *   <li>L0 提示词技能 —— 纯 Prompt 模板，零工具；</li>
 *   <li>L1 工具组合 —— 固定调用一组工具并拼装结果；</li>
 *   <li>L2 子流程 —— 一个完整的 ReAct 循环被封成一项技能。</li>
 * </ul>
 * 模型按语义选择技能，智能体负责执行 —— 这就是 Claude Skills /
 * 插件体系的编排思想。
 */
public interface SkillAgent extends Agent {

    class Impl implements SkillAgent {

        private final ChatModel model;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        private static final String SELECT_PROMPT = """
                你是技能选择器。当前已注册技能：
                - travel_plan(L1): 旅行攻略 —— 组合天气查询与时间工具生成出行建议
                - weather_report(L1): 天气播报 —— 调用天气工具
                - free_chat(L0): 自由聊天 —— 纯提示词技能，直接回答

                根据用户输入只输出 JSON：{"skill": "技能名", "reason": "一句话理由"}
                """;

        public Impl(ChatModel model) {
            this.model = model;
        }

        @Override
        public String name() {
            return "Step08 · 技能编排（Skills L0/L1/L2）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();

            // 1. 技能选择
            String raw = model.chat(List.of(Message.system(SELECT_PROMPT), Message.user(input))).trim();
            Object decision = tryParse(raw);
            String skill = Json.str(decision, "skill");
            if (skill == null) skill = "free_chat";
            lastTrace.add(Map.of(
                    "type", "skill",
                    "label", "技能选择",
                    "detail", skill + "（" + (Json.str(decision, "reason") == null ? "" : Json.str(decision, "reason")) + "）"));

            // 2. 技能执行
            return switch (skill) {
                case "travel_plan" -> travelPlan(input);      // L1: 多工具组合
                case "weather_report" -> weatherReport(input); // L1: 单工具
                default -> freeChat(input);                    // L0: 纯提示词
            };
        }

        /** L1 技能：组合天气 + 时间两个工具，产出结构化攻略。 */
        private String travelPlan(String input) {
            String city = input.contains("杭州") ? "杭州" : input.contains("上海") ? "上海"
                    : input.contains("成都") ? "成都" : input.contains("广州") ? "广州"
                    : input.contains("深圳") ? "深圳" : "北京";
            lastTrace.add(Map.of("type", "action", "label", "L1 组合工具 1/2", "detail", "get_weather(" + city + ")"));
            String weather = MockWeather.query(city);
            lastTrace.add(Map.of("type", "observation", "label", "工具返回", "detail", weather));
            lastTrace.add(Map.of("type", "action", "label", "L1 组合工具 2/2", "detail", "get_time()"));
            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            lastTrace.add(Map.of("type", "observation", "label", "工具返回", "detail", time));

            String answer = model.chat(List.of(
                    Message.system("""
                            你是旅行顾问。基于给定的天气与时间信息，输出简短出行攻略（3 条以内要点）。
                            """),
                    Message.user(input + "\n天气: " + weather + "\n当前时间: " + time)));
            lastTrace.add(Map.of("type", "final", "label", "L1 技能完成", "detail", "两件工具的结果已拼装进提示词"));
            return answer;
        }

        /** L1 技能：单工具播报。 */
        private String weatherReport(String input) {
            String city = input.contains("上海") ? "上海" : input.contains("杭州") ? "杭州" : "北京";
            lastTrace.add(Map.of("type", "action", "label", "L1 调用工具", "detail", "get_weather(" + city + ")"));
            String weather = MockWeather.query(city);
            lastTrace.add(Map.of("type", "final", "label", "播报完成", "detail", weather));
            return "「" + city + "」" + weather + "。";
        }

        /** L0 技能：纯提示词，不碰工具。 */
        private String freeChat(String input) {
            lastTrace.add(Map.of("type", "final", "label", "L0 提示词技能", "detail", "无工具，直接对话"));
            return model.chat(List.of(
                    Message.system("你是友好的中文助手，回答保持简洁。"),
                    Message.user(input)));
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
}
