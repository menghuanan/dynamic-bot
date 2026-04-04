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
# 0.5 cgroup 内存限制读取
# ============================================
# 读取当前容器 cgroup 内存上限（MB）；返回 0 代表未检测到有效限制。
read_cgroup_memory_limit_mb() {
    local limit_bytes=""

    # cgroup v2: memory.max，值为 max 代表无限制。
    if [ -r "/sys/fs/cgroup/memory.max" ]; then
        limit_bytes=$(cat /sys/fs/cgroup/memory.max 2>/dev/null || true)
        if [ -n "$limit_bytes" ] && [ "$limit_bytes" != "max" ] && [[ "$limit_bytes" =~ ^[0-9]+$ ]]; then
            echo $((limit_bytes / 1024 / 1024))
            return
        fi
    fi

    # cgroup v1: memory.limit_in_bytes，超大值通常表示无限制。
    if [ -r "/sys/fs/cgroup/memory/memory.limit_in_bytes" ]; then
        limit_bytes=$(cat /sys/fs/cgroup/memory/memory.limit_in_bytes 2>/dev/null || true)
        if [ -n "$limit_bytes" ] && [[ "$limit_bytes" =~ ^[0-9]+$ ]] && [ "$limit_bytes" -lt 9223372036854771712 ]; then
            echo $((limit_bytes / 1024 / 1024))
            return
        fi
    fi

    echo 0
}

# ============================================
# 0.6 容器预算校验
# ============================================
# 启动前校验 cgroup 内存上限，确保镜像按 512MB 预算运行而不是无限制运行。
enforce_container_memory_budget() {
    local expected_limit_mb=${CONTAINER_MEMORY_LIMIT_MB:-512}
    local detected_limit_mb
    detected_limit_mb=$(read_cgroup_memory_limit_mb)

    if [ "$detected_limit_mb" -le 0 ]; then
        log_warn "未检测到有效 cgroup 内存限制（期望 <= ${expected_limit_mb}MB），拒绝启动"
        exit 64
    fi

    if [ "$detected_limit_mb" -gt "$expected_limit_mb" ]; then
        log_warn "检测到 cgroup 内存上限 ${detected_limit_mb}MB，超过预算 ${expected_limit_mb}MB，拒绝启动"
        exit 64
    fi

    if [ "$detected_limit_mb" -lt "$expected_limit_mb" ]; then
        log_warn "检测到更小的 cgroup 内存上限 ${detected_limit_mb}MB（预算 ${expected_limit_mb}MB），将按更小限制运行"
    else
        log "检测到 cgroup 内存上限 ${detected_limit_mb}MB，符合预算"
    fi
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
    local threshold_mb=${MEMORY_THRESHOLD_MB:-460}
    local hold_seconds=${MEMORY_THRESHOLD_HOLD_SECONDS:-300}
    local above_since=0

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
enforce_container_memory_budget

# shellcheck disable=SC2086
java $JAVA_OPTS -jar /app/dynamic-bot.jar &
JAVA_PID=$!

# 启动 RSS watchdog，作为 native 内存异常增长场景的最后兜底。
memory_watchdog &
WATCHDOG_PID=$!

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
