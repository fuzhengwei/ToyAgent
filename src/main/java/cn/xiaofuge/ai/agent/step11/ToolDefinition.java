package cn.xiaofuge.ai.agent.step11;

import java.util.Map;

/**
 * Step11 · 工具定义协议 —— 一个接口 = 一个工具。
 * <p>
 * Step03/04 里工具是写死在 switch 分支里的：加一个工具要改执行器代码。
 * 本场景把"工具"抽象成统一协议（参考 deepseek-harness-java 的 ToolDefinition）：
 * <ul>
 *   <li>{@link #name()} 工具名 —— 模型调用时使用的标识；</li>
 *   <li>{@link #description()} 用途描述 —— 模型据此判断"该不该用这个工具"；</li>
 *   <li>{@link #parameters()} 参数说明 —— 生产中是 JSON Schema，教学化简为「参数名 → 类型说明」；</li>
 *   <li>{@link #execute(Map)} 执行逻辑 —— 工具的真正手脚。</li>
 * </ul>
 * 实现这个接口 + 注册进注册表 = 一个模型可见、可调用的工具。
 * MCP 协议的 tools/list + tools/call，本质就是这套协议的标准化版本（见场景 09）。
 */
public interface ToolDefinition {

    /** 工具名：模型输出 {"tool": "名字"} 时的标识。 */
    String name();

    /** 一句话用途：会进入系统提示词，是模型选择工具的唯一依据。 */
    String description();

    /** 参数说明：教学化简版 JSON Schema（参数名 → 类型与含义）。 */
    Map<String, String> parameters();

    /** 执行工具：输入参数，输出给模型看的观察结果。 */
    String execute(Map<String, Object> args) throws Exception;

    /** 是否可并行执行（fs 读类 true，shell 写类 false）—— 预留并行调度语义。 */
    default boolean concurrencySafe() {
        return false;
    }
}
