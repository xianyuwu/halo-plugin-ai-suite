# AI Foundation 新版兼容性评估（2026-10-02）

## 结论和实际版本

当前接入代码可通过 Foundation 1.1.1 Java SDK 编译及已有测试，未发现现有调用必然失败的新增接口断裂。不建议整体重写模型接入。提交前建议修正一个既有后台字段错误，升级前先解决 Halo 版本限制，再对正式插件执行真实链路回归。

- 本地 Console 插件详情和文件一致：Foundation **1.0.0-beta.4**，并非已经更新到正式版。
- 本地 dev/halo.jar manifest：Halo **2.25.3**。
- 官方最新发布：Foundation **1.1.1**，发布于 2026-09-30；其 plugin.yaml 要求 Halo **>=2.26.0**。
- 套件 compileOnly 仍为 API beta.4，pluginDependencies 为 `*`；此前 CI 运行库矩阵为 beta.4/1.1.0。只在测试中替换 SDK 不代表本地正式插件升级完成。

## 接口逐项核对

| 接入 | 结论 | 依据与限制 |
| --- | --- | --- |
| 非流式文本/JSON | 现有代码兼容 | 已由 providerOptions 改成共有 OutputSpec.json/text；新版删除 providerOptions 不再影响当前调用。新增采样字段不必全部接入。 |
| 流式文本与推理 | 已有签名兼容，需补针对性回归 | textStream/fullStream/result 和既有推理事件仍可编译；新工具输入事件由当前 default 分支忽略。当前不发工具请求，不需要迁移官方 UI Message Stream 协议。现有 Foundation 门面测试没有直接覆盖两个流式方法，不能以150项全过声称真实流式已验收。 |
| 向量 | 文本路径兼容 | inputs/dimensions/embed 和返回向量路径保留；新增多模态 contents/instructions 等为扩展，无需改当前文字RAG索引。已有原生/可变维度/缓存及不匹配测试通过。换向量模型或维度仍需重建索引。 |
| Rerank | 文本路径兼容 | query/documents/topN 和文本of构造保留；新版增加图片文档、headers，不影响当前文字排序。 |
| 生图和宠物 | 请求/结果签名兼容 | prompt/images/n/size/maxRetries/responseFormat 及 GeneratedFile 的 Base64/URL 路径仍可编译。豆包URL兼容措施继续有效；新 negativePrompt 等不是强制迁移项，本地合成/清理不依赖供应商mask。测试模拟响应不证明供应商映射和真实返回尺寸。 |
| 模型列表/默认模型 | 接口地址和主体字段兼容，生图默认字段有既有错误 | model-options、default-model-slots 仍存在；套件 imageModelName 与官方 imageGenerationModelName 不一致，旧beta.4也使用后者，故不是升级新增破坏。 |
| 1.1.1模型选择器 | 当前不受改动直接影响 | 官方 FormKit requiredFeatures 改为非阻断警告；套件自建下拉并直接请求model-options，未使用aiModelSelector/requiredFeatures，且宠物运行时已有文生图/图生图检查。官方明确后台model-options筛选语义不变。 |

## 调整优先级

1. **升级前置条件**：先把开发 Halo 升至受支持的 >=2.26.0，再安装 Foundation1.1.1。当前环境不适合直接替换 Foundation。升级前做常规开发数据备份；本轮未执行升级或修改数据。
2. **提交前的小修复**：ModelConfigView 的 DefaultModelSlots/defaultSlotName 改读 imageGenerationModelName。影响默认模型名称展示，不影响后端 service.imageGenerationModel() 的真实默认解析；显式选模型也不受此展示问题影响。
3. **发布兼容基线**：考虑 compileOnly/test 默认转正式1.1.1，并把CI矩阵加入1.1.1；若仍支持beta.4，保留旧运行库验证，不调用新版独有API。明确支持范围后再收紧pluginDependencies和Halo最低要求，避免继续宣称任意Foundation版本可用。这属于发布策略选择，并非现有方法签名必须更换。
4. **生图预检查增强**：现有只检查textToImage/imageToImage，母版及表情固定1024方图、轻动作2048方图；可结合capabilities中的sizes/outputMediaTypes在提交任务前提示限制，避免预检查显示就绪但尺寸请求不支持。不要把未知能力当作确认支持。此为现有检查覆盖不足，不宣称新版导致该问题。
5. **升级后真实验收和测试补齐**：普通文本、JSON大纲、两种流式/思考开关、向量、Rerank、母版与单帧轻动作；增加流式错误/usage专项测试。供应商参数映射、额度、可用尺寸、URL/Base64结果需在实际正式插件下验证。暂不需要重写宠物流程或接入新工具/多模态/UI SDK。

## 实际验证及边界

唯一目录 `/Users/rainwu/软件开发/halo-ai-suite-plugin`；分支 codex/pet-release-hardening，HEAD5975caf，全部既有未提交工作受保护。只读访问已登录本地插件详情和官方版本化源码，无云端模型调用、配置变更、插件升级、业务代码修改或提交。

- Java21 `./gradlew test -PfoundationTestVersion=1.1.1`：成功；27份JUnit报告，150 tests、0 failures/errors/skipped。测试使用真实1.1.1 API运行库，模拟AiModelService/provider返回。
- 临时Gradle init将API依赖统一force1.1.1，`./gradlew compileJava --rerun-tasks -I /tmp/ai-suite-foundation-111.init.gradle`：成功，整个后端源码可对新版API编译。未修改build.gradle，未打包/部署该临时编译产物。
- Git状态/唯一worktree已核对；git diff --check及文档检查通过。默认沙箱GitHub网络失败，经授权只读官方仓库成功；一次未给带问号参数加引号的树查询失败，正确引号后成功，不影响结论。

## 官方证据

- [1.1.1发布说明](https://github.com/halo-dev/plugin-ai-foundation/releases/tag/v1.1.1)
- [beta.4到1.1.1变更](https://github.com/halo-dev/plugin-ai-foundation/compare/v1.0.0-beta.4...v1.1.1)
- [1.1.0到1.1.1变更](https://github.com/halo-dev/plugin-ai-foundation/compare/v1.1.0...v1.1.1)：未改Java API源码，主要是模型选择器和工作流。
- [新版Halo要求](https://github.com/halo-dev/plugin-ai-foundation/blob/v1.1.1/app/src/main/resources/plugin.yaml)
- [默认模型字段](https://github.com/halo-dev/plugin-ai-foundation/blob/v1.1.1/app/src/main/java/run/halo/aifoundation/setting/DefaultModelSlots.java)
- [模型选择器官方说明](https://github.com/halo-dev/plugin-ai-foundation/blob/v1.1.1/dev/zh-CN/model-selector.md)

## 2026-10-03 后续验证

已升级本地 Halo 2.26.1 / Foundation 1.1.1 并完成配置迁移、153项测试及四类真实能力检查，详见[开发环境升级记录](dev-environment-upgrade-2026-10-03.md)。上文是升级前评估，不再代表当前安装版本。
