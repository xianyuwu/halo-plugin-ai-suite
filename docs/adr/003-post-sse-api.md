# ADR-003：访客 SSE 使用 POST

- 状态：已采纳

## 背景

访客问题和多轮历史可能很长。使用 GET 查询字符串会泄露内容到 URL、日志和缓存，并受到请求行长度限制。

## 决策

聊天和 AI 搜索回答使用 POST JSON body，并通过浏览器 `fetch` + `ReadableStream` 解析 SSE。聊天旧 GET 路由已清理；反馈 GET 仅保留兼容。

## 后果

- 问题和历史不进入 URL。
- 不需要放宽 Netty 请求行长度。
- 不能使用原生 `EventSource`，需要自定义 SSE parser。
- 匿名 RoleTemplate 需要精确放行 POST non-resource URL。
