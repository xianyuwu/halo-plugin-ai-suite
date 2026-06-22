# 兼容矩阵

| 组件 | 当前要求 | 说明 |
| --- | --- | --- |
| AI 智能套件 | 0.2.23 | 文档当前适用版本 |
| Halo | 2.24.0+ | `plugin.yaml` 声明 `>=2.24.0` |
| Java | 21 | 构建与本地运行要求 |
| Lucene | 10.3.2 | 必须与 Halo 2.24 内置版本对齐 |
| Node.js | 20+ | Console 构建 |
| pnpm | 9+ | Console 依赖管理 |
| 浏览器 | 支持 fetch/ReadableStream | POST SSE 客户端 |

## 模型协议

- Chat：OpenAI Chat Completions 兼容协议。
- Embedding：OpenAI Embeddings 兼容协议。
- Rerank：兼容当前 `LlmClient` 解析的结果结构。
- 不同厂商对 usage、SSE、错误结构和模型参数支持可能不同，必须使用后台连接测试验证。

## Halo 升级

升级 Halo 前必须确认其内置 Lucene 版本。Lucene Core 跨版本或跨 ClassLoader 不兼容可能导致 Codec 类型错误。没有明确兼容声明时，先在测试环境验证插件加载、索引读取和重建。
