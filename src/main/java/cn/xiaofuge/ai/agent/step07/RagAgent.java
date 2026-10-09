package cn.xiaofuge.ai.agent.step07;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.Message;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Step07 · RAG 智能体 —— 检索增强生成。
 * <p>
 * 模型的知识是静态的，私有知识它根本没见过。RAG 的思路：
 * 先把问题在知识库里检索出最相关的片段，再把片段作为"参考资料"
 * 塞进提示词，让模型"戴着资料说话"。回答有出处、知识可热更。
 * <p>
 * 完整 RAG = 切分 → 向量化 → 相似检索 → 增强生成。本场景用
 * 关键词重合度模拟向量相似度（教学简化），骨架完全一致 ——
 * 换成 Embedding 检索即生产级实现。
 */
public interface RagAgent extends Agent {

    class Impl implements RagAgent {

        private final ChatModel model;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        /** 私有知识库：真实系统存向量库，这里用内存文档集合演示。 */
        private static final List<String> KNOWLEDGE = List.of(
                "ToyAgent 是小傅哥的智能体教学项目，用 12 个渐进式场景讲清楚智能体实现，每个场景只有一个接口类。",
                "智能体 = 大模型（大脑）+ 工具（手脚）+ 记忆（延续）+ 循环（节拍）。ReAct 循环是智能体的经典执行模式。",
                "MCP（Model Context Protocol）是 Anthropic 提出的工具标准化协议，核心方法为 tools/list 与 tools/call，实现工具与智能体解耦。",
                "RAG（检索增强生成）先从知识库检索相关片段，再把片段注入提示词让模型基于事实回答，能缓解幻觉与知识过期问题。",
                "Function Calling 让模型输出结构化工具调用请求（工具名 + 参数），由外部程序执行后将结果回填给模型。",
                "上下文窗口是模型一次能处理的最大 Token 数，超出时需要用滑动窗口、摘要压缩等上下文工程手段保护关键信息。");

        private static final String SYSTEM_PROMPT = """
                你是知识库问答智能体。回答规则：
                1. 优先依据【参考资料】回答，并标注来源编号如 [1]。
                2. 资料不足以回答时，如实说"知识库中暂无相关内容"，禁止编造。
                """;

        public Impl(ChatModel model) {
            this.model = model;
        }

        @Override
        public String name() {
            return "Step07 · RAG 检索增强生成";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();

            // 1. 检索：按关键词重合度给知识片段打分，取 Top2
            record Hit(String text, double score) {}
            List<Hit> ranked = KNOWLEDGE.stream()
                    .map(text -> new Hit(text, score(text, input)))
                    .sorted(Comparator.comparingDouble(Hit::score).reversed())
                    .limit(2)
                    .toList();

            List<Hit> hits = ranked.stream().filter(h -> h.score() > 0).toList();
            lastTrace.add(Map.of(
                    "type", "retrieve",
                    "label", "知识库检索",
                    "detail", hits.isEmpty()
                            ? "未命中任何片段（得分均为 0）"
                            : hits.stream()
                                    .map(h -> "%.0f 分 · %s".formatted(h.score() * 100, clip(h.text(), 30)))
                                    .reduce((a, b) -> a + " ｜ " + b).orElse("")));

            // 2. 增强：命中片段注入提示词；未命中则要求拒答
            List<Message> messages = new ArrayList<>();
            messages.add(Message.system(SYSTEM_PROMPT));
            if (!hits.isEmpty()) {
                StringBuilder ctx = new StringBuilder("【参考资料】\n");
                for (int i = 0; i < hits.size(); i++) {
                    ctx.append("[").append(i + 1).append("] ").append(hits.get(i).text()).append("\n");
                }
                messages.add(Message.user(ctx.toString()));
            }
            messages.add(Message.user(input));

            // 3. 生成
            String answer = model.chat(messages);
            lastTrace.add(Map.of(
                    "type", "final",
                    "label", "增强生成完成",
                    "detail", hits.isEmpty() ? "模型按约定拒答" : "回答基于 " + hits.size() + " 个知识片段"));
            return answer;
        }

        /** 教学版相似度：字符 bigram 重合率。生产中替换为 Embedding 余弦相似度。 */
        private double score(String text, String query) {
            if (query.length() < 2) return 0;
            java.util.Set<String> grams = new java.util.HashSet<>();
            for (int i = 0; i < query.length() - 1; i++) grams.add(query.substring(i, i + 2));
            long hit = 0;
            for (String g : grams) {
                if (text.contains(g)) hit++;
            }
            return grams.isEmpty() ? 0 : (double) hit / grams.size();
        }

        private String clip(String s, int n) {
            return s.length() > n ? s.substring(0, n) + "…" : s;
        }

        @Override
        public List<Map<String, Object>> trace() {
            return List.copyOf(lastTrace);
        }
    }
}
