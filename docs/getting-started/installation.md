# 安装与首次配置

> 适用读者：Halo 站长、首次部署人员  
> 适用版本：AI 智能套件 0.2.23、Halo 2.24+  
> 预计耗时：15～30 分钟

## 安装完成后的目标状态

[![安装到首次成功](../diagrams/exported/installation-journey.svg)](/diagrams/exported/installation-journey.svg)

## 环境要求

| 项目 | 要求 |
| --- | --- |
| Halo | 2.24.0 或更高版本 |
| 插件安装方式 | Halo Console 上传 JAR |
| Chat 模型 | OpenAI Chat Completions 兼容接口 |
| Embedding 模型 | OpenAI Embeddings 兼容接口 |
| 浏览器 | 支持 `fetch` 和 `ReadableStream` 的现代浏览器 |
| 反向代理 | 使用 SSE 时必须避免代理缓冲 |

Rerank 和 Query Rewrite 模型是可选能力。第一次安装时先把 Chat 与 Embedding 两项跑通，再逐步开启增强能力。

## 1. 安装插件

从项目 Releases 下载 `plugin-ai-suite-*.jar`，在 Halo Console 中进入“插件”，上传并启用。

如果从源码构建：

```bash
JAVA_HOME=~/jdk21/contents/Contents/Home ./gradlew build
```

构建产物位于：

```text
build/libs/plugin-ai-suite-0.2.23.jar
```

本项目开发环境不使用 Docker，也不要运行 `./gradlew haloServer`。

### 验证插件状态

Console 中插件应显示为已启用。开发环境也可以调用 Halo 插件资源 API：

```bash
curl -u admin:admin123 \
  http://127.0.0.1:8090/apis/plugin.halo.run/v1alpha1/plugins/ai-suite
```

响应中的 `status.phase` 应为 `STARTED`。

## 2. 配置 Chat 模型

进入“AI 智能套件 → 模型配置”，填写：

![模型配置](../../assets/readme/screenshots/console-models.jpg)

- Base URL，例如 `https://api.deepseek.com/v1`
- API Key
- 模型名称，例如 `deepseek-chat`

点击连通性测试。成功只代表模型接口可调用，不代表 RAG 已经可用。

## 3. 配置 Embedding 模型

填写 Embedding Base URL、API Key、模型名称和向量维度。默认配置使用 `text-embedding-v3` 和 1024 维，但最终值必须与实际服务返回一致。

> Embedding 模型名称或向量维度变化后，必须执行全量重建。旧向量不能与新模型生成的向量混用。

## 4. 建立文章索引

进入“索引中心”，点击“全量重建”。插件会处理已发布的公开文章：

[![文章索引构建流程](../diagrams/exported/index-build-flow.svg)](/diagrams/exported/index-build-flow.svg)

![索引中心](../../assets/readme/screenshots/console-knowledge.jpg)

### 验证索引

- 索引文章数不应为 0，除非站点没有已发布公开文章。
- 随机打开一篇文章，应能看到切片内容。
- 构建失败时先检查 Embedding 连通性和向量维度。

## 5. 完成第一次问答

进入“对话与外观”的调试区域，提一个答案明确存在于站内文章中的问题。成功状态包括：

- 流式出现回答文字。
- 回答内容与文章一致。
- 开启引用后能看到来源文章。
- 调试 Trace 中能看到检索阶段和命中文档。

完整验证步骤见 [第一次 RAG 问答](first-rag-chat.md)。

## 6. 再开启访客功能

后台调试成功后，再按需开启访客聊天、AI 搜索、脑图、摘要和写作辅助。生产环境应先完成 [生产部署](../operations/production-deployment.md) 中的 SSE 代理配置。

## 配置保存在哪里

[![配置与密钥存储](../diagrams/exported/config-storage.svg)](/diagrams/exported/config-storage.svg)

普通配置保存在 ConfigMap `ai-suite-configmap`，API Key 保存在 Secret `ai-suite-api-keys`。插件能够兼容读取旧名称 `ai-assistant-configmap` 和 `ai-assistant-api-keys`，用于升级迁移。

## 下一步

- [第一次 RAG 问答](first-rag-chat.md)
- [配置参考](../reference/configuration-reference.md)
- [生产部署](../operations/production-deployment.md)
- [故障排查](../operations/troubleshooting.md)
