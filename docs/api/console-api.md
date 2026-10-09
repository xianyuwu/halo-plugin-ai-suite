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
| POST | `/config/test-image` | 生图连通性测试，实际调用模型并可能计费 |
| POST | `/config/test-embedding` | Embedding 测试 |
| POST | `/config/test-rerank` | Rerank 测试 |
| POST | `/config/test-query-rewrite` | Query Rewrite 测试 |
| POST | `/config/test-jobs` | 提交后台模型测试，立即返回任务编号 |
| GET | `/config/test-jobs/{jobId}` | 查询当前管理员提交的测试任务 |
| POST | `/chat/debug/stream` | 带 Trace 的调试 SSE |

`/config/save` 接收以配置组为键的 JSON。字段、默认值和重建影响见 [配置参考](../reference/configuration-reference.md)。

### 后台模型测试

模型配置页的五类测试统一使用 `/config/test-jobs`，避免慢模型依赖浏览器到源站的一次长 HTTP 连接。旧的同步测试端点保留兼容；直接调用旧端点仍受代理等待限制。

提交示例：

```json
{
  "requestId": "b8ecf6a3-475f-4e7f-9ccd-890a11c8af60",
  "kind": "image",
  "model": "",
  "dimensions": 0
}
```

- `requestId`：客户端生成的 UUID，同时作为任务编号。当前管理员使用同一编号及相同参数重复提交时，在任务记录保留期间返回原任务；参数不同返回 HTTP 409。
- `kind`：`chat`、`queryRewrite`、`embedding`、`rerank` 或 `image`。
- `model`：AI Foundation 模型资源名；空值使用现有配置及默认槽逻辑。
- `dimensions`：Embedding 请求维度；未指定或为 0 时使用已保存的维度。

提交成功返回 HTTP 202，包含 `jobId` 和 `job`。前端每 2 秒查询一次，状态为 `pending`、`done` 或 `failed`。成功时 `job.result` 保留对应测试的 `model`、`reply`、`dimensions`、`requestedDimensions`、`relevanceScore` 或 `imageCount`；失败时返回 `job.error`。

任务在后台独立执行，保留请求认证上下文；关闭页面只停止查询。原供应商调用超时不变，任务另有 6 分钟总时限；全局最多同时执行 4 个测试，超限提交返回 HTTP 429。任务仅保存在本机内存中，完成记录保留最多 30 分钟，总历史上限 128 条（满时先移除最早完成的记录）。插件停止时清理任务；多实例部署需要请求粘性或后续共享存储支持。

提交结果不确定时，模型配置页保留请求编号，并只查询原任务，不自动重发 POST。页面刷新后通过当前标签页的 `sessionStorage` 恢复查询。连续 5 次查询异常或等待超过 7 分钟后，显示“状态待确认”，允许“继续查询”；用户主动“开始新测试”可能产生额外费用。HTTP 错误、空响应和非 JSON 响应显示中文说明，不将网络问题显示为模型连接失败，也不修改模型配置。HTTP 404 表示记录不存在或失效，无法据此推断供应商是否完成了调用。

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


## AI 交互宠物

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/pets/list` | 获取管理员可见的宠物草稿、已确认快照与候选 |
| GET | `/pets/presets` | 三套内置皮肤及资源预览 |
| GET | `/pets/policies` | 可选附件存储策略 |
| GET | `/pets/model-status` | 模型配置及文生图/图生图能力检查，不调用模型 |
| POST | `/pets/prompt-preview` | 预览服务端组合提示词，不调用模型 |
| POST | `/pets/generate` | multipart 新建母版任务 |
| POST | `/pets/{id}/regenerate-master` | multipart 重做母版，成功才更新草稿 |
| POST | `/pets/{id}/background/cleanup` | 母版 Alpha 橡皮擦清理任务 |
| POST | `/pets/{id}/rename` | 修改生成宠物名称，不调用模型 |
| POST | `/pets/{id}/background/approve` | 确认所见母版背景并发布 |
| POST | `/pets/{id}/background/reset` | 恢复原始母版并重新等待审核 |
| POST | `/pets/{id}/cleanup-background` | 兼容背景清理路径，仍需版本参数 |
| POST | `/pets/{id}/regenerate` | 整组局部表情合成任务 |
| POST | `/pets/{id}/expressions/generate` | 四帧或指定单帧的表情/轻动作候选任务 |
| POST | `/pets/{id}/expressions/{state}/approve` | 确认指定候选，仅替换该状态 |
| POST | `/pets/{id}/expressions/{state}/cleanup` | 指定候选 Alpha 清理任务 |
| POST | `/pets/{id}/expressions/{state}/reset` | 恢复原始候选，仍需审核 |
| GET | `/pets/jobs/{jobId}` | 查询异步任务状态及真实处理阶段 |
| DELETE | `/pets/{id}` | 移除宠物记录；带归属标记的无引用附件延迟回收 |

新建 multipart 字段为 file/name/style/withExpressions/customPrompt，照片限 JPEG/PNG/WebP、5MB。新前端传 style=soft-3d 或 pixel、withExpressions=false；chibi/flat 保留旧接口兼容。母版补充要求最多 500 字，表情最多 300 字，最终质量约束由服务端追加。

背景审核 JSON 必须携带 masterUrl 和整数 masterRevision，清理额外传 strokes 和/或 mask；字段缺失或母版已变化要求刷新。候选审核携带 candidateUrl，清理额外传 strokes 和/或 mask。表情生成 JSON 支持 centerX/centerY/width/height/feather/customPrompt/mode，mode=face 或 motion；state 可指定 blink/happy/sad/thinking，省略则四张。像素风不支持轻动作。

颜色清理选区为 `mask: {width, height, runs: [[start, length], ...]}`。runs 按原图行优先顺序编码，必须升序、不重叠、正长度且不越界；最多 100000 段，原图最多 16777216 像素，尺寸必须与当前版本图片完全一致。只清除所选 Alpha，保留 RGB 和未选像素，颜色选区不再次重采样像素风母版。旧请求缺 mask 时保留原橡皮擦行为；无选区无笔画拒绝执行，不调用图像模型。

改名入口接收 `{name: "新名称"}`，去除首尾空白后非空、最多20个Unicode码点，不含控制字符。仅更新记录及已有公开快照的名称，图片、候选、审核状态、ID和选用配置不变，不创建公开快照或调用模型。与同一宠物的生成/清理共享互斥，正在处理时拒绝并提示等待；成功返回 `{success: true, pet: ...}`，校验失败或宠物不存在沿用宠物接口约定，返回 `{success: false, message: "中文说明"}`。

异步接口返回 success/jobId；任务 status 为 pending/done/failed，附 progress.stage/currentState/completed/total/completedStates/elapsedSeconds。任务不存在或过期返回 success=false/code=JOB_GONE/message，客户端应停止等待、重新读取宠物列表，禁止自动再次付费。历史保留 30 分钟、最多 128 条，运行最多 16 个；整体任务最长 25 分钟。服务停止取消本地任务订阅，供应商已受理的生成可能继续且仍产生费用；本接口不提供供应商任务取消保证。

全部修改与删除按宠物互斥；确认之前继续公开已确认快照，新建待审核母版不公开。删除记录不强制更改聊天配置，前台找不到该宠物时回退静态入口。附件回收仅处理归属明确、超过一小时且无草稿/快照/恢复/候选引用的文件；历史无标记附件保留。详细约定见 [宠物开发文档](../development/widget-development.md)。

### 背景重编辑与边缘优化

- POST `/pets/{id}/expressions/{state}/edit`：`{candidateUrl}`为当前状态图片URL，创建不影响公开版本的编辑副本；已有待审核图或版本不符时拒绝。
- POST `/pets/{id}/expressions/{state}/cancel-edit`：`{candidateUrl}`为副本当前URL，仅移除editingExisting副本，保持已发布图片。
- POST `/pets/{id}/background/edge-preview`、`/pets/{id}/background/edge-apply`：`{state, url, revision, shrink, dewhite}`，state为空表示母版，否则表示候选；revision用于母版版本校验，shrink为0–2像素、dewhite为0–100且至少一项非零。预览返回`{success,image}`（PNG data URL），不上传或修改记录；应用返回`{success,pet}`，候选需确认才发布，母版需重新验收。失败沿用success=false/message格式。
