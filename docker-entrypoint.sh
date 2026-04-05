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
# 其中默认包含 NMT(summary)；这里只处理通过 CMD 传入的堆内存参数，避免覆盖容器默认诊断能力
JAVA_OPTS="${JAVA_OPTS:-}"

if [ $# -gt 0 ]; then
    # 使用 CMD 传入的参数 (如 -Xms64m -Xmx192m)
    JAVA_OPTS="$JAVA_OPTS $*"
fi

# 补充固定的编码和时区参数，确保即使容器内切换额外 JVM 选项也不回退 UTF-8 与时区行为
JAVA_OPTS="$JAVA_OPTS -Dfile.encoding=UTF-8"
JAVA_OPTS="$JAVA_OPTS -Duser.timezone=Asia/Shanghai"

# ============================================
# 2. RSS 兜底守护
# ============================================
# 后台监控 Java 进程 RSS，超过阈值后主动发 TERM，交给容器重启策略快速恢复。
memory_watchdog() {
    local threshold_mb=${MEMORY_THRESHOLD_MB:-0}
    local hold_seconds=${MEMORY_THRESHOLD_HOLD_SECONDS:-300}
    local above_since=0

    # 未显式提供正数阈值时不启用 watchdog，避免在默认部署中引入隐式内存硬限制。
    if ! [[ "$threshold_mb" =~ ^[0-9]+$ ]] || [ "$threshold_mb" -le 0 ]; then
        log "未配置有效 MEMORY_THRESHOLD_MB，跳过 RSS watchdog"
        return
    fi

    while kill -0 "$JAVA_PID" 2>/dev/null; do
        sleep 60

        # 只采集 VmRSS，避免把 swap 或虚拟地址空间误判为常驻内存增长。
        local rss_kb
        rss_kb=$(grep -E "^VmRSS:" "/proc/$JAVA_PID/status" 2>/dev/null | awk '{print $2}')
        if [ -z "${rss_kb:-}" ]; then
            continue
        fi

        local rss_mb=$((rss_kb / 1024))
        if [ "$rss_mb" -gt "$threshold_mb" ]; then
            if [ "$above_since" -eq 0 ]; then
                above_since=$(date +%s)
                log_warn "RSS ${rss_mb}MB 首次超过阈值 ${threshold_mb}MB，进入观察窗口（${hold_seconds}s）"
                continue
            fi

            local now_epoch
            now_epoch=$(date +%s)
            local duration=$((now_epoch - above_since))
            if [ "$duration" -ge "$hold_seconds" ]; then
                log_warn "RSS ${rss_mb}MB 连续 ${duration}s 超过阈值 ${threshold_mb}MB，主动退出触发容器重启"
                kill -TERM "$JAVA_PID" 2>/dev/null || true
                break
            fi
        else
            above_since=0
        fi
    done
}

# ============================================
# 3. 信号处理
# ============================================
cleanup() {
    log_warn "Received stop signal, shutting down..."

    if [ -n "${WATCHDOG_PID:-}" ]; then
        kill "$WATCHDOG_PID" 2>/dev/null || true
        wait "$WATCHDOG_PID" 2>/dev/null || true
    fi

    if [ -n "${JAVA_PID:-}" ]; then
        log "Stopping Java (PID: $JAVA_PID)..."
        kill -TERM "$JAVA_PID" 2>/dev/null || true
        # 最多等待 8 秒，让 JVM 有机会优雅退出，同时预留余量给 Docker 的 stop 超时策略。
        for i in $(seq 1 8); do
            if ! kill -0 "$JAVA_PID" 2>/dev/null; then
                break
            fi
            sleep 1
        done
        # 如果 JVM 仍未退出，执行强制终止，避免 cleanup 长时间阻塞。
        kill -KILL "$JAVA_PID" 2>/dev/null || true
        wait "$JAVA_PID" 2>/dev/null || true
    fi

    log "Container stopped"
    exit 0
}

trap cleanup SIGTERM SIGINT

# ============================================
# 4. 启动 Java 应用
# ============================================
log "Starting dynamic-bot..."

# shellcheck disable=SC2086
java $JAVA_OPTS -jar /app/dynamic-bot.jar &
JAVA_PID=$!

# 仅在显式配置正数阈值时才启动 watchdog，默认运行不再强制内存阈值。
if [[ "${MEMORY_THRESHOLD_MB:-}" =~ ^[0-9]+$ ]] && [ "${MEMORY_THRESHOLD_MB:-0}" -gt 0 ]; then
    memory_watchdog &
    WATCHDOG_PID=$!
else
    WATCHDOG_PID=""
fi

if wait "$JAVA_PID"; then
    EXIT_CODE=0
else
    EXIT_CODE=$?
fi

if [ -n "${WATCHDOG_PID:-}" ]; then
    kill "$WATCHDOG_PID" 2>/dev/null || true
    wait "$WATCHDOG_PID" 2>/dev/null || true
fi

if [ "$EXIT_CODE" -eq 0 ]; then
    log "Java exited cleanly"
else
    log_warn "Java exited (code: $EXIT_CODE)"
fi

exit "$EXIT_CODE"
