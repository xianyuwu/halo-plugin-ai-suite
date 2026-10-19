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

项目版本以根目录 `gradle.properties` 为唯一来源。在线文档按 `0.4.x` 这样的系列维护。仅新文档系列首次发布时生成不可变的历史快照：

```bash
pnpm --dir docs build:version
VERSION=$(sed -n 's/^version=//p' gradle.properties)
DOCS_SSH=root@43.143.231.65
ssh "$DOCS_SSH" "test ! -e /opt/ai-suite-docs-versions/$VERSION"
rsync -az docs/.vitepress/dist/ \
  "$DOCS_SSH:/opt/ai-suite-docs-versions/$VERSION/"
pnpm --dir docs build
```

同系列补丁版本只执行 `pnpm --dir docs build` 并更新根站，不运行 `build:version`，不登记或公开小版本快照。`build:version` 会读取 `gradle.properties`，同时把新系列首个版本登记到 `docs/versions.json`。`/opt/ai-suite-docs-versions` 通过只读卷挂载到文档容器，重建根站不会删除历史文档。历史目录只发布一次，不要对已有版本重复同步或使用 `--delete`。

0.3.6 的历史快照已从 `v0.3.6` 源码按 `/versions/0.3.6/` 基础路径构建并归档。切换根站到 0.4.x 前，先验证 [0.3.6 历史文档](https://ai-suite-docs.rainwu.cn/versions/0.3.6/) 及其中的资源可访问；不要直接复制当前根站静态文件到历史目录，因为根站构建中的绝对路径会在版本切换后指向错误资源。

0.4.0 是 0.4.x 系列快照；0.4.1、0.4.2、0.4.3 等补丁版本都沿用根站。0.4.3 已发布过的快照文件保留为归档，公网旧地址跳转到根站，不再出现在版本菜单。更新根站时仍须保留所有历史版本目录。
