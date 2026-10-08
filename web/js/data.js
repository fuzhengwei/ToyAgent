/**
 * ToyAgent 场景元数据
 * 每个场景 = 后端一个接口类 + 一段右侧原理文章
 */
const SCENARIOS = [
  {
    id: "home",
    num: "00",
    group: "总览",
    title: "ToyAgent 总览",
    subtitle: "课程地图 · 从最小 MVP 到生产级运行时",
    samples: []
  },
  {
    id: "step01",
    num: "01",
    group: "起点 · 一个方法",
    title: "对话智能体",
    subtitle: "chat(input)：智能体的最小 MVP",
    samples: ["你好，介绍一下你自己", "什么是智能体？", "今天心情不太好"]
  },
  {
    id: "step02",
    num: "02",
    group: "起点 · 一个方法",
    title: "提示词工程",
    subtitle: "给模型一份角色说明书",
    samples: ["用一句话解释什么是 Agent", "介绍一下 Function Calling", "帮我类比一下记忆系统"]
  },
  {
    id: "step03",
    num: "03",
    group: "思考与行动",
    title: "ReAct 智能体",
    subtitle: "思考-行动-观察循环",
    samples: ["北京今天天气怎么样？", "上海天气如何，适合出行吗", "帮我算一下 12*8"]
  },
  {
    id: "step04",
    num: "04",
    group: "思考与行动",
    title: "工具调用",
    subtitle: "Function Calling：给模型装上手脚",
    samples: ["查一下广州的天气", "计算 1024/8", "你是谁？"]
  },
  {
    id: "step05",
    num: "05",
    group: "大脑的能力",
    title: "记忆系统",
    subtitle: "多轮记忆 + 上下文压缩",
    samples: ["我叫小傅哥", "我叫什么名字？", "帮我把我们的对话压缩总结一下"]
  },
  {
    id: "step06",
    num: "06",
    group: "大脑的能力",
    title: "意图路由",
    subtitle: "先识别意图，再决策走向",
    samples: ["深圳天气怎么样", "帮我算 99+1", "给我讲个笑话"]
  },
  {
    id: "step07",
    num: "07",
    group: "工具的进化",
    title: "MCP 协议",
    subtitle: "工具的标准化接口",
    samples: ["杭州现在几点？天气如何", "查一下北京的天气", "MCP 是什么协议？"]
  },
  {
    id: "step08",
    num: "08",
    group: "工具的进化",
    title: "技能编排",
    subtitle: "Skills：工具的组合与复用 L0/L1/L2",
    samples: ["帮我做一份杭州旅行攻略", "播报一下上海天气", "聊聊你最喜欢的书"]
  },
  {
    id: "step09",
    num: "09",
    group: "知识工程",
    title: "RAG 检索增强",
    subtitle: "戴着资料说话，缓解幻觉",
    samples: ["ToyAgent 是什么项目？", "MCP 的核心方法有哪些？", "量子力学的泡利不相容原理是什么？"]
  },
  {
    id: "step14",
    num: "14",
    group: "知识工程",
    title: "LLM-Wiki 知识编译",
    subtitle: "知识编译一次、持久维护，而非每次从头检索",
    samples: ["项目用什么代码规范？", "项目怎么部署？", "编译：发布流程：每周三灰度，周五全量上线", "发布流程是什么？"]
  },
  {
    id: "step10",
    num: "10",
    group: "团队协作",
    title: "多智能体协作",
    subtitle: "规划-研究-写作-审查流水线",
    samples: ["写一段 100 字的智能体科普短文", "调研一下 2026 年 Agent 趋势并成文"]
  },
  {
    id: "step11",
    num: "11",
    group: "工程化",
    title: "Loop 运行时",
    subtitle: "守卫 + 保险丝：模型聪明，运行时可靠",
    samples: ["北京天气怎么样？", "帮我 hack 别人的密码", "用超级超级超级长的输入测试守卫（这条会超过长度限制）"]
  },
  {
    id: "step12",
    num: "12",
    group: "工程化",
    title: "工作流状态机",
    subtitle: "LangGraph 思想：节点 + 条件边 + 状态",
    samples: ["查一下成都天气", "帮我算 25*4", "RAG 是什么？"]
  },
  {
    id: "step13",
    num: "13",
    group: "综合 · 全流程",
    title: "全流程智能体",
    subtitle: "守卫+记忆+ReAct+工具+保险丝，一条链路跑通",
    samples: ["我叫小傅哥，查一下北京天气再帮我算 26*4", "查下上海天气", "我叫什么名字？", "帮我 hack 别人的密码"]
  }
];

/** 后端场景名（与 Java Agent.name() 对应，用于 header 兜底） */
const BACKEND_NAMES = {};
