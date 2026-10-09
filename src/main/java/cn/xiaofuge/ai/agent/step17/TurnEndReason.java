package cn.xiaofuge.ai.agent.step17;

/**
 * Step17 · 回合结束原因（TurnEndReason）—— 用封闭类型统一表达"循环为什么停"。
 * <p>
 * Step03 的循环只有两种结局：给出答案，或步数超限。但真实的运行时里，
 * 结束原因有很多种，且下游（前端展示、审计、续跑决策）都需要知道"为什么停"。
 * 参考 deepseek-harness-java 的 TurnEndReason，用 sealed interface + record 把
 * 所有结束原因做成一个封闭代数类型：
 * <ul>
 *   <li>{@link Completed} —— 正常完成（模型给出最终答案）；</li>
 *   <li>{@link MaxSteps} —— 触达单回合步数保险丝，强制终止；</li>
 *   <li>{@link Error} —— 异常终止（模型失约、工具崩溃等）。</li>
 * </ul>
 * sealed 的价值：编译器穷尽检查 —— 新增一种结束原因时，所有 switch 它的地方
 * 都会被编译器提醒补分支，杜绝"忘了处理某种结局"的隐患。
 */
public sealed interface TurnEndReason {

    /** 正常完成：模型给出最终答案。 */
    record Completed(String summary) implements TurnEndReason { }

    /** 触达步数保险丝：单回合最多 MAX_STEPS 步，强制收敛。 */
    record MaxSteps(long steps) implements TurnEndReason { }

    /** 异常终止。 */
    record Error(String message) implements TurnEndReason { }
}
