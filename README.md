<div align="center">

# AI 智能套件

**把 Halo 博客变成一个能回答、会检索、可辅助创作，也懂内容运营的 AI 知识站。**

面向 Halo 2.24+ 的一体化 AI 插件，提供 RAG 智能问答、AI 搜索、写作辅助、摘要、脑图、效果评测、意图路由与运营智能体。兼容 OpenAI API 协议，内置 Lucene 混合检索，无需额外部署向量数据库。

[![Halo](https://img.shields.io/badge/Halo-%E2%89%A52.24.0-1e87f0?logo=halo&logoColor=white)](https://halo.run)
[![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Vue](https://img.shields.io/badge/Vue-3-42b883?logo=vuedotjs&logoColor=white)](https://vuejs.org/)
[![Lucene](https://img.shields.io/badge/Lucene-10.3.2-0a6f3a)](https://lucene.apache.org/)
[![Version](https://img.shields.io/badge/version-0.2.23--SNAPSHOT-orange)](src/main/resources/plugin.yaml)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

[快速开始](#快速开始) · [功能全景](#功能全景) · [工作原理](#工作原理) · [开发指南](#开发指南)

</div>

![AI 智能套件：让博客内容继续工作](assets/readme/ai-suite-hero.svg)

---

## 它解决什么问题

博客通常不缺内容，缺的是让内容被重新发现、准确回答和持续复用的能力。AI 智能套件围绕文章从生产到消费的完整链路工作：

- **访客找答案**：基于站内文章进行多轮问答，回答附带原文引用，而不是脱离博客自由发挥。
- **搜索更直接**：在关键词结果之外，先给出一段可追溯的 AI 综合回答。
- **作者写得更快**：在 Halo 编辑器内完成润色、续写、扩写、简化、翻译和大纲生成。
- **内容更易理解**：自动生成文章摘要与可交互脑图。
- **运营有依据**：从真实访客问题中发现内容缺口，形成选题、大纲和旧文优化建议。
- **高频问题走捷径**：用可配置的意图路由处理“最新文章”“热门内容”“某标签文章”等确定性请求，命中后无需经过 RAG。

## 功能全景

| 面向访客 | 面向作者与运营者 |
| --- | --- |
| **RAG 智能问答**：全站文章多轮对话、流式输出、引用溯源 | **写作辅助**：润色、续写、扩写、简化、译英，多轮追加要求并一键应用 |
| **AI 搜索**：搜索页生成综合回答，并保留关键词结果 | **AI 摘要**：单篇或批量生成 Halo 文章摘要 |
| **文章脑图**：自动生成并缓存可交互思维导图 | **索引中心**：全量/单篇重建，查看切片、关键词与索引状态 |
| **问答反馈**：点赞、点踩结果进入后台分析 | **效果评测**：维护评测集，检查检索命中、回答质量与引用效果 |
| **意图路由**：确定性问题进入可编排 Pipeline，响应更稳定 | **运营智能体**：分析访客需求与文章覆盖，产出可执行的内容建议 |
| **主题无关注入**：原生 JS/CSS Widget 接入 Halo 前台 | **用量与审计**：模型 token、调用记录、失败率、限额与检索链路追踪 |

### 意图路由

意图路由适合处理不需要语义检索、但需要读取站内实时数据的问题。例如：

```text
“最近更新了哪些 AI 文章？”
        ↓ 命中 builtin-latest-posts
主题匹配 → 发布时间倒序 → LLM 组织自然语言回答
```

后台可以配置触发规则、优先级和处理步骤。插件内置 `TOPIC_MATCH`、`TAG_MATCH`、`CATEGORY_MATCH`、`KEYWORD_MATCH`、`TIME_SORT`、`VISIT_SORT` 等处理器；未命中意图时自动回到正常 RAG 流程。

## 快速开始

### 环境要求

- Halo 2.24.0 或更高版本
- 一个兼容 OpenAI API 的模型服务
- Chat 模型与 Embedding 模型为必需；Rerank、Query Rewrite 模型可选

> Embedding 模型决定索引向量维度。更换模型或维度后，请在「索引中心」执行全量重建。

### 1. 安装插件

从 [Releases](https://github.com/rainwu/plugin-ai-suite/releases) 下载 `plugin-ai-suite-*.jar`，然后在 Halo Console 中进入「插件 → 安装」，上传并启用。

也可以使用 JDK 21 从源码构建：

```bash
git clone https://github.com/rainwu/plugin-ai-suite.git
cd plugin-ai-suite
JAVA_HOME=/path/to/jdk21 ./gradlew build
```

构建产物位于 `build/libs/`。

### 2. 配置模型

进入「AI 智能套件 → 模型配置」：

1. 填写 Chat 模型的 Base URL、API Key 和模型名称。
2. 填写 Embedding 模型及向量维度。
3. 分别执行连通性测试，确认配置可用。
4. 按需配置 Rerank 与 Query Rewrite 模型。

支持 DeepSeek、通义千问、智谱 GLM、Moonshot、SiliconFlow、OpenAI 等提供 OpenAI 兼容接口的服务。不同能力可以使用不同厂商。

### 3. 建立文章索引

进入「索引中心」，点击「全量重建」。插件会读取已发布的公开文章，完成清洗、切片、关键词提取与向量化。

索引完成后，可先在「对话与外观」中调试问答和检索链路，再开启访客浮窗、AI 搜索或文章脑图。

### 4. 配置生产环境的 SSE

智能问答、AI 搜索和写作辅助使用 SSE 流式输出。如果 Halo 前面有 Nginx，请在代理 Halo 的 `location` 中关闭缓冲：

```nginx
location / {
    proxy_pass http://127.0.0.1:8090;

    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;

    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 300s;
}
```

`proxy_buffering off` 是流式输出的关键；`X-Real-IP` 和 `X-Forwarded-For` 用于访客限流和日志记录。如果前面还有 CDN 或 WAF，也需要确认它们不会缓冲 SSE 响应。

## 工作原理

### 系统全景架构

这张图对应插件的真实运行边界：主题侧和 Console 是两个入口，业务编排位于 Halo 插件进程内，文章与自定义数据由 Halo 管理，检索索引由本地 Lucene 管理，模型能力通过 OpenAI 兼容接口调用。

```mermaid
%%{init: {"theme":"base","themeVariables":{"fontFamily":"Inter, PingFang SC, sans-serif","primaryColor":"#eef2ff","primaryTextColor":"#172554","primaryBorderColor":"#818cf8","lineColor":"#64748b","clusterBkg":"#f8fafc","clusterBorder":"#cbd5e1"}}}%%
flowchart TB
  subgraph Clients["体验层"]
    direction LR
    Visitor["博客访客"]
    Author["作者 / 运营者"]
    Theme["Halo 主题页面<br/>Chat · Search · MindMap"]
    Console["Halo Console<br/>管理页面 · Tiptap 编辑器"]
    Visitor --> Theme
    Author --> Console
  end

  subgraph Access["接入层 · Spring WebFlux"]
    direction LR
    PublicAPI["公开端点<br/>PublicChat · PublicSearch<br/>SearchAnswer · PublicMindMap"]
    ConsoleAPI["管理端点<br/>Config · Knowledge · Writing<br/>Evaluation · Agent · Intent · Usage"]
    Filter["AdditionalWebFilter<br/>注入 Widget JS / CSS"]
    RBAC["Halo RoleTemplate<br/>匿名权限 / 管理权限"]
  end

  subgraph Orchestration["业务编排层"]
    direction LR
    Chat["ChatService"]
    Detector["IntentDetector"]
    Intent["PipelineExecutor<br/>7 类 Processor"]
    RAG["RAGPipeline<br/>改写 · HyDE · 跨语言 · Rerank"]
    Content["内容生成服务<br/>Writing · Summary · MindMap"]
    Ops["质量与运营<br/>Evaluation · ContentGapAgent"]
  end

  subgraph Foundation["基础能力层"]
    direction LR
    LLM["LlmClient<br/>Chat · Embedding · Rerank"]
    Retriever["HybridRetriever<br/>BM25 + HNSW + RRF"]
    Index["LuceneIndexService"]
    Guard["LimitGuard<br/>VisitorRateLimiter"]
    Observe["UsageTracker · ChatLogger<br/>PipelineTrace · TraceCache"]
  end

  subgraph HaloData["Halo 数据与生命周期"]
    direction LR
    Posts["Post · Tag · Category · Counter"]
    GVK["自定义 Extension<br/>ChatLog · IntentRoute<br/>Evaluation · AgentTaskRecord"]
    Config["ConfigMap + Secret"]
    Reconciler["PostIndexReconciler"]
  end

  Models[("OpenAI 兼容模型服务")]
  Disk[("本地 Lucene 索引")]

  Theme --> PublicAPI
  Filter --> Theme
  Console --> ConsoleAPI
  RBAC -.保护.-> PublicAPI
  RBAC -.保护.-> ConsoleAPI
  PublicAPI --> Chat
  ConsoleAPI --> Chat
  ConsoleAPI --> Content
  ConsoleAPI --> Ops
  Chat --> Detector
  Detector -->|"命中"| Intent
  Detector -->|"未命中"| RAG
  Intent --> Posts
  RAG --> Retriever
  Retriever --> Index
  Content --> LLM
  Ops --> LLM
  Intent --> LLM
  RAG --> LLM
  LLM --> Models
  Guard -.调用前校验.-> LLM
  Observe -.记录.-> Chat
  Observe -.记录.-> LLM
  Config --> LLM
  Config --> RAG
  Posts --> Reconciler --> Index
  Index --> Disk
  ConsoleAPI <--> GVK

  classDef actor fill:#fff7ed,stroke:#fb923c,color:#7c2d12;
  classDef entry fill:#eff6ff,stroke:#60a5fa,color:#1e3a8a;
  classDef core fill:#eef2ff,stroke:#818cf8,color:#312e81;
  classDef data fill:#ecfdf5,stroke:#34d399,color:#064e3b;
  classDef external fill:#faf5ff,stroke:#c084fc,color:#581c87;
  class Visitor,Author actor;
  class Theme,Console,PublicAPI,ConsoleAPI,Filter,RBAC entry;
  class Chat,Detector,Intent,RAG,Content,Ops,LLM,Retriever,Guard,Observe core;
  class Posts,GVK,Config,Reconciler,Index,Disk data;
  class Models external;
```

### 一次访客问答如何流转

意图路由和 RAG 共用同一套公开 API、SSE 协议、引用结构、用量统计与问答日志，对前台 Widget 完全透明。

```mermaid
%%{init: {"theme":"base","themeVariables":{"fontFamily":"Inter, PingFang SC, sans-serif","actorBkg":"#eef2ff","actorBorder":"#818cf8","actorTextColor":"#312e81","signalColor":"#475569","noteBkgColor":"#fefce8","noteBorderColor":"#eab308"}}}%%
sequenceDiagram
  autonumber
  actor V as 访客
  participant E as PublicChatEndpoint
  participant G as 限流 / 用量守卫
  participant C as ChatService
  participant D as IntentDetector
  participant I as Intent Pipeline
  participant R as RAGPipeline
  participant L as LlmClient
  participant O as 日志 / Trace

  V->>E: POST /chat/stream（问题 + 历史）
  E->>G: 校验功能开关、IP 限流和额度
  G-->>E: 允许请求
  E->>C: chatStreamWithCitations(...)
  C->>D: 正则匹配，必要时 LLM 兜底分类

  alt 命中 IntentRoute
    D-->>C: 返回优先级最高的意图
    C->>I: 加载公开文章并顺序执行 Processor
    I-->>C: 排序 / 过滤后的 Post 列表
    C->>L: 文章列表 + outputTemplate
  else 未命中意图
    D-->>C: empty
    C->>R: retrieve(query, history)
    R-->>C: Top-N 片段 + 检索上下文
    C->>L: system prompt + 历史 + RAG 上下文
  end

  L-->>C: Flux&lt;token&gt;
  C-->>E: token 流 + citations
  E-->>V: SSE data: token / citations / [DONE]
  E-->>O: 异步记录模型、用量、引用、反馈与检索阶段
```

### RAG 检索管线

管线同时考虑了增强效果与失败降级。Query Rewrite、HyDE、跨语言检索和 Rerank 都可以独立关闭；单步超时不会拖死整条链路，调用侧还有 15 秒的整体兜底。

```mermaid
%%{init: {"theme":"base","themeVariables":{"fontFamily":"Inter, PingFang SC, sans-serif","primaryColor":"#f8fafc","primaryBorderColor":"#94a3b8","lineColor":"#64748b"}}}%%
flowchart LR
  Q["原始问题 + 对话历史"] --> RW{"Query Rewrite<br/>2s 超时"}
  RW -->|"开启"| RQ["改写后的 Query"]
  RW -->|"关闭 / 失败"| RQ0["保留原 Query"]
  RQ --> MODE{"检索模式"}
  RQ0 --> MODE

  MODE -->|"keyword"| BM["BM25 关键词召回"]
  MODE -->|"vector / hybrid"| HYDE{"HyDE<br/>3s 超时"}
  HYDE -->|"开启"| HE["假设性回答 → Embedding"]
  HYDE -->|"关闭 / 失败"| QE["Query → Embedding"]
  HE --> HR["HybridRetriever"]
  QE --> HR
  HR --> BM2["BM25 候选"]
  HR --> VEC["HNSW 向量候选"]
  BM2 --> RRF["RRF 融合"]
  VEC --> RRF

  RQ -."保留原查询".-> ORIG["原 Query 补充召回"]
  RQ -."跨语言开启".-> CROSS["翻译 + 二次检索<br/>3s 超时"]
  BM --> MERGE["合并 · 归一化 · 去重"]
  RRF --> MERGE
  ORIG --> MERGE
  CROSS --> MERGE
  MERGE --> RR{"Rerank<br/>2s 超时"}
  RR -->|"成功"| TOP["阈值过滤 + Top-N"]
  RR -->|"关闭 / 失败"| TOP
  TOP --> CTX["构建 RAGContext"]
  CTX --> OUT["LLM 回答 + 文章引用"]

  classDef optional fill:#fefce8,stroke:#eab308,color:#713f12;
  classDef retrieve fill:#eff6ff,stroke:#60a5fa,color:#1e3a8a;
  classDef result fill:#ecfdf5,stroke:#34d399,color:#064e3b;
  class RW,HYDE,RR optional;
  class BM,HE,QE,HR,BM2,VEC,RRF,ORIG,CROSS,MERGE retrieve;
  class TOP,CTX,OUT result;
```

### 数据、索引与状态

```mermaid
%%{init: {"theme":"base","themeVariables":{"fontFamily":"Inter, PingFang SC, sans-serif","primaryColor":"#f8fafc","primaryBorderColor":"#94a3b8","lineColor":"#64748b","clusterBkg":"#ffffff","clusterBorder":"#cbd5e1"}}}%%
flowchart TB
  subgraph Halo["Halo Extension Store"]
    Posts["Post / Tag / Category / Counter"]
    Logs["ChatLog"]
    Intents["IntentRoute"]
    Eval["EvaluationDataset<br/>EvaluationRunRecord"]
    Tasks["AgentTaskRecord"]
    CM["ConfigMap<br/>非敏感配置"]
    Secret["Secret<br/>模型 API Key"]
  end

  subgraph Sync["内容索引同步"]
    Event["文章发布 / 更新 / 删除"]
    Reconciler["PostIndexReconciler"]
    Chunker["DocumentChunker<br/>清洗 · 切片 · 关键词"]
    Embed["Embedding API"]
    Lucene["LuceneIndexService<br/>文档字段 + HNSW 向量"]
    Event --> Reconciler --> Chunker --> Embed --> Lucene
  end

  subgraph Runtime["运行时状态"]
    Rate["VisitorRateLimiter<br/>IP 小时 / 每日窗口"]
    Usage["UsageTracker<br/>reserve / settle"]
    Trace["TraceCache<br/>短期调试链路"]
  end

  Posts --> Event
  Lucene --> Files[("Halo 数据目录中的 Lucene 文件")]
  CM --> Config["AIProperties"]
  Secret --> Config
  Config --> Chunker
  Config --> Rate
  Config --> Usage
  Usage --> Calls["模型调用明细 / 统计"]
  Logs --> Analytics["问答记录与反馈分析"]
  Intents --> Detect["IntentDetector 30s 缓存"]
  Eval --> Runner["EvaluationService"]
  Tasks --> Agent["ContentGapAgentService"]

  classDef halo fill:#f5f3ff,stroke:#a78bfa,color:#4c1d95;
  classDef index fill:#eff6ff,stroke:#60a5fa,color:#1e3a8a;
  classDef runtime fill:#fff7ed,stroke:#fb923c,color:#7c2d12;
  class Posts,Logs,Intents,Eval,Tasks,CM,Secret halo;
  class Event,Reconciler,Chunker,Embed,Lucene,Files index;
  class Rate,Usage,Trace,Calls runtime;
```

### 为什么不需要外部向量数据库

插件直接使用与 Halo 2.24.0 对齐的 Lucene 10.3.2：BM25 负责关键词召回，HNSW 负责向量召回，再通过 RRF 融合结果。索引保存在 Halo 数据目录中，适合个人博客和中小型内容站点的一体化部署。

> Lucene 版本必须与 Halo 内置版本严格一致。核心依赖使用 `compileOnly` 复用 Halo ClassLoader，SmartChineseAnalyzer 单独打包且不传递引入 `lucene-core`。

## 配置导航

| Console 页面 | 主要用途 |
| --- | --- |
| 模型配置 | Chat、Embedding、Rerank、Query Rewrite 模型与连通性测试 |
| 切片设置 | 切片大小、重叠、标题/句子边界与自动关键词 |
| 索引中心 | 索引重建、文章状态、切片与关键词检查 |
| 检索策略 / 检索增强 | BM25/向量混合检索、Top-K、阈值、HyDE、Rerank、跨语言 |
| 对话与外观 | 系统提示词、历史轮数、访客开关与浮窗样式 |
| AI 搜索 / AI 脑图 / AI 摘要 | 各前台与内容生成功能的开关和参数 |
| 写作辅助 | 编辑器写作模型、大纲深度与输入限制 |
| 意图路由 | 触发规则、Pipeline、优先级与输出模板 |
| 效果评测 | 评测数据集、运行记录和质量问题定位 |
| 运营智能体 | 内容缺口、选题、大纲与旧文更新建议 |
| 问答记录 / 用量统计 | 会话、反馈、调用明细、token 和限额 |

配置保存在 ConfigMap `ai-suite-configmap`；API Key 单独保存在 Secret `ai-suite-api-keys`。

## 安全与稳定性

- **密钥隔离**：API Key 不以明文写入 ConfigMap；旧版配置可自动迁移。
- **调用限额**：支持按模型设置每日 token 上限，并通过预扣与对账降低并发超额风险。
- **访客限流**：支持按 IP 的每小时、每日限制和白名单。
- **访问控制**：公开 API 使用独立匿名 RoleTemplate；Console API 需要管理员权限。
- **SSRF 防护**：模型 Base URL 会拦截本机、内网和云元数据地址，避免插件成为内网探测入口。
- **错误脱敏**：模型错误写入日志前会清理疑似 API Key。
- **可观测性**：记录检索阶段、耗时、引用、命中意图、模型用量和访客反馈。

## 技术栈

| 层 | 技术 |
| --- | --- |
| 插件后端 | Java 21、Spring WebFlux、Halo Plugin API 2.24.0 |
| Console | Vue 3、TypeScript、Vite、Tiptap |
| 主题侧 | 原生 JavaScript / CSS，通过 AdditionalWebFilter 注入 |
| 检索 | Apache Lucene 10.3.2、BM25、HNSW、RRF、SmartChineseAnalyzer |
| 构建 | Gradle 9.4、Node.js 20+、pnpm 9+ |

## 项目结构

```text
src/main/java/run/halo/ai/suite/
├── agent/          # 运营智能体任务
├── config/         # 配置读取与 API Key 迁移
├── endpoint/       # 公开 API 与 Console API
├── extension/      # ChatLog、评测、意图路由、Agent 任务等 GVK
├── intent/         # 意图 Pipeline 与内置处理器
├── listener/       # 文章变更与索引同步
├── llm/            # OpenAI 兼容客户端与用量场景
├── rag/            # 切片、BM25/HNSW 检索与 RAG 编排
├── service/        # 对话、写作、摘要、脑图、评测等服务
├── state/          # 用量统计与限流
└── widget/         # 前台资源注入

ui/src/
├── extensions/ai-writing/  # Tiptap AI 写作扩展
├── views/                  # Console 管理页面
├── components/             # 通用 UI 组件
└── utils/                  # API 与工具函数

src/main/resources/
├── extensions/     # RoleTemplate、扩展点与静态资源代理
├── static/         # 前台 Widget JS / CSS
└── plugin.yaml     # Halo 插件清单
```

## 开发指南

本项目要求 JDK 21，不使用 Docker，也不要运行 `./gradlew haloServer`。

```bash
# 完整构建：后端 + Console
JAVA_HOME=~/jdk21/contents/Contents/Home ./gradlew build

# 仅构建后端 jar
JAVA_HOME=~/jdk21/contents/Contents/Home ./gradlew jar

# 运行后端测试
JAVA_HOME=~/jdk21/contents/Contents/Home ./gradlew test

# Console 前端 watch 模式
cd ui && pnpm dev
```

### 本地联调

仓库提供了开发脚本，会构建插件、检查 8090 端口并以 jar 方式运行 Halo：

```bash
./dev-start.sh
```

仅重新构建并部署插件：

```bash
./dev-start.sh --deploy-only
```

本地地址为 `http://localhost:8090`，Halo 必须从 `dev/` 目录启动，因为开发配置位于 `dev/application.yaml`。

## 常见问题

<details>
<summary><strong>回答不是流式出现，而是最后一次性显示？</strong></summary>

通常是 Nginx、CDN 或 WAF 缓冲了 SSE 响应。先确认 Halo 代理配置包含 `proxy_buffering off`，再检查上游 CDN 的缓存和响应优化策略。

</details>

<details>
<summary><strong>更换 Embedding 模型后搜索结果异常？</strong></summary>

确认新模型的向量维度与配置一致，然后执行一次全量索引重建。旧向量不能直接与不同模型或不同维度的新向量混用。

</details>

<details>
<summary><strong>本地 Ollama 的 URL 为什么被拒绝？</strong></summary>

SSRF 防护默认禁止访问 `localhost` 和内网地址，当前没有 Base URL 白名单。如果需要接入 Ollama，请通过具备访问控制的 HTTPS 网关暴露模型服务，不要直接关闭这项校验。

</details>

<details>
<summary><strong>升级 Halo 后插件无法加载 Lucene 索引？</strong></summary>

插件依赖的 Lucene 版本必须与 Halo 内置版本一致。升级 Halo 大版本前，请先确认本插件发布版本声明的兼容范围。

</details>

## 许可证

本项目基于 [GPL-3.0](LICENSE) 发布。欢迎提交 Issue 和 Pull Request。

<div align="center">

<sub>让博客里的内容继续工作，而不只是静静躺在归档页里。</sub>

</div>
