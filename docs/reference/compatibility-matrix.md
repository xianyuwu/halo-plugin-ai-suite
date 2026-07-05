# 兼容矩阵

| 组件 | 当前要求 | 说明 |
| --- | --- | --- |
| AI 智能套件 | 0.3.2 | 文档当前适用版本 |
| Halo | 2.25.0+ | `plugin.yaml` 声明 `>=2.25.0` |
| Halo AI Foundation | 必需 | `plugin.yaml` 声明 `pluginDependencies.ai-foundation` |
| Java | 21 | 构建与本地运行要求 |
| Lucene | 10.3.2 | 必须与 Halo 2.25 内置版本对齐 |
| Node.js | 20+ | Console 构建 |
| pnpm | 9+ | Console 依赖管理 |
| 浏览器 | 支持 fetch/ReadableStream | POST SSE 客户端 |

## 模型能力

- 语言模型、Embedding、Rerank 和结构化/流式输出由 Halo AI Foundation 统一适配。
- AI 智能套件只保存业务使用的 AI Foundation 模型资源名和生成参数。
- 字段为空时使用 AI Foundation 的默认模型；查询改写和写作模型为空时复用语言模型。
- 不同厂商对 usage、SSE、JSON 输出和 Rerank 支持可能不同，必须先在 AI Foundation 和 AI 智能套件后台连接测试中验证。
- 推理模型可能返回结构化 reasoning 字段，也可能在文本中输出 `<think>` / `<reasoning>`；0.3.2 会统一解析，并在关闭深度思考时过滤意外透传内容。
- 显式推理参数被供应商拒绝时会自动降级重试，因此“请求成功”不等于模型一定提供了可展示的推理过程。

## 功能依赖矩阵

插件强依赖 Halo AI Foundation 提供模型能力；不同功能还会依赖 Halo 核心能力或站点前台入口。

| 功能 | 是否依赖其他 Halo 插件 | 必需依赖 | 说明 |
| --- | --- | --- | --- |
| 访客问答浮窗 | 依赖 AI Foundation | Halo `AdditionalWebFilter`、匿名 RoleTemplate、AI Foundation 语言模型 | 通过全局 WebFilter 注入，不要求主题模板改造。 |
| RAG 索引 | 依赖 AI Foundation | Halo `Post`、`PostContentService`、AI Foundation Embedding 模型、Halo 内置 Lucene 10.3.2 | 只索引已发布公开文章。 |
| AI 搜索能力 | 依赖 AI Foundation | 插件 Lucene 索引、AI Foundation 语言模型 | 关键词结果和 AI 综合回答都由本插件接口提供，不依赖 Halo 官方搜索插件的检索能力。 |
| AI 搜索入口 | 访客使用时条件必需 | 默认安装并启用 Halo 官方搜索插件；若主题或自定义代码已经提供兼容搜索入口，可替代官方插件 | 未提供入口时接口和快捷键仍可能可用，但普通访客看不到搜索框，不能视为完整启用访客搜索。 |
| AI 摘要 | 依赖 AI Foundation | Halo 摘要扩展点、AI Foundation 语言模型 | 自动摘要接入 Halo `ExcerptGenerator` 扩展点，批量摘要直接写入文章摘要字段。 |
| 编辑器 AI 写作 | 依赖 AI Foundation | Halo Console 编辑器扩展点、AI Foundation 语言/写作模型 | 只支持 Halo 当前编辑器扩展点。 |
| 文章 AI 脑图 | 依赖 AI Foundation | Halo 文章内容、前台 Widget 注入、AI Foundation 语言模型 | 不依赖具体主题插件；极端自定义主题需验证文章上下文识别。 |
| 意图路由 | 部分能力依赖 AI Foundation | Halo `Post` / `Tag` / `Category` / `Counter`；LLM 兜底和含 LLM 的处理器需要 AI Foundation 语言模型 | 标签、分类、关键词、时间和浏览量等确定性 Pipeline 可直接返回结构化卡片；热门排序使用 Halo 核心 Counter 数据。 |
| 运营智能体 | 依赖 AI Foundation | ChatLog、文章数据、AI Foundation 语言模型 | 有足够访客问答记录后，内容缺口分析更有价值。 |
| 用量统计与限流 | 依赖 AI Foundation | 插件内部记录、AI Foundation usage 返回 | token 统计准确性取决于模型服务是否返回标准 usage 字段。 |

## Halo 升级

升级 Halo 前必须确认其内置 Lucene 版本。Lucene Core 跨版本或跨 ClassLoader 不兼容可能导致 Codec 类型错误。没有明确兼容声明时，先在测试环境验证插件加载、索引读取和重建。
