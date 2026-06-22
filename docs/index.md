# AI 智能套件文档中心

> 适用版本：AI 智能套件 0.2.23、Halo 2.24+

这里是 AI 智能套件的完整文档入口。根目录的 `README.md` 用于介绍产品和帮助第一次安装；本目录负责操作手册、生产运维、系统架构、API 和二次开发。

![AI 智能套件系统架构](../assets/readme/system-architecture.svg)

## 按你的目标开始

| 我想要…… | 从这里开始 |
| --- | --- |
| 第一次安装插件 | [安装与首次配置](getting-started/installation.md) |
| 完成第一次 RAG 问答 | [第一次 RAG 问答](getting-started/first-rag-chat.md) |
| 部署到生产环境 | [生产部署](operations/production-deployment.md) |
| 配置自定义问答意图 | [意图路由使用手册](user-guide/intent-routing.md) |
| 理解 RAG 怎样工作 | [RAG 管线](architecture/rag-pipeline.md) |
| 理解整个系统 | [系统架构](architecture/overview.md) |
| 查询所有配置默认值 | [配置参考](reference/configuration-reference.md) |
| 对接流式接口 | [SSE 协议](api/sse-protocol.md) |
| 定位运行故障 | [故障排查](operations/troubleshooting.md) |

## 文档地图

![文档体系地图](diagrams/exported/documentation-map.svg)

## 已完成文档

### 快速开始

- [安装与首次配置](getting-started/installation.md)
- [第一次 RAG 问答](getting-started/first-rag-chat.md)

### 用户手册

- [意图路由使用手册](user-guide/intent-routing.md)

### 生产运维

- [生产部署](operations/production-deployment.md)
- [故障排查](operations/troubleshooting.md)

### 系统架构

- [系统架构总览](architecture/overview.md)
- [RAG 管线](architecture/rag-pipeline.md)
- [意图路由架构](architecture/intent-routing.md)

### API 与参考资料

- [SSE 协议](api/sse-protocol.md)
- [配置参考](reference/configuration-reference.md)

## 后续文档路线

以下内容会在后续批次继续补齐：

- AI 搜索、脑图、摘要、写作辅助、效果评测和运营智能体使用手册
- 索引生命周期、数据存储、安全与限流架构
- 完整 Public API 与 Console API 参考
- 本地开发、测试、Widget 开发和新增 Pipeline Processor 教程
- 升级迁移、备份恢复、监控与发布流程
- 架构决策记录（ADR）

## 文档事实来源

当描述与代码不一致时，按以下优先级核对：

1. Endpoint、Service、`AIProperties` 等当前源码。
2. `plugin.yaml`、RoleTemplate 和 Gradle 配置。
3. 本目录中的版本化文档。
4. 根目录 `README.md`。

`AGENTS.md` 是开发协作说明，`CLAUDE.md` 是历史开发笔记，都不作为公开功能和 API 的最终事实来源。

## 文档贡献规范

新增或修改文档前，请阅读 [文档写作规范](contributing-docs.md)。核心要求是：操作步骤必须可验证、配置必须写明默认值和生效条件、复杂流程必须配图、版本变化必须同步更新相关页面。
