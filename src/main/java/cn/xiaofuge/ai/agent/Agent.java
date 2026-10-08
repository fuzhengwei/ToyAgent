package cn.xiaofuge.ai.agent;

import java.util.List;
import java.util.Map;

/**
 * 智能体契约 —— 整个 ToyAgent 的灵魂只有一个方法：
 *
 * <pre>{@code
 *     String chat(String input);
 * }</pre>
 *
 * 输入一句话，输出一句话，中间发生了什么（思考、调工具、查记忆），
 * 都是这个方法内部的策略 —— 12 个场景的演进，全是围绕这个方法的展开。
 * <p>
 * trace() 把中间过程暴露给页面渲染（思考步骤、工具调用等），
 * reset() 供有状态场景（如记忆）清空上下文。
 */
public interface Agent {

    /** 场景名称，用于页面展示。 */
    String name();

    /** 智能体最小契约：输入用户消息，返回回答。 */
    String chat(String input);

    /** 最近一次 chat 的执行轨迹（供页面渲染时间线），无轨迹则返回空列表。 */
    default List<Map<String, Object>> trace() {
        return List.of();
    }

    /** 清空场景内部状态（如记忆），无状态场景无需实现。 */
    default void reset() {
    }
}
