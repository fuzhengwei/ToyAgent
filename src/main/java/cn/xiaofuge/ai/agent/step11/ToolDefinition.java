package cn.xiaofuge.ai.agent.step11;

import java.util.List;
import java.util.Map;

/**
 * Step11 · 工具定义协议 —— 一个接口 = 一个工具。
 * <p>
 * Step04 里工具清单是手写在常量里的 JSON Schema、执行是写死的 switch；
 * 本场景把"工具"收敛成一个自描述的协议（参考 deepseek-harness-java 的 ToolDefinition）：
 * <ul>
 *   <li>{@link #name()} 工具名 —— 模型 tool_calls 里的调用标识；</li>
 *   <li>{@link #description()} 用途描述 —— 模型据此判断"该不该用这个工具"；</li>
 *   <li>{@link #parameters()} 参数声明 —— 就是标准 JSON Schema 的 properties
 *       （生产中 Spring AI 的 &#064;Tool、MCP 的 tools/list 生成的都是它）；</li>
 *   <li>{@link #toolSchema()} 把以上三件套组装成 OpenAI tools 协议条目 ——
 *       工具自己会"申报"给协议，注册表只负责收集；</li>
 *   <li>{@link #execute(Map)} 执行逻辑 —— 工具的真正手脚。</li>
 * </ul>
 * 实现这个接口 + 注册进注册表 = 一个模型可见、可调用的工具。
 * MCP 协议的 tools/list + tools/call，本质就是这套协议的标准化版本（见场景 09）。
 */
public interface ToolDefinition {

    /** 工具名：模型 tool_calls 中 function.name 的取值。 */
    String name();

    /** 一句话用途：进入 tools 协议的 function.description，是模型选择工具的主要依据。 */
    String description();

    /**
     * 参数声明：标准 JSON Schema 的 properties 片段 ——
     * 参数名 → {"type": "...", "description": "..."}。
     * 注意这是真实协议形态，不再用"参数名 → 文字说明"的教学化简版。
     */
    Map<String, Object> parameters();

    /** 必填参数列表，进入 Schema 的 required 字段；默认全部必填。 */
    default List<String> required() {
        return List.copyOf(parameters().keySet());
    }

    /**
     * 组装 OpenAI tools 协议条目（即请求体 tools 数组的一个元素）：
     * <pre>
     * {"type": "function", "function": {"name": ..., "description": ..., "parameters": {...}}}
     * </pre>
     * 工具的自描述能力就在这里 —— 注册表把这些条目收集起来随每次请求发给模型。
     */
    default Map<String, Object> toolSchema() {
        Map<String, Object> fn = cn.xiaofuge.ai.llm.Json.obj();
        fn.put("name", name());
        fn.put("description", description());
        Map<String, Object> schema = cn.xiaofuge.ai.llm.Json.obj();
        schema.put("type", "object");
        schema.put("properties", parameters());
        schema.put("required", required());
        fn.put("parameters", schema);

        Map<String, Object> wire = cn.xiaofuge.ai.llm.Json.obj();
        wire.put("type", "function");
        wire.put("function", fn);
        return wire;
    }

    /** 执行工具：输入参数（来自 tool_calls 的 arguments JSON），输出给模型看的观察结果。 */
    String execute(Map<String, Object> args) throws Exception;

    /** 是否可并行执行（fs 读类 true，shell 写类 false）—— 预留并行调度语义。 */
    default boolean concurrencySafe() {
        return false;
    }
}
