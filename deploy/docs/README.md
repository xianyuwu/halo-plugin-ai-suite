# 文档站 Docker 部署

## 构建与启动

```bash
docker compose -f deploy/docs/compose.yaml up -d --build
```

容器仅监听宿主机 `127.0.0.1:18088`，由宿主机 Nginx 对外提供域名和 HTTPS。

宿主机反向代理模板位于 `deploy/docs/host-nginx.conf`。安装后使用 Certbot 为域名签发证书：

```bash
sudo certbot --nginx -d ai-suite-docs.rainwu.cn --redirect
```

## 检查

```bash
docker compose -f deploy/docs/compose.yaml ps
curl -I http://127.0.0.1:18088/
```

## 更新

同步最新文档后重新执行构建命令。VitePress 构建发生在多阶段镜像的 Node 阶段，最终运行镜像只包含 Nginx 和静态文件。

## 发布版本文档

项目版本以根目录 `gradle.properties` 为唯一来源。发布插件版本时，先生成不可变的版本文档快照：

```bash
pnpm --dir docs build:version
VERSION=$(sed -n 's/^version=//p' gradle.properties)
rsync -az --delete docs/.vitepress/dist/ \
  lighthouse@43.143.231.65:/opt/ai-suite-docs-versions/$VERSION/
pnpm --dir docs build
```

`build:version` 会读取 `gradle.properties`，同时把新版本自动登记到 `docs/versions.json`。`/opt/ai-suite-docs-versions` 通过只读卷挂载到文档容器，重建最新版容器不会删除历史文档。
