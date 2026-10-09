package cn.xiaofuge.ai.agent.step26;

import cn.xiaofuge.ai.agent.Agent;
import cn.xiaofuge.ai.llm.ChatModel;
import cn.xiaofuge.ai.llm.Json;
import cn.xiaofuge.ai.llm.Message;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step26 · 定时工具 —— 给智能体装上「时间维度」的行动能力
 * （对应 deepseek-harness-java 的 domain/tool/schedule 定时工具子域）。
 * <p>
 * 之前的工具都是<strong>即时</strong>的：调用 → 执行 → 返回。定时工具把「什么时候做」
 * 也变成模型的决策对象：
 * <ul>
 *   <li><b>一次性调度</b>：n 秒后执行（schedule + delay）；</li>
 *   <li><b>循环调度</b>：每 n 秒执行一次（scheduleAtFixedRate）；</li>
 *   <li><b>任务管理</b>：列出 / 取消，每个任务有 job_id。</li>
 * </ul>
 * 关键工程点：<b>HTTP 请求已经返回，任务却在未来执行</b> —— 所以触发结果不能塞进本次响应，
 * 要落盘（events/step26-tasks.jsonl，append-only 事件溯源，呼应 Step20）并在后续对话中汇报。
 * 这也是生产提醒/巡检类 Agent 的最小雏形。
 * <p>
 * 试试本场景的对话：
 * <pre>
 *   定时：5秒 提醒我喝水        —— 注册一次性任务，拿到 job_id
 *   任务列表                    —— 看到 pending；5 秒后再问就变 done
 *   循环：每10秒 报一次时        —— 周期任务，触发次数持续增长
 *   取消：job-2                 —— 摘掉循环任务
 *   完成了什么                  —— 汇报已触发任务（结果从事件日志读回）
 * </pre>
 */
public interface ScheduleAgent extends Agent {

    class Impl implements ScheduleAgent {

        private final ChatModel model;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        /** 调度池：守护线程 —— 宿主退出即消亡，教学足够（生产用持久化调度器/延时队列）。 */
        private final ScheduledExecutorService pool =
                Executors.newScheduledThreadPool(1, r -> {
                    Thread t = new Thread(r, "step26-scheduler");
                    t.setDaemon(true);
                    return t;
                });

        private record Job(String id, String type, String content, long period,
                           ScheduledFuture<?> future, AtomicInteger fired) {}
        private final Map<String, Job> jobs = new LinkedHashMap<>();
        /** 已触发记录（线程安全：调度线程与 HTTP 线程都会碰）。 */
        private final List<String> firedLog = new ArrayList<>();
        private final Path eventFile = Path.of("events", "step26-tasks.jsonl");
        private final AtomicInteger seq = new AtomicInteger();

        public Impl(ChatModel model) {
            this.model = model;
        }

        @Override
        public String name() {
            return "Step26 · 定时工具（一次性/循环调度 · 触发结果事件落盘）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();

            // —— 定时指令（宿主直连路由，不经过模型） ——
            Matcher once = Pattern.compile("定时：(\\d+)\\s*秒\\s*(.*)").matcher(input);
            if (once.matches()) {
                return scheduleOnce(Long.parseLong(once.group(1)), once.group(2).trim());
            }
            Matcher loop = Pattern.compile("循环：每(\\d+)\\s*秒\\s*(.*)").matcher(input);
            if (loop.matches()) {
                return scheduleRecurring(Long.parseLong(loop.group(1)), loop.group(2).trim());
            }
            if (input.contains("任务列表")) {
                return listJobs();
            }
            if (input.startsWith("取消：")) {
                return cancel(input.substring(3).trim());
            }
            if (input.contains("完成了什么") || input.contains("已触发")) {
                return reportFired();
            }

            // —— 普通对话 → 模型讲解 ——
            List<Message> req = new ArrayList<>();
            req.add(Message.system(SYSTEM_PROMPT));
            req.add(Message.user(input));
            String raw = model.chat(req).trim();
            Object parsed = tryParse(raw);
            String answer = parsed instanceof Map<?, ?> m && Json.str(m, "final") != null
                    ? Json.str(m, "final") : raw;
            lastTrace.add(Map.of("type", "final", "label", "讲解回答",
                    "detail", "试试「定时：5秒 提醒我喝水」「循环：每10秒 报一次时」「任务列表」「完成了什么」"));
            return answer;
        }

        /** 一次性调度：schedule + delay，到点执行 → 落盘事件。 */
        private String scheduleOnce(long delaySeconds, String content) {
            String id = "job-" + seq.incrementAndGet();
            Runnable fire = () -> fire(id, content);
            ScheduledFuture<?> f = pool.schedule(fire, delaySeconds, TimeUnit.SECONDS);
            jobs.put(id, new Job(id, "一次性", content, delaySeconds, f, new AtomicInteger()));
            lastTrace.add(Map.of("type", "action", "label", "schedule_task · 一次性注册",
                    "detail", id + "：" + delaySeconds + " 秒后执行「" + content + "」"));
            lastTrace.add(Map.of("type", "thought", "label", "异步语义",
                    "detail", "本次 HTTP 响应立即返回；任务在未来由调度线程执行，结果落盘 events/step26-tasks.jsonl"));
            return "⏰ 已注册一次性任务 " + id + "：" + delaySeconds + " 秒后「" + content + "」\n"
                    + "本次请求已返回，任务到点后在后台执行并记录 —— 稍后问「完成了什么」看结果。";
        }

        /** 循环调度：scheduleAtFixedRate（首延迟 = 周期），触发次数持续累计。 */
        private String scheduleRecurring(long intervalSeconds, String content) {
            String id = "job-" + seq.incrementAndGet();
            Runnable fire = () -> fire(id, content);
            ScheduledFuture<?> f = pool.scheduleAtFixedRate(fire, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
            jobs.put(id, new Job(id, "循环", content, intervalSeconds, f, new AtomicInteger()));
            lastTrace.add(Map.of("type", "action", "label", "schedule_recurring · 循环注册",
                    "detail", id + "：每 " + intervalSeconds + " 秒执行「" + content + "」（首次延迟 = 周期）"));
            return "🔁 已注册循环任务 " + id + "：每 " + intervalSeconds + " 秒「" + content + "」\n"
                    + "问「任务列表」看触发次数，问「完成了什么」看已触发内容，不需要时「取消：" + id + "」。";
        }

        /** 调度线程回调：记录 + 落盘（append-only JSONL，事件溯源）。 */
        private void fire(String id, String content) {
            String time = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
            synchronized (firedLog) {
                firedLog.add(time + "  [" + id + "] " + content);
            }
            try {
                Files.createDirectories(eventFile.getParent());
                Files.writeString(eventFile,
                        "{\"time\":\"" + time + "\",\"job\":\"" + id + "\",\"content\":\"" + content + "\"}\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
            }
        }

        private String listJobs() {
            StringBuilder sb = new StringBuilder("📋 定时任务：\n");
            for (Job j : jobs.values()) {
                String status = j.future().isDone() ? "done/取消"
                        : j.type().equals("循环") ? "循环中 · 已触发 " + j.fired().get() + " 次"
                        : "pending";
                sb.append("  ").append(j.id()).append(" · ").append(j.type())
                        .append(" · 每/").append(j.period()).append("秒 · 「").append(j.content())
                        .append("」 · ").append(status).append("\n");
            }
            lastTrace.add(Map.of("type", "observation", "label", "任务列表",
                    "detail", jobs.size() + " 个任务在册"));
            return sb.toString().stripTrailing();
        }

        private String cancel(String id) {
            Job j = jobs.get(id);
            if (j == null) return "没有任务 " + id + "。在册：" + jobs.keySet();
            j.future().cancel(false);
            lastTrace.add(Map.of("type", "guard", "label", "cancel_task · 取消",
                    "detail", id + " 已停止（在册保留，状态 done/取消）"));
            return "🚫 已取消 " + id + "（「" + j.content() + "」不再触发）。";
        }

        private String reportFired() {
            List<String> log;
            synchronized (firedLog) {
                log = List.copyOf(firedLog);
            }
            lastTrace.add(Map.of("type", "observation", "label", "已触发任务汇报",
                    "detail", log.isEmpty() ? "暂无触发记录" : log.size() + " 条，事件已 append 到 " + eventFile));
            return "✅ 已触发的任务（事件日志 " + eventFile + "，append-only）：\n"
                    + (log.isEmpty() ? "（暂无 —— 注册一个「定时：5秒 提醒我喝水」再来看看）"
                    : String.join("\n", log));
        }

        private static final String SYSTEM_PROMPT = """
                你是 ToyAgent 的定时工具讲解员。用户可能询问定时任务、调度、提醒、巡检等话题。
                只输出 JSON：{"final": "回答"}。请引导用户试用：
                「定时：5秒 提醒我喝水」「循环：每10秒 报一次时」「任务列表」「取消：job-1」「完成了什么」。""";

        @Override
        public void reset() {
            jobs.values().forEach(j -> j.future().cancel(false));
            jobs.clear();
            synchronized (firedLog) {
                firedLog.clear();
            }
        }

        private Object tryParse(String raw) {
            try {
                return Json.parse(raw.replaceFirst("^```\\w*\\s*", "").replaceFirst("```\\s*$", "").trim());
            } catch (Exception e) {
                return raw;
            }
        }

        @Override
        public List<Map<String, Object>> trace() {
            return List.copyOf(lastTrace);
        }
    }
}
