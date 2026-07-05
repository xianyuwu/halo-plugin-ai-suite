# 自定义 Extension 参考

> API Group：`ai-suite.halo.run`  
> Version：`v1alpha1`

| Kind | Plural | 用途 | 关键字段 |
| --- | --- | --- | --- |
| `ChatLog` | `chatlogs` | 访客问答与反馈 | timestamp、question、answer、model、citations、feedbackType、trace |
| `EvaluationDataset` | `evaluationdatasets` | 可重复评测集 | displayName、description、cases、updatedAt |
| `EvaluationRunRecord` | `evaluationrunrecords` | 完整评测报告 | runId、datasetId、summary、resultsJson |
| `IntentRoute` | `intentroutes` | 自定义意图与 Pipeline | enabled、priority、triggerPatterns、llmFallback、pipeline、outputTemplate（兼容字段） |
| `AgentTaskRecord` | `agenttaskrecords` | 运营智能体历史任务 | taskId、status、progress、inputJson、reportJson |

## 管理原则

- metadata.name 是稳定资源 ID。
- Service 层负责业务校验，不依赖客户端直接写 Extension。
- 插件启动时注册 Scheme 和字段索引，并处理 reload 残留。
- 升级或卸载前随 Halo 数据备份。
- JSON 字符串字段用于规避复杂嵌套对象的 Extension Schema 限制，消费时必须容忍旧结构。

这些 Extension 不是 Public API。外部业务优先使用 Console Endpoint，而不是直接绕过校验修改资源。
