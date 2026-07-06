# 升级与迁移

> 适用读者：生产环境运维人员

## 升级前

1. 阅读目标版本 Release Notes 和兼容矩阵。
2. 记录当前 Halo、插件、Lucene 和模型配置。
3. 备份 Halo 数据目录、数据库、AI 智能套件 ConfigMap、AI Foundation 配置和自定义 Extension。
4. 导出或截图关键配置与索引统计。
5. 保留上一版本插件 JAR。

## 升级流程

1. 在测试环境安装新版本。
2. 确认插件状态为 `STARTED`。
3. 验证 AI 智能套件 ConfigMap 与 AI Foundation 模型配置已正确读取。
4. 检查意图路由、评测集和历史记录仍存在。
5. 按版本说明决定是否重建索引。
6. 执行后台调试、匿名聊天、搜索和写作验收。
7. 再升级生产环境。

## 配置资源

AI 智能套件当前只保存业务配置：

- ConfigMap：`ai-suite-configmap`
- 用量历史：`ai-suite-usage-YYYY-MM-DD`（按日期保存，默认恢复/查询窗口为最近 30 天）

模型供应商、Base URL、API Key 和默认模型由 Halo AI Foundation 管理。旧 `ai-suite-api-keys` Secret 不再读取。

## 升级到 0.3.2

- Halo 必须为 2.25.0 或更高版本，并已安装 AI Foundation。
- 旧版插件内 Base URL、API Key 和供应商配置不再生效，应先在 AI Foundation 中重建模型资源。
- `shortcutQuestions` 可以继续读取，保存后会逐步转换为结构化 `shortcutItems`。
- 旧 `reasoningMode=enabled` 会兼容为“默认开启深度思考”。建议在“对话与外观”重新确认访客权限与默认值。
- 插件热更新后若旧聊天路由仍被 Halo 缓存，可临时使用 `v1alpha2`；生产升级建议完整重启 Halo。
- Embedding 模型或维度发生变化时必须全量重建索引。

## 升级到 0.3.4

- 最低运行版本仍为 Halo 2.25.0；当前按 AI Foundation `1.0.0-beta.4` 的公开 API 编译并完成验证。Halo 2.25 暂不支持在插件依赖表达式中锁定预发布版本，因此升级 AI Foundation 后需重新执行兼容验证。
- 模型接入已改为 AI Foundation 公开类型化 SDK，不再反射调用 API，也不再读取 AI Foundation 内部 ConfigMap。
- Java 实现包名迁移到 `cn.rainwu.halo.ai.suite`；插件 ID、API Group、ConfigMap 和业务 Extension GVK 保持不变，不需要迁移现有业务数据。
- 脑图渲染依赖改为插件内本地构建，不再从 jsDelivr 或 unpkg 加载脚本、样式。
- 若同时更换 Embedding 模型或向量维度，升级后必须执行全量索引重建；仅从 0.3.3 升级且模型与维度未变化时无需重建。

## 何时重建索引

必须重建：Embedding 模型、向量维度或索引结构变化。建议重建：切片规则大幅调整、Lucene 版本变化或统计不一致。仅 Chat 模型、Prompt、UI 主题变化不需要重建。

## 回滚

1. 禁用新插件。
2. 恢复上一版本 JAR。
3. 恢复升级前 AI 智能套件 ConfigMap、需要保留的用量 ConfigMap、AI Foundation 配置和业务 Extension。
4. 必要时恢复 Halo 数据目录中的索引。
5. 启用旧插件并验证状态、配置和公开 API。

不同 Lucene 版本生成的索引不一定能互相读取；最安全的回滚方式是恢复对应数据快照，或在旧版本下重新建立索引。
