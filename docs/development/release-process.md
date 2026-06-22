# 发布流程

> 适用读者：项目维护者

## 发布前

1. 更新 `gradle.properties` 版本。
2. 同步 `plugin.yaml` 与 `ui/package.json`。
3. 重新生成文档图表。
4. 更新 README、兼容矩阵和升级说明。
5. 运行文档检查、测试和完整构建。

```bash
node docs/diagrams/source/render-diagrams.mjs
node scripts/check-docs.mjs
JAVA_HOME=~/jdk21/contents/Contents/Home ./gradlew test
JAVA_HOME=~/jdk21/contents/Contents/Home ./gradlew build
```

## 制品

构建产物位于 `build/libs/plugin-ai-suite-<version>.jar`。发布时记录文件大小和 SHA-256：

```bash
shasum -a 256 build/libs/plugin-ai-suite-*.jar
```

## 验收

- JAR 能在干净的 Halo 2.24+ 环境安装并启用。
- 配置保存和重新打开一致。
- 索引、聊天、搜索、脑图、写作、评测可用。
- 匿名 RoleTemplate 权限准确。
- SSE 在直连和 Nginx 环境逐帧返回。
- 上一版本升级数据能够迁移。

## 发布后

创建 Release Notes，说明新增、修复、兼容性、迁移、是否需要重建索引和已知问题。保留上一版本制品用于快速回滚。
