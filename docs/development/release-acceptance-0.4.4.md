# 0.4.4 安装、升级与模型测试验收

验收日期：2026-10-19。目标制品为 Java 21 构建的 `plugin-ai-suite-0.4.4.jar`（6,716,049 bytes），SHA-256：`9f18802deb5fff7c7a6f6f42f22d9653ea8d514ae58330caa7a1dcc6d1d9dccb`。升级起点为正式 `v0.4.3` JAR，SHA-256：`711e4768c9bd5d4edfe15645037b6dcf018dcb81eeee84353ea22d549636cbae`。发布范围为模型连通性测试缺省维度修复（#22）与安全策略维护版本说明（PR #14）。

## 问题基线与修复

- 基线：Halo 2.26.x 中，Chat、Query Rewrite、Rerank、图像测试请求不带 `dimensions` 时，Jackson 3 无法把缺省值反序列化到原始类型 `int`，接口返回 HTTP 400；Embedding 测试因带有该字段而正常。
- 修复：请求字段改为 `Integer`，校验时把缺省值归一化为 0；不改变下游“非正数表示未指定”的维度规则。请求体解析失败时返回明确的中文提示。
- 回滚：保留上一版 `v0.4.3` JAR 与升级前数据备份。此版不改变配置结构或 RAG 索引格式；回滚时恢复旧 JAR，模型测试问题会再次出现。

## 构建与回归

- `JAVA_HOME=/Users/rainwu/sdks/jdk-21/Contents/Home ./gradlew clean build -PfoundationTestVersion=1.1.1` 通过：207 项 Java 测试，失败/错误 0；前端类型检查、回归脚本和 Vite 构建通过。
- `node docs/diagrams/source/render-diagrams.mjs`、`node scripts/check-docs.mjs`（53 篇 Markdown、22 张 SVG）、`DOCS_LAST_UPDATED=false DOCS_BASE=/ node docs/node_modules/vitepress/bin/vitepress.js build docs` 与 `git diff --check` 通过。
- PR #23（修复）与 PR #14（安全策略）均通过 Documentation 和 Build and regression checks 两项 CI 后合并到 main。发布分支最终 CI 结果另在发布时核对。

## 全新安装与原位升级

- 在独立 `/private/tmp/ai-suite-044-clean`、本机 8093 创建新的 Halo 2.26.1/H2 站点，初始化后将预置 AI Foundation 1.0.0 升级至 1.1.1，再通过 Halo 插件安装接口安装候选 0.4.4。插件 `status.phase=STARTED`；搜索结果条数保存为 7 后重新读取仍为 7；匿名 `/widget-config` 返回 HTTP 200，默认文章保留 1 篇。
- 在独立 `/private/tmp/ai-suite-044-upgrade`、本机 8094 安装正式 0.4.3 作为基线，先将搜索结果条数保存为 6，记录九组业务配置与默认文章 ID、标题、标签。通过 Halo 官方原位升级接口安装候选 0.4.4 后，插件仍为 `STARTED`；九组配置完全一致，结果条数仍为 6，文章 ID、标题、标签未变。
- 8093、8094 均只使用各自临时数据目录；验收后进程已停止。既有 8090 站点、其 `dev/data`、生产站点和生产配置均未改动。

## 模型测试与其他链路

- 全新安装与升级后的两套站点上，`chat`、`queryRewrite`、`rerank`、`image` 四类不带 `dimensions` 的原始 JSON 请求均返回 HTTP 202 且返回对应 `jobId`；带 `dimensions=1024` 的 Embedding 请求同样返回 202。升级站上损坏的 JSON 返回 HTTP 400，并显示中文“测试请求参数格式错误”提示。
- 全新站匿名 Halo 关键词搜索返回 HTTP 200、`source=halo`，`Halo` 有 1 篇结果；无站内匹配的问题的 AI 搜索 SSE 返回 HTTP 200、`[DONE]`、无错误事件。文档截图沿用此前用 0.4.3 实际页面重拍的 40 张图；0.4.4 没有界面改动。
- 两套临时站都未配置外部模型，因此 HTTP 202 证明任务创建成功，不证明付费供应商调用成功。真实模型结果、脑图、摘要、标签、写作与评测未在候选版重复现场运行；其未修改路径以完整构建、回归测试和既有 0.4.3 验收为依据。生产插件未升级，生产 Nginx 下候选版 SSE 仍需生产升级后复核。
