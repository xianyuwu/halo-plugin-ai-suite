<div align="center">

# AI 智能套件

**把 Halo 博客变成一个能回答、会检索、可辅助创作，也懂内容运营的 AI 知识站。**

面向 Halo 2.24+ 的一体化 AI 插件，提供 RAG 智能问答、AI 搜索、写作辅助、摘要、脑图、效果评测、意图路由与运营智能体。兼容 OpenAI API 协议，内置 Lucene 混合检索，无需额外部署向量数据库。

[![Halo](https://img.shields.io/badge/Halo-%E2%89%A52.24.0-1e87f0?logo=halo&logoColor=white)](https://halo.run)
[![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Vue](https://img.shields.io/badge/Vue-3-42b883?logo=vuedotjs&logoColor=white)](https://vuejs.org/)
[![Lucene](https://img.shields.io/badge/Lucene-10.3.2-0a6f3a)](https://lucene.apache.org/)
[![Version](https://img.shields.io/badge/version-0.2.23-blue)](src/main/resources/plugin.yaml)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

[快速开始](#快速开始) · [功能全景](#功能全景) · [完整文档](https://ai-suite-docs.rainwu.cn) · [工作原理](#工作原理) · [开发指南](#开发指南)

</div>

[![AI 智能套件：让博客内容继续工作](assets/readme/ai-suite-hero.svg)](assets/readme/ai-suite-hero.svg)

---

## 项目文档

- 在线文档：[AI 智能套件文档中心](https://ai-suite-docs.rainwu.cn)
- 文档源码：[docs/](docs/)

## 它解决什么问题

博客通常不缺内容，缺的是让内容被重新发现、准确回答和持续复用的能力。AI 智能套件围绕文章从生产到消费的完整链路工作：

- **访客找答案**：基于站内文章进行多轮问答，回答附带原文引用，而不是脱离博客自由发挥。
- **搜索更直接**：在关键词结果之外，先给出一段可追溯的 AI 综合回答。
- **作者写得更快**：在 Halo 编辑器内完成润色、续写、扩写、简化、翻译和大纲生成。
- **内容更易理解**：自动生成文章摘要与可交互脑图。
- **运营有依据**：从真实访客问题中发现内容缺口，形成选题、大纲和旧文优化建议。
- **高频问题走捷径**：用可配置的意图路由处理“最新文章”“热门内容”“某标签文章”等确定性请求，命中后无需经过 RAG。

> 需要完整的安装、配置、运维、架构和 API 说明？进入 [AI 智能套件文档中心](https://ai-suite-docs.rainwu.cn)。

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

## 实际界面

以下截图来自 `dev.rainwu.cn` 测试环境，展示当前版本的真实运行效果。站点内容、统计数字和界面细节会随数据与版本变化。

### 访客端

#### RAG 智能问答

[![访客端 RAG 智能问答与文章引用](assets/readme/screenshots/visitor-chat.jpg)](assets/readme/screenshots/visitor-chat.jpg)

基于博客内容回答，展示文章引用与反馈操作。

#### 文章 AI 脑图

<p align="center">
  <a href="assets/readme/screenshots/visitor-mindmap.jpg"><img src="assets/readme/screenshots/visitor-mindmap.jpg" alt="文章页 AI 思维导图" width="90%"></a>
</p>

<p align="center"><sub>聚焦显示文章结构，支持节点折叠、展开与原文跳转。</sub></p>

### Console 管理端

| 索引中心 | 意图路由 |
| --- | --- |
| [![AI 智能套件索引中心](assets/readme/screenshots/console-knowledge.jpg)](assets/readme/screenshots/console-knowledge.jpg) | [![意图路由管理页面](assets/readme/screenshots/console-intent-routes.jpg)](assets/readme/screenshots/console-intent-routes.jpg) |
| 查看索引健康度、切片、关键词覆盖与维护建议 | 管理触发词、处理器 Pipeline、优先级与启用状态 |

| 检索增强 | AI 搜索 |
| --- | --- |
| [![检索增强配置页面](assets/readme/screenshots/console-enhance.jpg)](assets/readme/screenshots/console-enhance.jpg) | [![AI 搜索配置与实时预览](assets/readme/screenshots/console-search.jpg)](assets/readme/screenshots/console-search.jpg) |
| 配置 Query Rewrite、HyDE、Rerank 与跨语言检索 | 调整回答策略、界面主题，并实时预览搜索卡片 |

| AI 脑图 | 写作辅助 |
| --- | --- |
| [![AI 脑图配置与管理页面](assets/readme/screenshots/console-mindmap.jpg)](assets/readme/screenshots/console-mindmap.jpg) | [![编辑器 AI 写作辅助配置页面](assets/readme/screenshots/console-writing.jpg)](assets/readme/screenshots/console-writing.jpg) |
| 设置生成参数、独立主题，并管理文章脑图 | 配置编辑器 AI 动作、写作模型与大纲生成 |

| 效果评测 | 运营智能体 |
| --- | --- |
| [![RAG 效果评测页面](assets/readme/screenshots/console-evaluation.jpg)](assets/readme/screenshots/console-evaluation.jpg) | [![内容缺口运营智能体页面](assets/readme/screenshots/console-agent.jpg)](assets/readme/screenshots/console-agent.jpg) |
| 管理评测集、运行范围与回答质量指标 | 分析站内内容缺口，生成选题与旧文更新建议 |

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

访客聊天浮窗通过 `POST /chat/stream` 在请求体中传递问题和对话历史，AI 搜索回答通过 `POST /search/answer` 传递关键词。两者都不依赖放宽 Halo Netty 的 HTTP 请求行长度，可以移除 `SERVER_NETTY_MAX_INITIAL_LINE_LENGTH=64KB`。请求头大小与对话历史无关，可按站点 Cookie 和代理头的实际规模决定是否保留 `SERVER_MAX_HTTP_REQUEST_HEADER_SIZE`。

## 工作原理

### 系统全景架构

主图采用“体验入口 → 业务能力 → AI 编排核心 → 共享基础设施 → 数据与知识”的阅读路径。蓝色表示同步业务流，紫色表示模型调用，绿色表示内容与索引数据流；具体类级调用继续由下方专项图展开。

[![AI 智能套件系统全景架构](assets/readme/system-architecture.svg)](assets/readme/system-architecture.svg)

<details>
<summary><strong>查看插件内部组件映射</strong></summary>

| 架构区域 | 核心实现 |
| --- | --- |
| 前台体验 | `ChatWidgetFilter`、`PublicChatEndpoint`、`PublicSearchEndpoint`、`SearchAnswerEndpoint`、`PublicMindMapEndpoint` |
| Console 与编辑器 | Vue 管理页面、`AiWritingExtension`、Console 系列 Endpoint |
| AI 编排核心 | `ChatService`、`IntentDetector`、`PipelineExecutor`、`RAGPipeline`、`LlmClient` |
| 检索与索引 | `DocumentChunker`、`HybridRetriever`、`LuceneIndexService`、`PostIndexReconciler` |
| 内容与运营 | `WritingService`、`SummaryService`、`MindMapService`、`EvaluationService`、`ContentGapAgentService` |
| 安全与观测 | `LimitGuard`、`VisitorRateLimiter`、`UsageTracker`、`ChatLogger`、`PipelineTrace`、`TraceCache` |
| Halo 数据 | Post / Tag / Category / Counter、ConfigMap / Secret、自定义 Extension |

</details>

### 一次访客问答如何流转

意图路由和 RAG 共用同一套公开 API、SSE 协议、引用结构、用量统计与问答日志，对前台 Widget 完全透明。

[![AI 智能套件访客问答时序](assets/readme/visitor-chat-sequence.svg)](assets/readme/visitor-chat-sequence.svg)

### RAG 检索管线

管线同时考虑了增强效果与失败降级。Query Rewrite、HyDE、跨语言检索和 Rerank 都可以独立关闭；单步超时不会拖死整条链路，调用侧还有 15 秒的整体兜底。

[![AI 智能套件 RAG 检索管线](assets/readme/rag-pipeline.svg)](assets/readme/rag-pipeline.svg)

### 数据、索引与状态

数据层分为三类：Halo Extension Store 保存业务数据和配置，文章变更通过 Reconciler 同步到 Lucene，本地运行状态负责限流、用量与调试链路。三者生命周期不同，避免把短期状态误当成持久化数据。

[![AI 智能套件数据、索引与运行状态](assets/readme/data-index-state.svg)](assets/readme/data-index-state.svg)

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
