# API 总览

## 前缀与认证

| 类型 | 前缀 | 认证 |
| --- | --- | --- |
| Public API | `/apis/api.ai-suite.halo.run/v1alpha1` | 匿名 RoleTemplate 精确授权 |
| Public Chat 兼容 API | `/apis/api.ai-suite.halo.run/v1alpha2` | 匿名；聊天、非流式聊天与 Widget 配置 |
| Console API | `/apis/console.api.ai-suite.halo.run/v1alpha1` | Halo 管理员会话/认证 |

Public API 只用于访客 Widget、搜索和脑图读取。配置、索引、用量、日志、评测和任务接口都属于 Console API，不应匿名开放。

## 内容类型

- 普通请求与响应：`application/json`
- 流式响应：`text/event-stream`
- POST JSON：请求头使用 `Content-Type: application/json`

## 错误结构

多数 JSON 错误使用：

```json
{ "error": "可读错误消息" }
```

部分公开 SSE 为保持统一解析，会返回 HTTP 200，并把友好错误作为流事件发送，最后追加 `[DONE]`。调用方必须同时处理 HTTP 状态、SSE error、连接中断和业务错误文本。

## 分页

不同历史模块的分页起始值并不完全一致：问答日志使用 `page=0`，知识库/脑图/用量页面多使用 `page=1`。调用时以具体端点表为准，不要假设全局统一。

## 稳定性

主要 API 版本为 `v1alpha1`，仍可能发生兼容性变化。`v1alpha2` 只用于聊天热更新兼容，不代表整套 API 已升级。外部集成应锁定插件版本，并在升级前核对当前版本能力清单。

## 参考

- [Public API](public-api.md)
- [Console API](console-api.md)
- [SSE 协议](sse-protocol.md)
