package cn.xiaofuge.ai.llm;

/**
 * 一条对话消息。role 取值：system / user / assistant。
 * <p>
 * 用 record 表达最纯的数据结构 —— 智能体与模型之间流动的
 * 永远只是「角色 + 文本」，这就是一切复杂行为的原材料。
 */
public record Message(String role, String content) {

    public static Message system(String content) {
        return new Message("system", content);
    }

    public static Message user(String content) {
        return new Message("user", content);
    }

    public static Message assistant(String content) {
        return new Message("assistant", content);
    }
}
