# 发布流程

> 适用读者：项目维护者

## 发布前

1. 更新 `gradle.properties` 版本。
2. 同步根目录和 `src/main/resources/` 下的 `plugin.yaml`、`ui/package.json` 与 `docs/package.json`，确认版本一致。
3. 重新生成文档图表，更新受影响的 README 和用户手册截图。
4. 更新 README、CHANGELOG、当前版本能力清单、兼容矩阵、配置参考、用户手册、Console API 和升级说明；待发布功能进入 Release Notes 后，再移除文档中的“待发布”标识。
5. 为本次版本生成文档快照。
6. 运行文档检查、测试和完整构建。

```bash
node docs/diagrams/source/render-diagrams.mjs
pnpm --dir docs build:version
node scripts/check-docs.mjs
JAVA_HOME=~/jdk21/contents/Contents/Home ./gradlew test
JAVA_HOME=~/jdk21/contents/Contents/Home ./gradlew build
```

`build:version` 从 `gradle.properties` 读取版本号，并自动登记到 `docs/versions.json`。将生成的 `docs/.vitepress/dist/` 发布到服务器的 `/opt/ai-suite-docs-versions/<version>/`，随后重新构建并发布根路径的最新版文档。历史版本目录不可覆盖或删除；发布前先确认目标版本目录不存在。

0.4.2 按本次发布决定沿用现有 0.4.x 文档根站，只构建并更新根路径；不新增 `0.4.2` 快照或修改 `docs/versions.json`。保留现有历史版本目录。

0.3.6 的独立快照已从 `v0.3.6` 文档源码以 `DOCS_BASE=/versions/0.3.6/` 构建并发布到 `/opt/ai-suite-docs-versions/0.3.6/`。上线 0.4.x 前再次确认 `/versions/0.3.6/` 的页面及资源可访问，再切换根路径。不能直接把根站静态文件复制到历史子路径：原文件的绝对资源路径仍指向根站，切换最新版后可能加载错误。旧版页面的历史提示提供“前往最新版”链接，0.4.x 顶部版本菜单提供返回 0.3.x 的入口。0.4.0 的新增操作手册见[交互宠物与 AI 宠物制作](../user-guide/interactive-pets.md)。

## 制品

构建产物位于 `build/libs/plugin-ai-suite-<version>.jar`。发布时记录文件大小和 SHA-256：

```bash
shasum -a 256 build/libs/plugin-ai-suite-*.jar
```

## 验收

- JAR 能在干净的 Halo 2.26+ 环境安装并启用，并能识别已安装的 Halo AI Foundation 1.1.1+。
- 配置保存和重新打开一致。
- 索引、聊天、搜索、脑图、摘要、AI 标签、写作和评测可用；检查内容创作模型与各功能独立模型的回退关系。
- 匿名 RoleTemplate 权限准确。
- SSE 在直连和 Nginx 环境逐帧返回。
- 上一版本升级数据能够迁移。

## 发布后

创建 Release Notes，说明新增、修复、兼容性、迁移、是否需要重建索引和已知问题。确认文档站版本选择器能在最新版与本次历史快照之间切换，并保留上一版本制品用于快速回滚。
