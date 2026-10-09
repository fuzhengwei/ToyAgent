package cn.xiaofuge.ai.agent.step11;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Step11 · 工具注册表 —— 模型"看得见"的工具目录。
 * <p>
 * 三个职责（与 deepseek-harness-java 的 ToolRegistry/AgentToolCatalog 同构）：
 * <ol>
 *   <li><b>注册</b>：{@link #register} 把工具放进目录，并返回一个「注销器 Runnable」——
 *       持有它就能把工具从目录摘除。插件停止/卸载时，宿主靠这个句柄回收注册项，
 *       这就是「注册-回收对称性」；</li>
 *   <li><b>发现</b>：{@link #lookup} 按名字取工具，执行器只认注册表、不认具体工具；</li>
 *   <li><b>清单生成</b>：{@link #promptCatalog()} 把目录渲染成系统提示词里的工具清单 ——
 *       <b>模型能看见哪些工具，完全由注册表当前内容决定</b>。注册即生效，注销即消失。</li>
 * </ol>
 */
public final class ToolRegistry {

    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

    /** 注册工具，返回注销器（disposer）：调用 run() 即把该工具从目录摘除。 */
    public synchronized Runnable register(ToolDefinition tool) {
        tools.put(tool.name(), tool);
        return () -> {
            synchronized (ToolRegistry.this) {
                tools.remove(tool.name());
            }
        };
    }

    /** 按名字查找工具。 */
    public synchronized Optional<ToolDefinition> lookup(String name) {
        return Optional.ofNullable(name == null ? null : tools.get(name));
    }

    public synchronized int size() {
        return tools.size();
    }

    /** 是否为空。 */
    public synchronized boolean isEmpty() {
        return tools.isEmpty();
    }

    /** 清空注册表（重置场景时使用）。 */
    public synchronized void clear() {
        tools.clear();
    }

    /**
     * 把当前目录渲染成系统提示词中的工具清单 ——
     * 工具增删后无需改任何提示词代码，下一次对话模型自动"看见"变化。
     */
    public synchronized String promptCatalog() {
        StringBuilder sb = new StringBuilder();
        for (ToolDefinition t : tools.values()) {
            sb.append("- ").append(t.name()).append("(");
            int i = 0;
            for (Map.Entry<String, String> p : t.parameters().entrySet()) {
                if (i++ > 0) sb.append(", ");
                sb.append(p.getKey()).append(": ").append(p.getValue());
            }
            sb.append(") — ").append(t.description()).append("\n");
        }
        return sb.toString();
    }
}
