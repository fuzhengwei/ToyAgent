package cn.xiaofuge.ai.agent.step05;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.Message;
import cn.xiaofuge.ai.llm.Models;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Step05 · 记忆智能体 —— 让对话"有来有回"。
 * <p>
 * Step01 每轮都是失忆的。本场景引入短期记忆：把历史消息一并放进
 * 上下文窗口发给模型。当历史超过阈值时，用"抽取式摘要压缩"保护窗口 ——
 * 取最旧一问一答的关键文本，拼进 summary；这样窗口变短，事实不直接丢失。
 * {@link Impl} 是一个“记忆实验台”：短期记忆用 List 模拟上下文窗口，
 * 结构化记忆用 Map 模拟数据库，跨进程持久化用 Properties 模拟文件存储。
 * 每轮请求不是“查一次数据库就完”，而是把命中的长期事实、压缩摘要和最近原文
 * 重新组装成一次模型可读的上下文。
 * <p>
 * 记忆的本质不是数据库，而是：每一次请求，都带上该带的历史。
 */
public interface MemoryAgent extends Agent {

    class Impl implements MemoryAgent {

        /**
         * 短期记忆最多保留 4 条消息，也就是最近 2 个完整问答轮。
         */
        private static final int MAX_SHORT_MESSAGES = 4;

        /**
         * 模型适配器：课程里统一通过 ChatModel 隔离 OpenAI 兼容服务和 Mock 模型。
         */
        private final ChatModel model;

        /**
         * 长期记忆的磁盘位置；Properties 在这里只是替代 SQLite 的教学存储。
         */
        private final Path file;

        /**
         * 短期记忆：最近对话原文，最像真实产品的“会话内上下文窗口”。
         */
        private final List<Message> shortMemory = new ArrayList<>();

        /**
         * 结构化长期记忆：key/value 事实，最像真实产品的用户档案 / Profile 表。
         */
        private final Map<String, String> database = new LinkedHashMap<>();

        /**
         * 最近一次请求的可视化轨迹，页面用它展示“模型看到了什么”。
         */
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        /**
         * 压缩摘要：离开短期窗口的旧轮次被降采样成的一句话事实链。
         */
        private String summary = "";

        /**
         * 创建记忆智能体。
         * 生产对应：构造时注入模型客户端和记忆仓库；这里用文件路径代替 DAO / VectorStore。
         *
         * @param model 负责最终回答的模型
         * @param file  长期记忆持久化文件
         */
        public Impl(ChatModel model, Path file) {
            this.model = model;
            this.file = file;
            loadFile();
        }

        /**
         * 兼容网页服务旧装配方式：默认把长期记忆写入 runtime/step05-memory.properties。
         * 生产对应：仓库地址通常来自配置中心，不会硬编码在调用方。
         */
        public Impl(ChatModel model) {
            this(model, Path.of("runtime", "step05-memory.properties"));
        }

        @Override
        public String name() {
            return "Step05 · 记忆实验台";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();
            shortMemory.add(Message.user(input));
            if (shortMemory.size() > MAX_SHORT_MESSAGES) {
                compress();
            }

            String databaseMemory = recallDatabase(input);
            List<Message> context = buildContext(databaseMemory);

            lastTrace.add(Map.of(
                    "type", "memory",
                    "label", "本次装进上下文",
                    "detail", "文件记忆 + 数据库记忆 + 压缩摘要 + " + shortMemory.size() + " 条短期记忆"));

            String answer = model.chat(context);
            shortMemory.add(Message.assistant(answer));
            saveFile();

            lastTrace.add(Map.of(
                    "type", "final",
                    "label", "回答完成",
                    "detail", "短期记忆已更新，长期记忆已落盘"));
            return answer;
        }

        /**
         * 滑动窗口压缩：超过阈值后移除最旧的一问一答，并抽取成摘要链。
         * 生产对应：通常是 Context Compressor / Memory Summarizer；
         * 教学版用“截断抽取”省掉一次 LLM 调用，产品中常用 LLM 增量摘要或规则抽取。
         */
        private void compress() {
            Message question = shortMemory.remove(0);
            Message answer = shortMemory.remove(0);
            String digest = clip(question.content()) + " → " + clip(answer.content());
            summary = summary.isEmpty() ? digest : summary + "；" + digest;
            lastTrace.add(Map.of(
                    "type", "compress",
                    "label", "触发摘要压缩",
                    "detail", "最旧 1 轮移出短期窗口，当前摘要：" + summary));
        }

        /**
         * 组装模型上下文：系统规则 + 长期文件 + 结构化事实 + 压缩摘要 + 最近原文。
         * 生产对应：Context Builder / Prompt Assembler，把不同记忆源按需注入 prompt。
         */
        private List<Message> buildContext(String databaseMemory) {
            List<Message> context = new ArrayList<>();
            context.add(Message.system("""
                    你是一个有记忆的助手。回答时优先使用“记忆区”里的事实；
                    如果记忆里没有，就明确说不知道，不要编造。
                    """));
            context.add(Message.system("【长期记忆/文件】\n" + readMemoryText()));
            context.add(Message.system("【结构记忆/数据库】\n" + databaseMemory));
            if (!summary.isEmpty()) {
                context.add(Message.system("【压缩摘要】\n" + summary));
            }
            context.add(Message.system("【短期记忆】"));
            context.addAll(shortMemory);
            return context;
        }

        /**
         * 结构化记忆检索：按关键词命中 key/value 事实。
         * 生产对应：SQL / KV 查询；如果做语义问题，会换成向量检索或混合检索。
         */
        private String recallDatabase(String input) {
            String key;
            if (input.contains("咖啡")) key = "coffee";
            else if (input.contains("项目")) key = "project";
            else if (input.contains("名字")) key = "name";
            else key = "";

            if (key.isEmpty() || !database.containsKey(key)) {
                return "（没有命中）";
            }
            return key + " = " + database.get(key);
        }

        /**
         * 写入结构化长期记忆。
         * 生产对应：用户明确授权后的 Profile Update，例如“记住我偏好拿铁”写入用户偏好表。
         */
        public void rememberInDatabase(String key, String value) {
            database.put(key, value);
            saveFile();
        }

        /**
         * 启动时读取文件记忆；对应真实产品启动时连接并预热持久化存储。
         */
        private void loadFile() {
            Properties props = new Properties();
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    props.load(in);
                    for (String key : props.stringPropertyNames()) {
                        if (key.startsWith("db.")) database.put(key.substring(3), props.getProperty(key));
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("读取长期记忆失败", e);
                }
            }
        }

        /**
         * 立即落盘；对应真实产品的 Write-Through，保证进程重启后记忆还在。
         */
        private void saveFile() {
            try {
                Files.createDirectories(file.getParent());
                Properties props = new Properties();
                database.forEach((key, value) -> props.setProperty("db." + key, value));
                try (OutputStream out = Files.newOutputStream(file)) {
                    props.store(out, "Step05 long-term memory");
                }
            } catch (IOException e) {
                throw new IllegalStateException("写入长期记忆失败", e);
            }
        }

        /**
         * 把文件里的长期事实渲染成模型可读文本。
         */
        private String readMemoryText() {
            if (database.isEmpty()) return "（空）";
            return String.join("\n", database.keySet().stream()
                    .map(key -> key + " = " + database.get(key))
                    .toList());
        }

        /**
         * 抽取式压缩用的裁剪：保留关键开头，去掉换行和超长细节。
         */
        private String clip(String text) {
            String value = text.replaceAll("\\s+", " ").trim();
            return value.length() > 28 ? value.substring(0, 28) + "…" : value;
        }

        @Override
        /*
         * 清空会话内记忆；长期记忆保留，模拟用户重开一个新会话。
         **/
        public void reset() {
            shortMemory.clear();
            summary = "";
        }

        @Override
        public List<Map<String, Object>> trace() {
            return List.copyOf(lastTrace);
        }
    }

    /**
     * 本地直接测试入口：java -cp target/classes cn.xiaofuge.ai.agent.step05.MemoryAgent [你的问题]
     */
    public static void main(String[] args) {
        ChatModel model = Models.openAiOrNull();

        Impl agent = new Impl(model, Path.of("runtime", "step05-memory.properties"));

        agent.rememberInDatabase("name", "小傅哥");
        agent.rememberInDatabase("coffee", "冰拿铁");
        agent.rememberInDatabase("project", "ToyAgent");

        String[][] script = {
                {"我叫小傅哥，记住我的名字。", "第一轮：名字写入数据库记忆"},
                {"我最近在做 ToyAgent。", "第二轮：项目事实进入短期记忆"},
                {"我想优化上下文压缩。", "第三轮：触发滑动窗口压缩"},
                {"新会话测试：我平时喝什么咖啡？", "第四轮：短期记忆已重置，仍查长期记忆"},
                {"那我叫什么名字？在做什么项目？", "第五轮：一次回答引用结构记忆"}
        };

        for (int i = 0; i < script.length; i++) {
            if (i == 3) {
                agent.reset();
                System.out.println("\n[操作] 模拟用户重新打开一个会话（reset 短期记忆）");
            }

            String[] step = script[i];
            System.out.println("\n[输入] " + step[0]);
            System.out.println("[输出] " + agent.chat(step[0]));
            System.out.println("[说明] " + step[1]);
        }
    }
}
