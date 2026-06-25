# 本地开发

> 适用读者：插件开发者

## 环境

- JDK 21：`~/jdk21/contents/Contents/Home`
- Node.js 20+
- pnpm 9+
- Halo 2.25，端口 8090
- 不使用 Docker，不运行 `./gradlew haloServer`

## 一键启动

```bash
./dev-start.sh
```

脚本会检查 JDK 和端口、构建插件、从 `dev/` 目录启动 Halo、安装并启用插件。后台地址为 `http://localhost:8090/console`，本地账号为 `admin/admin123`。

## 常用命令

```bash
JAVA_HOME=~/jdk21/contents/Contents/Home ./gradlew build
JAVA_HOME=~/jdk21/contents/Contents/Home ./gradlew test
JAVA_HOME=~/jdk21/contents/Contents/Home ./gradlew jar
./dev-start.sh --deploy-only
./dev-start.sh --stop
```

前端 watch：

```bash
cd ui
pnpm dev
```

## 日志与验证

Halo 日志位于 `/tmp/halo-dev.log`。部署后先确认：

```bash
curl -u admin:admin123 \
  http://127.0.0.1:8090/apis/plugin.halo.run/v1alpha1/plugins/ai-suite
```

`status.phase` 应为 `STARTED`。

## 注意事项

- Halo 必须从 `dev/` 目录启动，因为 `application.yaml` 位于那里。
- Lucene 依赖版本必须与 Halo 对齐。
- reload 后遇到 Scheme/ClassLoader 异常时，完整停止并重新启动 Halo。
- 不提交 `dev/`、API Key、数据库或构建产物。
