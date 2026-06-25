# 发布流程

> 适用读者：项目维护者

## 发布前

1. 更新 `gradle.properties` 版本。
2. 同步 `plugin.yaml` 与 `ui/package.json`。
3. 重新生成文档图表。
4. 更新 README、兼容矩阵和升级说明。
5. 为本次版本生成文档快照。
6. 运行文档检查、测试和完整构建。

```bash
node docs/diagrams/source/render-diagrams.mjs
pnpm --dir docs build:version
node scripts/check-docs.mjs
JAVA_HOME=~/jdk21/contents/Contents/Home ./gradlew test
JAVA_HOME=~/jdk21/contents/Contents/Home ./gradlew build
```

`build:version` 从 `gradle.properties` 读取版本号，并自动登记到 `docs/versions.json`。将生成的 `docs/.vitepress/dist/` 发布到服务器的 `/opt/ai-suite-docs-versions/<version>/`，随后重新构建并发布根路径的最新版文档。历史版本目录不可覆盖或删除。

## 制品

构建产物位于 `build/libs/plugin-ai-suite-<version>.jar`。发布时记录文件大小和 SHA-256：

```bash
shasum -a 256 build/libs/plugin-ai-suite-*.jar
```

## 验收

- JAR 能在干净的 Halo 2.25+ 环境安装并启用，并能识别已安装的 Halo AI Foundation。
- 配置保存和重新打开一致。
- 索引、聊天、搜索、脑图、写作、评测可用。
- 匿名 RoleTemplate 权限准确。
- SSE 在直连和 Nginx 环境逐帧返回。
- 上一版本升级数据能够迁移。

## 发布后

创建 Release Notes，说明新增、修复、兼容性、迁移、是否需要重建索引和已知问题。确认文档站版本选择器能在最新版与本次历史快照之间切换，并保留上一版本制品用于快速回滚。
