# ADR-002：ConfigMap 与 Secret 分离配置

- 状态：已废弃，被 [ADR-005](005-ai-foundation-adapter.md) 取代

## 背景

早期 `settings.yaml` 难以支持当前自定义 Console 配置和多模型密钥管理，API Key 也不应与普通配置混存。

## 决策

历史版本曾将普通配置按组序列化到 `ai-suite-configmap`，并将 Chat、Embedding、Rerank、Query Rewrite 和 Writing API Key 保存到 `ai-suite-api-keys`。

当前版本不再由 AI 智能套件保存模型 API Key。模型供应商、Base URL、API Key 和默认模型统一由 Halo AI Foundation 管理。

## 后果

- AI 智能套件只需要备份 `ai-suite-configmap` 和自定义 Extension 数据。
- AI Foundation 的模型与密钥按 AI Foundation 自身的数据策略备份。
- 旧 `ai-suite-api-keys` Secret 不再读取。
