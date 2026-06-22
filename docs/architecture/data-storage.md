# 数据存储与生命周期

[![数据、索引与运行状态](../../assets/readme/data-index-state.svg)](../../assets/readme/data-index-state.svg)

## 三类数据

| 类型 | 示例 | 生命周期 |
| --- | --- | --- |
| 业务与配置 | ConfigMap、Secret、自定义 Extension | 需要备份和迁移 |
| 派生索引 | Lucene 切片与向量 | 可从文章重建 |
| 运行状态 | 限流窗口、TraceCache、任务进度 | 允许过期或重启丢失 |

## 配置

普通配置存于 `ai-suite-configmap`，API Key 存于 `ai-suite-api-keys`。`AIProperties` 读取两者并注入默认值。旧 `ai-assistant-*` 名称仅用于迁移兼容。

## 自定义 Extension

ChatLog、EvaluationDataset、EvaluationRunRecord、IntentRoute 和 AgentTaskRecord 使用 `ai-suite.halo.run/v1alpha1` GVK 持久化，随 Halo Extension Store 生命周期管理。

## 文章与 annotation

Halo Post 是内容事实来源。摘要写入文章摘要字段；脑图缓存写入文章 annotation，并使用内容与配置 hash 判断是否陈旧。

## Lucene

Lucene 保存 BM25 字段、向量和切片元数据。索引位于 Halo 数据目录，依赖与 Halo 一致的 Lucene 版本。备份可以缩短恢复时间，但不能代替文章和配置备份。

## 运行状态

访客限流、部分用量预扣、TraceCache 和活动任务状态驻留内存或运行时存储。重启后可能重置，因此不能用于长期审计。需要长期保存的评测、Agent 报告和问答记录都使用 Extension。
