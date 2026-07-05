# 用量场景参考

> 事实来源：`llm/UsageScenario.java`

| 场景 | 业务含义 |
| --- | --- |
| `model_test` | 模型连接测试 |
| `visitor_qa` | 访客问答生成 |
| `hot_articles` | 旧热门文章兼容场景 |
| `search_answer` | AI 搜索综合回答 |
| `search_query_rewrite` | 查询改写 |
| `search_hyde` | HyDE 假设文档 |
| `search_cross_language` | 跨语言检索 |
| `search_embedding` | 问题 Embedding |
| `search_rerank` | 候选重排 |
| `index_embedding` | 建索引 Embedding |
| `keyword_extract` | 自动关键词提取 |
| `mindmap_generate` | 脑图生成 |
| `summary_generate` | 摘要生成 |
| `writing_assist` | 写作辅助与大纲 |
| `evaluation_answer` | 评测回答 |
| `evaluation_judge` | 评审模型评分 |
| `agent_content_gap` | 内容缺口运营智能体 |
| `intent_detect` | LLM 意图分类 |
| `intent_pipeline` | 意图 Pipeline 中含 LLM 的处理器，例如主题判断和标题过滤 |
| `intent_route_generate` | 根据自然语言生成意图路由草稿 |
| `unknown` | 无法识别的兼容场景 |

场景用于用量归因，不等同于模型名称。同一个模型可以同时承担多个场景。0.3.2 的意图结果由服务端直接生成导语、引用和结构化卡片，不会为了格式化回答额外产生 `intent_pipeline` 调用。
