# Console API

> 前缀：`/apis/console.api.ai-suite.halo.run/v1alpha1`  
> 认证：Halo 管理员

## 配置与调试

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/config` | 获取全部配置（密钥按 Console 规则处理） |
| POST | `/config/save` | 保存配置组 |
| POST | `/config/test-connection` | 通用连接测试 |
| POST | `/config/test-model` | Chat 模型测试 |
| POST | `/config/test-embedding` | Embedding 测试 |
| POST | `/config/test-rerank` | Rerank 测试 |
| POST | `/config/test-query-rewrite` | Query Rewrite 测试 |
| POST | `/chat/debug/stream` | 带 Trace 的调试 SSE |

`/config/save` 接收以配置组为键的 JSON。字段、默认值和重建影响见 [配置参考](../reference/configuration-reference.md)。

调试 SSE 除 citations/token 外还会发送 `trace_stage` 和 `trace_summary`，仅用于后台调试界面。

## 知识库与摘要

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/knowledge/reindex` | 启动全量重建 |
| GET | `/knowledge/reindex/progress` | 全量重建 SSE 进度 |
| GET | `/knowledge/stats` | 索引统计 |
| GET | `/knowledge/sidebar-stats` | 侧栏精简统计 |
| GET | `/knowledge/articles` | 文章索引列表 |
| GET | `/knowledge/articles/{name}/chunks` | 文章切片详情 |
| POST | `/knowledge/reindex-post/{name}` | 单篇重建 |
| GET | `/knowledge/reindex-post/{name}/progress` | 单篇进度 |
| POST | `/knowledge/clear-post/{name}` | 清除单篇索引 |
| POST | `/knowledge/summarize` | 生成单篇摘要 |
| POST | `/knowledge/summarize-all` | 批量生成摘要 |
| GET | `/knowledge/excerpts` | 摘要列表（旧分页） |
| GET | `/knowledge/excerpts/all` | 摘要完整分页列表 |
| POST | `/knowledge/excerpts/generate` | 单篇生成摘要 |
| POST | `/knowledge/excerpts/toggle-auto` | 设置文章自动摘要 |
| POST | `/knowledge/excerpts/clear` | 清除单篇摘要 |
| POST | `/knowledge/excerpts/batch-generate` | 批量生成 |
| POST | `/knowledge/excerpts/batch-clear` | 批量清除 |
| POST | `/knowledge/excerpts/clear-all` | 清除全部摘要 |

分页列表常用参数包括 `page`、`size`、`sort`、`keyword`。批量端点接收 JSON 字符串体，前端当前使用 `postName`、`postNames` 和 `enabled` 等字段；外部集成应锁定插件版本。

## 脑图

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/mindmap/articles` | 脑图文章列表 |
| POST | `/mindmap/generate` | 生成单篇 |
| POST | `/mindmap/regenerate` | 重新生成 |
| POST | `/mindmap/clear` | 清除单篇 |
| POST | `/mindmap/batch-generate` | 批量生成 |
| POST | `/mindmap/batch-clear` | 批量清除 |
| POST | `/mindmap/jobs/generate-all` | 启动全量后台任务 |
| GET | `/mindmap/jobs/{jobId}` | 查询任务 |
| POST | `/mindmap/jobs/{jobId}/cancel` | 取消任务 |

文章列表参数：`page`、`size`、`sort`、`status`、`keyword`。全量任务可以通过 `scope` 选择缺失/过期范围。

## 写作

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/writing/assist` | 非流式写作辅助 |
| POST | `/writing/assist/stream` | SSE 写作辅助 |

请求体：

```json
{
  "text": "需要处理的文本",
  "action": "polish",
  "instruction": "语气更简洁"
}
```

支持的动作由编辑器当前版本定义，包括润色、续写、扩写、简化、译英和大纲。流式错误使用 `event:error`，随后发送 `[DONE]`。

## 效果评测

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/evaluations/template` | 默认数据集模板 |
| GET | `/evaluations/datasets` | 数据集列表 |
| GET | `/evaluations/datasets/{id}` | 数据集详情 |
| POST | `/evaluations/datasets` | 新建数据集 |
| PUT | `/evaluations/datasets/{id}` | 更新数据集 |
| DELETE | `/evaluations/datasets/{id}` | 删除数据集 |
| GET | `/evaluations/runs` | 运行记录 |
| GET | `/evaluations/runs/{runId}` | 报告详情 |
| GET | `/evaluations/runs/{runId}/status` | 任务进度 |
| DELETE | `/evaluations/runs/{runId}` | 删除运行记录 |
| POST | `/evaluations/run` | 提交评测 |

数据集请求：

```json
{
  "id": "my-dataset",
  "name": "核心问答",
  "description": "发布前回归",
  "cases": [
    {
      "id": "case-1",
      "question": "问题",
      "referenceAnswer": "参考答案",
      "expectedSources": ["文章标题"],
      "tags": ["事实问答"]
    }
  ]
}
```

提交运行：`{"name":"发布前回归","datasetId":"my-dataset","cases":[]}`。服务返回 `runId` 和 `running`，随后轮询 status。

## 意图路由

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/intent-routes` | 列表 |
| GET | `/intent-routes/{id}` | 详情 |
| POST | `/intent-routes` | 新建 |
| PUT | `/intent-routes/{id}` | 更新，路径 ID 覆盖 body ID |
| DELETE | `/intent-routes/{id}` | 删除，内置路由不可删 |
| POST | `/intent-routes/{id}/preview` | 试跑 Pipeline |
| POST | `/intent-routes/generate` | 根据自然语言生成未保存、默认关闭的路由草稿 |
| POST | `/intent-routes/simulate` | 使用未保存草稿模拟执行 Pipeline |

保存请求字段包括 `id`、`displayName`、`description`、`enabled`、`priority`、`triggerPatterns`、`llmFallback`、`llmFallbackHint`、`pipeline` 和 `outputTemplate`。其中 `outputTemplate` 是兼容字段，0.3.2 的确定性导语和 `structured_result` 卡片不读取它。预览请求为 `{"query":"测试问题"}`，含 LLM 的处理器会真实产生费用。

生成请求为 `{"requirement":"查找旅行分类中最热门的 10 篇文章"}`。模拟请求为 `{"draft":{...保存请求字段...},"query":"推荐热门旅行文章"}`。两个接口都不会自动保存或启用路由。

## 用量

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/usage/today` | 今日概览 |
| GET | `/usage/stats` | 区间统计 |
| GET | `/usage/calls` | 调用明细 |
| GET | `/usage/failure-diagnostics` | 失败原因聚合诊断 |
| GET | `/usage/limits` | 当前限制 |
| POST | `/usage/limits` | 保存限制 |
| GET | `/usage/cleanup` | 读取用量清理配置 |
| POST | `/usage/cleanup/hidden` | 保存隐藏模型列表 |
| POST | `/usage/cleanup/merge` | 合并历史模型用量 |
| POST | `/usage/cleanup/delete` | 删除历史模型用量 |

`/usage/calls` 支持 `model`、`type`、`scenario`、`status`、`sort`、`page`、`size`、`start`、`end`。`/usage/stats` 支持 `range` 或自定义日期。`/usage/failure-diagnostics` 支持 `start`、`end` 和可选 `model`，日期范围不能超过调用明细保留窗口。

限制请求主要结构：

```json
{
  "enabled": true,
  "chatModelLimits": { "deepseek-chat": 1000000 },
  "visitor": {
    "enabled": true,
    "dailyLimit": 50,
    "hourlyLimit": 10,
    "whitelist": ["127.0.0.1"]
  }
}
```

用量清理接口用于处理模型资源重命名、测试模型污染统计或隐藏已废弃模型：

```jsonc
// POST /usage/cleanup/hidden
{ "hiddenModels": ["old-model"] }

// POST /usage/cleanup/merge
{ "sourceModel": "old-model", "targetModel": "new-model", "start": "2026-06-01", "end": "2026-06-26" }

// POST /usage/cleanup/delete
{ "model": "test-model", "start": "2026-06-01", "end": "2026-06-26" }
```

## 问答日志

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/chat-logs` | 分页筛选 |
| GET | `/chat-logs/stats` | 统计 |
| GET | `/chat-logs/{id}` | 详情 |
| DELETE | `/chat-logs/{id}` | 删除单条 |
| POST | `/chat-logs/clear` | 清理记录 |

列表参数：`page`（从 0 开始）、`size`（最大 100）、`from`、`to`、`model`、`feedbackType`、`question`。

## 运营智能体

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/agent/content-gap/run` | 提交内容缺口任务 |
| GET | `/agent/tasks` | 任务列表 |
| GET | `/agent/tasks/{taskId}` | 任务详情 |
| DELETE | `/agent/tasks/{taskId}` | 删除任务记录 |

提交示例：

```json
{ "days": 30, "maxLogs": 80, "maxTokens": 5000 }
```

任务在后台异步运行，详情包含进度、步骤、结果或失败信息。

## 调用建议

- Console 内部调用优先复用 Halo 当前认证会话。
- 外部自动化不要使用匿名权限访问 Console API。
- 删除、清空、批量生成和重建属于有副作用操作，先在测试环境验证。
- `v1alpha1` 响应允许新增字段，客户端解析应保持前向兼容。
