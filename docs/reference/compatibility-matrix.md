# 兼容矩阵

| 组件 | 当前要求 | 说明 |
| --- | --- | --- |
| AI 智能套件 | 0.3.0 | 文档当前适用版本 |
| Halo | 2.25.0+ | `plugin.yaml` 声明 `>=2.25.0` |
| Java | 21 | 构建与本地运行要求 |
| Lucene | 10.3.2 | 必须与 Halo 2.25 内置版本对齐 |
| Node.js | 20+ | Console 构建 |
| pnpm | 9+ | Console 依赖管理 |
| 浏览器 | 支持 fetch/ReadableStream | POST SSE 客户端 |

## 模型协议

- Chat：OpenAI Chat Completions 兼容协议。
- Embedding：OpenAI Embeddings 兼容协议。
- Rerank：兼容当前 `LlmClient` 解析的结果结构。
- 不同厂商对 usage、SSE、错误结构和模型参数支持可能不同，必须使用后台连接测试验证。

## 功能依赖矩阵

插件依赖 Halo AI Foundation 提供模型能力；不同功能还会依赖 Halo 核心能力或站点前台入口。

| 功能 | 是否依赖其他 Halo 插件 | 必需依赖 | 说明 |
| --- | --- | --- | --- |
| 访客问答浮窗 | 否 | Halo `AdditionalWebFilter`、匿名 RoleTemplate、Chat 模型 | 通过全局 WebFilter 注入，不要求主题模板改造。 |
| RAG 索引 | 否 | Halo `Post`、`PostContentService`、Embedding 模型、Halo 内置 Lucene 10.3.2 | 只索引已发布公开文章。 |
| AI 搜索能力 | 否 | 插件 Lucene 索引、Chat 模型 | 关键词结果和 AI 综合回答都由本插件接口提供，不依赖 Halo 官方搜索插件的检索能力。 |
| AI 搜索入口 | 可选依赖 | 主题搜索入口、官方搜索插件入口或自定义入口三选一 | 如果页面没有搜索按钮，仍可用 `Ctrl/Cmd + K`、`/` 快捷键，或自定义按钮调用 `window.SearchWidget.open()`。 |
| AI 摘要 | 否 | Halo 摘要扩展点、Chat 模型 | 自动摘要接入 Halo `ExcerptGenerator` 扩展点，批量摘要直接写入文章摘要字段。 |
| 编辑器 AI 写作 | 否 | Halo Console 编辑器扩展点、Chat/写作模型 | 只支持 Halo 当前编辑器扩展点。 |
| 文章 AI 脑图 | 否 | Halo 文章内容、前台 Widget 注入、Chat 模型 | 不依赖具体主题插件；极端自定义主题需验证文章上下文识别。 |
| 意图路由 | 否 | Halo `Post` / `Tag` / `Category` / `Counter`、Chat 模型 | 热门文章排序使用 Halo 核心 Counter 数据；无浏览量数据时排序效果有限。 |
| 运营智能体 | 否 | ChatLog、文章数据、Chat 模型 | 有足够访客问答记录后，内容缺口分析更有价值。 |
| 用量统计与限流 | 否 | 插件内部记录、模型 usage 返回 | token 统计准确性取决于模型服务是否返回标准 usage 字段。 |

## Halo 升级

升级 Halo 前必须确认其内置 Lucene 版本。Lucene Core 跨版本或跨 ClassLoader 不兼容可能导致 Codec 类型错误。没有明确兼容声明时，先在测试环境验证插件加载、索引读取和重建。
