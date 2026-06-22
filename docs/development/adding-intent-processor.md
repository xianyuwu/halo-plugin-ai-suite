# 新增意图处理器

> 适用读者：后端与 Console 开发者

[![新增 Pipeline Processor](../diagrams/exported/processor-extension.svg)](../diagrams/exported/processor-extension.svg)

## 1. 实现接口

在 `intent/processor/` 新增 Spring Component，实现 `PipelineProcessor`：

- `type()` 返回稳定、唯一的大写类型。
- `process()` 接收候选文章、步骤参数、问题和历史。
- 不阻塞 Reactor 线程；阻塞查询切换到合适 Scheduler。
- 参数通过共享辅助方法读取并设置安全默认值。

## 2. 加入保存校验

把类型加入 `IntentRouteService.ALLOWED_PROCESSORS`，并为参数数量、长度、枚举和数值范围增加校验。不要只依赖前端校验。

## 3. 增加 Console 编辑器

在 `IntentRoutesView.vue` 中：

- 增加处理器选项和业务说明。
- 根据类型渲染参数表单。
- 明确默认值、失败策略和是否产生模型费用。
- 在列表中提供简洁摘要。

## 4. Trace 与失败

`PipelineExecutor` 自动记录 in/out、params 和前 20 篇文章。处理器抛错时默认结果为空；只有用户显式设置 `onFailure=keep` 才保留上一步候选。

## 5. 测试

- 空候选、空参数和非法参数。
- 正常过滤或排序。
- 外部服务超时与失败策略。
- 与前后处理器串联。
- 试跑预览的 Trace 结果。

## 6. 文档

同步更新意图路由用户手册、架构文档、API 参数和内置处理器表。新增 LLM 调用时增加明确的 UsageScenario。
