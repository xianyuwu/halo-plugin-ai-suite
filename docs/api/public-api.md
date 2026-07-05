# Public API

> 前缀：`/apis/api.ai-suite.halo.run/v1alpha1`  
> 认证：匿名，仅限 RoleTemplate 放行范围

聊天另提供同路径的 `v1alpha2` 兼容端点。它用于 Halo 插件热更新后旧 `v1alpha1` RouterFunction 尚未释放的场景；完整重启后两者行为一致。反馈接口仍只在 `v1alpha1`。

## 路由表

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/chat/stream` | SSE 访客问答 |
| POST | `/chat` | 非流式访客问答 |
| POST | `/chat/feedback` | 提交点赞/点踩 |
| GET | `/widget-config` | 读取访客浮窗配置 |
| GET | `/search/halo-results` | 插件 Lucene 关键词结果 |
| POST | `/search/answer` | SSE AI 搜索回答 |
| GET | `/mindmap` | 读取文章脑图缓存 |

## POST `/chat/stream`

```json
{
  "message": "站内有哪些 AI 文章？",
  "history": [
    { "role": "user", "content": "上一轮问题" },
    { "role": "assistant", "content": "上一轮回答" }
  ],
  "intentRouteId": "builtin-hot-articles",
  "reasoningEnabled": true
}
```

限制：`message` 4000 字符，历史最多 20 项，每项内容 4000 字符。`intentRouteId` 可选，仅用于快捷入口直达已启用的意图；`reasoningEnabled` 可选，仅在后台允许访客选择时生效。响应见 [SSE 协议](sse-protocol.md)。后台关闭访客使用时会发送 `event:error` 和 `[DONE]`。

## POST `/chat`

请求体沿用流式接口的 `message`、`history` 和可选 `intentRouteId`，但当前非流式接口不处理 `reasoningEnabled`；需要深度思考时应使用 `/chat/stream`。

成功响应为 JSON：

```json
{
  "reply": "回答文本",
  "citations": [
    {
      "postId": "post-name",
      "title": "文章标题",
      "url": "/archives/example"
    }
  ],
  "structuredResult": {
    "type": "article-list",
    "variant": "ranking",
    "items": []
  }
}
```

`structuredResult` 仅在意图 Pipeline 返回结构化数据时出现。后台关闭访客使用时，接口仍返回 HTTP 200，响应体为 `{"error":"访客聊天功能已关闭"}`。调用方应容忍后续版本新增字段。

## `/chat/feedback`

参数通过查询字符串传递：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `logId` | 是 | 流式聊天返回的日志 ID，最多 80 字符 |
| `type` | 否 | `like` 或 `dislike`；缺省时按 `like` 处理 |
| `comment` | 否 | 反馈补充，最多 200 字符 |

```bash
curl -X POST \
  'https://YOUR_DOMAIN/apis/api.ai-suite.halo.run/v1alpha1/chat/feedback?logId=LOG_ID&type=dislike&comment=引用不正确'
```

反馈只接受 POST 请求，GET 兼容端点已移除。

## GET `/widget-config`

返回访客端可公开读取的聊天、搜索和外观配置。`shortcuts` 为结构化数组，每项包含 `label`、`query`、`icon` 和可选 `intentRouteId`；深度思考相关字段为 `allowVisitorReasoning` 和 `reasoningDefaultEnabled`。响应不包含 API Key。前端应为缺失字段提供兼容默认值。

## GET `/search/halo-results`

查询参数：`keyword`。返回：

```json
{
  "total": 1,
  "articles": [
    {
      "postId": "post-name",
      "title": "文章标题",
      "url": "/archives/example",
      "snippet": "包含 <mark>关键词</mark> 的摘要",
      "score": 1.23
    }
  ],
  "source": "lucene"
}
```

`snippet` 只允许受控的 `<mark>` 高亮；外部调用方仍应按自身渲染策略清洗 HTML。

## POST `/search/answer`

```json
{ "keyword": "Halo 插件开发" }
```

关键词最多 500 字符。响应为 citations、JSON token 和 `[DONE]`，不发送 `logId`。

## GET `/mindmap`

查询参数：`postName`。接口只读取已生成且仍有效的脑图缓存，不触发实时模型生成。

成功响应：

```json
{
  "markdown": "# 文章标题\n## 核心主题",
  "cached": true
}
```

参数缺失、文章不存在、功能关闭、缓存尚未生成或已经失效时，当前实现统一返回 HTTP 200，并通过业务错误体说明原因：

```json
{
  "error": "生成失败：思维导图尚未生成"
}
```

因此调用方不能只根据 HTTP 状态判断成功，还必须检查响应体是否包含 `error`。

## 权限验证

公开接口不应需要管理员 Cookie；未列出的 Console API 也不应被匿名访问。RoleTemplate 同时使用资源授权和精确 non-resource URL 授权 POST SSE 路由。
