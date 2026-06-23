# 意图路由使用手册

> 适用读者：Halo 管理员、内容运营人员  
> 适用版本：AI 智能套件 0.2.23  
> 前置条件：插件已启用，Chat 模型可用

## 适用场景

意图路由适合需要读取站内实时文章数据、规则明确且可验证的问题：

- 最近发布了哪些 AI 文章？
- 哪些文章最热门？
- 给我看看 Java 标签下的文章。
- 列出某个分类中最新的十篇文章。

如果问题需要从文章正文中寻找知识答案，应该继续走 RAG，而不是为每个问题建立意图。

![意图路由管理页面](../../assets/readme/screenshots/console-intent-routes.jpg)

## 一次命中会发生什么

[![意图路由示例](../diagrams/exported/intent-example-flow.svg)](/diagrams/exported/intent-example-flow.svg)

## 路由字段

| 字段 | 作用 | 建议 |
| --- | --- | --- |
| ID | 路由唯一名称 | 使用小写字母、数字和连字符 |
| 显示名称 | Console 中可读名称 | 用业务语言命名 |
| 描述 | 说明适用范围 | 写清包含和不包含什么 |
| 启用 | 是否参与检测 | 调试完成后再开启 |
| 优先级 | 多路由同时命中时的顺序 | 越具体的路由优先级越高 |
| 触发规则 | 正则表达式，任一命中即可 | 避免过宽的单字触发词 |
| LLM 兜底 | 正则未命中时允许模型分类 | 只对难以正则表达的场景开启 |
| Pipeline | 有序文章处理步骤 | 先过滤，再排序和限制数量 |
| 输出模板 | 约束最终回答格式 | 为空时使用默认模板 |

## 新建路由示例：Java 教程推荐

### 1. 基本信息

```text
ID: java-tutorials
显示名称: Java 教程推荐
优先级: 100
启用: 先关闭
```

### 2. 触发规则

```text
Java.*教程
学习.*Java
Java.*入门
```

触发规则是 OR 关系。表达式使用部分匹配，不需要手工在两端添加 `.*`，除非你确实要表达中间存在任意内容。

### 3. Pipeline

推荐步骤：

```text
TOPIC_MATCH
  prompt=判断文章是否属于 Java 教程或 Java 学习资料
  aliases=Java=JVM,JDK,Spring
  candidateLimit=200
  limit=30

TIME_SORT
  order=desc
  limit=10
```

### 4. 输出模板

```text
请按从新到旧的顺序推荐文章。每篇使用“标题 + 一句话说明”的格式，
只允许使用候选文章，不要虚构不存在的文章。
```

### 5. 试跑并启用

在编辑页使用试跑功能，输入：

```text
我想系统学习 Java，有哪些教程？
```

检查每一步的输入、输出文章数和标题。结果正确后再开启路由。

## 处理器参数

### TOPIC_MATCH

| 参数 | 含义 |
| --- | --- |
| `prompt` | 提供给主题判断的额外规则 |
| `aliases` | 主题别名，使用 `AI=人工智能`，多组用分号分隔 |
| `candidateLimit` | 进入 LLM 判断的候选上限 |
| `limit` | 输出文章上限 |
| `onFailure` | `empty`（默认）或 `keep` |

### LLM_TITLE_FILTER

纯粹根据标题让 LLM 返回相关序号。它看不到完整正文，适合标题表达清楚的文章集合。

| 参数 | 含义 |
| --- | --- |
| `prompt` | 主题判断说明 |
| `limit` | 输出上限 |
| `onFailure` | 失败策略 |

### TAG_MATCH / CATEGORY_MATCH

| 参数 | 含义 |
| --- | --- |
| `mode` | `from_query` 从问题提取；`fixed` 使用固定值 |
| `tags` / `categories` | 固定模式使用的名称 |
| `onFailure` | 失败策略 |

### KEYWORD_MATCH

| 参数 | 含义 |
| --- | --- |
| `mode` | 从查询或固定参数取关键词 |
| `fields` | `title` 或标题/内容组合 |
| `keyword` | 固定关键词 |

它做字符串包含，不做语义理解。同义词需求优先使用 TOPIC_MATCH。

### TIME_SORT

| 参数 | 含义 |
| --- | --- |
| `order` | `desc` 最新优先；`asc` 最早优先 |
| `limit` | 排序后保留数量 |

发布时间为空的文章会排在末尾。

### VISIT_SORT

`limit` 控制保留数量，浏览量来自 Halo Counter。

## 优先级设计

[![意图优先级设计](../diagrams/exported/intent-priority.svg)](/diagrams/exported/intent-priority.svg)

如果两个路由都能匹配同一句话，只会返回排序后的第一个。优先级设计错误可能让通用意图“截胡”具体意图。

## LLM 兜底的成本与边界

只有正则全部未命中后，才会考虑 `llmFallback=true` 的候选。分类超时 2 秒，失败后自动回到 RAG。开启后会增加 `intent_detect` 模型调用，因此不要给所有路由无差别开启。

## 失败策略

处理器异常时默认输出空列表。这是为了避免过滤失败后把所有文章当成匹配结果。只有明确接受这种风险时才设置 `onFailure=keep`。

## 验证清单

- 目标问题能命中。
- 相似但不属于目标的问题不会误命中。
- 多路由冲突时优先级符合预期。
- 每一步输出数量合理。
- 最终回答只包含真实候选文章。
- 引用链接可以打开。
- 用量统计中能区分 `intent_detect` 与 `intent_pipeline`。

## 相关文档

- [意图路由架构](../architecture/intent-routing.md)
- [RAG 管线](../architecture/rag-pipeline.md)
- [故障排查](../operations/troubleshooting.md)
