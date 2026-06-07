#!/bin/bash
# Halo AI 助手插件 - 开发环境一键启动
#
# 用法:
#   ./dev-start.sh              # 构建 + 启动 Halo + 部署插件
#   ./dev-start.sh --deploy-only # 仅部署插件（Halo 已在运行）
#   ./dev-start.sh --stop        # 停止 Halo
#   ./dev-start.sh --restart     # 停止 + 重新启动 + 重新部署
#
# 访问:
#   后台: http://localhost:8090/console (admin / admin123)
#   前台: http://localhost:8090
#   日志: /tmp/halo-dev.log

set -e

# ---- 配置 ----
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR"
DEV_DIR="$SCRIPT_DIR/dev"
HALO_JAR="$DEV_DIR/halo.jar"
export JAVA_HOME="${JAVA_HOME:-$HOME/jdk21/contents/Contents/Home}"
JAVA="$JAVA_HOME/bin/java"
PLUGIN_JAR="$PROJECT_DIR/build/libs/plugin-ai-assistant-0.2.1-SNAPSHOT.jar"
PLUGIN_DEST="$DEV_DIR/data/plugins/plugin-ai-assistant.jar"
HALO_PORT=8090
HALO_LOG="/tmp/halo-dev.log"
HALO_URL="http://localhost:$HALO_PORT"
HALO_ADMIN="admin:admin123"

# ---- 颜色 ----
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
RED='\033[0;31m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }

# ---- 前置检查 ----
check_java() {
    if [ ! -f "$JAVA" ]; then
        error "JDK 21 未找到: $JAVA"
        echo "请设置 JAVA_HOME 或安装 JDK 21"
        exit 1
    fi
    local version=$("$JAVA" -version 2>&1 | head -1 | grep -o '[0-9]\+' | head -1)
    if [ "$version" -lt 21 ]; then
        error "需要 JDK 21+，当前版本: $version"
        exit 1
    fi
}

check_halo_jar() {
    if [ ! -f "$HALO_JAR" ]; then
        error "Halo JAR 不存在: $HALO_JAR"
        echo "请先下载 Halo 2.24.0: https://github.com/halo-dev/halo/releases/tag/v2.24.0"
        exit 1
    fi
}

check_port() {
    if lsof -i ":$HALO_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
        return 0
    else
        return 1
    fi
}

# ---- 构建插件 ----
build_plugin() {
    info "构建插件..."
    cd "$PROJECT_DIR"
    "$PROJECT_DIR/gradlew" build --no-daemon -q
    if [ ! -f "$PLUGIN_JAR" ]; then
        error "构建产物不存在: $PLUGIN_JAR"
        exit 1
    fi
    info "构建完成: $(basename "$PLUGIN_JAR") ($(du -h "$PLUGIN_JAR" | cut -f1))"
}

# ---- 启动 Halo ----
start_halo() {
    if check_port; then
        warn "Halo 已在运行 (端口 $HALO_PORT)"
        return 0
    fi

    info "启动 Halo..."
    cd "$DEV_DIR"
    "$JAVA" -jar "$HALO_JAR" \
        --spring.config.additional-location=file:./application.yaml \
        > "$HALO_LOG" 2>&1 &
    local pid=$!
    info "PID: $pid"

    # 等待启动
    info -n "等待 Halo 启动"
    for i in $(seq 1 30); do
        if check_port; then
            echo ""
            info "Halo 已启动 (端口 $HALO_PORT)"
            return 0
        fi
        if ! kill -0 "$pid" 2>/dev/null; then
            echo ""
            error "Halo 启动失败，查看日志: $HALO_LOG"
            tail -20 "$HALO_LOG"
            exit 1
        fi
        echo -n "."
        sleep 2
    done
    echo ""
    error "Halo 启动超时（60秒），查看日志: $HALO_LOG"
    exit 1
}

# ---- 部署插件 ----
deploy_plugin() {
    info "部署插件..."

    # 复制 jar
    mkdir -p "$(dirname "$PLUGIN_DEST")"
    cp "$PLUGIN_JAR" "$PLUGIN_DEST"

    # 检查插件是否已安装
    local exists=$(curl -s -o /dev/null -w "%{http_code}" -u "$HALO_ADMIN" \
        "$HALO_URL/apis/plugin.halo.run/v1alpha1/plugins/ai-assistant" 2>/dev/null)

    if [ "$exists" = "200" ]; then
        info "更新已有插件..."
        # 禁用
        curl -s -u "$HALO_ADMIN" -X PATCH \
            "$HALO_URL/apis/plugin.halo.run/v1alpha1/plugins/ai-assistant" \
            -H "Content-Type: application/json-patch+json" \
            -d '[{"op":"replace","path":"/spec/enabled","value":false}]' \
            -o /dev/null 2>/dev/null
        sleep 2
        # 删除
        curl -s -u "$HALO_ADMIN" -X DELETE \
            "$HALO_URL/apis/plugin.halo.run/v1alpha1/plugins/ai-assistant" \
            -o /dev/null 2>/dev/null
        sleep 2
    fi

    # 安装
    curl -s -u "$HALO_ADMIN" -X POST \
        "$HALO_URL/apis/api.console.halo.run/v1alpha1/plugins/install" \
        -F "file=@$PLUGIN_DEST" \
        -o /dev/null 2>/dev/null
    sleep 2

    # 启用
    curl -s -u "$HALO_ADMIN" -X PATCH \
        "$HALO_URL/apis/plugin.halo.run/v1alpha1/plugins/ai-assistant" \
        -H "Content-Type: application/json-patch+json" \
        -d '[{"op":"replace","path":"/spec/enabled","value":true}]' \
        -o /dev/null 2>/dev/null
    sleep 3

    # 验证
    local phase=$(curl -s -u "$HALO_ADMIN" \
        "$HALO_URL/apis/plugin.halo.run/v1alpha1/plugins/ai-assistant" 2>/dev/null \
        | python3 -c "import sys,json; print(json.load(sys.stdin)['status']['phase'])" 2>/dev/null)

    if [ "$phase" = "STARTED" ]; then
        info "插件状态: $phase"
    else
        warn "插件状态: $phase（可能需要几秒钟才能启动完成）"
    fi
}

# ---- 停止 Halo ----
stop_halo() {
    local pid=$(lsof -i ":$HALO_PORT" -sTCP:LISTEN -t 2>/dev/null)
    if [ -z "$pid" ]; then
        info "Halo 未在运行"
        return 0
    fi
    info "停止 Halo (PID: $pid)..."
    kill "$pid"
    for i in $(seq 1 10); do
        if ! kill -0 "$pid" 2>/dev/null; then
            info "Halo 已停止"
            return 0
        fi
        sleep 1
    done
    warn "正常停止超时，强制终止..."
    kill -9 "$pid" 2>/dev/null
    info "Halo 已强制终止"
}

# ---- 主流程 ----
main() {
    local action="${1:-start}"

    case "$action" in
        --deploy-only)
            check_java
            if ! check_port; then
                error "Halo 未运行，请先启动: ./dev-start.sh"
                exit 1
            fi
            build_plugin
            deploy_plugin
            ;;
        --stop)
            stop_halo
            ;;
        --restart)
            stop_halo
            sleep 2
            check_java
            check_halo_jar
            build_plugin
            start_halo
            deploy_plugin
            ;;
        *)
            check_java
            check_halo_jar
            build_plugin
            start_halo
            deploy_plugin

            echo ""
            echo "============================================"
            echo "  开发环境已就绪"
            echo "  后台: $HALO_URL/console"
            echo "  前台: $HALO_URL"
            echo "  账号: admin / admin123"
            echo "  日志: $HALO_LOG"
            echo ""
            echo "  停止: ./dev-start.sh --stop"
            echo "  重启: ./dev-start.sh --restart"
            echo "============================================"
            ;;
    esac
}

main "$@"
