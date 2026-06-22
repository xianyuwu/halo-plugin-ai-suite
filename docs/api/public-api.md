# Public API

> 前缀：`/apis/api.ai-suite.halo.run/v1alpha1`  
> 认证：匿名，仅限 RoleTemplate 放行范围

## 路由表

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/chat/stream` | SSE 访客问答 |
| POST | `/chat` | 非流式访客问答 |
| POST | `/chat/feedback` | 提交点赞/点踩 |
| GET | `/chat/feedback` | 旧前端反馈兼容 |
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
  ]
}
```

限制：`message` 4000 字符，历史最多 20 项，每项内容 4000 字符。响应见 [SSE 协议](sse-protocol.md)。后台关闭访客使用时会发送 `event:error` 和 `[DONE]`。

## POST `/chat`

请求体与流式接口相同。成功响应为 JSON，包含回答文本和引用；具体字段可能随 `v1alpha1` 演进，调用方应容忍新增字段。

## `/chat/feedback`

参数通过查询字符串传递：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `logId` | 是 | 流式聊天返回的日志 ID，最多 80 字符 |
| `type` | 是 | `like` 或 `dislike` |
| `comment` | 否 | 反馈补充，最多 200 字符 |

```bash
curl -X POST \
  'https://YOUR_DOMAIN/apis/api.ai-suite.halo.run/v1alpha1/chat/feedback?logId=LOG_ID&type=dislike&comment=引用不正确'
```

新客户端使用 POST；GET 仅用于旧前端兼容。

## GET `/widget-config`

返回访客端可公开读取的聊天、搜索和外观配置。响应不包含 API Key。前端应为缺失字段提供兼容默认值。

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

查询参数：`postName`。返回已生成的脑图 Markdown/缓存信息；没有缓存、功能关闭或参数缺失时按 HTTP 状态与错误体处理。该接口不触发实时模型生成。

## 权限验证

公开接口不应需要管理员 Cookie；未列出的 Console API 也不应被匿名访问。RoleTemplate 同时使用资源授权和精确 non-resource URL 授权 POST SSE 路由。
