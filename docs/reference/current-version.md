# 当前版本能力清单

> 适用版本：AI 智能套件 0.3.x、Halo 2.25+
> 更新日期：2026-07-04

本文是 0.3.x 系列的版本事实入口。README 负责产品介绍，具体操作方法在用户手册，本页用于快速确认“当前版本到底包含什么”。补丁版本只包含缺陷修复时，不重复调整整套文档版本口径。

## 运行边界

| 项目 | 当前状态 |
| --- | --- |
| 插件 ID | `ai-suite` |
| 最低 Halo 版本 | `2.25.0` |
| 模型基础设施 | 强依赖 Halo AI Foundation |
| Java | 21 |
| 检索引擎 | Halo 内置 Lucene 10.3.2 + SmartCN |
| 配置存储 | ConfigMap `ai-suite-configmap`；用量按日期写入 `ai-suite-usage-YYYY-MM-DD` |
| 公开 API | `v1alpha1`，聊天另提供 `v1alpha2` 热更新兼容入口 |
| Console API | `v1alpha1` |

模型供应商、Base URL、API Key 和默认模型由 AI Foundation 管理。AI 智能套件只选择业务使用的模型资源，并负责 RAG、意图、内容生成、用量和前端体验。

## 当前功能

### 访客侧

- RAG 多轮问答、SSE 流式输出、引用和反馈。
- 可由访客逐次选择的“深度思考”；推理内容默认折叠，可展开查看。
- 结构化快捷问题，可直接绑定已启用意图路由。
- AI 搜索综合回答和 Lucene 关键词结果。
- 文章 AI 脑图与主题无关的前台注入。
- 意图命中后的结构化文章卡片。

### Console 与编辑器

- AI Foundation Chat、Embedding、Rerank、Query Rewrite 模型选择与测试。
- 切片、索引、混合检索、Query Rewrite、HyDE、Rerank、跨语言检索。
- 对话提示词、深度思考策略、快捷问题和 Widget 外观。
- AI 摘要、文章脑图、编辑器多轮写作和大纲生成。
- 问答日志、反馈、Trace、效果评测、用量与限额。
- 内容缺口运营智能体。
- 手工编辑意图路由，以及通过自然语言生成、检查、模拟和保存路由草稿。

## 0.3.2 的关键变化

1. 模型调用统一迁移到 Halo AI Foundation，移除插件内供应商直连配置。
2. 新增深度思考控制、结构化推理 SSE 事件和 `<think>` / `<reasoning>` 兼容解析。
3. 新增访客端折叠推理面板，未开启时不会把供应商推理文本混入最终回答。
4. 快捷问题升级为可编辑卡片，并支持 `intentRouteId` 直达意图。
5. 意图路由支持自然语言生成草稿和未保存草稿模拟。
6. 意图文章结果可直接返回可信的结构化卡片，减少不必要的模型排版调用。
7. 用量页增加失败诊断和历史模型清理、合并、隐藏能力。

## 已知兼容说明

- `v1alpha2` 聊天接口用于插件热更新时避开 Halo 仍持有旧 `v1alpha1` RouterFunction 的情况；正常安装或重启后两个版本都可用。
- 深度思考是否真正生效取决于选中的 AI Foundation 模型及供应商适配。若供应商拒绝显式推理参数，客户端会降级重试。
- 部分供应商把推理放在结构化字段中，部分会输出 `<think>` 标签；当前版本同时兼容两种形式。
- Token 精确度取决于供应商是否通过 AI Foundation 返回 usage。
- 更换 Embedding 模型或向量维度后必须全量重建索引。

## 文档入口

- [安装与首次配置](../getting-started/installation.md)
- [模型、切片与检索配置](../user-guide/models-and-retrieval.md)
- [访客问答、深度思考与浮窗](../user-guide/rag-chat.md)
- [意图路由使用手册](../user-guide/intent-routing.md)
- [API 总览](../api/overview.md)
- [配置参考](configuration-reference.md)
- [兼容矩阵](compatibility-matrix.md)
