package cn.xiaofuge.ai.llm;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 教学演示用 Mock 模型：不联网、不花钱，让 12 个场景开箱即跑。
 * <p>
 * 它并非"真智能"，而是按场景预设了符合协议的返回（例如工具调用时
 * 返回 JSON 决策、ReAct 时返回 Thought/Action），让学习者可以完整
 * 观察智能体的「骨架」如何运转。配置真实 API Key 后骨架不变，
 * 换上真大脑即可。
 */
public final class MockChatModel implements ChatModel {

    private final String scenario;

    public MockChatModel(String scenario) {
        this.scenario = scenario;
    }

    @Override
    public String chat(List<Message> messages) {
        String system = messages.get(0).content();
        String last = lastUserText(messages);
        String all = messages.stream().map(Message::content).reduce("", (a, b) -> a + "\n" + b);

        return switch (scenario) {
            case "step01" -> mockChat(last);
            case "step02" -> mockPrompt(last, system);
            case "step03" -> mockReAct(all, last);
            case "step04" -> mockToolCall(last);
            case "step05" -> mockMemory(all, last);
            case "step06" -> mockRouter(last);
            case "step07" -> mockMcp(last);
            case "step08" -> mockSkill(all, last);
            case "step09" -> mockRag(all, last);
            case "step10" -> mockMulti(all, system);
            case "step11" -> mockLoop(last, all);
            case "step12" -> mockWorkflow(all, last);
            case "step13" -> mockFull(all, last);
            case "step15" -> mockRegistry(last);
            case "step16" -> mockRuntime(all, last);
            case "step17" -> mockAsk(all, last);
            case "step18" -> mockApproval(last);
            case "step19" -> mockSandbox(last);
            case "step20" -> mockEvent(all, last);
            case "step21" -> mockPlugin(all, last);
            case "step22" -> mockCli(all, last, system);
            case "step23" -> mockSubagent(all, last, system);
            case "step24" -> mockHooks(all, last, system);
            case "step25" -> mockA2a(all, last, system);
            case "step26" -> mockSchedule(all, last, system);
            default -> "（Mock 模型）收到：" + last;
        };
    }

    private String lastUserText(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role().equals("user")) return messages.get(i).content();
        }
        return "";
    }

    // ------------------------------------------------------------- step01 对话

    private String mockChat(String input) {
        if (input.contains("你好") || input.contains("hi") || input.contains("hello")) {
            return "你好！我是 ToyAgent 里的最小智能体。我的全部实现只有一个方法：chat(input)。输入进来，模型处理，回答出去 —— 这就是智能体的最小 MVP。";
        }
        return "（Mock 模型）已收到你的消息：「" + clip(input) + "」。\n"
                + "Step01 的实现没有策略、没有工具、没有记忆 —— 每次对话都是一次独立的「输入 → 模型 → 输出」。\n"
                + "配置真实 API Key 后，这句回复将由真实大模型生成。继续试试右边的示例问题吧。";
    }

    // ------------------------------------------------------------- step02 提示词

    private String mockPrompt(String input, String system) {
        boolean oneLine = input.contains("一句话");
        if (oneLine) {
            return "好的，遵守「一句话」约束：智能体就是一个用大模型做决策、用工具做执行的程序。";
        }
        return "好的，我是你的技术助理（角色由系统提示词设定）。「" + clip(input) + "」可以这样理解：\n\n"
                + "1. 定义 —— 智能体 = 大模型(大脑) + 工具(手脚) + 循环(节拍)。\n"
                + "2. 原理 —— 模型根据系统提示词中的人设与约束来组织回答，本条回复就是模板化输出的效果。\n"
                + "3. 建议 —— 好提示词 = 明确角色 + 交代背景 + 给出任务与约束，这正是 Step02 代码里 systemPrompt 的三层结构。\n\n"
                + "（Mock 模型按系统提示词的格式约定作答；真实模型会表现出同样的角色倾向。）";
    }

    // ------------------------------------------------------------- step13 全流程

    /** 与 step03 同一套 ReAct JSON 协议，但画像信息会让回答更「有记忆」。 */
    private String mockFull(String all, String last) {
        String portrait = "";
        if (all.contains("名字={")) {
            portrait = "，" + extractAfter(all, "名字={").replaceFirst("\\}.*", "") + "同学";
        } else if (all.contains("喜好={")) {
            portrait = "，还记得你喜欢" + extractAfter(all, "喜好={").replaceFirst("\\}.*", "");
        }
        if (all.contains("Observation:")) {
            String obs = extractAfter(all, "Observation:");
            return Json.write(Map.of(
                    "thought", "工具结果已返回，结合记忆与观察结果组织最终回答",
                    "final", clip(obs) + " —— 本轮完整走过了：守卫 → 记忆 → 循环 → 工具 → 输出校验 → 回写记忆" + (portrait.isEmpty() ? "。" : "（" + portrait.replaceFirst("，", "") + "）。")));
        }
        if (last.matches(".*\\d+\\s*[+\\-*/]\\s*\\d+.*")) {
            Matcher m = Pattern.compile("(\\d+\\s*[+\\-*/]\\s*\\d+)").matcher(last);
            String expr = m.find() ? m.group(1) : "1+1";
            return Json.write(Map.of(
                    "thought", "有算式，调用计算器工具，不能靠心算",
                    "action", "calculator",
                    "action_input", expr));
        }
        String city = findCity(last);
        if (!city.equals("北京") || last.contains("天气")) {
            return Json.write(Map.of(
                    "thought", "问题需要实时数据，先调天气工具",
                    "action", "get_weather",
                    "action_input", city));
        }
        return Json.write(Map.of(
                "thought", "普通寒暄无需工具，直接结合记忆回答",
                "final", "你好" + (portrait.isEmpty() ? "！" : portrait + "！")
                        + "我是全流程智能体：守卫已放行你的输入，记忆里" + (portrait.isEmpty() ? "还没有你的档案" : "有你的画像")
                        + "，本轮无需调用工具即可作答。"));
    }

    // ------------------------------------------------------------- step03 ReAct

    private String mockReAct(String all, String last) {
        if (all.contains("Observation:")) {
            String obs = extractAfter(all, "Observation:");
            return Json.write(Map.of(
                    "thought", "已拿到工具观察结果，可以回答用户了",
                    "final", "根据工具查询结果：" + clip(obs) + " —— 这就是 ReAct：先想（Thought）、再做（Action）、看结果（Observation），直到能给出最终答案（Final Answer）。"));
        }
        if (last.matches(".*\\d+\\s*[+\\-*/]\\s*\\d+.*")) {
            Matcher m = Pattern.compile("(\\d+\\s*[+\\-*/]\\s*\\d+)").matcher(last);
            String expr = m.find() ? m.group(1) : "1+1";
            return Json.write(Map.of(
                    "thought", "这是一个算式，我不能心算，应该调用计算器工具保证准确",
                    "action", "calculator",
                    "action_input", expr));
        }
        String city = findCity(last);
        return Json.write(Map.of(
                "thought", "用户询问了天气相关的问题，我需要先调用天气工具获取实时数据",
                "action", "get_weather",
                "action_input", city));
    }

    // ------------------------------------------------------------- step04 工具调用

    private String mockToolCall(String last) {
        // 工具结果回填轮：不再发起调用，基于事实生成回答
        if (last.contains("执行结果") || last.contains("Observation")) {
            String fact = extractAfter(last, "执行结果");
            return "根据工具返回的结果：" + (fact.isBlank() ? clip(last) : clip(fact)) + "，这就是你要的答案 —— 工具调用链闭环完成。";
        }
        if (last.contains("天气") || last.contains("weather")) {
            return Json.write(Map.of(
                    "tool", "get_weather",
                    "arguments", Map.of("city", findCity(last))));
        }
        Matcher m = Pattern.compile("(\\d+\\s*[+\\-*/]\\s*\\d+)").matcher(last);
        if (m.find()) {
            return Json.write(Map.of(
                    "tool", "calculator",
                    "arguments", Map.of("expression", m.group(1))));
        }
        return "这个问题不需要调用工具，我直接回答：「" + clip(last) + "」—— 工具调用的关键在于：模型自己判断「要不要用工具、用哪个、参数是什么」。";
    }

    // ------------------------------------------------------------- step05 记忆

    private String mockMemory(String all, String last) {
        // 从完整对话历史里找"我叫X"，真实体现"记忆 = 把历史放进上下文"
        Matcher m = Pattern.compile("我叫([\\u4e00-\\u9fa5A-Za-z0-9]{1,10})").matcher(all);
        String remembered = null;
        while (m.find()) remembered = m.group(1);

        if (last.contains("我叫")) {
            return "你好，" + remembered + "！我已把你的名字放进对话历史（注意看右侧的「记忆窗口」计数）。";
        }
        if (last.contains("叫什么") || last.contains("记得") || last.contains("谁")) {
            return remembered != null
                    ? "当然记得，你叫「" + remembered + "」。我没有数据库 —— 是因为之前那轮对话还在上下文窗口里，这就是短期记忆的全部秘密。"
                    : "目前的历史里还没有你的名字，先告诉我「我叫XX」吧。";
        }
        if (last.contains("压缩") || last.contains("总结")) {
            return "已对历史对话做了压缩摘要：保留关键事实（如你的名字、讨论主题），丢弃寒暄与重复内容 —— 滑窗 + 摘要，就是上下文工程最常用的两板斧。";
        }
        return "（Mock 模型）这一轮我带着 " + countHistory(all) + " 条历史消息在工作。多轮对话 = 每次都把「系统提示词 + 全部历史 + 新输入」发给模型。";
    }

    private long countHistory(String all) {
        return all.lines().filter(l -> !l.isBlank()).count();
    }

    // ------------------------------------------------------------- step06 路由

    private String mockRouter(String last) {
        String intent;
        if (last.contains("天气") || last.contains("weather")) intent = "weather";
        else if (last.matches(".*\\d+\\s*[+\\-*/]\\s*\\d+.*")) intent = "math";
        else intent = "chat";
        return Json.write(Map.of(
                "intent", intent,
                "reason", "根据用户输入关键词与语义判断下一步走向"));
    }

    // ------------------------------------------------------------- step07 MCP

    private String mockMcp(String last) {
        // 工具结果回填轮：不再发起调用
        if (last.contains("工具结果") || last.contains("Observation")) {
            String fact = extractAfter(last, "工具结果");
            return "基于 MCP Server 返回的事实：" + (fact.isBlank() ? clip(last) : clip(fact)) + " —— 标准协议下的调用链闭环完成。";
        }
        if (last.contains("天气") || last.contains("weather")) {
            return Json.write(Map.of(
                    "method", "tools/call",
                    "params", Map.of("name", "get_weather", "arguments", Map.of("city", findCity(last)))));
        }
        return "（Mock 模型）MCP 的核心是把「工具」标准化：统一注册、统一发现（tools/list）、统一调用（tools/call）。这个问题我直接回答即可，无需调用工具。";
    }

    // ------------------------------------------------------------- step08 技能

    private String mockSkill(String all, String last) {
        // 技能执行轮：travel_plan 技能内的模型调用按角色作答
        if (all.contains("旅行顾问")) {
            return "杭州出行攻略：① 当前晴，27℃，适宜户外；② 建议上午游西湖，下午避晒逛馆；③ 早晚温差小，轻装出行即可。（由 L1 技能组合天气与时间工具产出）";
        }
        String skill;
        if (last.contains("旅行") || last.contains("攻略") || last.contains("旅游")) skill = "travel_plan";
        else if (last.contains("天气")) skill = "weather_report";
        else skill = "free_chat";
        return Json.write(Map.of(
                "skill", skill,
                "reason", "依据输入语义匹配已注册技能"));
    }

    // ------------------------------------------------------------- step09 RAG

    private String mockRag(String all, String last) {
        String ctx = extractAfter(all, "【参考资料】");
        if (!ctx.isBlank()) {
            String firstLine = ctx.lines().findFirst().orElse("相关资料");
            return "根据检索到的资料：「" + clip(firstLine) + "」……\n\n"
                    + "回答基于知识库片段 [1][2] 生成 —— 模型只负责「戴着资料说话」，事实由检索保证，这就是 RAG 解决模型幻觉与知识过期的思路。";
        }
        return "（Mock 模型）知识库中未命中相关片段时，我会如实回答不知道，而不是编造 —— 拒答也是 RAG 的重要能力。";
    }

    // ------------------------------------------------------------- step10 多智能体

    private String mockMulti(String all, String system) {
        if (system.contains("规划者")) {
            return Json.write(Map.of(
                    "plan", List.of(
                            Map.of("role", "研究员", "task", "围绕主题收集要点"),
                            Map.of("role", "写手", "task", "把要点组织成一篇短文"),
                            Map.of("role", "审查员", "task", "检查事实与结构，不通过则打回"))));
        }
        if (system.contains("研究员")) {
            return "研究笔记：① 智能体的本质是模型驱动的循环；② 工具扩展了模型的行动边界；③ 工程化决定上限。";
        }
        if (system.contains("审查员")) {
            return Json.write(Map.of("pass", true, "comment", "结构完整、表述准确，建议保留要点式风格"));
        }
        if (all.contains("研究笔记")) {
            return "短文：智能体并不神秘 —— 它是一个让大模型「边想边做」的循环程序：模型负责决策，工具负责执行，记忆负责延续，工程负责兜底。（由写手智能体基于研究笔记产出）";
        }
        return "（Mock 模型）已收到任务。";
    }

    // ------------------------------------------------------------- step11 Loop + 守卫

    private String mockLoop(String last, String all) {
        if (all.contains("Observation:")) {
            String obs = extractAfter(all, "Observation:");
            return Json.write(Map.of(
                    "thought", "信息足够，输出最终答案",
                    "final", "循环结束，最终回答：" + clip(obs)));
        }
        return Json.write(Map.of(
                "thought", "进入运行时循环，先获取所需信息",
                "action", "get_weather",
                "action_input", findCity(last)));
    }

    // ------------------------------------------------------------- step12 工作流

    private String mockWorkflow(String all, String last) {
        if (all.contains("[节点:classify]")) {
            String next = last.contains("天气") || last.matches(".*\\d+\\s*[+\\-*/].*") ? "tool" : "faq";
            return Json.write(Map.of("next", next, "reason", "意图分类节点给出路由"));
        }
        if (all.contains("[节点:tool]")) {
            if (last.matches(".*\\d+\\s*[+\\-*/]\\s*\\d+.*")) {
                Matcher m = Pattern.compile("(\\d+\\s*[+\\-*/]\\s*\\d+)").matcher(last);
                return Json.write(Map.of("tool", "calculator", "arguments", Map.of("expression", m.find() ? m.group(1) : "1+1")));
            }
            return Json.write(Map.of("tool", "get_weather", "arguments", Map.of("city", findCity(last))));
        }
        if (all.contains("Observation:")) {
            return "综合以上节点结果，这里是最终回答：" + clip(extractAfter(all, "Observation:")) + "（由 polish 节点整理输出）。";
        }
        if (all.contains("[节点:polish]")) {
            String base = extractAfter(all, "前序产出: ").lines().findFirst().orElse("");
            return base.isBlank() ? "润色完成：「" + clip(last) + "」。" : "润色完成：" + clip(base);
        }
        return "这是 FAQ 节点的直接回答。「" + clip(last) + "」属于常见问题，无需工具，命中预设答案后进入 polish 润色。";
    }

    // ------------------------------------------------------------- step15 工具注册表

    /** 与 step04 同一套 {"tool","arguments"} 协议，但工具清单来自注册表。 */
    private String mockRegistry(String last) {
        if (last.contains("执行结果")) {
            String fact = extractAfter(last, "执行结果");
            return "根据工具返回的结果：" + (fact.isBlank() ? clip(last) : clip(fact)) + " —— 执行器全程只查注册表，不认识任何具体工具。";
        }
        if (last.contains("天气") || last.contains("weather")) {
            return Json.write(Map.of("tool", "get_weather", "arguments", Map.of("city", findCity(last))));
        }
        Matcher m = Pattern.compile("(\\d+\\s*[+\\-*/]\\s*\\d+)").matcher(last);
        if (m.find()) {
            return Json.write(Map.of("tool", "calculator", "arguments", Map.of("expression", m.group(1))));
        }
        if (last.contains("几点") || last.contains("时间")) {
            return Json.write(Map.of("tool", "get_time", "arguments", Map.of()));
        }
        return "这个问题不需要调用工具，直接回答即可 —— 工具清单由注册表动态生成，注册即生效、注销即消失。";
    }

    // ------------------------------------------------------------- step16 ReAct 运行时

    /** ReAct 协议；「死循环测试」会永远发起 echo 工具调用，触发 MAX_STEPS 保险丝。 */
    private String mockRuntime(String all, String last) {
        // echo 的观察结果继续发起 echo —— 模拟失控场景，直到保险丝起跳
        if (last.startsWith("Observation: echo:")) {
            return Json.write(Map.of(
                    "thought", "任务还没完成，继续调用 echo（模拟失控）",
                    "action", "echo",
                    "action_input", "tick"));
        }
        if (last.contains("死循环") || last.contains("循环测试")) {
            return Json.write(Map.of(
                    "thought", "这个任务永远做不完（模拟失控场景）",
                    "action", "echo",
                    "action_input", "tick"));
        }
        if (all.contains("Observation:")) {
            String obs = extractAfter(all, "Observation:");
            return Json.write(Map.of(
                    "thought", "已拿到观察结果，信息足够，结束本回合",
                    "final", "根据工具查询结果：" + clip(obs) + "（turn 以 TurnEndReason.Completed 收场）"));
        }
        Matcher m = Pattern.compile("(\\d+\\s*[+\\-*/]\\s*\\d+)").matcher(last);
        if (m.find()) {
            return Json.write(Map.of(
                    "thought", "有算式，调用计算器，不能心算",
                    "action", "calculator",
                    "action_input", m.group(1)));
        }
        if (last.contains("天气") || last.contains("weather")) {
            return Json.write(Map.of(
                    "thought", "需要实时数据，先调天气工具",
                    "action", "get_weather",
                    "action_input", findCity(last)));
        }
        return Json.write(Map.of(
                "thought", "普通对话无需工具，直接作答",
                "final", "（Mock 模型）本轮 " + countHistory(all) + " 条历史的上下文已按预算裁剪后发给模型。多聊几轮，可在轨迹里看到「上下文裁剪」事件。"));
    }

    // ------------------------------------------------------------- step17 人工介入

    /** 缺关键信息时发起 ask_user_question；「人工答复：」回填后带着完整信息完成任务。 */
    private String mockAsk(String all, String last) {
        if (all.contains("人工答复：")) {
            String ans = extractAfter(all, "人工答复：").lines().findFirst().orElse("上海");
            String from = ans.contains("上海") ? "上海" : ans.contains("杭州") ? "杭州" : ans.contains("广州") ? "广州" : clip(ans);
            return Json.write(Map.of(
                    "final", "已为你预订：" + from + " → 北京，明日 08:30 起飞，经济舱 ¥1,280（模拟出票）。"
                            + "刚才缺出发地时我没有瞎猜，而是问了人 —— 这就是 ask_user_question 的人机协同闭环。"));
        }
        if (last.contains("机票") || last.contains("订") || last.contains("出行")) {
            return Json.write(Map.of(
                    "ask", "预订机票需要确认出发地 —— 请问从哪个城市出发？（可选：北京 / 上海 / 杭州 / 广州）"));
        }
        return Json.write(Map.of(
                "final", "（Mock 模型）信息完整的问题我直接回答。想看人工介入：发「帮我订一张去北京的机票」，我会停下来向你确认出发地。"));
    }

    // ------------------------------------------------------------- step18 审批门禁

    private String mockApproval(String last) {
        if (last.contains("执行结果") || last.contains("审批结果")) {
            return Json.write(Map.of(
                    "final", "任务完成：" + clip(last) + " —— 注意轨迹里的权限矩阵：安全工具自动放行，"
                            + "高危工具必须过审批门禁，这是生产级智能体与玩具的分水岭。"));
        }
        if (last.contains("邮件") || last.contains("发送") || last.contains("发一封") || last.contains("发一封")) {
            return Json.write(Map.of(
                    "tool", "send_email",
                    "arguments", "to=team@xiaofuge.cn, content=本周周报"));
        }
        if (last.contains("删除") || last.contains("清理")) {
            return Json.write(Map.of(
                    "tool", "delete_file",
                    "arguments", "path=workspace/tmp/old.log"));
        }
        if (last.contains("天气")) {
            return Json.write(Map.of("tool", "get_weather", "arguments", "city=" + findCity(last)));
        }
        return Json.write(Map.of(
                "final", "（Mock 模型）想看审批门禁：发「给团队发一封周报邮件」（高危，会请求审批），"
                        + "回复「批准 / 拒绝 / 批准并记住」观察三种走向。"));
    }

    // ------------------------------------------------------------- step19 沙箱

    private String mockSandbox(String last) {
        if (last.contains("执行结果") || last.contains("已拦截") || last.contains("沙箱内")) {
            return Json.write(Map.of("final", clip(last)));
        }
        return Json.write(Map.of(
                "final", "（Mock 模型）沙箱采用纵深防御：策略 → 黑名单 → 边界 → 规范化四层拦截。"
                        + "发「执行：ls sandbox」看放行，或「执行：rm -rf /」看第 2 层拦截。"));
    }

    // ------------------------------------------------------------- step20 事件溯源

    private String mockEvent(String all, String last) {
        Matcher m = Pattern.compile("我叫([\\u4e00-\\u9fa5A-Za-z0-9]{1,10})").matcher(all);
        String remembered = null;
        while (m.find()) {
            String n = m.group(1);
            if (n.startsWith("什么") || n.startsWith("名字") || n.startsWith("谁")) continue;
            remembered = n;
        }
        if (last.contains("我叫")) {
            return Json.write(Map.of(
                    "final", "你好，" + remembered + "！这次对话连同你的名字已作为事件写入 JSONL 日志 ——"
                            + "我的记忆不是内存变量，而是从日志投影出来的视图，服务重启也不丢。"));
        }
        if (last.contains("叫什么") || last.contains("记得")) {
            return remembered != null
                    ? Json.write(Map.of("final", "你叫「" + remembered + "」。这条记忆是我刚才从事件日志回放重建的 —— 状态即日志。"))
                    : Json.write(Map.of("final", "日志里还没有你的名字，先说「我叫XX」吧。"));
        }
        return Json.write(Map.of(
                "final", "（Mock 模型）本轮会话由事件日志回放重建。多聊几轮再看 events/step20-events.jsonl，"
                        + "每一条对话都是不可变事件；「重置」删掉日志，记忆归零。"));
    }

    // ------------------------------------------------------------- step21 插件机制

    /** 插件机制剧本：安装后正常决策调用插件工具；未安装/已卸载时由注册表未命中路径兜底。 */
    private String mockPlugin(String all, String last) {
        if (last.contains("执行结果")) {
            String fact = extractAfter(last, "执行结果");
            return "根据插件工具返回的结果：" + (fact.isBlank() ? clip(last) : clip(fact))
                    + " —— 这个工具不在宿主代码里，而是插件经独立 ClassLoader 装进注册表的。";
        }
        if (last.contains("掷") || last.contains("硬币") || last.contains("coin")) {
            return Json.write(Map.of("tool", "coin_flip", "arguments", Map.of()));
        }
        if (last.contains("运行") || last.contains("多久") || last.contains("uptime")) {
            return Json.write(Map.of("tool", "uptime", "arguments", Map.of()));
        }
        return "（Mock 模型）先「安装插件」，工具才会出现在我的清单里；「卸载插件」后我再也看不见它们 —— 能力即插即拔。";
    }

    // ------------------------------------------------------------- step22 CLI 智能体

    /** CLI 剧本：命令类输入返回 run_command 决策；其余兜底引导。 */
    private String mockCli(String all, String last, String system) {
        if (last.contains("执行结果")) {
            String fact = extractAfter(last, "执行结果");
            return "命令结果：" + (fact.isBlank() ? clip(last) : clip(fact))
                    + " —— run_command 在受限工作区执行，Step19 的四层防御全程护航。";
        }
        if (last.contains("列出") || last.contains("文件") || last.contains("目录") || last.contains("ls")) {
            return Json.write(Map.of("tool", "run_command", "arguments", Map.of("command", "ls")));
        }
        if (last.contains("几点") || last.contains("时间") || last.contains("日期")) {
            return Json.write(Map.of("tool", "run_command", "arguments", Map.of("command", "date")));
        }
        if (last.contains("hello")) {
            return Json.write(Map.of("tool", "run_command", "arguments", Map.of("command", "cat hello.txt")));
        }
        return "我是终端里的 CLI 智能体。试试「运行：ls」，或让我「列出工作区文件」—— 命令都在 sandbox/step22 里执行，危险命令会被四层防御拦截。";
    }

    // ------------------------------------------------------------- step23 子代理

    /** 子代理剧本：system 带【子代理身份】为子代理回合（fork 能看到父会话里的紫色）；否则为主代理回合。 */
    private String mockSubagent(String all, String last, String system) {
        if (system.contains("子代理身份")) {
            boolean forkKnows = system.contains("继承的父会话上下文") && system.contains("紫色");
            if (system.contains("写作子代理")) {
                return forkKnows
                        ? "愿你的每一天都被喜爱的事物环绕 —— 像那一抹恰到好处的紫色。（fork：我从父会话记得你最喜欢紫色）"
                        : "愿你所行皆坦途，所遇皆温暖，往后的每一天都值得期待。（spawn：我只知道任务本身）";
            }
            return "Agent 调研要点：1) 工具标准化（MCP）成为默认范式；2) 运行时基座（审批/沙箱/事件）走向生产化；3) 多代理协作从固定流水线走向动态派遣。";
        }
        if (last.contains("执行结果")) {
            String fact = extractAfter(last, "执行结果");
            return "子代理已完成任务，结果如下：\n" + (fact.isBlank() ? clip(last) : fact);
        }
        if (last.contains("fork") || last.contains("继承")) {
            return Json.write(Map.of("tool", "dispatch_subagent", "name", "writer", "mode", "fork", "task", "写一句贺词"));
        }
        if (last.contains("spawn") || last.contains("全新")) {
            return Json.write(Map.of("tool", "dispatch_subagent", "name", "writer", "mode", "spawn", "task", "写一句贺词"));
        }
        if (last.contains("调研") || last.contains("研究") || last.contains("趋势")) {
            return Json.write(Map.of("tool", "dispatch_subagent", "name", "researcher", "mode", "spawn", "task", "调研 Agent 趋势"));
        }
        if (last.contains("记住") || last.contains("最喜欢")) {
            return "好的，已记下。这条信息已进入父会话上下文 —— 之后 fork 出去的子代理都能看到它。";
        }
        if (last.contains("对比")) {
            return "两次贺词的差异就是 spawn 与 fork 的差异：fork 的子代理继承了「最喜欢紫色」，贺词带了紫色；spawn 的子代理是全新实例，只会写通用贺词。";
        }
        return "（Mock 模型）试试：「fork 一个子代理，让它写一句贺词」或「spawn 一个子代理写贺词」，体会两种派遣的上下文差异。";
    }

    // ------------------------------------------------------------- step24 钩子体系

    /** 钩子讲解员：工具调用走宿主直连路由（查询：/发送：/钩子：），模型只负责讲解引导。 */
    private String mockHooks(String all, String last, String system) {
        if (last.contains("执行结果")) {
            String fact = extractAfter(last, "执行结果");
            return "钩子管线说明：" + (fact.isBlank() ? clip(last) : clip(fact));
        }
        return Json.write(Map.of("final",
                "本场景的钩子挂在工具调用的 before / after 生命周期上。试试：\n"
                        + "·「查询：张三」—— after 脱敏钩子把手机号/邮箱打码\n"
                        + "·「发送：给 13812345678 发 密码是123456」—— before 风控钩子直接拦截\n"
                        + "·「钩子：关」再「查询：张三」—— 摘掉钩子看明文，对比出钩子的价值\n"
                        + "·「审计日志」—— before 审计钩子记下的每一次调用"));
    }

    // ------------------------------------------------------------- step25 A2A 协作

    /** A2A 剧本：先发现名片，再按任务类型路由到 translator / weather。 */
    private String mockA2a(String all, String last, String system) {
        if (last.contains("执行结果")) {
            String fact = extractAfter(last, "执行结果");
            return "外部代理已通过 A2A 回执：" + (fact.isBlank() ? clip(last) : fact)
                    + " —— 对方有自己的模型与工具，我们只认 task_id 与回执。";
        }
        if (last.contains("发现") || last.contains("名片") || last.contains("附近")) {
            return Json.write(Map.of("tool", "a2a_discover"));
        }
        if (last.contains("翻译")) {
            return Json.write(Map.of("tool", "a2a_send", "agent", "translator",
                    "task", "把「你好，智能体」翻译成英文"));
        }
        if (last.contains("天气") || last.contains("户外")) {
            return Json.write(Map.of("tool", "a2a_send", "agent", "weather",
                    "task", "明天适合户外运动吗？"));
        }
        return Json.write(Map.of("final",
                "我是主代理，可以经 A2A 协议与外部代理协作。试试：「发现一下附近的代理」，"
                        + "或直接「让翻译代理把……翻译成英文」「问问天气代理明天适合户外运动吗」。"));
    }

    // ------------------------------------------------------------- step26 定时工具

    /** 定时讲解员：调度走宿主直连路由（定时：/循环：/任务列表/取消：），模型只负责讲解引导。 */
    private String mockSchedule(String all, String last, String system) {
        if (last.contains("执行结果")) {
            String fact = extractAfter(last, "执行结果");
            return "定时任务说明：" + (fact.isBlank() ? clip(last) : clip(fact));
        }
        return Json.write(Map.of("final",
                "本场景给工具装上了时间维度。试试：\n"
                        + "·「定时：5秒 提醒我喝水」—— 一次性调度，拿 job_id\n"
                        + "·「任务列表」—— 看 pending / 已触发次数\n"
                        + "·「循环：每10秒 报一次时」—— 周期任务\n"
                        + "·「取消：job-1」「完成了什么」—— 管理与回看（触发结果落盘 events/step26-tasks.jsonl）"));
    }

    // ------------------------------------------------------------- 工具方法

    private static final Map<String, String> CITIES = Map.of(
            "北京", "晴，26℃", "上海", "多云，28℃", "广州", "雷阵雨，31℃",
            "深圳", "阵雨，30℃", "杭州", "晴，27℃", "成都", "阴，22℃");

    private String findCity(String text) {
        for (String city : CITIES.keySet()) {
            if (text.contains(city)) return city;
        }
        return "北京";
    }

    private String extractAfter(String text, String marker) {
        int idx = text.lastIndexOf(marker);
        if (idx < 0) return "";
        return text.substring(idx + marker.length()).replaceFirst("^[\\s:：]+", "").trim();
    }

    private String clip(String s) {
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() > 60 ? one.substring(0, 60) + "…" : one;
    }
}
