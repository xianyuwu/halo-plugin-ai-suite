# 开发环境升级与兼容验证（2026-10-03）

本轮按用户授权升级唯一项目的本地 Halo 开发环境，保持现有数据、插件启用状态和模型选择。目录 `/Users/rainwu/软件开发/halo-ai-suite-plugin`，分支 `codex/pet-release-hardening`，基线 `5975caf`，未提交、未推送、未发布生产。

## 版本与范围

Halo 从 2.25.3 升至当前官方最新稳定版 **2.26.1**；Foundation 从 1.0.0-beta.4 升至 **1.1.1**。未采用 Halo 2.27 预发布版。应用市场及有可用兼容更新的现有插件一并更新；其余在本地 Console 中没有更新提示。未更换主题、未升级操作系统或无关工具。

Java 编译平台/API 使用官方已发布的 **2.26.0**，运行版本使用 2.26.1：Maven 没有 2.26.1 平台制品。Foundation API 编译和默认测试运行库为 1.1.1，插件要求 Halo >=2.26.0、Foundation >=1.1.1。

| 插件 ID | 最终版本 | 状态 |
| --- | --- | --- |
| ai-suite | 0.3.6 | STARTED |
| ai-foundation | 1.1.1 | STARTED |
| app-store-integration | 1.19.0 | STARTED |
| plugin-blog-hao | 1.1.1 | DISABLED |
| export2doc | 1.2.5 | DISABLED |
| PluginStackEdit | 1.2.0 | DISABLED |
| hybrid-edit-block | 1.7.0 | DISABLED |
| willow-mde | 1.2.0 | STARTED |
| PluginS3ObjectStorage | 1.17.0 | STARTED |
| PluginMigrate | 2.0.3 | STARTED |
| PluginSitemap | 1.3.0 | STARTED |
| PluginSearchWidget | 1.7.1 | STARTED |
| PluginFeed | 1.5.0 | STARTED |
| PluginCommentWidget | 3.3.2 | STARTED |

智能套件仍使用开发版本号 0.3.6，包含既有未提交工作，不能视为正式发布包。所有原启用/禁用状态保持。

## 根因与适配

- Halo 2.26.1 内置 Lucene 10.5.0，插件 core/analysis-common/queryparser compileOnly 与 smartcn 对齐 10.5.0，继续禁止重复打包 core。旧索引实际读取成功，25 篇文章、408 个切片，无需重建。
- Console 组件、富文本编辑器及 bundler-kit 升至 2.26.0；构建输出改为新版 ESM manifest 与分块资源，环境泄漏检查改为扫描入口及所有 JS 分块。
- Vue 3.5.41、vue-router 5.1.0 与 Halo 2.26 的共享依赖基线对齐。富文本编辑器宽范围 Tiptap 依赖最初解析到 3.31.4，导致 ProseMirror 类型不一致；按官方编辑器声明基线统一锁定 Tiptap 3.29.0 并 dedupe，类型检查通过。不是盲目采用 npm 最新版本。
- 修复默认生图槽字段：`imageGenerationModelName`，原 `imageModelName` 无法显示默认模型名称。
- 新版模型资源增加 `adapterType`，旧资源被默认填为 `openai-chat`。真实向量/重排测试发现类型错误，生图预检查也失败。依据当前官方 provider-types 接口与 AdapterType 枚举，逐个仅修复 adapterType：豆包生图 → doubao-image；百炼生图 → dashscope-image；OpenAI 兼容向量 → openai-embedding；Gitee 重排 → rerank；MiniMax/MiMo/DeepSeek 文本分别 → minimax-chat/mimo-chat/deepseek-chat。保留模型 ID、供应商、参数映射、能力、密钥及默认槽。未把迁移操作加入套件自动修改其他插件的逻辑。

## 验证证据与边界

Java 21 下执行 `./gradlew clean build`：新增流式回归首次因测试用量字段误用 Long 编译失败，改为 SDK Integer 后执行 `./gradlew build`，完整构建成功。JUnit **153 tests，0 failures/errors/skipped**；覆盖最终流式用量一次记录、原生 reasoning/text 事件、终态失败不重复生成。前端 vue-tsc、宠物流程/头像回归、Vite 和 markmap 构建通过。

`AI_SUITE_BUILD_SENTINEL=ai-suite-build-sentinel-do-not-ship node ui/scripts/verify-build-env.mjs` 通过，4 个浏览器 JS 文件未包含构建标记；两个访客脚本 `node --check` 通过。文档检查与 `git diff --check` 通过。

通过 Halo 官方 `POST /plugins/{name}/upgrade` 原位升级，不删除重装。最终已启用插件均 STARTED，原禁用项仍 DISABLED。套件升级前后宠物列表 JSON 完全相同；现有小白四张待确认候选图全部加载。Console 模型配置默认槽可读、ESM 页面可加载；新版默认富文本编辑器的 AI 大纲弹窗正常打开/取消，未编辑或保存文章。

真实模型测试：默认 DeepSeek 文本通过（流式最终汇总）；bge-m3 返回 1024 维通过；Gitee bge-reranker-v2-m3 通过；当前豆包 Seedream 5 文生图返回 1 张通过。迁移前后各一次文本/向量/重排测试，生图一次。只发送固定的通用测试文本或红圆提示词；没有发送用户照片、生成新宠物或改变现有宠物。没有重新验证所有文本模型、Qwen 真实生图、图生图到四表情全流程；模拟测试及预检查不等于这些云端画质验收。

日志存在 macOS Netty 原生 DNS 库缺失、回退系统 DNS 的提示，以上云端测试实际成功。文章列表的浏览器日志有通用 Event 图片加载错误，模型/宠物页面没有发现模块加载错误；不据此宣称整个站点日志为零。

## 制品、备份与回滚

开发服务 `http://127.0.0.1:8090` 保持运行，PID 66785，日志 `/tmp/halo-dev.log`。

- Halo 2.26.1 官方 JAR SHA-256：`7a1d6ea0800e8940672aab99a7328b7bd9520e297677169594328004a41991bc`。
- Foundation 1.1.1 官方 JAR SHA-256：`5a7ebe719d0118985d65b759cfd96d3e656a63db2e1dd9d4b083461091246fa0`。
- 最终套件 `build/libs/plugin-ai-suite-0.3.6.jar` SHA-256：`8d1f0fe1c66513bee7fb4c3945eef56f5b179b5d65f694a80381cde9f9bc36e1`。

停机后完成的全量备份在忽略目录 `output/dev-upgrade-2026-10-02/backup-before-upgrade/`，含旧 Halo 2.25.3 JAR、整个 data、原配置、日志；模型修复前另存完整资源副本。目录权限 700，敏感文件 600。包含开发数据库/配置，不得提交或上传。不要尝试让旧 Halo 读取升级后的数据库。

如需回滚，先停止当前开发服务并另存升级后的整个 data，再恢复备份的整个 data、旧 halo.jar 与原 application.yaml 后启动。插件 JAR、ConfigMap、模型资源随完整数据库备份恢复。只回退模型适配器时使用其修复前副本并保留最新 resourceVersion，不能覆盖升级后新增数据。代码回滚需逐项撤销本轮依赖/字段/检查改动，禁止 reset 混合工作区。

后续仍需宠物完整云端流程与画质回归、混合改动归类提交及正式发布检查。

## 官方依据

- [Halo 2.26.1](https://github.com/halo-dev/halo/releases/tag/v2.26.1)
- [Foundation 1.1.1](https://github.com/halo-dev/plugin-ai-foundation/releases/tag/v1.1.1)
- [Foundation 适配器类型](https://github.com/halo-dev/plugin-ai-foundation/blob/v1.1.1/app/src/main/java/run/halo/aifoundation/provider/support/AdapterType.java)
