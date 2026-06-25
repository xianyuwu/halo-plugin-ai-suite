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

模型供应商、Base URL、API Key 和默认模型由 Halo AI Foundation 管理。旧 `ai-suite-api-keys` Secret 不再读取。

## 何时重建索引

必须重建：Embedding 模型、向量维度或索引结构变化。建议重建：切片规则大幅调整、Lucene 版本变化或统计不一致。仅 Chat 模型、Prompt、UI 主题变化不需要重建。

## 回滚

1. 禁用新插件。
2. 恢复上一版本 JAR。
3. 恢复升级前 AI 智能套件 ConfigMap、AI Foundation 配置和业务 Extension。
4. 必要时恢复 Halo 数据目录中的索引。
5. 启用旧插件并验证状态、配置和公开 API。

不同 Lucene 版本生成的索引不一定能互相读取；最安全的回滚方式是恢复对应数据快照，或在旧版本下重新建立索引。
