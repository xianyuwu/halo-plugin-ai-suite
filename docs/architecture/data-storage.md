# 数据存储与生命周期

[![数据、索引与运行状态](../../assets/readme/data-index-state.svg)](/readme/data-index-state.svg)

## 三类数据

| 类型 | 示例 | 生命周期 |
| --- | --- | --- |
| 业务与配置 | ConfigMap、自定义 Extension、Halo Post/annotation | 需要备份和迁移 |
| 派生索引 | Lucene 切片与向量 | 可从文章重建 |
| 运行状态 | 限流窗口、TraceCache、活动任务进度 | 允许过期或重启丢失 |

## 配置

AI 智能套件配置存于 `ai-suite-configmap`，由 Halo Extension Store 最终持久化到 Halo 数据库。模型供应商、Base URL、API Key 和默认模型由 Halo AI Foundation 独立管理，AI 智能套件只保存各业务使用的模型资源名和生成参数。

## 自定义 Extension

ChatLog、EvaluationDataset、EvaluationRunRecord、IntentRoute 和 AgentTaskRecord 使用 `ai-suite.halo.run/v1alpha1` GVK 持久化。它们由 Halo Extension Store 管理，并最终写入 Halo 数据库；不是保存在插件 JAR 或浏览器中。

## 文章与 annotation

Halo Post 是内容事实来源。摘要写入文章摘要字段；脑图缓存写入文章 annotation，并使用内容与配置 hash 判断是否陈旧。

## Lucene

Lucene 保存 BM25 字段、向量和切片元数据。物理目录为：

```text
{halo.work-dir}/data/ai-suite/lucene/
```

该目录通常位于 Halo 持久化数据卷中，依赖与 Halo 一致的 Lucene 版本。索引可以从已发布文章全量重建；备份它可以缩短恢复时间，但不能代替文章、配置和业务记录备份。

## 用量数据

当天统计和调用明细优先驻留 JVM 内存，并每 5 分钟、跨日及插件正常停止时异步刷入按日期命名的 ConfigMap：

```text
ai-suite-usage-YYYY-MM-DD
```

插件启动时会恢复最近 30 天记录，查询缺失日期时也会按需读取。因此用量不是纯内存数据，但异常退出与最近一次刷盘之间仍存在短暂丢失窗口。

## 运行状态

访客限流窗口、并发请求的用量预扣、TraceCache、重建进度和活动评测状态驻留 JVM 内存。重启后可能重置，因此不能用于长期审计。需要长期保存的评测结果、Agent 报告和问答记录都使用 Extension；模型用量则按上节的 ConfigMap 机制持久化。

## 备份优先级

1. Halo 数据库：文章、ConfigMap、自定义 Extension 和 AI Foundation 资源的事实来源；
2. Halo 工作目录：附件以及 Lucene 等本地派生数据；
3. 插件 JAR 与版本记录：用于按原版本恢复运行环境。

只复制 Lucene 目录无法恢复完整系统；只复制插件 JAR也不会包含任何业务数据。
