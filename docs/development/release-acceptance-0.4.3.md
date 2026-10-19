# 0.4.3 安装、升级与搜索链路验收

验收日期：2026-10-18 至 2026-10-19。目标制品为 Java 21 构建的 `plugin-ai-suite-0.4.3.jar`，SHA-256：`711e4768c9bd5d4edfe15645037b6dcf018dcb81eeee84353ea22d549636cbae`。升级起点为 GitHub Release `v0.4.2` 的正式 JAR，SHA-256：`dee1309fce710e8506774b5b8e3615f7f298e9ea2a043c3fe4438bbeadea658d`，与该 Release 公布的摘要一致。

## 环境与构建

- 两套相互独立的临时 Halo 2.26.1/H2 站点，分别监听本机 8091、8092；均将预置 AI Foundation 升级至 1.1.1。另将既有 8090 开发站点通过 Halo 升级接口装入候选 JAR，升级前备份了原 JAR；站点其他数据未改动。
- `JAVA_HOME=/Users/rainwu/sdks/jdk-21/Contents/Home ./gradlew clean build -PfoundationTestVersion=1.1.1` 通过；206 项 Java 测试，失败/错误 0；前端类型、回归与构建通过。`node scripts/check-docs.mjs` 通过。
- `node docs/diagrams/source/render-diagrams.mjs` 与 `pnpm --dir docs build:version` 通过，生成 0.4.3 文档快照。

## 全新安装（8091）

1. Halo 识别插件 ID `ai-suite`、版本 `0.4.3`；启用后 `status.phase=STARTED`、`Ready=TRUE`，依赖 AI Foundation 1.1.1 同为 `STARTED`。
2. 后台将搜索结果条数从 10 保存为 7，再读取仍为 7。匿名读取 `/widget-config` 返回 HTTP 200；匿名访问 Console `/config` 被重定向至登录页。
3. 匿名搜索 `Halo` 返回 `source=halo`、1 篇 `Hello Halo`。长问句 `Halo 有哪些文章？` 标记 `questionMode=true`；实时天气问句返回 0 篇且 `clearMatch=false`。
4. 在未配置语言与 Embedding 模型的该站点，搜索回答 SSE 返回 2 个 `data:` 帧和 `[DONE]`、无 `event:error`，对应站内依据不足的正常结束路径。

## 从 0.4.2 升级（8092）

1. 安装、启用正式 `v0.4.2`，确认 `status.phase=STARTED`。把搜索结果条数保存为 6，记录完整业务配置和默认文章 `Hello Halo` 的 ID、标题及 1 个标签。
2. 经 Halo 官方插件升级接口原位上传 0.4.3 JAR 后，版本为 `0.4.3`，启用状态保持为 `true`，`status.phase=STARTED`。
3. 升级前后的九组业务配置 JSON 完全一致，搜索结果条数仍为 6；文章 ID、标题和标签数量不变。匿名搜索 `Halo` 仍返回 `source=halo`、1 篇文章。

## 搜索与 SSE 链路

- 在已有文章和模型配置的本地 8090 集成站，`DeepSeek-R1` 返回 1 篇精准文章；`Halo博客在原生Kubernetes上怎么部署？` 返回 13 篇，首条是 YAML 部署教程，`questionMode=true`、`clearMatch=true`；`今天海南天气怎么样？` 返回 15 篇候选但 `clearMatch=false`，访客可展开宽泛结果。
- 同一集成站的访客弹框实测可见 AI 回答、引用、相关列表和“查看全部”；已更新[搜索用户手册截图](../user-guide/ai-search.md)。直连搜索 SSE 对 `DeepSeek-R1` 返回 178 个 `data:` 帧、1 个引用事件、`[DONE]`、无错误帧。
- 8090 后续升级到候选 `0.4.3`，插件保持 `STARTED`；同题真实模型调用返回 146 个 `data:` 帧、1 个引用事件、`[DONE]`、无错误帧。首帧约 10.11 秒、末帧约 12.64 秒，回答逐帧到达。这是候选 JAR 的直连真实模型验收。
- 现有公网 Nginx 入口同题搜索 SSE 返回 186 个 `data:` 帧，首帧约 5.08 秒、末帧约 8.31 秒，帧到达横跨 3.23 秒；含 `[DONE]`、无错误帧，说明该代理链路没有把整段回答缓冲到末尾。该入口运行的仍是已部署版本，**不是本次 0.4.3 候选 JAR**；此次只验证现有 Nginx 的流式传输能力。

## 验收边界与上线检查

- 8091、8092 未配置外部模型；8090 候选版已验证真实搜索回答，但未重复执行脑图、摘要、标签、写作和评测的付费模型流程。这些功能的兼容性由完整构建、回归测试和既有开发环境验证支撑。模型异常结束、提前 EOF 和前端提示由专项单元/回归测试覆盖。
- 本次没有在生产站点安装候选版。公网 Nginx 的逐帧检查只证明现有代理配置可流式传输，未覆盖候选版经该代理的端到端路径；生产升级后应再次核对。8090 原 JAR 保存在独立临时目录，其余开发数据保留。生产升级前仍需备份、保留 0.4.2 JAR，并核对实际模型配置、索引与访客搜索入口。
- 0.4.3 不变更配置结构或插件 RAG 索引格式；模型与维度不变时无需重建本插件索引。Halo 核心搜索引擎的文章索引应保持可用。
