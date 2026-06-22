# ADR-002：ConfigMap 与 Secret 分离配置

- 状态：已采纳

## 背景

早期 `settings.yaml` 难以支持当前自定义 Console 配置和多模型密钥管理，API Key 也不应与普通配置混存。

## 决策

普通配置按组序列化到 `ai-suite-configmap`；Chat、Embedding、Rerank、Query Rewrite 和 Writing API Key 保存到 `ai-suite-api-keys`。`AIProperties` 统一读取并兼容旧 `ai-assistant-*` 名称。

## 后果

- 密钥与普通配置隔离。
- Console 可以独立演进表单。
- 备份恢复必须同时包含 ConfigMap 和 Secret。
- Service 层必须负责默认值、迁移和错误回退。
