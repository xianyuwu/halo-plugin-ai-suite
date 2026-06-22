FROM node:20-alpine AS builder

WORKDIR /workspace
RUN corepack enable && corepack prepare pnpm@9.15.9 --activate

COPY docs/package.json docs/pnpm-lock.yaml ./docs/
RUN pnpm --dir docs install --frozen-lockfile

COPY gradle.properties ./gradle.properties
COPY docs ./docs
COPY assets ./assets

ENV DOCS_LAST_UPDATED=false
RUN pnpm --dir docs build

FROM nginx:1.27-alpine

COPY deploy/docs/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /workspace/docs/.vitepress/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q --spider http://127.0.0.1/ || exit 1
