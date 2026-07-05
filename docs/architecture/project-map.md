# 项目模块地图

> 适用版本：AI 智能套件 0.3.2
> 适用读者：维护者、评审者、二次开发者

本页按当前源码梳理项目，不使用历史 Phase 文档作为事实来源。

## 顶层结构

| 路径 | 职责 |
| --- | --- |
| `src/main/java/run/halo/ai/suite` | 插件生命周期、API、业务服务、RAG、意图、运行状态 |
| `src/main/resources/console` | Vue Console 构建产物 |
| `src/main/resources/static` | 访客聊天、搜索、脑图原生 JS/CSS |
| `src/main/resources/extensions` | RoleTemplate、WebFilter、摘要扩展和静态资源代理声明 |
| `ui/src` | Console 页面和 Tiptap 写作扩展源码 |
| `docs` | VitePress 多页文档、图表源码和导出 SVG |
| `dev` | 本地 Halo JAR、配置和数据，仅用于开发 |

## 后端模块

| 模块 | 关键实现 | 责任边界 |
| --- | --- | --- |
| 生命周期 | `AISuitePlugin` | 注册 Extension Scheme 和索引，停止时释放 Lucene Writer |
| 配置 | `AIProperties`、`ConsoleConfigEndpoint` | 从 `ai-suite-configmap` 读取分组 JSON，保存与连接测试 |
| 模型 | `AiFoundationClient`、`LlmClient` | AI Foundation 调用、推理兼容、用量预扣与结算 |
| 对话 | `ChatService`、`PublicChatEndpoint` | 意图/RAG 分流、流式与非流式回答、引用、结构化结果 |
| RAG | `RAGPipeline`、`HybridRetriever`、`LuceneIndexService` | 查询增强、BM25/HNSW、RRF、Rerank、上下文构建 |
| 索引 | `DocumentChunker`、`ReindexService`、`PostIndexReconciler` | 文章清洗切片、关键词、Embedding、增量同步 |
| 意图 | `IntentDetector`、`PipelineExecutor`、`processor/*` | 正则/LLM 分类、处理器编排、实时文章查询 |
| AI 创建路由 | `IntentRouteGenerationService` | 自然语言生成白名单 Pipeline、校验与冲突提示 |
| 内容生成 | `WritingService`、`SummaryService`、`MindMapService` | 写作、摘要和脑图生成 |
| 质量与运营 | `EvaluationService`、`ContentGapAgentService` | 数据集评测、内容缺口分析 |
| 观测与保护 | `UsageTracker`、`LimitGuard`、`VisitorRateLimiter`、`ChatLogger` | 用量、限额、匿名限流、日志与反馈 |

## Console 页面

Console 以 `ui/src/index.ts` 注册路由，`AISuiteLayout.vue` 提供套件内侧栏。当前页面包括：总览、模型配置、切片设置、索引中心、检索策略、检索增强、对话与外观、AI 搜索、AI 脑图、AI 摘要、写作辅助、问答记录、效果评测、运营智能体、意图路由和用量统计。

意图路由有两个编辑入口：

- `IntentRoutesView.vue`：列表、手工创建、编辑、试跑、启停和删除。
- `IntentRouteAiCreateView.vue`：描述需求、检查生成规则、模拟并保存草稿。

## 访客侧

`ChatWidgetFilter` 通过 Halo AdditionalWebFilter 注入前台资源。聊天 Widget 使用 POST + `fetch` + `ReadableStream`，支持引用、结构化文章卡片、日志反馈和折叠推理面板。搜索与脑图使用独立脚本，但共享配置、模型调用和主题约定。

## 持久化与可重建数据

| 类型 | 内容 | 恢复方式 |
| --- | --- | --- |
| ConfigMap | AI Suite 业务配置 | 备份 `ai-suite-configmap` |
| AI Foundation 资源 | 供应商、密钥、模型与默认槽位 | 按 AI Foundation 方式备份 |
| Extension | ChatLog、EvaluationDataset、EvaluationRunRecord、IntentRoute、AgentTaskRecord | 随 Halo Extension Store 备份 |
| Lucene 索引 | 文章切片、BM25、向量和关键词 | 可从公开文章全量重建 |
| 运行状态 | 限流窗口、TraceCache、任务内存状态 | 重启后重新建立 |

## 关键请求路径

1. 访客问题进入 `PublicChatEndpoint`。
2. `ChatService` 优先处理指定 `intentRouteId`，否则调用 `IntentDetector`。
3. 命中意图时执行 `PipelineExecutor`；未命中时进入 `RAGPipeline`。
4. 模型调用通过 `LlmClient` 进入 `AiFoundationClient`。
5. 输出统一为 citations、structured_result、reasoning、token、logId 和 DONE 事件。
6. 调用结果进入用量统计和 ChatLog；点踩时可以附带 Trace。

## 继续阅读

- [系统架构总览](overview.md)
- [RAG 管线](rag-pipeline.md)
- [意图路由架构](intent-routing.md)
- [数据存储与生命周期](data-storage.md)
- [当前版本能力清单](../reference/current-version.md)

