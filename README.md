# AI 智能套件（plugin-ai-suite）

为 [Halo](https://halo.run) 2.24+ 博客提供 AI 智能问答（RAG）、搜索增强、AI 写作辅助、文章摘要、内容缺口检测等一体化 AI 能力，支持对接任意 OpenAI 兼容协议的大模型厂商。

## 功能特性

### 访客侧（前台主题页）

- **AI 智能问答浮窗**：访客在博客任意页面与 AI 多轮对话，基于全站文章内容（RAG 检索增强）回答问题，支持引用溯源
- **搜索 AI 综合回答**：搜索结果页展示 AI 基于检索结果的综合回答卡片
- **文章 AI 脑图**：文章页自动生成/缓存思维导图，markmap 可视化

### 管理侧（Console 后台）

- **AI 写作辅助**：文章编辑器内嵌，选中文字即可润色 / 续写 / 扩写 / 简化 / 译英，支持多轮对话式修改
- **文章大纲生成**：编辑器工具栏一键生成文章大纲并插入
- **AI 摘要生成**：批量或单篇为文章生成 AI 摘要（Halo excerpt 扩展点实现）
- **知识库管理**：文章索引状态查看、单篇/全量重建、关键词提取
- **模型用量统计**：按模型/日期统计 token 用量与调用次数，支持每日 token 限额与访客 IP 限流
- **对话日志**：访客问答全量记录、点赞/点踩反馈分析、检索链路追踪
- **评测系统**：用例集管理 + 一键评测（检索命中率、回答质量多维评分）
- **Agent 内容缺口检测**：基于对话日志自动分析知识盲区，给出待补内容建议

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 21 · Spring WebFlux · Halo Plugin API 2.24.0 |
| 前端 Console | Vue 3 · TypeScript · Vite · Tiptap |
| 前台 Widget | 原生 JS/CSS（注入主题页面） |
| 检索引擎 | Apache Lucene 10.3.2（BM25 关键词 + HNSW 向量混合检索） |
| 中文分词 | Lucene SmartChineseAnalyzer |
| 构建 | Gradle 9.4 · pnpm |

## 环境要求

- Halo >= 2.24.0
- JDK 21（构建用）
- Node.js 20+ / pnpm 9+（构建前端用）
- 一个 OpenAI 兼容的 LLM 服务（支持 Embedding + Chat，可选 Rerank）

## 安装

### 方式一：下载预构建产物

从 [Releases](https://github.com/rainwu/plugin-ai-suite/releases) 下载 `plugin-ai-suite-*.jar`，在 Halo 后台「插件管理」→ 安装 → 上传 jar。

### 方式二：源码构建

```bash
# 必须用 JDK 21
git clone https://github.com/rainwu/plugin-ai-suite.git
cd plugin-ai-suite
JAVA_HOME=/path/to/jdk21 ./gradlew build
# 产物：build/libs/plugin-ai-suite-<version>.jar
```

然后在 Halo 后台上传 `build/libs/plugin-ai-suite-*.jar` 并启用。

## 配置

安装启用后，进入「AI 智能套件」配置页：

1. **模型配置**：填写 LLM 服务的 Base URL、API Key、模型名（Chat / Embedding 必填，Rerank / Query Rewrite 可选）
   - 兼容任意 OpenAI 协议厂商：DeepSeek、阿里通义千问、OpenAI、Moonshot、本地 Ollama 等
   - Base URL 示例：`https://api.deepseek.com/v1`（已含 `/v1` 无需补，不含则自动追加）
2. **知识库索引**：首次使用需触发「全量重建」，将已发布文章切片并向量化入库
3. **检索增强**：调整 Top-K、相似度阈值、是否启用 HyDE 查询改写、Rerank 精排
4. **对话与外观**：系统提示词、浮窗主题色、访客开关
5. **用量限制**：每日 token 限额（按模型）、访客 IP 每日/每小时限额、白名单

## API Key 安全

API Key 加密存储于 Halo Secret（非 ConfigMap 明文）。从旧版 `ai-assistant` 升级时，存量 API Key 会自动迁移到 Secret。

## 限流与防护

- **Token 限额**：按模型设置每日 token 上限，并发请求采用「预扣对账」机制（reserve/settle），防止并发绕过
- **访客限流**：按 IP 的每日 + 每小时滑动窗口，hourly 超限时回退 daily 计数避免误封
- **公开端点校验**：访客聊天/搜索端点前置校验功能开关，关闭后匿名调用直接拒绝
- **SSRF 防护**：LLM Base URL 校验禁止指向内网/本机/云元数据地址
- **错误脱敏**：LLM 错误信息抹除疑似 API Key 的敏感片段，防止泄漏到日志

## 项目结构

```
src/main/java/run/halo/ai/suite/
├── AISuitePlugin.java          # 插件主类
├── config/                     # 配置读取、API Key 迁移
├── llm/                        # 统一 LLM 客户端（OpenAI 兼容）
├── rag/                        # 混合检索（BM25 + HNSW 向量）
├── service/                    # 对话/写作/摘要/脑图/评测服务
├── endpoint/                   # 公开 + Console HTTP 端点
├── widget/                     # 前台浮窗注入
├── state/                      # 用量统计、访客限流
├── agent/                      # Agent 内容缺口检测
├── extension/                  # GVK 数据模型
└── listener/                   # 文章变更 → 索引同步

ui/                             # Console 管理端（Vue 3）
├── src/views/                  # 各管理页面
└── src/extensions/ai-writing/  # 编辑器 AI 写作扩展（Tiptap）

src/main/resources/
├── plugin.yaml
├── extensions/                 # 角色模板、扩展点注册
└── static/                     # 前台 widget JS/CSS
```

## 开发

```bash
# 前端开发（watch 模式）
cd ui && pnpm dev

# 后端 + 前端完整构建（JDK 21）
JAVA_HOME=/path/to/jdk21 ./gradlew build

# 运行测试
JAVA_HOME=/path/to/jdk21 ./gradlew test
```

> 不使用 `./gradlew haloServer`（依赖 Docker），开发时直接 jar 运行 Halo 并热部署插件。

## 许可证

[GPL-3.0](LICENSE)
