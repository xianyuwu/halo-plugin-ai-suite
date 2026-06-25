# ADR-005：统一采用 Halo AI Foundation

- 状态：已采纳

## 背景

Halo 官方提供 `plugin-ai-foundation`，用于统一管理模型供应商、语言模型、Embedding、Rerank、结构化输出和流式输出。AI 智能套件原先自带 OpenAI 兼容直连配置，导致模型供应商、密钥、Base URL 和业务能力混在同一个插件中维护。

双轨模型来源会增加排查成本：一次失败需要先判断调用是否走 AI 智能套件内置配置，还是走 AI Foundation。关键词提取、索引构建、写作和访客问答都容易被这种差异影响。

## 决策

AI 智能套件强依赖 Halo AI Foundation，不再提供内置 OpenAI 兼容模型配置。

- `plugin.yaml` 声明 `ai-foundation` 为必需依赖。
- 最低 Halo 版本提升到 `2.25.0`。
- AI 智能套件不再保存模型供应商 Base URL 或 API Key。
- 所有模型调用统一经过 `AiFoundationClient`。
- `LlmClient` 只保留访客限流和配额结算，不再实现供应商 HTTP 协议。

## 配置

AI 智能套件只保存业务使用的 AI Foundation 模型资源名：

```json
{
  "aiFoundationChatModelName": "",
  "aiFoundationEmbeddingModelName": "",
  "aiFoundationRerankModelName": "",
  "aiFoundationQueryRewriteModelName": "",
  "embeddingDimensions": 1024,
  "rerankEnabled": false,
  "queryRewriteEnabled": false
}
```

字段为空时使用 AI Foundation 默认模型，查询改写和写作模型为空时复用语言模型。

## 保留在 AI 智能套件内的能力

- Lucene 索引、文章切片、混合检索和引用组装。
- 访客端浮窗、搜索入口、脑图 Widget。
- 意图路由、PipelineProcessor、运营智能体。
- ChatLog、Trace、评测数据、限流和业务场景用量统计。

## 后果

- 模型供应商适配集中在 AI Foundation，AI 智能套件代码更直观。
- 用户必须先安装并配置 AI Foundation。
- 旧的 Base URL/API Key 配置不再被读取或迁移。
- 生产排障时先检查 AI Foundation 模型连通性，再检查 AI 智能套件业务链路。
