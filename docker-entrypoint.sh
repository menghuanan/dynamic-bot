#!/bin/bash
# ============================================
# Docker 容器启动脚本
# 功能: 仅启动 Java 应用（纯软件渲染模式下不再拉起 Xvfb）
# ============================================

set -euo pipefail

# 颜色输出
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log() {
    echo -e "${GREEN}[$(date '+%Y-%m-%d %H:%M:%S')]${NC} $*"
}

log_warn() {
    echo -e "${YELLOW}[$(date '+%Y-%m-%d %H:%M:%S')] WARNING${NC} $*"
}

# ============================================
# 0. 内存分配器说明
# ============================================
# jemalloc 已通过 LD_PRELOAD 和 MALLOC_CONF 在 Dockerfile 中配置
# 不需要额外设置 MALLOC_* 环境变量

# ============================================
# 1. 构建 Java 启动参数
# ============================================
# 注意: JAVA_TOOL_OPTIONS 已在 Dockerfile 中配置 JVM 优化参数
# 这里只处理通过 CMD 传入的堆内存参数
JAVA_OPTS="${JAVA_OPTS:-}"

if [ $# -gt 0 ]; then
    # 使用 CMD 传入的参数 (如 -Xms64m -Xmx192m)
    JAVA_OPTS="$JAVA_OPTS $*"
fi

# 补充固定的编码和时区参数
JAVA_OPTS="$JAVA_OPTS -Dfile.encoding=UTF-8"
JAVA_OPTS="$JAVA_OPTS -Duser.timezone=Asia/Shanghai"

# ============================================
# 2. 信号处理
# ============================================
cleanup() {
    log_warn "Received stop signal, shutting down..."

    if [ -n "${JAVA_PID:-}" ]; then
        log "Stopping Java (PID: $JAVA_PID)..."
        kill -TERM "$JAVA_PID" 2>/dev/null || true
        wait "$JAVA_PID" 2>/dev/null || true
    fi

    log "Container stopped"
    exit 0
}

trap cleanup SIGTERM SIGINT

# ============================================
# 3. 启动 Java 应用
# ============================================
log "Starting dynamic-bot..."

# shellcheck disable=SC2086
java $JAVA_OPTS -jar /app/dynamic-bot.jar &
JAVA_PID=$!

wait "$JAVA_PID"
EXIT_CODE=$?

if [ "$EXIT_CODE" -eq 0 ]; then
    log "Java exited cleanly"
else
    log_warn "Java exited (code: $EXIT_CODE)"
fi

exit "$EXIT_CODE"
