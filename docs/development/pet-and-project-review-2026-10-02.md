# 交互宠物与项目发布收尾评估（2026-10-02）

> 后续状态：本次评估中的可靠性、兼容性及构建门禁问题已完成修复和本地验证，详见 [发布收尾修复记录](pet-release-hardening-2026-10-02.md)。下文保留评估时的基线事实，未完成事项以修复记录为准。

## 结论与边界

交互宠物主功能已完整，建议停止扩展功能，转入可靠性修复、回归和发布整理。当前适合开发环境验收，尚不满足正式发布标准。

本轮检查当前工作区、关键宠物链路、模型门面、构建配置和测试；不是逐文件安全审计，也未进行新的云端生图或浏览器端到端验收。基线 HEAD `5975caf`，分支 `codex/pet-chat-avatar`，唯一目录 `/Users/rainwu/软件开发/halo-ai-suite-plugin`。不修改业务代码、不部署、不提交、不改变配置或宠物图片。

## 已完成能力

- 三套内置皮肤、五状态资源、悬停/聊天状态反馈与宠物语录。
- AI 母版生成、参考姿态与补充提示词、精致立体/复古像素两种画风。
- 母版背景强制验收、深浅背景预览、橡皮擦清理与恢复。
- 仅表情局部合成，以及精致立体轻动作候选、逐张清理/确认与单帧重试。
- 模型配置/能力预检查、真实阶段和耗时、完成态防误触。
- 聊天顶部与回复统一宠物头像、按宠物独立裁切和实时预览。

## 优先问题

### P1：未验收母版可能提前进入前台

`PetGeneratorService.regenerateMaster` 直接替换 `images.idle` 并将背景设为 pending；背景清理也会修改当前 images。`PublicChatEndpoint.attachPetManifest` 只检查 idle 存在，不检查背景审批。如果该宠物之前已选用，前台重新读取配置就可能显示新的未确认母版。后台禁止新选用并不能保护已有选择。

建议区分草稿图片和已发布快照，重做及清理期间继续使用上一版已确认资源；确认后原子切换。仅增加 pending 回退虽能阻止草稿展示，但会中断已有宠物，应明确产品取舍。增加已选用宠物重做/清理、失败保留旧版本的测试。

证据：`src/main/java/cn/rainwu/halo/ai/suite/service/PetGeneratorService.java:258`、`src/main/java/cn/rainwu/halo/ai/suite/endpoint/PublicChatEndpoint.java:591`。

### P1：背景确认缺少版本校验，部分写操作可能覆盖新状态

`approveBackground(petId)` 只按 ID 确认当前记录，没有客户端所见母版 URL/修订号。旧页面显示 A，另一个页面已生成 B 后，旧页面点击确认会批准未看过的 B。母版重做、背景清理/恢复/确认未统一加入同一宠物的变更互斥。`PetStore.update` 将调用方读取的整条记录替换进最新列表；ConfigMap 乐观锁并不防止旧业务记录覆盖新字段。

建议沿用候选帧已有的版本校验思想：提交所见母版版本，服务端在原子修改中验证；所有相关写操作统一互斥或比较并交换，冲突明确要求刷新。测试双页面、清理与重做交错、确认旧版本等情况。

证据：`PetGeneratorService.java:591`、`PetStore.java:127`。本轮为代码路径分析，未触发真实并发模型调用。

### P2：任务丢失后轮询不会结束，任务表没有回收

Console 任务仅存于内存 Map；插件重启后查询返回 success=false 且没有 job。前端对缺少 job 每两秒继续轮询，异常也继续重试，没有终止条件。已完成任务未设置 TTL 或数量上限。

建议明确任务不存在/过期的终态、限制连续失败及总等待时间，提示重新加载宠物数据，避免盲目再次付费生成；回收终态任务。若要求刷新后继续查看，则持久化最小任务状态或关联正在执行的任务。下载/上传也应有有界超时。

证据：`ConsolePetEndpoint.java:49`、`:207`，`ui/src/views/WidgetView.vue:1136`。

### P2：宠物存储的异常处理需要补齐

- `PetStore.get` 的 Reactor map 在未找到 ID 时返回 null，会产生异常而不是空 Mono，后续 switchIfEmpty 无法返回预期的不存在提示。
- `PetStore.parse` 对坏 JSON 返回空列表；后续 mutate 将空列表作为合法原数据继续写入，存在覆盖损坏但仍可恢复的数据的风险。非数组 JSON 也会被当成空列表。

建议未找到记录返回空 Mono；解析失败时阻止写入并保留原配置，给出明确恢复提示。增加真实存储层测试，不仅 mock PetStore。

证据：`src/main/java/cn/rainwu/halo/ai/suite/service/PetStore.java:112`、`:202`。

### P2：旧附件与失败上传的附件未回收

删除宠物仅删除记录，重做/清理/候选替换及部分上传失败都可能留下附件。上传方法主要保存 permalink，缺少所有权和引用管理。

建议记录宠物附件 ID，状态提交成功后回收不再引用的本插件附件，并对失败上传做补偿及重试。原始母版、恢复用原候选和其他仍引用图片必须保留，不能按名字批量删除附件。

证据：`ConsolePetEndpoint.handleDelete`、`PetGeneratorService.uploadImage`。这是已知独立待办，尚未实现。

## 整体代码与发布检查

1. **正式版 Foundation 兼容性应优先处理。** 当前编译和测试依赖仍为 api 1.0.0-beta.4，结构化输出路径调用 providerOptions。最新交接记录对 Issue #7 已核对正式 1.1.0 API 的二进制不兼容；本轮确认旧依赖/调用仍存在，未重新实机复现正式运行库。当前单元测试通过不能证明正式版兼容，需专项迁移和实际运行库验证。
2. **前端构建不等于类型检查通过。** vue-tsc 当前报告 155 项错误：125 项模块/声明解析错误，其余 30 项包括类型和 API 使用问题。不能解释成 155 个运行时 bug。WidgetView 也有 CSS imageRendering 类型问题。需要先补齐 Halo 外部模块/虚拟图标声明，再修复实际错误，将检查纳入构建或 CI。
3. **构建环境变量注入需要收敛。** vite.config 使用旧 HaloUIPluginBundlerKit；已安装包该入口配置 `define: {"process.env": process.env}`。有将构建环境变量带入浏览器产物的风险；本轮没有确认密钥泄漏。应仅注入必要变量并扫描发布产物。
4. **前端回归尚未形成正式门禁。** test:unit 仍是占位命令，关键 VM 验证脚本位于忽略的 output 目录，现有 workflow 仅文档检查。应把关键行为测试版本化并加入自动检查，包括完成/取消零调用、失效任务、过期确认和静态图标兼容。
5. **主要文件承担过多职责。** WidgetView 约 2344 行、chat-widget 约 2264 行、PetGeneratorService 约 1036 行。后续按向导、背景编辑器、头像裁切、任务轮询，以及提示词/图片处理/存储拆分。建议在可靠性修复后渐进拆分，避免收尾阶段整体重写。
6. **工作区尚未形成发布候选。** 22 个已跟踪文件修改，另有 35 个未跟踪文件（按实际文件计数），横跨宠物、模型/用量、聊天配置及管理界面。已跟踪 diff 为 1259 additions/574 deletions，不包含新宠物源码和资源。应归类审查并整理可追溯提交，不直接发布当前混合开发 JAR。

## 验证证据

- Java 21 `JAVA_HOME=/Users/rainwu/sdks/jdk-21/Contents/Home ./gradlew build`：成功；本轮部分任务 UP-TO-DATE，当前测试 XML 为 132 tests、0 failures/errors/skipped，不宣称全部测试本轮重新执行。
- `git diff --check`：通过。
- `node --check src/main/resources/static/js/chat-widget.js` 与 `sticker-pet.js`：通过。
- `node output/expression-completion-qa/motion-check.mjs`：通过。
- `node output/pet-avatar-qa/check.mjs`：通过。
- `ui/node_modules/.bin/vue-tsc --noEmit`（ui 目录）：失败，155 项；完整输出 `output/project-review/typecheck.txt`。
- 未调用模型、未部署、未保存管理配置。当前 build 使用缓存，不替代正式发布前干净构建。

## 最后一轮验收与建议顺序

先修已发布/草稿隔离、母版版本一致性与任务终止恢复；同时独立处理 Foundation 正式版兼容。随后补存储异常保护与附件回收，解决类型检查及构建变量注入，固化自动测试。

最后用实际新流程各验收一只精致立体和复古像素宠物；轻动作至少走一次生成→独立清理→确认→前台切换。补桌面/移动触摸、深浅主题、刷新后配置保持、坏图片回退和插件重启场景。此前的真实模型原型及本地图片回放不替代新运行时端到端验收。

完成上述后整理提交、干净完整构建并产出发布候选。更多皮肤、连续动作动画和更多模型专用适配可留后续版本。
