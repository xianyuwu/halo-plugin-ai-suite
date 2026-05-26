# Halo AI 智能助手插件

Halo 2.24+ 博客插件，提供 AI 智能问答（RAG）、文档智能编辑、AI 摘要等功能。

## 技术栈

- 后端: Java 21 / Spring WebFlux / Halo Plugin API 2.24.0
- 前端 Console: Vue 3 + TypeScript + Vite
- 前端 Widget: 纯原生 JS/CSS（注入到主题页面）
- 向量搜索: Apache Lucene 10.4（BM25 + HNSW 向量）
- 构建: Gradle 9.4 + pnpm

## 项目结构

```
src/main/java/run/halo/ai/assistant/
├── AIAssistantPlugin.java      # 插件主类
├── config/AIProperties.java    # 5 组配置读取（模型/切片/检索/增强/对话）
├── llm/LlmClient.java          # 统一 LLM 客户端（OpenAI 兼容协议）
├── service/ChatService.java    # 对话服务
├── endpoint/
│   ├── PublicChatEndpoint.java # 访客聊天 API（api. 前缀，无需认证）
│   └── ConsoleConfigEndpoint.java # 管理配置 API（console.api. 前缀）
├── widget/
│   ├── ChatWidgetFooterProcessor.java # 注入浮窗到主题页脚
│   └── ChatWidgetExtension.java       # 扩展点声明
├── rag/                        # Phase 2: Lucene 索引、混合检索
├── model/                      # Phase 2: GVK 数据模型
└── listener/                   # Phase 2: 文章变更监听

src/main/resources/
├── plugin.yaml                 # 插件清单
├── extensions/
│   ├── settings.yaml           # 5 Tab 设置表单（模型/切片/检索/增强/对话）
│   ├── widget-extension.yaml   # 浮窗扩展点注册
│   └── reverse-proxy.yaml      # 静态资源代理
└── static/
    ├── js/chat-widget.js       # 访客聊天浮窗
    └── css/chat-widget.css

ui/                             # Console 管理端（Vue 3）
├── src/
│   ├── index.ts                # definePlugin 入口
│   └── views/AIOverview.vue    # 设置概览页
├── package.json
└── vite.config.ts
```

## 构建命令

```bash
# 完整构建（后端 + 前端）
./gradlew build

# 仅后端
./gradlew jar

# 开发模式（启动 Halo + 加载插件）
./gradlew haloServer

# 前端开发（watch 模式）
cd ui && pnpm dev
```

## API 端点

| 端点 | 方法 | 认证 | 说明 |
|---|---|---|---|
| `/apis/api.ai-assistant.halo.run/v1alpha1/chat/stream` | POST | 无 | SSE 流式对话 |
| `/apis/api.ai-assistant.halo.run/v1alpha1/chat` | POST | 无 | 非流式对话 |
| `/apis/console.api.ai-assistant.halo.run/v1alpha1/config` | GET | 管理员 | 获取配置 |
| `/apis/console.api.ai-assistant.halo.run/v1alpha1/config/test-connection` | POST | 管理员 | 测试 LLM 连通性 |

## 开发阶段

- **Phase 1** ✅: 骨架 + 模型配置 + 基础对话（无 RAG）
- **Phase 2**: Lucene 嵌入 + 文章切片 + 混合检索 + 浮窗对接 RAG
- **Phase 3**: Query Rewrite + HyDE + Rerank + 跨语言 + 引用展示
- **Phase 4**: 摘要生成 + 编辑器集成 + 百度文心适配

## 注意事项

- JDK 21 必须，Halo 2.24.0 API
- 前端依赖 pnpm 9+、Node 20+
- Lucene 依赖已引入但 Phase 2 才会使用
- 所有 LLM 调用走 OpenAI 兼容协议，通过 baseUrl 切换厂商
