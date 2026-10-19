# 发布流程

> 适用读者：项目维护者

## 发布前

1. 更新 `gradle.properties` 版本。
2. 同步根目录和 `src/main/resources/` 下的 `plugin.yaml`、`ui/package.json` 与 `docs/package.json`，确认版本一致。
3. 重新生成文档图表，更新受影响的 README 和用户手册截图。
4. 更新 README、CHANGELOG、当前版本能力清单、兼容矩阵、配置参考、用户手册、Console API 和升级说明；待发布功能进入 Release Notes 后，再移除文档中的“待发布”标识。
5. 新文档系列首次发布时生成历史快照；同一系列的补丁版本只更新根路径文档。
6. 运行文档检查、测试和完整构建。

```bash
node docs/diagrams/source/render-diagrams.mjs
# 仅新文档系列首次发布时运行：pnpm --dir docs build:version
pnpm --dir docs build
node scripts/check-docs.mjs
JAVA_HOME=~/jdk21/contents/Contents/Home ./gradlew test
JAVA_HOME=~/jdk21/contents/Contents/Home ./gradlew build
```

在线文档按 `0.4.x` 这样的系列维护：同系列补丁版本更新根路径，不创建单独的小版本在线文档。只有新系列首次发布时才运行 `build:version`；它从 `gradle.properties` 读取版本号，登记到 `docs/versions.json`，并生成 `/versions/<version>/` 历史快照。先确认服务器目标目录不存在，再将构建产物发布到 `/opt/ai-suite-docs-versions/<version>/`，随后构建并发布根站。历史目录不可覆盖或删除。

0.4.0 是 0.4.x 系列快照；0.4.1、0.4.2、0.4.3 等补丁版本仅更新根站。0.4.3 快照曾短暂作为独立页面上线，现保留原归档文件，但访问其旧地址会跳转到 0.4.x 根站；不把它列为可选文档版本。

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

创建 Release Notes，说明新增、修复、兼容性、迁移、是否需要重建索引和已知问题。确认版本选择器显示当前系列与可用的上一系列历史文档；补丁版本只需确认根站内容更新。保留上一版本制品用于快速回滚。
