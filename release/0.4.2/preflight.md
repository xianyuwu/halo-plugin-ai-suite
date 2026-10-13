# 0.4.2 发布前核对

核对日期：2026-10-13。此页记录本地发布候选的检查结果，不代表已创建 GitHub Release、推送分支或部署文档站。

## 制品与版本

| 检查项 | 结果 |
| --- | --- |
| `gradle.properties`、插件清单、UI 与文档包版本 | 均为 `0.4.2` |
| JAR 内 `plugin.yaml` 与 Manifest | 版本均为 `0.4.2`；要求 Halo `>=2.26.0`、AI Foundation `>=1.1.1` |
| JAR 内容 | 包含 Console 前端、AI 标签端点与生成服务 |
| 最终候选文件 | `build/libs/plugin-ai-suite-0.4.2.jar`，约 6.4 MiB |
| SHA-256 | `dee1309fce710e8506774b5b8e3615f7f298e9ea2a043c3fe4438bbeadea658d` |

使用 JDK 21 执行 `./gradlew clean build` 成功；183 项 Java 测试通过，前端检查通过。重新构建后 JAR 的 SHA-256 与[安装和升级验收](../../docs/development/release-acceptance-0.4.2.md)所用制品完全相同。

## 文档与界面

- `node scripts/check-docs.mjs` 通过：51 个 Markdown 文件和 22 张 SVG 图表；链接、图表导出和版本字段检查无异常。
- README 与用户手册中的 AI 标签、文章列表操作、内容创作默认模型截图已逐项查看；图片均为 1440 × 900，入口和页面文案与本版本一致。其余产品截图的来源差异已在 README 说明。
- 以 `DOCS_BASE=/versions/0.4.2/` 构建文档快照候选成功；首页资源链接正确指向 `/versions/0.4.2/`，AI 标签与当前版本页面均已生成。候选目录 `docs/.vitepress/dist/` 被 Git 忽略，尚未部署。
- 本机全局 `pnpm` 为 11.1.3，项目文档声明 9.15.9；`pnpm --dir docs build:version` 尝试联网获取包管理器而失败。改用仓库已安装的 VitePress 1.6.4 直接构建后通过。正式发布时应使用项目锁定的 pnpm 版本完成版本登记和最终快照构建。
- 发布提交已将 README、CHANGELOG、兼容矩阵、用户手册及 API 文档切换为 0.4.2 正式版本说明。

## 安装、升级与回滚

- 独立 Halo 2.26.1 环境的全新安装及正式 0.4.1 → 0.4.2 升级均通过；插件达到 `STARTED`，原有 ConfigMap、文章和标签保持不变。详情见[安装与升级验收](../../docs/development/release-acceptance-0.4.2.md)。
- [升级与迁移](../../docs/operations/upgrade-and-migration.md)明确要求备份数据库、数据目录、配置和旧 JAR，并给出回滚步骤；更换 Embedding 模型或维度仍需重建索引。
- 0.4.2 不引入自动配置迁移或索引结构变更。真实模型调用、生产数据库和反向代理链路未在这两套临时站点重复验证；模型生成与应用曾在原开发站点的测试文章上验收。

## 发布动作前仍需完成

1. 在最终发布提交上重新核对 JAR SHA-256；若源码或资源有变化，重新构建并重做受影响的验收。
2. 使用[Release Notes](notes.md)创建 GitHub Release。
3. 按本次发布决定，仅更新在线文档站的现有 0.4.x 根路径；不新增 `0.4.2` 版本入口或 `/versions/0.4.2/` 快照，保留现有历史版本目录。
4. 在实际部署环境按备份与回滚流程检查配置、索引、模型连通性、匿名访问和反向代理 SSE。

发布动作完成后在本页补记实际 Release、在线文档和最终制品的核对结果。
