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
