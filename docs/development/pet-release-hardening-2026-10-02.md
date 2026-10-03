# 宠物与项目发布收尾修复（2026-10-02）

唯一目录 `/Users/rainwu/软件开发/halo-ai-suite-plugin`，集成分支 `codex/pet-release-hardening`，HEAD `5975caf`。承接全部既有未提交工作，无提交、生产发布或云端模型调用。

## 修复结果

| 优先项 | 根因及修复 | 验证 |
| --- | --- | --- |
| 草稿公开 | 前台读取编辑中的 images；新增确认快照，重做与清理继续展示旧版，新 pending 不公开 | 公开接口与母版失败/确认测试、Console 预览回归 |
| 版本与并发 | 确认只按 ID，旧记录覆盖风险；背景增加所见 URL/修订号，全部修改/删除统一互斥并修改最新记录 | 旧版本拒绝、锁释放、候选及背景专项测试 |
| 任务生命周期 | 无界历史、失效任务无限等待；增加 TTL/数量、停止取消、超时和轮询终止 | Registry 及前端丢失/连续异常回归 |
| 存储保护 | map 返回 null、坏 JSON 当空数据；缺记录返回空 Mono，损坏存储明确报错并禁止覆盖 | PetStore 三项专项测试 |
| 附件回收 | 替换后图片遗留；创建前写归属标记，1 小时保护期、每 10 分钟保守回收无引用图片并重试 | 五项归属/引用/异常/上传元数据测试 |
| Foundation 兼容 | 正式版移除 providerOptions；结构化输出改用共有 OutputSpec | beta.4 和 1.1.0 运行库分别全量 150 项通过 |
| 发布检查 | 前端类型未通过、测试占位、环境变量整体注入；修复类型/API问题，版本化回归脚本，官方 Vite 入口及 CI 矩阵 | typecheck、两组前端回归、无敏感性环境标记扫描 |

## 实际验证

Java 21：`JAVA_HOME=/Users/rainwu/sdks/jdk-21/Contents/Home`。

- `./gradlew clean build`：成功；150 tests，0 failures/errors/skipped。
- `./gradlew test -PfoundationTestVersion=1.1.0`：成功；150 tests，0 failures/errors/skipped。
- `pnpm --dir ui build`：类型检查、宠物流程/头像回归及资源构建通过。
- `AI_SUITE_BUILD_SENTINEL=ai-suite-build-sentinel-do-not-ship node ui/scripts/verify-build-env.mjs`：通过，标记未进入 main.js。
- `node --check src/main/resources/static/js/chat-widget.js`、同目录 sticker-pet.js、`git diff --check`：通过。
- `node scripts/check-docs.mjs`：补齐宠物与生图测试接口清单后通过。
- `./dev-start.sh --deploy-only`：完整构建成功，开发插件 STARTED。
- 实际 Console 打开现有小白表情列表，四个轻动作候选保持待逐张确认，进入单帧背景检查、深色预览、返回及完成关闭正常；相关图片 loaded=true，浏览器 error 日志为 0。未确认/清理/恢复/生成或保存已有图片与配置。

缺失任务 HTTP 直连浏览器检查被浏览器限制拦截，未绕过；终止行为由服务端和版本化前端测试覆盖。正式 Foundation 验证使用真实 1.1.0 API 运行库、模拟模型响应，不能等同正式插件下的真实云端端到端验收。

开发制品 `build/libs/plugin-ai-suite-0.3.6.jar` SHA-256：`e5f1b550f272c32501a3ae9cdf209ef2c8eebb2a88bbf182dbac70da42f008b7`。本地测试汇总在忽略目录 output/project-review/hardening-default-tests.json 和 hardening-foundation-1.1.0-tests.json。

## 剩余发布条件与回滚

仍需在真实模型下验收新上传归属、生成中断、确认及附件最终删除；本轮未额外消耗模型额度。历史无归属标记附件需独立盘点，避免错误删除。CI 已新增但未推送运行。混合工作区尚需整理可审查提交、执行发布验收；大型 WidgetView/生成服务拆分属于后续维护，不影响本轮可靠性修复结论。

回滚本轮快照/门禁/任务/附件服务、OutputSpec 及构建门禁改动；新增 JSON 字段可缺省兼容。附件回收已删除的文件不可靠代码回滚恢复，因此只处理有明确归属且超过保护期的无引用文件；有快照或恢复引用的文件不回收。全部任务开始前修改及未跟踪文件继续保护，未清理或提交。
