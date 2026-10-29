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
 * 字符 bigram 重合率模拟向量相似度（教学简化），骨架完全一致 ——
 * 换成 Embedding 检索即生产级实现。
 * <p>
 * ── 向量是怎么匹配的（生产级原理）──────────────────────────
 * 1. 向量化（Embedding）：
 *    Embedding 模型（如 text-embedding-3-small）把一段文本压缩成一个
 *    固定长度的浮点向量（如 1536 维）。语义相近的文本，向量在高维
 *    空间中的方向也相近 —— "如何退货" 和 "退货运费谁出" 字面差异大，
 *    但向量距离很近。入库时对每个文档分块(chunk)分别向量化并存储。
 * 2. 相似检索（相似度度量）：
 *    查询时同样把用户问题向量化，再计算它与每个文档向量的相似度。
 *    最常用的度量是 <b>余弦相似度</b>（只看方向夹角、不看长度，消除文本
 *    长短影响）：
 *    <pre>cos(A, B) = (A · B) / (|A| × |B|)</pre>
 *    取值 [-1, 1]，越接近 1 越相似。工程上另有内积(IP，向量已归一化
 *    时等价于余弦)与欧氏距离(L2，越小越相似)两种度量。
 * 3. 近似最近邻（ANN）加速：
 *    全量暴力比对是 O(N)，文档量大时用向量库（Milvus/pgvector/FAISS）
 *    建索引做近似检索，常见算法：
 *    - HNSW：分层可导航小世界图，从顶层粗定位、逐层下沉精查找，
 *      查询近似 O(logN)，是主流选择；
 *    - IVF：先 K-Means 聚成若干桶，查询只比对最近的几个桶。
 *    以"损失一点召回精度"换几个数量级的加速。
 * 4. 教学简化：
 *    本类不引入 Embedding 依赖，改用字符 bigram（相邻两字组合）重合率
 *    打分 —— 可以理解为把文本表示成"所有 bigram 是否出现"的 0/1 稀疏
 *    向量，再算重合比例，思想与向量相似度同源（Jaccard/重合率近似），
 *    但只能匹配字面、无法匹配同义改写，这正是 Embedding 的价值所在。
 */
public interface RagAgent extends Agent {

    class Impl implements RagAgent {

        private final ChatModel model;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        /** 私有知识库：真实系统存向量库（每条文档一个 Embedding 向量 + HNSW 索引），这里用内存文档集合演示。 */
        private static final List<String> KNOWLEDGE = List.of(
                "ToyAgent 是小傅哥的智能体教学项目，用 12 个渐进式场景讲清楚智能体实现，每个场景只有一个接口类。",
                "智能体 = 大模型（大脑）+ 工具（手脚）+ 记忆（延续）+ 循环（节拍）。ReAct 循环是智能体的经典执行模式。",
                "MCP（Model Context Protocol）是 Anthropic 提出的工具标准化协议，核心方法为 tools/list 与 tools/call，实现工具与智能体解耦。",
                "RAG（检索增强生成）先从知识库检索相关片段，再把片段注入提示词让模型基于事实回答，能缓解幻觉与知识过期问题。",
                "Function Calling 让模型输出结构化工具调用请求（工具名 + 参数），由外部程序执行后将结果回填给模型。",
                "上下文窗口是模型一次能处理的最大 Token 数，超出时需要用滑动窗口、摘要压缩等上下文工程手段保护关键信息。");

        /**
         * 系统提示词：为生成阶段划定边界 —— 有据可依时带 [n] 引用，
         * 无据可依时明确拒答。拒答约定是抑制幻觉的关键手段：
         * 没有它，模型会拿相似但无关的知识"脑补"出一个看似合理的答案。
         */
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

        /**
         * 单轮 RAG 执行流程：检索 → 增强 → 生成（无多轮记忆）。
         * <pre>
         *   ① 检索：query 与每个文档打相似度分 → 降序取 Top2 → 过滤 0 分项
         *   ② 增强：命中片段编号注入上下文，让模型带 [n] 引用作答
         *   ③ 生成：模型"戴着资料说话"；资料不足时按系统提示词拒答
         * </pre>
         * 注意：ranked（含 0 分）仅用于 trace 展示；注入上下文的只有
         * hits —— 0 分片段注入反而会稀释上下文、诱导幻觉，这是
         * RAG 常见陷阱（宁可拒答，不要塞噪声）。
         */
        @Override
        public String chat(String input) {
            lastTrace.clear();

            // 1. 检索：全量文档打分 → 按分降序 → Top2 → 过滤 0 分（无重叠 = 无证据，宁缺毋滥）
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

            // 2. 增强：命中片段以 [1][2] 编号注入提示词，编号即回答中的引用出处；
            //    未命中则不注入任何资料，交由系统提示词触发拒答逻辑
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

            // 3. 生成：模型基于注入的资料作答（或按约定拒答）
            String answer = model.chat(messages);
            lastTrace.add(Map.of(
                    "type", "final",
                    "label", "增强生成完成",
                    "detail", hits.isEmpty() ? "模型按约定拒答" : "回答基于 " + hits.size() + " 个知识片段"));
            return answer;
        }

        /**
         * 教学版相似度：字符 bigram 重合率（查询的所有相邻二字组合，
         * 有多少能在文档中找到）。可理解为把两段文本各自表示成
         * "bigram 是否出现"的 0/1 稀疏向量，再算重合比例 —— 与向量
         * 相似度思想同源（重合率 ≈ Jaccard 近似）。
         * <p>
         * 局限：纯字面匹配，"退货" 搜不到 "退换货流程" 里的同义改写。
         * 生产实现：两端各调一次 Embedding 模型得到稠密向量，改算
         * 余弦相似度：
         * <pre>cos(A, B) = Σ(aᵢ·bᵢ) / (√Σaᵢ² × √Σbᵢ²)</pre>
         * 并将本方法的逐文档比对替换为向量库 ANN 查询（如 HNSW）。
         *
         * @param text  知识库文档
         * @param query 用户问题
         * @return [0, 1] 相似度分，0 表示无任何 bigram 重合
         */
        private double score(String text, String query) {
            // 问题太短（单字符无 bigram）直接判 0 分
            if (query.length() < 2) return 0;
            // 构造查询的 bigram 集合（Set 去重，避免重复组合稀释分母）
            java.util.Set<String> grams = new java.util.HashSet<>();
            for (int i = 0; i < query.length() - 1; i++) grams.add(query.substring(i, i + 2));
            // 统计能在文档中命中的 bigram 个数
            long hit = 0;
            for (String g : grams) {
                if (text.contains(g)) hit++;
            }
            return grams.isEmpty() ? 0 : (double) hit / grams.size();
        }

        /** trace 展示用的截断：超长片段取前 n 个字符加省略号，避免前端明细过长。 */
        private String clip(String s, int n) {
            return s.length() > n ? s.substring(0, n) + "…" : s;
        }

        /**
         * 返回本轮执行的检索与生成轨迹（只读快照），供前端可视化
         * 展示"先检索了什么、命中了哪些片段、基于几条资料作答"。
         */
        @Override
        public List<Map<String, Object>> trace() {
            return List.copyOf(lastTrace);
        }
    }

    /** 本地直接测试入口：java -cp target/classes cn.xiaofuge.ai.agent.step07.RagAgent [你的问题] */
    public static void main(String[] args) {
        cn.xiaofuge.ai.llm.ChatModel model = cn.xiaofuge.ai.llm.Models.openAiOrNull();
        if (model == null) model = cn.xiaofuge.ai.llm.Models.mock("demo");
        String input = args.length > 0 ? String.join(" ", args) : "ToyAgent 是什么？";
        System.out.println("[输入] " + input);
        System.out.println("[输出] " + new Impl(model).chat(input));
    }
}
