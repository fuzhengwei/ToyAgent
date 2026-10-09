/**
 * ToyAgent 前端控制器 v2
 * 左侧场景导航 · 中间对话测试（Markdown 渲染 + 执行轨迹时间线）
 * 右侧原理文章（可拖拽调宽） · 主题切换（localStorage 记忆） · 页面级模型配置（热切换）
 */
(function () {
  "use strict";

  // ============================================================ 主题（尽早应用避免闪屏）

  const THEMES = ["paper", "mist", "ink", "dusk", "snow", "latte", "sakura"];
  const THEME_NAMES = {
    paper: "纸面 · 赭石", mist: "青瓷 · 苔绿", ink: "玄墨 · 极简", dusk: "暮蓝 · 靛青",
    snow: "纯白 · 极简", latte: "咖啡 · 奶棕", sakura: "樱粉 · 柔和"
  };

  /** 主题切换后需要重绘的东西（如 mermaid 图），由后续模块注册 */
  let themeRerender = null;

  function applyTheme(t) {
    if (!THEMES.includes(t)) t = "paper";
    document.documentElement.dataset.theme = t;
    try { localStorage.setItem("toyagent-theme", t); } catch (e) { /* 隐私模式忽略 */ }
    document.querySelectorAll(".theme-dot").forEach((d) => {
      d.classList.toggle("active", d.dataset.theme === t);
      d.title = THEME_NAMES[d.dataset.theme] || d.dataset.theme;
    });
    if (themeRerender) requestAnimationFrame(() => themeRerender());
  }

  applyTheme((function () {
    try { return localStorage.getItem("toyagent-theme"); } catch (e) { return null; }
  })() || "paper");

  // ============================================================ 右侧面板拖拽调宽

  const ARTICLE_W_KEY = "toyagent-article-w";
  const ARTICLE_W_MIN = 320, ARTICLE_W_MAX = 760, ARTICLE_W_DEFAULT = 420;

  function setArticleW(w, save) {
    w = Math.max(ARTICLE_W_MIN, Math.min(ARTICLE_W_MAX, Math.round(w)));
    document.documentElement.style.setProperty("--article-w", w + "px");
    if (save) {
      try { localStorage.setItem(ARTICLE_W_KEY, String(w)); } catch (e) { /* 忽略 */ }
    }
    return w;
  }

  // 恢复上次的宽度
  (function () {
    let saved = 0;
    try { saved = parseInt(localStorage.getItem(ARTICLE_W_KEY), 10) || 0; } catch (e) { /* 忽略 */ }
    if (saved) setArticleW(saved, false);
  })();

  function initResizer() {
    const resizer = $("#panelResizer");
    const panel = $("#articlePanel");
    if (!resizer || !panel) return;

    let dragging = false, startX = 0, startW = 0;

    resizer.addEventListener("mousedown", (e) => {
      dragging = true;
      startX = e.clientX;
      startW = panel.getBoundingClientRect().width;
      resizer.classList.add("active");
      document.body.classList.add("resizing");
      e.preventDefault();
    });

    window.addEventListener("mousemove", (e) => {
      if (!dragging) return;
      setArticleW(startW + (startX - e.clientX), false);
    });

    window.addEventListener("mouseup", () => {
      if (!dragging) return;
      dragging = false;
      resizer.classList.remove("active");
      document.body.classList.remove("resizing");
      const w = parseInt(document.documentElement.style.getPropertyValue("--article-w"), 10) || ARTICLE_W_DEFAULT;
      setArticleW(w, true);
    });

    // 双击复位
    resizer.addEventListener("dblclick", () => {
      setArticleW(ARTICLE_W_DEFAULT, true);
      toast("文章面板宽度已复位", "ok");
    });
  }

  const $ = (sel) => document.querySelector(sel);
  const chatArea = $("#chatArea");
  const input = $("#input");
  const sendBtn = $("#sendBtn");
  const resetBtn = $("#resetBtn");
  const nav = $("#nav");
  const articleBody = $("#articleBody");
  const modelModal = $("#modelModal");

  let currentId = "home";

  // ============================================================ 代码高亮

  const JAVA_KEYWORDS = "abstract|class|interface|extends|implements|public|private|protected|static|final|void|return|new|this|super|import|package|for|while|if|else|switch|case|default|break|continue|try|catch|finally|throw|throws|yield|record|enum|null|true|false|var|int|long|double|boolean|String|List|Map";
  const BASH_KEYWORDS = "cd|cp|javac|java|find|export|echo|mvn|git";

  function escapeHtml(s) {
    return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }

  function highlight(code, lang) {
    let html = escapeHtml(code);
    const stash = [];
    const keep = (cls, m) => {
      stash.push('<span class="' + cls + '">' + m + "</span>");
      return "\x00" + (stash.length - 1) + "\x00";
    };
    html = html.replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*/g, (m) => keep("tok-com", m));
    html = html.replace(/"(?:[^"\\\n]|\\.)*"/g, (m) => keep("tok-str", m));
    if (lang === "java") {
      html = html.replace(/@\w+/g, (m) => keep("tok-ann", m));
      html = html.replace(new RegExp("\\b(" + JAVA_KEYWORDS + ")\\b", "g"), '<span class="tok-kw">$1</span>');
      // 数字着色：先消费完整的占位符（\x00n\x00）原样放行，避免破坏还原机制
      html = html.replace(/(\x00\d+\x00)|\b(\d+(?:\.\d+)?[fLdD]?)\b/g, (m, ph, num) =>
        ph ? ph : '<span class="tok-num">' + num + "</span>");
    } else if (lang === "bash") {
      html = html.replace(new RegExp("(^|\\s)(" + BASH_KEYWORDS + ")\\b", "g"), '$1<span class="tok-kw">$2</span>');
    }
    html = html.replace(/\x00(\d+)\x00/g, (_, i) => stash[+i]);
    return html;
  }

  function renderCodeBlocks(root) {
    root.querySelectorAll("pre > code").forEach((code) => {
      const lang = code.dataset.lang || "text";
      const pre = code.parentElement;
      if (pre.querySelector(".copy-btn")) return;
      const raw = code.textContent;
      code.innerHTML = highlight(raw, lang);
      const btn = document.createElement("button");
      btn.className = "copy-btn";
      btn.textContent = "复制";
      btn.addEventListener("click", () => {
        navigator.clipboard.writeText(raw).then(() => {
          btn.textContent = "已复制 ✓";
          setTimeout(() => (btn.textContent = "复制"), 1500);
        });
      });
      pre.appendChild(btn);
    });
  }

  // ============================================================ 轻量 Markdown 渲染

  function inlineMd(s) {
    return s
      .replace(/`([^`]+)`/g, "<code>$1</code>")
      .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
      .replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g, "$1<em>$2</em>")
      .replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
  }

  /** 把模型输出的 Markdown 转为安全的 HTML（先整体转义，再受控还原） */
  function mdToHtml(src) {
    const lines = escapeHtml(src).split("\n");
    const out = [];
    let inCode = false, codeBuf = [], codeLang = "text";
    let listType = null, listBuf = [];

    const flushList = () => {
      if (listBuf.length) {
        out.push("<" + listType + ">" + listBuf.map((li) => "<li>" + inlineMd(li) + "</li>").join("") + "</" + listType + ">");
        listBuf = [];
        listType = null;
      }
    };

    for (const raw of lines) {
      const line = raw;
      const fence = line.match(/^\s*```(\w*)/);
      if (fence) {
        flushList();
        if (inCode) {
          out.push('<pre><code data-lang="' + codeLang + '">' + codeBuf.join("\n") + "</code></pre>");
          inCode = false;
          codeBuf = [];
        } else {
          inCode = true;
          codeLang = fence[1] || "text";
        }
        continue;
      }
      if (inCode) {
        codeBuf.push(line);
        continue;
      }

      const heading = line.match(/^(#{1,4})\s+(.*)/);
      const ul = line.match(/^\s*[-*•]\s+(.*)/);
      const ol = line.match(/^\s*\d+[.、]\s+(.*)/);
      const quote = line.match(/^>\s?(.*)/);

      if (heading) {
        flushList();
        const level = Math.min(heading[1].length + 1, 5);
        out.push("<h" + level + ">" + inlineMd(heading[2]) + "</h" + level + ">");
      } else if (ul) {
        if (listType !== "ul") flushList();
        listType = "ul";
        listBuf.push(ul[1]);
      } else if (ol) {
        if (listType !== "ol") flushList();
        listType = "ol";
        listBuf.push(ol[1]);
      } else if (quote) {
        flushList();
        out.push("<blockquote>" + inlineMd(quote[1]) + "</blockquote>");
      } else if (/^\s*(---|\*\*\*)\s*$/.test(line)) {
        flushList();
        out.push("<hr>");
      } else if (line.trim() === "") {
        flushList();
      } else {
        flushList();
        out.push("<p>" + inlineMd(line) + "</p>");
      }
    }
    if (inCode && codeBuf.length) {
      out.push('<pre><code data-lang="' + codeLang + '">' + codeBuf.join("\n") + "</code></pre>");
    }
    flushList();
    return out.join("");
  }

  // ============================================================ Toast

  let toastTimer = null;
  function toast(msg, type) {
    const el = $("#toast");
    el.textContent = msg;
    el.className = "toast " + (type || "");
    el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => (el.hidden = true), 2600);
  }

  // ============================================================ 左侧导航

  function renderNav() {
    const groups = {};
    SCENARIOS.forEach((s) => {
      (groups[s.group] = groups[s.group] || []).push(s);
    });
    nav.innerHTML = "";
    Object.entries(groups).forEach(([group, items]) => {
      const g = document.createElement("div");
      g.className = "nav-group";
      g.textContent = group;
      nav.appendChild(g);
      items.forEach((s) => {
        const a = document.createElement("a");
        a.className = "nav-item" + (s.id === currentId ? " active" : "");
        a.dataset.id = s.id;
        a.innerHTML =
          '<span class="nav-num">' + s.num + "</span>" +
          '<span class="nav-name">' + s.title + "</span>";
        a.addEventListener("click", () => {
          location.hash = s.id;
          $("#sidebar").classList.remove("open");
        });
        nav.appendChild(a);
      });
    });
  }

  // ============================================================ 场景切换

  function renderScenario(id) {
    currentId = id;
    const meta = SCENARIOS.find((s) => s.id === id) || SCENARIOS[0];
    const article = ARTICLES[id] || ARTICLES.home;

    document.querySelectorAll(".nav-item").forEach((el) => {
      el.classList.toggle("active", el.dataset.id === id);
    });

    $("#stepChip").textContent = meta.num;
    $("#stepTitle").textContent = meta.title;
    $("#stepSubtitle").textContent = meta.subtitle;
    document.title = meta.title + " · ToyAgent";

    // 本场景源码直达链接（GitHub 仓库对应 step 包）
    const REPO = "https://github.com/fuzhengwei/ToyAgent";
    const srcLink = $("#srcLink");
    if (id === "home") {
      srcLink.href = REPO;
      srcLink.innerHTML = '<span class="src-emoji">🐙</span> GitHub 仓库';
    } else {
      srcLink.href = REPO + "/tree/main/src/main/java/cn/xiaofuge/ai/agent/" + id;
      srcLink.innerHTML = '<span class="src-emoji">🐙</span> 源码 ' + meta.num;
    }

    // 示例问题
    const samples = $("#samples");
    samples.innerHTML = "";
    meta.samples.forEach((q) => {
      const chip = document.createElement("span");
      chip.className = "sample-chip";
      chip.textContent = q;
      chip.addEventListener("click", () => {
        input.value = q;
        send();
      });
      samples.appendChild(chip);
    });

    resetBtn.style.display = id === "home" ? "none" : "";
    input.placeholder = id === "home"
      ? "在左侧选择一个场景后即可对话"
      : "输入消息，Enter 发送，Shift+Enter 换行";

    // 右侧文章
    const chips = article.chapters.length
      ? '<div class="chapters">' +
        article.chapters.map((c) => '<span class="chip">📗 ' + c + "</span>").join("") +
        "</div>"
      : "";
    articleBody.innerHTML =
      "<h1>" + article.title + "</h1>" +
      '<p class="lede">' + article.lede + "</p>" +
      chips + article.html;
    renderCodeBlocks(articleBody);
    articleBody.scrollTop = 0;

    renderChatHome();
  }

  // ============================================================ 中间内容渲染

  function renderChatHome() {
    chatArea.innerHTML = "";
    if (currentId === "home") {
      renderOverview();
    } else {
      const div = document.createElement("div");
      div.className = "welcome";
      div.innerHTML =
        '<div class="big-emoji">💬</div>' +
        "<h3>" + $("#stepTitle").textContent + "</h3>" +
        "<p>点击上方示例问题，或在下方输入消息，观察每次回答附带的执行轨迹时间线。</p>";
      chatArea.appendChild(div);
    }
  }

  /** 总览：课程地图 + 场景卡片网格 + 进阶实战 + 作者信息 */
  function renderOverview() {
    const wrap = document.createElement("div");
    wrap.className = "overview";

    const cards = SCENARIOS.filter((s) => s.id !== "home").map((s) => {
      const art = ARTICLES[s.id];
      return (
        '<div class="card" data-id="' + s.id + '">' +
        '<div class="card-top"><span class="card-num">' + s.num + "</span><h4>" + s.title + "</h4></div>" +
        "<p>" + s.subtitle + "</p>" +
        '<div class="card-chapters">' +
        (art ? art.chapters.map((c) => '<span class="chip">' + c + "</span>").join("") : "") +
        "</div></div>"
      );
    }).join("");

    // 进阶实战：与 ai-agent-guide 官网「实战项目」板块保持一致，全部可点击跳转
    const GUIDE_SITE = "https://ai-agent-guide.xiaofuge.cn";
    const LEARN_ROUTE = [
      { icon: "🎓", name: "AI Agent 应用开发工程师学习计划", desc: "系统化学习路线，从基础到实战的完整成长路径", href: "https://bugstack.cn/md/zsxq/material/student-learn-ai.html", tag: "学习路线" }
    ];
    const GUIDE_PROJECTS = [
      { icon: "🛡️", name: "WaLiAPI - AI LLM LocalGateway 本地网关系统", desc: "渠道分发、日志留存、日志审计、知识库", href: "https://bugstack.cn/md/project/waliapi/waliapi.html" },
      { icon: "🖥️", name: "WaLiSSH - AI Shell 智能终端", desc: "AI 驱动的 Shell 智能终端，对话式操作云服务器", href: "https://bugstack.cn/md/project/walissh/walissh.html" },
      { icon: "🤖", name: "WaLiCode - AI Coding 辅助编码", desc: "AI 辅助编程助手，智能代码生成与审查", href: "https://bugstack.cn/md/project/walicode/walicode.html" },
      { icon: "🌐", name: "AI MCP Gateway 网关服务系统", desc: "MCP 协议网关，统一管理 AI 工具调用", href: "https://bugstack.cn/md/project/ai-mcp-gateway/ai-mcp-gateway.html" },
      { icon: "🧩", name: "AI Agent 脚手架 + 场景应用", desc: "Spring AI + LangChain4j + Google ADK，智能体架构方案", href: "https://bugstack.cn/md/project/ai-agent-scaffold/ai-agent-scaffold.html" },
      { icon: "🔗", name: "AI Agent 拖拉拽 + 动态配置", desc: "RAG、MCP、Prompt 动态编排与配置", href: "https://bugstack.cn/md/project/ai-knowledge/ai-knowledge.html" },
      { icon: "🔍", name: "OpenAI 代码自动评审组件", desc: "AI 驱动的代码评审，自动发现代码问题", href: "https://bugstack.cn/md/zsxq/project/openai-code-review.html" },
      { icon: "🔑", name: "OpenAI 大模型微服务应用体系构建", desc: "API-SDK、鉴权、公众号、微信支付", href: "https://bugstack.cn/md/zsxq/project/chatbot-api.html" },
      { icon: "💬", name: "AI 智能问答助手", desc: "小型项目，对接知识星球", href: "https://bugstack.cn/md/zsxq/project/chatbot-api.html" },
      { icon: "⚙️", name: "DSH Java · 智能体运行时基座", desc: "deepseek-harness-java：Java Agent 运行时与插件体系（新项目）", href: "https://dsh-java.xiaofuge.cn/", isNew: true }
    ];
    const NEW_PARADIGM = [
      { icon: "🚀", name: "AI 新范式（0编码）", desc: "Vibe Coding 方式开发 + 运维（部署、压测、调优）", href: "https://bugstack.cn/md/project/ai-new-paradigm/ai-new-paradigm.html" }
    ];
    const linkCard = (p) =>
      '<div class="card adv guide" data-href="' + p.href + '">' +
      '<div class="card-top"><span class="card-num">' + (p.icon || "📗") + "</span><h4>" + p.name +
      (p.isNew ? ' <span class="new-badge">NEW</span>' : "") + "</h4></div>" +
      "<p>" + p.desc + "</p>" +
      '<div class="card-chapters"><span class="chip mono-chip">↗ ' + p.href.replace(/^https?:\/\//, "").replace(/\/$/, "").split("/")[0] + "</span></div>" +
      "</div>";
    const learnCards = LEARN_ROUTE.map(linkCard).join("");

    // ===== Toy 桌面 3D 地图：7 分区 + 26 块积木 =====
    const DESK_ZONES = [
      { g: "基础内核", x: 20, y: 20, c: "#9a4a1e" },
      { g: "记忆与知识", x: 340, y: 20, c: "#2f6b66" },
      { g: "工具的进化", x: 660, y: 20, c: "#8a6116" },
      { g: "协作与派遣", x: 20, y: 172, c: "#3e5c76" },
      { g: "运行时与工程化", x: 270, y: 172, c: "#3d6b4f" },
      { g: "人机协同与安全", x: 610, y: 172, c: "#a03028" },
      { g: "扩展与形态", x: 270, y: 324, c: "#6d4a6e" }
    ];
    const deskZones = DESK_ZONES.map((z, zi) => {
      const items = SCENARIOS.filter((s) => s.group === z.g);
      const w = 68 * items.length + 16;
      const blocks = items.map((s, i) => (
        '<div class="cube" data-id="' + s.id + '" style="left:' + (11 + i * 68) + 'px;top:40px;--h:' + (40 + (i % 3) * 9) + 'px;animation-delay:' + ((zi * 4 + i) * 22) + 'ms">' +
        '<div class="core">' +
        '<div class="face f-top"></div>' +
        '<div class="face f-front">' + s.num + "</div>" +
        '<div class="face f-side"></div>' +
        "</div>" +
        '<div class="tip"><b>' + s.num + " · " + s.title + "</b><span>" + s.subtitle + "</span></div>" +
        "</div>"
      )).join("");
      return (
        '<div class="zone" style="--zc:' + z.c + ";left:" + z.x + "px;top:" + z.y + "px;width:" + w + 'px">' +
        '<div class="z-sign">' + z.g + "</div>" +
        blocks +
        "</div>"
      );
    }).join("");
    const deskHtml =
      '<div class="map-toolbar">' +
      '<button class="map-btn active" data-view="desk">🧊 3D 桌面</button>' +
      '<button class="map-btn" data-view="list">📄 列表</button>' +
      '<span class="map-hint">🖱 拖拽旋转桌面 · 点击积木进入场景 · 悬停看简介 · 桌两端的大王可以悬停问话</span>' +
      "</div>" +
      '<div class="desk-map"><div class="desk-scene"><div class="desk">' +
      deskZones +
      '<div class="start-pin" data-id="step01">▶ 从 01 开始</div>' +
      '<div class="decor d1">✏️</div>' +
      '<div class="decor d2">🧸</div>' +
      // 桌两端对坐的两位大王（致敬《打，打个大西瓜》）
      '<div class="king k-west" style="left:-58px;top:140px">' +
      '<div class="k-fig"><div class="k-shadow"></div><div class="k-chair"></div><div class="k-robe"></div><div class="k-head"></div><div class="k-crown"></div><div class="k-weapon"></div></div>' +
      '<div class="k-say">朕的 <b>26 块积木</b>，一块都不能少！</div>' +
      "</div>" +
      '<div class="king k-east" style="left:910px;top:140px">' +
      '<div class="k-fig"><div class="k-shadow"></div><div class="k-chair"></div><div class="k-robe"></div><div class="k-head"></div><div class="k-crown"></div><div class="k-weapon"></div></div>' +
      '<div class="k-say">朕的 <b>Agent.chat()</b>，跑起来给朕看！</div>' +
      "</div>" +
      "</div></div></div>";
    const projCards = GUIDE_PROJECTS.map(linkCard).join("");
    const paradigmCards = NEW_PARADIGM.map(linkCard).join("");

    wrap.innerHTML =
      '<div class="hero">' +
      '<img class="logo-big" src="logo.png" alt="ToyAgent Logo">' +
      "<h3>用 26 个接口类讲清楚智能体</h3>" +
      '<div class="slogan">玩，玩个 Toy Agent。<small>—— 致敬动画《打，打个大西瓜》：他们打个大西瓜，我们玩个 Toy Agent</small></div>' +
      "<p>剥掉所有概念外衣，智能体的灵魂只有一个方法。26 个场景按七条主题弧线递进：第 1-4 个基础内核（对话 / 提示词 / ReAct / 工具），第 5-8 个记忆与知识（记忆 / 路由 / RAG / LLM-Wiki），第 9-11 个工具的进化（MCP / Skills / 注册表），第 12-14 个协作与派遣（流水线 / 子代理 / A2A），第 15-18 个运行时与工程化（Loop / 状态机 / ReAct 运行时 / 全流程综合），第 19-22 个人机协同与安全（提问 / 审批 / 沙箱 / 事件溯源），第 23-26 个扩展与形态（插件 / 钩子 / CLI / 定时）。</p>" +
      '<div class="soul">Agent.chat(String input) —— 输入一句话，输出一句话</div>' +
      "</div>" +
      '<div class="stats">' +
      "<div class=\"stat\"><b>26</b><span>渐进场景</span></div>" +
      "<div class=\"stat\"><b>1</b><span>核心方法</span></div>" +
      "<div class=\"stat\"><b>0</b><span>第三方依赖</span></div>" +
      "<div class=\"stat\"><b>26</b><span>覆盖教程章节</span></div>" +
      "</div>" +
      '<div class="sec-title">🗺 Toy 桌面 · 场景学习地图</div>' +
      deskHtml +
      '<div class="list-view" style="display:none"><div class="card-grid">' + cards + "</div></div>" +
      '<div class="sec-title">🚀 学完之后 · 进阶实战</div>' +
      '<p class="adv-lede">ToyAgent 刻意保持极简，帮你建立概念骨架。往下走有两条路：跟着「AI Agent 应用开发工程师学习计划」系统进阶，或进入星球完整工程，让能力在真实项目里长出来。</p>' +
      '<div class="adv-sub">🎓 学习路线 · 系统化成长路径</div>' +
      '<div class="card-grid">' + learnCards + "</div>" +
      '<div class="adv-sub">🏗️ 实战项目 · 码农会锁 AI 系列</div>' +
      '<div class="card-grid">' + projCards + "</div>" +
      '<div class="adv-sub">🚀 AI 新范式</div>' +
      '<div class="card-grid">' + paradigmCards + "</div>" +
      '<div class="hint-bar">点击卡片进入对应场景 · 左下角 <span class="kbd">⚙</span> 可配置你自己的模型 · 回答附带实时执行轨迹</div>' +
      '<div class="author-card">' +
      '<div class="author-line"><b>👨‍💻 关于作者</b></div>' +
      '<p><b>小傅哥</b>（fuzhengwei）· 《AI Agent 通识教程》主编，社群星球「码农会锁」主理人，已沉淀 20+ 实战项目。26 章配套教程与更多实战项目：</p>' +
      '<p class="author-links">' +
      '<a href="https://ai-agent-guide.xiaofuge.cn" target="_blank" rel="noopener">📗 在线阅读 ai-agent-guide.xiaofuge.cn</a>' +
      '<a href="https://github.com/fuzhengwei" target="_blank" rel="noopener">🐙 GitHub @fuzhengwei</a>' +
      '<a href="https://bugstack.cn" target="_blank" rel="noopener">🐛 博客 bugstack.cn</a>' +
      "</p>" +
      '<p class="author-license">ToyAgent 为教程配套练习项目 · <a href="https://github.com/fuzhengwei/ToyAgent" target="_blank" rel="noopener">GitHub 仓库 ↗</a></p>' +
      "</div>" +
      '<div class="beian-bar">' +
      '<a href="https://github.com/fuzhengwei/ai-agent-guide" target="_blank" rel="noopener">配套教程 ai-agent-guide</a>' +
      "<span>·</span>" +
      '<a href="http://beian.miit.gov.cn" target="_blank" rel="noopener">津ICP备2025037015号-1</a>' +
      "<span>·</span>" +
      '<a href="http://www.beian.gov.cn/portal/registerSystemInfo?recordcode=11010102000001" target="_blank" rel="noopener">公安备案 京公网安备11010102000001号</a>' +
      "<span>·</span>" +
      "<span>MIT 协议 © 2023-2026 小傅哥，All rights reserved.</span>" +
      "</div>";

    chatArea.appendChild(wrap);
    wrap.querySelectorAll(".card:not(.adv)").forEach((card) => {
      card.addEventListener("click", () => (location.hash = card.dataset.id));
    });
    // 教程实战篇章卡片 → 新标签打开在线教程
    wrap.querySelectorAll(".card.guide").forEach((card) => {
      card.addEventListener("click", () => window.open(card.dataset.href, "_blank", "noopener"));
    });

    // ===== Toy 桌面交互：视图切换 / 拖拽旋转 / 积木点击 / 自适应缩放 =====
    const scene = wrap.querySelector(".desk-scene");
    const deskMap = wrap.querySelector(".desk-map");
    const listView = wrap.querySelector(".list-view");
    const view = { rz: -34, rx: 52 };
    let drag = null;
    let dragMoved = false;

    const fitDesk = () => {
      if (deskMap.style.display === "none") return;
      const s = Math.min(1, (deskMap.clientWidth - 6) / 1010);
      scene.style.setProperty("--s", s.toFixed(3));
    };
    fitDesk();
    window.addEventListener("resize", fitDesk);
    // 拖拽右侧文章面板改变宽度时，桌面地图实时重新缩放
    if (window.ResizeObserver) new ResizeObserver(fitDesk).observe(deskMap);

    wrap.querySelectorAll(".map-btn").forEach((b) => {
      b.addEventListener("click", () => {
        wrap.querySelectorAll(".map-btn").forEach((x) => x.classList.toggle("active", x === b));
        const isDesk = b.dataset.view === "desk";
        deskMap.style.display = isDesk ? "" : "none";
        listView.style.display = isDesk ? "none" : "";
        if (isDesk) fitDesk();
      });
    });

    scene.addEventListener("pointerdown", (e) => {
      if (e.button !== 0) return;
      drag = { x: e.clientX, y: e.clientY, rz: view.rz, rx: view.rx, moved: false, pid: e.pointerId };
    });
    scene.addEventListener("pointermove", (e) => {
      if (!drag) return;
      const dx = e.clientX - drag.x;
      const dy = e.clientY - drag.y;
      if (!drag.moved && Math.abs(dx) + Math.abs(dy) > 4) {
        drag.moved = true;
        dragMoved = true;
        scene.classList.add("dragging");
        try { scene.setPointerCapture(drag.pid); } catch (err) { /* ignore */ }
      }
      if (!drag.moved) return;
      view.rz = Math.max(-62, Math.min(-8, drag.rz + dx * 0.25));
      view.rx = Math.max(30, Math.min(60, drag.rx - dy * 0.2));
      scene.style.setProperty("--rz", view.rz + "deg");
      scene.style.setProperty("--rx", view.rx + "deg");
    });
    const endDrag = () => {
      if (!drag) return;
      drag = null;
      scene.classList.remove("dragging");
      setTimeout(() => { dragMoved = false; }, 0);
    };
    scene.addEventListener("pointerup", endDrag);
    scene.addEventListener("pointercancel", endDrag);

    wrap.querySelectorAll(".cube, .start-pin").forEach((el) => {
      el.addEventListener("click", () => {
        if (dragMoved) return;
        location.hash = el.dataset.id;
      });
    });
  }

  // ============================================================ Mermaid 执行流程图

  let mermaidLib = null;
  let mermaidPromise = null;
  const activeDiagrams = [];

  /** 清洗文本，保证能安全放进 mermaid 节点/消息 */
  function mq(s, n) {
    s = String(s || "").replace(/[<>"'`{}|;\\[\]]/g, " ").replace(/\s+/g, " ").trim();
    if (n && s.length > n) s = s.slice(0, n) + "…";
    return s;
  }

  /** 按需加载 mermaid（CDN，双源兜底） */
  function ensureMermaid() {
    if (mermaidLib) return Promise.resolve(mermaidLib);
    if (!mermaidPromise) {
      const urls = [
        "https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs",
        "https://unpkg.com/mermaid@11/dist/mermaid.esm.min.mjs"
      ];
      mermaidPromise = (async () => {
        let lastErr;
        for (const u of urls) {
          try { return (await import(u)).default; } catch (e) { lastErr = e; }
        }
        mermaidPromise = null;
        throw lastErr || new Error("mermaid load failed");
      })().then((lib) => {
        mermaidLib = lib;
        mermaidConfigure();
        return lib;
      });
    }
    return mermaidPromise;
  }

  /** 用当前主题变量初始化 mermaid（切主题时会再次调用） */
  function mermaidConfigure() {
    if (!mermaidLib) return;
    const css = getComputedStyle(document.documentElement);
    const v = (name, def) => (css.getPropertyValue(name) || def).trim();
    mermaidLib.initialize({
      startOnLoad: false,
      theme: "base",
      securityLevel: "loose",
      fontFamily: '-apple-system, "PingFang SC", sans-serif',
      themeVariables: {
        background: v("--panel", "#fff"),
        primaryColor: v("--accent-soft", "#f6ede3"),
        primaryBorderColor: v("--accent", "#9a4a1e"),
        primaryTextColor: v("--ink-2", "#3d372f"),
        secondaryColor: "#f1ecf8",
        tertiaryColor: "#e6f1ee",
        lineColor: v("--muted", "#837a6d"),
        textColor: v("--ink-2", "#3d372f"),
        actorBkg: v("--accent-soft", "#f6ede3"),
        actorBorder: v("--accent", "#9a4a1e"),
        actorTextColor: v("--ink-2", "#3d372f"),
        noteBkgColor: "#f7f0de",
        noteBorderColor: "#8a6116",
        noteTextColor: "#5c420f"
      }
    });
  }

  // mermaid 节点按 trace 类型着色（柔和、适配所有浅色主题）
  const MQ_TYPE_CLASS = {
    thought: "mqThought", prompt: "mqThought", memory: "mqThought",
    action: "mqAction", tool: "mqTool", compress: "mqTool",
    observation: "mqObs", mcp: "mqObs", retrieve: "mqObs",
    intent: "mqIntent", skill: "mqIntent",
    agent: "mqAgent", guard: "mqGuard", warn: "mqGuard",
    node: "mqNode", edge: "mqEdge", final: "mqFinal"
  };
  const MQ_CLASSDEFS =
    "classDef mqThought fill:#f1ecf8,stroke:#7c5cb0,color:#3d2f5e\n" +
    "classDef mqAction fill:#e8eef6,stroke:#4a6b8a,color:#2a3c50\n" +
    "classDef mqObs fill:#e6f1ee,stroke:#2f6b66,color:#1f4440\n" +
    "classDef mqTool fill:#f7f0de,stroke:#8a6116,color:#5c420f\n" +
    "classDef mqIntent fill:#f8e9ef,stroke:#a04f6e,color:#5e2a3e\n" +
    "classDef mqAgent fill:#f2e8e2,stroke:#9a4a1e,color:#5e2a10\n" +
    "classDef mqGuard fill:#f8ece8,stroke:#a03028,color:#5e1a14\n" +
    "classDef mqNode fill:#eef0f6,stroke:#5a6280,color:#2e3350\n" +
    "classDef mqEdge fill:#f4f4ef,stroke:#837a6d,color:#5c554a\n" +
    "classDef mqFinal fill:#e7f0e7,stroke:#3d6b4f,color:#26402e,stroke-width:2px";

  function mqNodeText(s) {
    return mq((s.label ? s.label + " · " : "") + (s.detail || ""), 36);
  }

  function mqShape(s) {
    const inner = mqNodeText(s);
    if (s.type === "intent") return "{" + JSON.stringify(inner) + "}";
    if (s.type === "final") return "([\"✅ " + inner + "\"])";
    return "[" + JSON.stringify(inner) + "]";
  }

  /** 通用（也是 ReAct/Loop/Router 等场景的）纵向流程图：观察后接思考 = 循环回边 */
  function mqFlowchart(steps, userMsg) {
    const lines = ["flowchart TD"];
    lines.push("  U([\"👤 用户输入: " + mq(userMsg, 18) + "\"])");
    let prev = "U";
    steps.forEach((s, i) => {
      const nid = "S" + i;
      lines.push("  " + nid + mqShape(s));
      const loopBack = s.type === "observation" && steps[i + 1] && steps[i + 1].type === "thought";
      lines.push(loopBack
        ? "  " + prev + ' -. "🔁 继续循环" .-> ' + nid
        : "  " + prev + " --> " + nid);
      prev = nid;
    });
    lines.push(MQ_CLASSDEFS);
    steps.forEach((s, i) => lines.push("  S" + i + ":::" + (MQ_TYPE_CLASS[s.type] || "mqNode")));
    return lines.join("\n");
  }

  /** 多智能体：LR 流水线 + 每个角色一个 subgraph */
  function mqMultiAgent(steps, userMsg) {
    const lines = ["flowchart LR"];
    lines.push("  U([\"👤 用户输入: " + mq(userMsg, 14) + "\"])");
    let prev = "U";
    steps.forEach((s, i) => {
      const nid = "S" + i;
      if (s.type === "agent") {
        lines.push("  subgraph G" + i + "[\"" + mq(s.label, 12) + "\"]");
        lines.push("  " + nid + "[\"" + mqNodeText(s) + "\"]");
        lines.push("  end");
        lines.push("  " + nid + ":::mqAgent");
      } else {
        lines.push("  " + nid + mqShape(s));
        lines.push("  " + nid + ":::" + (MQ_TYPE_CLASS[s.type] || "mqNode"));
      }
      lines.push("  " + prev + " --> " + nid);
      prev = nid;
    });
    lines.push(MQ_CLASSDEFS);
    return lines.join("\n");
  }

  /** 工作流状态机：node 为节点，edge 的 detail 作为连线上标签 */
  function mqWorkflow(steps, userMsg) {
    const lines = ["flowchart TD"];
    lines.push("  U([\"👤 用户输入: " + mq(userMsg, 18) + "\"])");
    let prev = "U";
    let pendingEdge = "";
    let i = 0;
    steps.forEach((s) => {
      if (s.type === "edge") { pendingEdge = mq((s.label ? s.label + " · " : "") + s.detail, 18); return; }
      const nid = "S" + i++;
      lines.push("  " + nid + mqShape(s));
      lines.push("  " + prev + (pendingEdge ? " -- \"" + pendingEdge + "\" --> " : " --> ") + nid);
      lines.push("  " + nid + ":::" + (MQ_TYPE_CLASS[s.type] || "mqNode"));
      pendingEdge = "";
      prev = nid;
    });
    lines.push(MQ_CLASSDEFS);
    return lines.join("\n");
  }

  /** 时序图：Function Calling / MCP / 记忆 / 技能 / RAG */
  function mqSequence(id, steps, userMsg) {
    const lines = ["sequenceDiagram", "autonumber"];
    lines.push("actor U as 👤 用户");
    lines.push("participant A as 🤖 Agent");
    const clip = (s, n) => mq((s.label && s.detail ? "" : s.label ? s.label + " · " : "") + s.detail, n || 24);

    let mid = "L";
    if (id === "step04") {
      const tool = steps.find((s) => s.type === "tool" || s.type === "action");
      lines.push("participant T as 🛠 " + (tool ? mq(tool.label, 10) : "工具"));
      mid = "T";
    } else if (id === "step09") {
      lines.push("participant M as 🔌 MCP Server");
      mid = "M";
    } else if (id === "step05") {
      lines.push("participant M as 💾 记忆");
      mid = "M";
    } else if (id === "step10") {
      lines.push("participant K as 🎯 技能库");
      mid = "K";
    } else if (id === "step07") {
      lines.push("participant K as 📚 知识库");
      mid = "K";
    }
    lines.push("participant L as 🧠 大模型");

    lines.push("U->>A: " + mq(userMsg, 26));
    steps.forEach((s) => {
      switch (s.type) {
        case "prompt": lines.push("A->>L: " + clip(s, 24)); break;
        case "thought": lines.push("L-->>A: " + clip(s, 26)); break;
        case "intent": lines.push("L-->>A: 意图 → " + clip(s, 20)); break;
        case "action":
        case "tool":
          lines.push("A->>" + mid + ": " + (id === "step09" ? "tools/call · " : "") + clip(s, 20));
          break;
        case "mcp":
          if (/list|清单|发现/.test(s.label + s.detail)) {
            lines.push("A->>M: tools/list 发现工具");
            lines.push("M-->>A: 返回工具清单");
          } else {
            lines.push("A->>M: " + clip(s, 22));
          }
          break;
        case "observation": lines.push(mid + "-->>A: " + clip(s, 26)); break;
        case "memory": lines.push("A->>M: " + clip(s, 20)); lines.push("M-->>A: 已存取"); break;
        case "compress": lines.push("A->>M: 上下文压缩"); lines.push("M-->>A: 压缩后窗口"); break;
        case "skill": lines.push("A->>K: " + clip(s, 20)); lines.push("K-->>A: 技能就绪"); break;
        case "retrieve": lines.push("A->>K: 检索 · " + clip(s, 20)); lines.push("K-->>A: " + clip(s, 24)); break;
        case "guard": lines.push("A->>A: " + clip(s, 22)); break;
        case "final": lines.push("A-->>U: " + clip(s, 28)); break;
        default: lines.push("Note over A: " + clip(s, 22));
      }
    });
    return lines.join("\n");
  }

  /** 按场景选图型 */
  function buildMermaidCode(id, steps, userMsg) {
    if (!steps || !steps.length) return null;
    try {
      if (id === "step12") return mqMultiAgent(steps, userMsg);
      if (id === "step16") return mqWorkflow(steps, userMsg);
      if (id === "step18") return mqFullSequence(steps, userMsg);
      if (id === "step04" || id === "step05" || id === "step09" || id === "step10" || id === "step07")
        return mqSequence(id, steps, userMsg);
      return mqFlowchart(steps, userMsg);
    } catch (e) {
      return null;
    }
  }

  /** 全流程智能体（step18）：五参与者完整时序 —— 用户/Agent/记忆/大模型/工具集 */
  function mqFullSequence(steps, userMsg) {
    const lines = ["sequenceDiagram", "autonumber"];
    lines.push("actor U as 👤 用户");
    lines.push("participant A as 🤖 Agent");
    lines.push("participant M as 💾 记忆");
    lines.push("participant L as 🧠 大模型");
    lines.push("participant T as 🛠 工具集");
    const clip = (s, n) => mq((s.label ? s.label + " · " : "") + (s.detail || ""), n || 22);

    let inLoop = false;
    const closeLoop = (next) => {
      // 循环块持续到 final/guard/记忆环节才闭合（thought 也是循环的一部分）
      if (inLoop && !(next && ["action", "tool", "observation", "thought"].includes(next.type))) {
        lines.push("end");
        inLoop = false;
      }
    };

    steps.forEach((s, i) => {
      const next = steps[i + 1];
      switch (s.type) {
        case "guard": {
          const blocked = /拦截|拒绝|熔断|超过|过长/.test(s.label + s.detail);
          if (blocked) lines.push("A-->>U: 🛡 " + clip(s, 20));
          else lines.push("A->>A: 🛡 " + clip(s, 20));
          break;
        }
        case "memory":
          if (/回写|写入/.test(s.label)) {
            lines.push("A->>M: 回写本轮对话" + (/压缩/.test(s.detail) ? " + 上下文压缩" : ""));
            lines.push("M-->>A: 已入档");
          } else {
            lines.push("A->>M: 读取历史与画像");
            lines.push("M-->>A: 组装进上下文");
          }
          break;
        case "prompt":
          lines.push("A->>L: 组装上下文 → 请求决策");
          break;
        case "thought":
          lines.push("L-->>A: 决策 · " + clip(s, 20));
          break;
        case "action":
          if (!inLoop) { lines.push("loop ReAct 循环（≤ 5 步）"); inLoop = true; }
          lines.push("A->>T: " + clip(s, 18));
          break;
        case "tool":
          lines.push("Note right of T: 执行工具");
          break;
        case "observation":
          lines.push("T-->>A: 观察结果 · " + clip(s, 20));
          break;
        case "compress":
          lines.push("A->>M: 上下文压缩");
          break;
        case "final":
          closeLoop(next);
          lines.push("A-->>U: ✅ " + clip(s, 24));
          break;
        default:
          lines.push("Note over A: " + clip(s, 20));
      }
      closeLoop(next);
    });
    if (inLoop) lines.push("end");
    return lines.join("\n");
  }

  function renderDiagram(boxEl, id, steps, userMsg) {
    const code = buildMermaidCode(id, steps, userMsg);
    if (!code) { boxEl.innerHTML = ""; return; }
    boxEl.innerHTML = '<div class="diag-loading">⏳ 正在渲染流程图…</div>';
    ensureMermaid().then(async (lib) => {
      try {
        mermaidConfigure();
        const { svg } = await lib.render("mmd-" + Date.now().toString(36) + Math.random().toString(36).slice(2, 6), code);
        boxEl.innerHTML = svg;
      } catch (e) {
        boxEl.innerHTML = '<div class="diag-fail">图渲染失败：' + mq(e.message, 90) + '</div>';
      }
    }).catch(() => {
      boxEl.innerHTML = '<div class="diag-fail">ⓘ Mermaid 库需要联网加载，本次未渲染；下方分步轨迹仍然可用。</div>';
    });
  }

  // 主题切换后重绘所有活跃图
  themeRerender = () => {
    mermaidConfigure();
    activeDiagrams.forEach((d) => {
      if (d.boxEl.isConnected) renderDiagram(d.boxEl, d.id, d.steps, d.userMsg);
    });
  };

  // ============================================================ 聊天渲染

  function addMsg(role, text, isError) {
    chatArea.querySelectorAll(".welcome, .overview").forEach((w) => w.remove());
    const div = document.createElement("div");
    div.className = "msg " + role;
    div.innerHTML =
      '<div class="avatar">' + (role === "user" ? "🙋" : '<img class="avatar-img" src="logo.png" alt="TA">') + "</div>" +
      '<div class="bubble"></div>';
    const bubble = div.querySelector(".bubble");
    if (isError) {
      bubble.classList.add("error");
      bubble.textContent = text;
    } else if (role === "agent") {
      bubble.classList.add("md");
      bubble.innerHTML = mdToHtml(text);
      renderCodeBlocks(bubble);
    } else {
      bubble.textContent = text;
    }
    chatArea.appendChild(div);
    chatArea.scrollTop = chatArea.scrollHeight;
    return div;
  }

  function addTyping() {
    chatArea.querySelectorAll(".welcome, .overview").forEach((w) => w.remove());
    const div = document.createElement("div");
    div.className = "msg agent";
    div.innerHTML =
      '<div class="avatar"><img class="avatar-img" src="logo.png" alt="TA"></div>' +
      '<div class="bubble"><span class="typing"><i></i><i></i><i></i></span></div>';
    chatArea.appendChild(div);
    chatArea.scrollTop = chatArea.scrollHeight;
    return div;
  }

  const TRACE_LABELS = {
    thought: "💭 思考", action: "⚡ 行动", observation: "👁 观察",
    tool: "🛠 工具", intent: "🧭 意图", memory: "🧠 记忆",
    compress: "📦 压缩", mcp: "🔌 MCP", skill: "🎯 技能",
    retrieve: "🔎 检索", agent: "🤖 智能体", guard: "🛡 守卫",
    warn: "⚠️ 警告", node: "🔹 节点", edge: "➡️ 边",
    prompt: "📝 提示词", final: "✅ 完成"
  };

  function addTrace(steps, ms, userMsg) {
    if (!steps || !steps.length) return;

    // 流程图卡片（mermaid）：先图后时间线
    const diag = document.createElement("div");
    diag.className = "trace diag";
    diag.innerHTML =
      '<div class="trace-head"><span>🗺 执行流程图 · mermaid · ' + steps.length + " 步</span>" +
      '<span class="chev">▾</span></div>' +
      '<div class="diag-box"></div>';
    diag.querySelector(".trace-head").addEventListener("click", () => diag.classList.toggle("collapsed"));
    chatArea.appendChild(diag);
    const diagBox = diag.querySelector(".diag-box");
    renderDiagram(diagBox, currentId, steps, userMsg);
    activeDiagrams.push({ boxEl: diagBox, id: currentId, steps, userMsg });
    if (activeDiagrams.length > 24) activeDiagrams.shift();

    const div = document.createElement("div");
    div.className = "trace";
    const items = steps
      .map((s) => {
        const label = TRACE_LABELS[s.type] ? TRACE_LABELS[s.type] + " · " : "";
        return (
          '<div class="tstep t-' + s.type + '">' +
          '<div class="tlabel">' + label + (s.label || "") + "</div>" +
          '<div class="tdetail"></div></div>'
        );
      })
      .join("");
    const durText =
      ms >= 60000
        ? Math.floor(ms / 60000) + " 分 " + Math.round((ms % 60000) / 1000) + " 秒"
        : ms >= 1000
          ? (ms / 1000).toFixed(1) + " 秒"
          : ms + " ms";
    div.innerHTML =
      '<div class="trace-head"><span>🔍 执行轨迹 · ' + steps.length + " 步 · " + durText + "</span>" +
      '<span class="chev">▾</span></div>' +
      '<div class="trace-steps">' + items + "</div>";
    div.querySelectorAll(".tstep").forEach((el, i) => {
      el.querySelector(".tdetail").textContent = steps[i].detail || "";
    });
    div.querySelector(".trace-head").addEventListener("click", () => {
      div.classList.toggle("collapsed");
    });
    chatArea.appendChild(div);
    chatArea.scrollTop = chatArea.scrollHeight;
  }

  // ============================================================ 发送与重置

  let busy = false;

  async function send() {
    const text = input.value.trim();
    if (!text || busy || currentId === "home") {
      if (currentId === "home" && text) toast("请先在左侧选择一个场景", "");
      return;
    }
    busy = true;
    sendBtn.disabled = true;
    input.value = "";
    autoGrow();

    addMsg("user", text);
    const typing = addTyping();

    try {
      const resp = await fetch("/api/" + currentId + "/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message: text, model: readLocalModelCfg() || undefined })
      });
      const data = await resp.json();
      typing.remove();
      if (data.error) {
        addMsg("agent", "出错了：" + data.error, true);
      } else {
        addTrace(data.trace, data.ms, text);
        addMsg("agent", data.answer);
      }
    } catch (e) {
      typing.remove();
      addMsg("agent", "网络异常：无法连接 ToyAgent 服务，请确认 Java 服务已启动。", true);
    } finally {
      busy = false;
      sendBtn.disabled = false;
      input.focus();
    }
  }

  async function reset() {
    if (currentId === "home") return;
    try {
      await fetch("/api/" + currentId + "/reset", { method: "POST" });
      renderChatHome();
      toast("场景状态已重置（记忆、历史已清空）", "ok");
    } catch (e) {
      toast("重置失败，请确认服务已启动", "err");
    }
  }

  function autoGrow() {
    input.style.height = "auto";
    input.style.height = Math.min(input.scrollHeight, 140) + "px";
  }

  // ============================================================ 模型配置（浏览器级：存各自浏览器，互不干扰）

  const MODEL_CFG_KEY = "toyagent-model-config";

  function readLocalModelCfg() {
    try {
      const raw = localStorage.getItem(MODEL_CFG_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch (e) { return null; }
  }

  function writeLocalModelCfg(cfg) {
    try { localStorage.setItem(MODEL_CFG_KEY, JSON.stringify(cfg)); } catch (e) { /* 隐私模式忽略 */ }
  }

  /** 顶部徽标：优先展示本浏览器配置，未配置时回落服务端默认。 */
  async function loadModelBadge() {
    const badge = $("#modelBadge");
    const local = readLocalModelCfg();
    if (local && local.mock) {
      badge.classList.add("mock");
      $("#modelText").textContent = "Mock 模型 · 本浏览器 · 点击配置";
      return;
    }
    if (local && local.baseUrl && local.model) {
      badge.classList.remove("mock");
      $("#modelText").textContent = "真实模型 · " + local.model + " · 本浏览器";
      return;
    }
    try {
      const resp = await fetch("/api/config");
      const data = await resp.json();
      badge.classList.toggle("mock", data.mode !== "real");
      $("#modelText").textContent =
        data.mode === "real" ? "真实模型 · " + data.model : "Mock 模型 · 点击配置";
    } catch (e) {
      $("#modelText").textContent = "服务未连接";
    }
  }

  async function openModelModal() {
    modelModal.hidden = false;
    setStatus("", "");
    // 先用本浏览器已保存的配置回填
    const local = readLocalModelCfg();
    if (local) {
      $("#cfgBaseUrl").value = local.baseUrl || "";
      $("#cfgApiKey").value = local.apiKey || "";
      $("#cfgApiKey").placeholder = local.apiKey ? "已保存（留空沿用）" : "sk-...（留空则用服务端默认 Key）";
      $("#cfgModel").value = local.model || "";
      if (local.mock) setStatus("当前本浏览器使用 Mock 演示模型", "");
    }
    // 再取服务端默认做兜底提示
    try {
      const d = await fetch("/api/model").then((r) => r.json());
      if (!local) {
        $("#cfgBaseUrl").value = d.baseUrl || "";
        $("#cfgApiKey").value = "";
        $("#cfgApiKey").placeholder = d.apiKeyMasked
          ? "服务端已有 Key: " + d.apiKeyMasked + "（留空沿用）"
          : "sk-...";
        $("#cfgModel").value = d.model || "";
      }
    } catch (e) { /* 服务未连接时仅编辑本地 */ }
  }

  function setStatus(msg, cls) {
    const el = $("#modelStatus");
    el.textContent = msg;
    el.className = "modal-status " + (cls || "");
  }

  async function testModel() {
    const baseUrl = $("#cfgBaseUrl").value.trim();
    const apiKey = $("#cfgApiKey").value.trim();
    const model = $("#cfgModel").value.trim();
    if (!baseUrl || !model) {
      setStatus("请先填写接口地址与模型名称", "err");
      return;
    }
    setStatus("⏳ 正在连接测试…", "");
    try {
      const resp = await fetch("/api/model/test", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ baseUrl, apiKey, model })
      });
      const data = await resp.json();
      if (data.ok) {
        setStatus("✅ 连接成功，模型回复：" + (data.reply || "").slice(0, 60), "ok");
      } else {
        setStatus("❌ 连接失败：" + (data.error || "未知错误"), "err");
      }
    } catch (e) {
      setStatus("❌ 请求失败：" + e.message, "err");
    }
  }

  async function saveModel() {
    const baseUrl = $("#cfgBaseUrl").value.trim();
    const apiKey = $("#cfgApiKey").value.trim();
    const model = $("#cfgModel").value.trim();
    if (!baseUrl || !model) {
      setStatus("接口地址与模型名称不能为空", "err");
      return;
    }
    // 只保存到本浏览器 localStorage，不改服务端全局配置
    writeLocalModelCfg({ baseUrl, apiKey, model });
    setStatus("", "");
    modelModal.hidden = true;
    loadModelBadge();
    toast("已保存到本浏览器: " + model + "，仅对你的浏览器生效", "ok");
  }

  async function useMock() {
    // 本浏览器切 Mock：写入本地，不影响其他人
    writeLocalModelCfg({ mock: true });
    modelModal.hidden = true;
    loadModelBadge();
    toast("本浏览器已切换为 Mock 演示模型（无需 API Key）", "ok");
  }

  // ============================================================ 事件与路由

  sendBtn.addEventListener("click", send);
  resetBtn.addEventListener("click", reset);
  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      send();
    }
  });
  input.addEventListener("input", autoGrow);

  // 侧栏品牌区：点击回到首页（总览）
  $("#brandHome").addEventListener("click", () => {
    location.hash = "home";
    $("#sidebar").classList.remove("open");
  });

  $("#modelBadge").addEventListener("click", openModelModal);
  $("#clearModelBtn").addEventListener("click", () => {
    try { localStorage.removeItem(MODEL_CFG_KEY); } catch (e) { /* 忽略 */ }
    $("#cfgBaseUrl").value = "";
    $("#cfgApiKey").value = "";
    $("#cfgModel").value = "";
    loadModelBadge();
    toast("已清除本浏览器配置，回到服务端默认模型", "ok");
  });
  $("#closeModelModal").addEventListener("click", () => (modelModal.hidden = true));
  modelModal.addEventListener("click", (e) => {
    if (e.target === modelModal) modelModal.hidden = true;
  });
  $("#testModelBtn").addEventListener("click", testModel);
  $("#saveModelBtn").addEventListener("click", saveModel);
  $("#useMockBtn").addEventListener("click", useMock);

  // 主题切换（记忆到 localStorage，applyTheme 已在顶部初始化）
  document.querySelectorAll(".theme-dot").forEach((d) => {
    d.addEventListener("click", () => {
      applyTheme(d.dataset.theme);
      toast("主题已切换 · " + (THEME_NAMES[d.dataset.theme] || d.dataset.theme), "ok");
    });
  });

  // 右侧面板拖拽调宽
  initResizer();

  $("#toggleArticle").addEventListener("click", () => {
    $("#articlePanel").classList.toggle("open");
  });
  $("#closeArticle").addEventListener("click", () => {
    $("#articlePanel").classList.remove("open");
  });
  $("#toggleSidebar").addEventListener("click", () => {
    $("#sidebar").classList.toggle("open");
  });

  window.addEventListener("hashchange", () => {
    renderScenario(location.hash.replace("#", "") || "home");
  });

  renderNav();
  renderScenario(location.hash.replace("#", "") || "home");
  loadModelBadge();
})();
